package com.graden.editor

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Track rules, so every edit has one predictable outcome:
 *  1. Lanes form one stack, drawn as listed. V0 is the base video layer and never moves. A video layer
 *     is always above V0; among themselves the video layers can be put in any order, and the highest
 *     one with a clip under the playhead is the picture you see. Audio lanes can sit anywhere in the
 *     stack, above or below V0: their position changes nothing, every lane's sound plays.
 *     A lane keeps its name (V1, A2…) wherever it is moved; the name is not its position.
 *  2. V0 is gapless: its clips stick to each other, starting at zero. Dropping a clip on V0 inserts
 *     it at the nearest cut and the clips after it move along; removing one closes the space.
 *     To have black (or anything else) between two clips, put a picture there, e.g. the built-in Black.
 *     On every other lane a clip sits wherever it is put.
 *  3. Pictures are clips like any other and go on any video layer. Having no length of their own,
 *     they can be stretched as long as needed.
 *  4. V0 always has a lane. Every other lane exists only while it holds clips. New video layers
 *     open on top of the existing ones (so by default V0, V1, V2 run bottom to top); new audio lanes at the bottom.
 *  5. There is never a V1 without a V0: video dropped on an empty timeline lands on V0, and when
 *     V0 loses its last clip the layer above it becomes V0, keeping its clips where they are.
 *  6. Clips on one lane never overlap: a clip dropped onto another goes to the first free spot after it.
 *  7. Whatever changes V0's length at some point (insert, delete, duplicate, trim) ripples: clips on
 *     the other lanes after that point move by the same amount, so they stay in sync with V0.
 *  8. A locked track is never changed by any of the above.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /** How many undo steps are kept with a saved project. */
        const val HISTORY_KEPT = 60

        /** Track id of the base video layer. */
        const val V0 = 0
        /** Drop targets that create a track. */
        const val NEW_VIDEO = -1
        const val NEW_AUDIO = -2
    }

    class Located(val trackId: Int, val clip: Clip, val startUs: Long)

    /**
     * Where a dragged clip would land: at [startUs] on track [trackId] (or on a new one). On V0 it is
     * an insertion: [index] is the cut it goes into (counted without the clip itself, if it is already
     * on V0) and [startUs] is where that cut is right now.
     */
    class DropTarget(
        val trackId: Int, val startUs: Long, val durationUs: Long, val index: Int = -1,
        /** On a free lane: clips starting at or after [pushFromUs] move right by [pushUs] to make room. */
        val pushFromUs: Long = 0L, val pushUs: Long = 0L,
    )

    var assets by mutableStateOf<Map<Int, MediaAsset>>(emptyMap())
        private set
    /** The lane stack from bottom to top. Order among video layers is their priority; audio can be anywhere. */
    var tracks by mutableStateOf(listOf(Track(V0, true, emptyList())))
        private set
    var playheadUs by mutableLongStateOf(0L)
        private set
    private var _pxPerSec by mutableFloatStateOf(0f)
    /** Timeline zoom. Changing it is remembered across restarts, like everything else on screen. */
    var pxPerSec: Float
        get() = _pxPerSec
        set(v) {
            _pxPerSec = v
            saveSoon()
        }
    var selectedClipId by mutableStateOf<Long?>(null)
        private set
    var movingClipId by mutableStateOf<Long?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    var mediaBoxOpen by mutableStateOf(false)
    /** The Color page's top panel shows the timeline (true) or the chosen scope. */
    var colorShowTimeline by mutableStateOf(true)
    /** The export screen is open. While it is, the editor's players hold no decoders: the export needs them all. */
    var exportOpen: Boolean
        get() = _exportOpen
        set(v) {
            _exportOpen = v
            if (v) {
                pause()
                layers.values.forEach { it.releaseAll() }
                still.stop()
                stillLoaded = null
                restExec.execute { grabbers[0].close() }
                restExec2.execute { grabbers[1].close() }
                thumbs.paused = true
            } else {
                thumbs.paused = false
                syncLayers(true)
            }
        }
    private var _exportOpen by mutableStateOf(false)

    /** A frozen copy of the project for export, read from the original files. */
    fun exportProject(): com.graden.editor.export.ExportProject {
        val norm = HashMap<Long, Float>()
        for (t in tracks) for (c in t.clips) if (c.snd.normalize) norm[c.id] = normalizeGain(c)
        return com.graden.editor.export.ExportProject(meta, tracks, assets, assets.mapValues { originalOf(it.value) }, mutedTracks, hiddenTracks, norm)
    }

    /** The clip details panel, opened from the top bar. */
    var infoOpen by mutableStateOf(false)
    /** How far the lanes are scrolled up, in pixels, and how far they can go; both kept by the timeline. */
    /** Lane height multiplier, changed by pinching up and down on the timeline. */
    private var _laneScale by mutableFloatStateOf(1f)
    var laneScale: Float
        get() = _laneScale
        set(v) {
            _laneScale = v
            saveSoon()
        }
    var laneScroll by mutableFloatStateOf(0f)
    var laneScrollMax = 0f

    fun scrollLanesBy(px: Float) {
        laneScroll = (laneScroll + px).coerceIn(0f, laneScrollMax)
    }
    private var _snap by mutableStateOf(true)
    var snapEnabled: Boolean
        get() = _snap
        set(v) {
            _snap = v
            saveSoon()
        }
    /** Snap distance in pixels, set by the timeline from the screen density. */
    var snapPx = 24f
    var drop by mutableStateOf<DropTarget?>(null)
        private set

    /** Bumped when a dragged clip clicks into a slot (snap, new lane): the UI answers with a light tick. */
    var hapticTick by mutableIntStateOf(0)
        private set
    /** Bumped when a drag is completed: the UI answers with a firmer confirmation. */
    var confirmTick by mutableIntStateOf(0)
        private set
    /** -1 or 1 while the left or right edge of the selected clip is being trimmed, else 0. */
    var trimSide by mutableIntStateOf(0)
        private set
    private var lastDropLane = Int.MIN_VALUE
    private var lastDropSlot = -1L

    var mutedTracks by mutableStateOf<Set<Int>>(emptySet())
        private set
    var hiddenTracks by mutableStateOf<Set<Int>>(emptySet())
        private set
    var lockedTracks by mutableStateOf<Set<Int>>(emptySet())
        private set
    /** Video layers that currently have a clip under the playhead. */
    val trackVisible = mutableStateMapOf<Int, Boolean>()
    /** Which of a lane's two players is the one on screen. */
    val layerActive = mutableStateMapOf<Int, Int>()

    /** Scrub proxy file per asset id, once built. */
    var proxies by mutableStateOf<Map<Int, String>>(emptyMap())
        private set
    /** Playback proxy per asset id. Playback uses these; the original shows only while paused. */
    var playProxies by mutableStateOf<Map<Int, String>>(emptyMap())
        private set
    /** The proxy job running right now: asset id (-1 when idle), kind and percent. */
    var jobAssetId by mutableIntStateOf(-1)
        private set
    var jobKind by mutableStateOf<ProxyBuilder.Kind?>(null)
        private set
    var jobPercent by mutableIntStateOf(0)
        private set
    /** Proxy jobs being tried again, with the attempt number; they always finish in the end, nothing is given up. */
    private var retrying by mutableStateOf<Map<Pair<Int, ProxyBuilder.Kind>, Int>>(emptyMap())

    /** Files still being analysed, or that could not be read. */
    class ImportItem(val key: String, val name: String, val failed: Boolean)
    var importQueue by mutableStateOf<List<ImportItem>>(emptyList())
        private set

    /** True while the preview shows the proxy scrub frame instead of the full-quality players. */
    var showProxy by mutableStateOf(false)
        private set
    var overlayAspect by mutableFloatStateOf(16f / 9f)
        private set

    private val main = Handler(Looper.getMainLooper())
    val thumbs = ThumbnailCache(app)
    val waves = WaveformCache(app)
    val scrub = ScrubDecoder { main.post { if (proxyActive) showProxy = true } }
    private val layers = HashMap<Int, LayerPlayer>()

    /**
     * Shows the original file's frame while paused. Playback runs from proxies, so when the playhead
     * comes to rest this player loads the full-resolution frame of the picture on top and covers them.
     */
    val still: ExoPlayer = ExoPlayer.Builder(app)
        // It only ever shows one frame of an original, which can run at hundreds of megabits a second: a short,
        // small read-ahead is all it needs, and the default (sized for streaming) would hold a huge amount of it.
        .setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 1_000, 100, 100)
                .setTargetBufferBytes(24 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        )
        .build().apply {
        volume = 0f
        setSeekParameters(SeekParameters.EXACT)
        addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // A file that cannot be seeked (some fragmented MP4s) would only ever show its first frame
                // here, so for those the proxy frame is left in place.
                if (stillWanted && isCurrentMediaItemSeekable) {
                    if (!stillVisible) android.util.Log.d("PlayProbe", "STILL shown after ${(System.nanoTime() - stillAskedAt) / 1_000_000}ms ${stillSource?.substringAfterLast('/')}")
                    stillVisible = true
                }
                // Found one: make it a seekable copy, so from now on the original can be shown here.
                if (!isCurrentMediaItemSeekable) assets.values.firstOrNull { it.uri == stillSource }?.let { proxyBuilder.makeSeekable(it) }
            }
        })
    }
    var stillVisible by mutableStateOf(false)
        private set

    /**
     * Lanes whose picture at rest is the original's frame instead of the proxy's: trackId to the frame it shows. Set
     * one lane at a time as the frames arrive; cleared the moment anything moves.
     */
    var restReady by mutableStateOf<Map<Int, String>>(emptyMap())
        private set
    private val restExec = Executors.newSingleThreadExecutor { r -> Thread(r, "rest-frames").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile private var restGen = 0
    private var restKey: String? = null
    private val restLater = Runnable { refineRest() }
    private var restTries = 0
    /** Lanes whose native frame is already being fetched in this pause. */
    private val restQueued = HashSet<Int>()
    /** Decode the exact frames for [refineRest]: two at a time, each worker with its own decoder. */
    private val restExec2 = Executors.newSingleThreadExecutor { r -> Thread(r, "rest-frames-2").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val grabbers = listOf(ExactFrameGrabber(app), ExactFrameGrabber(app))
    private val REST_WORKERS = 1
    private var stillWanted = false
    private var stillSource: String? = null
    private var stillLoaded: String? = null
    private var stillAskedAt = 0L
    // A plain split leaves two clips that continue straight on in the same file. For playback they are
    // one piece, so there is nothing to hand over at the split. Cached per clip list.
    private val joined = HashMap<Int, Pair<List<Clip>, List<Clip>>>()
    private val proxyBuilder = ProxyBuilder(app, { id, kind, path ->
        // A job queued for another project can finish after switching; its file must belong to this asset.
        val mine = assets[id]?.let { path.contains("${it.uri.hashCode().toUInt()}_${it.durationUs}") } == true
        if (!mine) Unit
        else if (kind == ProxyBuilder.Kind.SEEKABLE) {
            seekables = seekables + (id to path)
            stillLoaded = null
            updateStill()
        } else if (kind == ProxyBuilder.Kind.SCRUB) {
            retrying = retrying - (id to kind)
            proxies = proxies + (id to path)
            // A clip that was waiting for any proxy at all can play now.
            if (id !in playProxies) syncLayers(true)
        }
        else {
            retrying = retrying - (id to kind)
            playProxies = playProxies + (id to path)
            syncLayers(true)
        }
    }, { id, kind, percent ->
        val wasBusy = jobAssetId != -1
        jobAssetId = id
        jobKind = kind
        jobPercent = percent
        // When the last proxy is done, the parked players and the full-quality still come back.
        if (wasBusy && id == -1) syncLayers(true)
    }, { id, kind, attempt, _ -> retrying = retrying + ((id to kind) to attempt) }, { releaseIdleCodecs() })

    /**
     * The proxy builder could not get a hardware codec: the editor lets go of the ones it is not using at this moment
     * (players parked on clips that are not on screen, and the still of the original while it is hidden). They are
     * opened again when needed.
     */
    private fun releaseIdleCodecs() {
        layers.values.forEach { it.releaseIdle() }
        restExec.execute { grabbers[0].close() }
        restExec2.execute { grabbers[1].close() }
        if (!stillVisible) {
            still.stop()
            stillLoaded = null
        }
    }

    private val projects = Projects(app)
    private var store: ProjectStore? = null

    /** The open project, or null on the dashboard. */
    var projectId by mutableStateOf<String?>(null)
        private set
    var meta by mutableStateOf(ProjectMeta(ProjectMeta.defaultName(), System.currentTimeMillis()))
        private set
    var projectList by mutableStateOf<List<ProjectSummary>>(emptyList())
        private set
    private val saver = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val undo = ArrayDeque<List<Track>>()
    private val redo = ArrayDeque<List<Track>>()
    private var nextClipId = 1L
    private var nextTrackId = 1
    private var scrubbing = false
    private var proxyActive = false
    private var lastFrameNanos = 0L
    private var startHoldUntil = 0L

    private var trimOrig: Located? = null
    private var trimLeft = false
    private var trimOrigPlayhead = 0L
    private var trimBefore: List<Track>? = null
    private var trimProxy: String? = null
    private var moveOrig: Located? = null

    val totalUs: Long get() = tracks.maxOfOrNull { it.endUs } ?: 0L
    /** The project's frame rate, chosen when it was created: timecodes and frame steps follow it. */
    val fps: Float get() = meta.fps
    /** The asset whose picture is on top at the playhead. */
    val currentAsset: MediaAsset? get() = topVideoAt(playheadUs)?.let { assets[it.clip.assetId] }

    fun layer(id: Int): LayerPlayer? = layers[id]

    /** The player showing the picture on top right now, for the frame-rate meter. */
    fun topPlayer(): ExoPlayer? = topVideoAt(playheadUs)?.let { layers[it.trackId]?.shownPlayer }

    fun labelOf(trackId: Int): String = tracks.firstOrNull { it.id == trackId }?.name ?: ""

    /** The name a newly created lane of this kind would get: one past the highest in use. */
    fun nextName(video: Boolean, among: List<Track> = tracks): String {
        val n = (among.filter { it.video == video }.mapNotNull { it.name.drop(1).toIntOrNull() }.maxOrNull() ?: 0) + 1
        return (if (video) "V" else "A") + n
    }

    fun locate(clipId: Long): Located? {
        for (t in tracks) t.clips.firstOrNull { it.id == clipId }?.let { return Located(t.id, it, it.startUs) }
        return null
    }

    /** Rule 1: the highest video layer that is not hidden and has a clip at [us]. */
    private fun topVideoAt(us: Long): Located? {
        for (t in tracks.asReversed()) {
            if (!t.video || t.id in hiddenTracks) continue
            t.clipAt(us)?.let { return Located(t.id, it, it.startUs) }
        }
        return null
    }

    /** The built-in Black picture, for filling a stretch of the frame with nothing. Created once on disk. */
    private fun blackUri(): String {
        val f = File(getApplication<Application>().filesDir, "black.png")
        if (!f.exists()) {
            val b = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888)
            b.eraseColor(android.graphics.Color.BLACK)
            f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return Uri.fromFile(f).toString()
    }

    private fun withBlack(pool: Map<Int, MediaAsset>, uri: String): Map<Int, MediaAsset> {
        if (pool.values.any { it.uri == uri }) return pool
        val id = (pool.keys.maxOrNull() ?: 0) + 1
        return pool + (id to MediaAsset(id, uri, "Black", MediaProbe.PICTURE_US, 320, 180, 30f, false, false, "image/png"))
    }

    init {
        apply(tracks, save = false)
        refreshProjects()
    }

    // ---- projects ------------------------------------------------------------------

    init {
        // Once at start: reclaim proxies and waveforms of media no project uses any more.
        viewModelScope.launch(Dispatchers.IO) { runCatching { projects.cleanCaches() } }
    }

    fun refreshProjects() {
        viewModelScope.launch { projectList = withContext(Dispatchers.IO) { runCatching { projects.list() }.getOrDefault(emptyList()) } }
    }

    /** Starts a project with the settings from the new-project sheet, and opens it. */
    fun createProject(settings: ProjectMeta) {
        viewModelScope.launch {
            val name = settings.name.trim().take(ProjectMeta.MAX_NAME).ifEmpty { ProjectMeta.defaultName() }
            val id = withContext(Dispatchers.IO) { projects.create(settings.copy(name = name, created = System.currentTimeMillis())) }
            openProject(id)
        }
    }

    /** Opens a project: everything comes back as it was left, including zoom, lanes and playhead. */
    fun openProject(id: String) {
        if (projectId != null) closeProject()
        viewModelScope.launch {
            val st = projects.store(id)
            val black = withContext(Dispatchers.IO) { runCatching { blackUri() }.getOrNull() }
            val loaded = withContext(Dispatchers.IO) { st.load() } ?: return@launch
            store = st
            meta = loaded.meta
            // Nothing carries over from the previous project.
            undo.clear()
            redo.clear()
            withContext(Dispatchers.IO) { runCatching { st.loadHistory() }.getOrNull() }?.let { (u, r) ->
                undo.addAll(u)
                redo.addAll(r)
            }
            selectedClipId = null
            importQueue = emptyList()
            proxies = emptyMap()
            playProxies = emptyMap()
            seekables = emptyMap()
            retrying = emptyMap()
            clearDrop()
            fxTarget = null
            assets = if (black != null) withBlack(loaded.assets, black) else loaded.assets
            assets.values.forEach {
                proxyBuilder.enqueue(it)
                waves.request(it)
            }
            nextClipId = (loaded.tracks.flatMap { it.clips }.maxOfOrNull { it.id } ?: 0L) + 1
            nextTrackId = (loaded.tracks.maxOfOrNull { it.id } ?: 0) + 1
            val ui = loaded.ui
            _pxPerSec = if (ui.pxPerSec > 0f) ui.pxPerSec else _pxPerSec
            _laneScale = ui.laneScale.coerceIn(0.5f, 2.2f)
            _snap = ui.snap
            mutedTracks = ui.muted
            hiddenTracks = ui.hidden
            lockedTracks = ui.locked
            laneScroll = 0f
            playheadUs = ui.playheadUs
            apply(loaded.tracks, save = false)
            projectId = id
        }
    }

    /** Back to the dashboard. The project is written first. */
    fun closeProject() {
        pause()
        flush()
        main.removeCallbacks(saveHistoryLater)
        saveHistoryLater.run()
        store = null
        projectId = null
        mediaBoxOpen = false
        notice = null
        closeColor()
        reverseJobs.values.forEach { it.cancel() }
        reverseJobs.clear()
        reversing = emptyMap()
        apply(listOf(Track(V0, true, emptyList())), save = false)
        refreshProjects()
    }

    /** Applies settings changed from the editor's project settings sheet. */
    fun updateMeta(m: ProjectMeta) {
        meta = m.copy(name = m.name.trim().take(ProjectMeta.MAX_NAME).ifEmpty { meta.name }, created = meta.created)
        save()
    }

    /**
     * For the "Centre" setting: how much to enlarge lane [t]'s picture beyond "fit" so the clip under the
     * playhead shows at its own pixel size against the timeline resolution. 1 for every other setting.
     */
    fun centreScale(t: Track): Float = t.clipAt(playheadUs)?.let(::centreScaleOf) ?: 1f

    fun centreScaleOf(c: Clip): Float {
        if (meta.scaling != "Centre") return 1f
        val a = assets[c.assetId] ?: return 1f
        if (a.width <= 0 || a.height <= 0) return 1f
        return maxOf(a.width.toFloat() / meta.width, a.height.toFloat() / meta.height)
    }

    /** A timeline time as timecode at the project's frame rate. */
    fun tc(us: Long): String = timecode(us, fps)

    fun renameProject(name: String) {
        val n = name.trim().take(ProjectMeta.MAX_NAME).ifEmpty { ProjectMeta.defaultName() }
        meta = meta.copy(name = n)
        save()
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                projects.delete(id)
                runCatching { projects.cleanCaches() }
            }
            refreshProjects()
        }
    }

    // ---- media pool ----------------------------------------------------------------

    class Status(val text: String, val percent: Int?, val ready: Boolean)

    /** Where [a] is in the import pipeline, for the media window. */
    fun statusOf(a: MediaAsset): Status {
        if (a.isAudio) return Status("Ready · audio", null, true)
        if (a.isImage) return Status("Ready · picture", null, true)
        val side = DeviceProfiler.proxyShortSide(a.tier)
        if (jobAssetId == a.id) {
            val what = if (jobKind == ProxyBuilder.Kind.SCRUB) "Scrub proxy" else "Edit proxy ${side}p"
            return Status("$what $jobPercent%", jobPercent, false)
        }
        retrying[a.id to ProxyBuilder.Kind.PLAY]?.let { return Status("Edit proxy · retrying (try ${it + 1})", null, false) }
        retrying[a.id to ProxyBuilder.Kind.SCRUB]?.let { return Status("Scrub proxy · retrying (try ${it + 1})", null, false) }
        val scrubDone = a.id in proxies
        val playDone = !DeviceProfiler.needsPlayProxy(a) || a.id in playProxies
        return when {
            !scrubDone || !playDone -> Status("Queued", null, false)
            else -> Status("Ready · edit proxy ${side}p", null, true)
        }
    }

    /** Analyses each file into the media pool. Nothing is placed on the timeline here. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        val fresh = uris.filter { u -> importQueue.none { it.key == u.toString() && !it.failed } }
        importQueue = importQueue.filterNot { q -> q.failed && fresh.any { it.toString() == q.key } } +
            fresh.map { ImportItem(it.toString(), "Importing…", false) }
        viewModelScope.launch {
            for (uri in fresh) {
                val key = uri.toString()
                if (assets.values.any { it.uri == key }) {
                    importQueue = importQueue.filterNot { it.key == key }
                    continue
                }
                var name = "Clip"
                val asset = withContext(Dispatchers.IO) {
                    runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    name = MediaProbe.displayName(app, uri)
                    MediaProbe.probe(app, uri)?.let { if (it.isAudio || it.isImage) it else it.copy(tier = DeviceProfiler.profile(app, uri, it).tier) }
                }
                if (asset == null) {
                    importQueue = importQueue.map { if (it.key == key) ImportItem(key, name, true) else it }
                    continue
                }
                val a = asset.copy(id = (assets.keys.maxOrNull() ?: 0) + 1)
                assets = assets + (a.id to a)
                importQueue = importQueue.filterNot { it.key == key }
                proxyBuilder.enqueue(a)
                waves.request(a)
                save()
            }
        }
    }

    fun dismissFailed(key: String) {
        importQueue = importQueue.filterNot { it.key == key }
    }

    /** Tap on a media card: video goes after the last clip on V0, audio to the first audio lane at the playhead. */
    fun addAsset(a: MediaAsset) {
        val clip = Clip(nextClipId++, a.id, 0L, a.durationUs)
        if (a.isAudio) {
            val t = tracks.filter { !it.video && it.id !in lockedTracks }.minByOrNull { it.id }
            commit(insert(tracks, clip, t?.id ?: NEW_AUDIO, false, playheadUs))
        } else if (V0 !in lockedTracks) {
            commit(insert(tracks, clip, V0, true, tracks.first { it.id == V0 }.endUs))
        } else return
        selectedClipId = clip.id
    }

    /**
     * Which lane a clip of the given kind lands on when the finger is over [laneKey]: a track id,
     * NEW_VIDEO for the space above the top video layer, or NEW_AUDIO for the space below the last lane.
     * [exceptClip] is the clip being moved, if any.
     */
    private fun targetLane(video: Boolean, laneKey: Int, exceptClip: Long?): Int {
        val lane = tracks.firstOrNull { it.id == laneKey }
        // Rule 5: with no other video anywhere, video can only go on V0.
        val noOtherVideo = tracks.none { t -> t.video && t.clips.any { it.id != exceptClip } }
        return when {
            !video -> if (lane != null && !lane.video && lane.id !in lockedTracks) lane.id else NEW_AUDIO
            noOtherVideo -> V0
            lane != null && lane.video && lane.id !in lockedTracks -> lane.id
            // Over the audio lanes or below them: the nearest video layer is the base.
            laneKey == NEW_AUDIO || (lane != null && !lane.video) -> V0
            else -> NEW_VIDEO
        }
    }

    /** A media item is being dragged over lane [laneKey] (null when outside the timeline) with its left edge at [timeUs]. */
    fun hoverDrop(a: MediaAsset, laneKey: Int?, timeUs: Long) {
        if (laneKey == null) {
            clearDrop()
            return
        }
        val target = targetLane(!a.isAudio, laneKey, null)
        if (target in lockedTracks) {
            clearDrop()
            return
        }
        drop = slot(target, timeUs, a.durationUs, null)
    }

    /** The landing spot on [target] for a clip wanting to start at [wantUs]: snapped, then moved clear of other clips. */
    private fun slot(target: Int, wantUs: Long, durationUs: Long, exceptClip: Long?): DropTarget {
        if (target == V0) return cut(wantUs, durationUs, exceptClip)
        val others = (tracks.firstOrNull { it.id == target }?.clips ?: emptyList()).filter { it.id != exceptClip }
        val raw = wantUs.coerceAtLeast(0L)
        val snapped = snap(raw, durationUs, exceptClip)
        // Tick when entering a different lane or clicking onto a different snap edge.
        val slotId = if (snapped != raw) snapped else -1L
        if (target != lastDropLane || (slotId >= 0 && slotId != lastDropSlot)) hapticTick++
        lastDropLane = target
        lastDropSlot = slotId
        val (start, push) = landing(others, snapped, durationUs)
        if (push != null && push.second > 0L && (lastPushFrom != push.first)) hapticTick++
        lastPushFrom = push?.first ?: -1L
        return DropTarget(target, start, durationUs, pushFromUs = push?.first ?: 0L, pushUs = push?.second ?: 0L)
    }

    private var lastPushFrom = -1L

    /**
     * Where a clip of [durationUs] dropped with its start at [wantUs] lands on a free lane, among [others]: in the
     * gap it was dropped in (over a clip, the gap on the side of that clip it is nearer to). If it fits there it is
     * kept inside that gap; if the gap is too small, it goes at the start of the gap and the clips after it move
     * along by what is missing, returned as (from, by).
     */
    private fun landing(others: List<Clip>, wantUs: Long, durationUs: Long): Pair<Long, Pair<Long, Long>?> {
        val sorted = others.sortedBy { it.startUs }
        val s = wantUs.coerceAtLeast(0L)
        // The clip boundary the gap starts after (index of the first clip to the right of the gap).
        var next = sorted.indexOfFirst { it.endUs > s }
        if (next >= 0) {
            val c = sorted[next]
            // Over a clip: whichever side of it the clip being dropped mostly is.
            if (s >= c.startUs && s + durationUs / 2 > c.startUs + c.durationUs / 2) next += 1
        } else next = sorted.size
        val gapStart = if (next == 0) 0L else sorted[next - 1].endUs
        val gapEnd = sorted.getOrNull(next)?.startUs ?: Long.MAX_VALUE
        if (gapEnd - gapStart >= durationUs) return s.coerceIn(gapStart, gapEnd - durationUs) to null
        return gapStart to (gapEnd to durationUs - (gapEnd - gapStart))
    }

    /** Rule 2: the cut on V0 nearest to [wantUs], as an insertion point. */
    private fun cut(wantUs: Long, durationUs: Long, exceptClip: Long?): DropTarget {
        val v0 = tracks.first { it.id == V0 }.clips
        val own = v0.firstOrNull { it.id == exceptClip }
        val rest = v0.filter { it.id != exceptClip }
        // Measure against V0 as it will be without the clip being moved.
        val t = when {
            own == null -> wantUs
            wantUs >= own.endUs -> wantUs - own.durationUs
            wantUs > own.startUs -> own.startUs
            else -> wantUs
        }.coerceAtLeast(0L)
        var at = 0L
        var index = rest.size
        for ((i, c) in rest.withIndex()) {
            if (t < at + c.durationUs / 2) {
                index = i
                break
            }
            at += c.durationUs
        }
        val cutUs = rest.take(index).sumOf { it.durationUs }
        if (V0 != lastDropLane || index.toLong() != lastDropSlot) hapticTick++
        lastDropLane = V0
        lastDropSlot = index.toLong()
        // Shown where that cut is now: after the moved clip's footprint if the cut lies beyond it.
        val shown = cutUs + if (own != null && index > v0.indexOf(own)) own.durationUs else 0L
        return DropTarget(V0, shown, durationUs, index)
    }

    /** V0 with [clip] put into cut [index] (taking it out first if it is already there), packed from zero. */
    private fun insertV0(ts: List<Track>, clip: Clip, index: Int): List<Track> = ts.map { t ->
        if (t.id != V0) t else {
            val rest = t.clips.filter { it.id != clip.id }.sortedBy { it.startUs }.toMutableList()
            rest.add(index.coerceIn(0, rest.size), clip)
            t.copy(clips = pack(rest))
        }
    }

    private fun pack(ordered: List<Clip>): List<Clip> {
        var at = 0L
        return ordered.map { c -> c.copy(startUs = at).also { at += c.durationUs } }
    }

    /**
     * Rule 7: V0 grew or shrank by [deltaUs] at [fromUs]. Clips on the other unlocked lanes that start at or
     * after that point move with it, re-seated in order so a shift can never make two overlap.
     */
    private fun ripple(ts: List<Track>, fromUs: Long, deltaUs: Long): List<Track> = if (deltaUs == 0L) ts else ts.map { t ->
        if (t.id == V0 || t.id in lockedTracks) t
        else {
            var floor = 0L
            t.copy(clips = t.clips.sortedBy { it.startUs }.map { c ->
                val moved = if (c.startUs >= fromUs) c.startUs + deltaUs else c.startUs
                c.copy(startUs = maxOf(moved, floor)).also { floor = it.endUs }
            })
        }
    }

    fun clearDrop() {
        drop = null
        lastDropLane = Int.MIN_VALUE
        lastDropSlot = -1L
        lastPushFrom = -1L
    }

    fun dropAsset(a: MediaAsset) {
        val d = drop ?: return
        clearDrop()
        confirmTick++
        val clip = Clip(nextClipId++, a.id, 0L, a.durationUs)
        if (d.trackId == V0) commit(ripple(insertV0(tracks, clip, d.index), d.startUs, clip.durationUs))
        else commit(insert(pushed(tracks, d, null), clip, d.trackId, !a.isAudio, d.startUs))
        selectedClipId = clip.id
    }

    /** The clips a drop makes room for, moved along on their lane (not [except], the clip being moved). */
    private fun pushed(ts: List<Track>, d: DropTarget, except: Long?): List<Track> = if (d.pushUs <= 0L) ts else ts.map { t ->
        if (t.id != d.trackId) t
        else t.copy(clips = t.clips.map { c -> if (c.id != except && c.startUs >= d.pushFromUs) c.copy(startUs = c.startUs + d.pushUs) else c })
    }

    /** Puts [clip] on track [trackId] (creating it for NEW_VIDEO / NEW_AUDIO) at the first free spot from [startUs]. */
    private fun insert(ts: List<Track>, clip: Clip, trackId: Int, video: Boolean, startUs: Long): List<Track> {
        val list = ts.toMutableList()
        var i = list.indexOfFirst { it.id == trackId }
        if (i < 0) {
            // A new video layer goes on top of the existing ones; a new audio lane at the bottom of the stack.
            i = if (video) list.indexOfLast { it.video } + 1 else 0
            list.add(i, Track(nextTrackId++, video, emptyList(), nextName(video, list)))
        }
        val t = list[i]
        val others = t.clips.filter { it.id != clip.id }
        list[i] = t.copy(clips = others + clip.copy(startUs = freeStart(others, startUs, clip.durationUs)))
        return list
    }

    /** Rule 5: first position at or after [desired] where a clip of [durationUs] fits between [others]. */
    private fun freeStart(others: List<Clip>, desired: Long, durationUs: Long): Long {
        var s = desired.coerceAtLeast(0L)
        for (c in others.sortedBy { it.startUs }) if (s < c.endUs && s + durationUs > c.startUs) s = c.endUs
        return s
    }

    /** Pulls [startUs] onto a nearby edge (timeline start, playhead, other clips) by either end of the clip. */
    private fun snap(startUs: Long, durationUs: Long, excludeId: Long?): Long {
        if (!snapEnabled || pxPerSec <= 0f) return startUs
        var best = startUs
        var bestDistance = (snapPx / pxPerSec * 1e6f).toLong()
        fun consider(edge: Long) {
            for (candidate in longArrayOf(edge, edge - durationUs)) {
                val d = abs(candidate - startUs)
                if (candidate >= 0 && d < bestDistance) {
                    bestDistance = d
                    best = candidate
                }
            }
        }
        consider(0L)
        consider(playheadUs)
        for (t in tracks) for (c in t.clips) if (c.id != excludeId) {
            consider(c.startUs)
            consider(c.endUs)
        }
        return best
    }

    // ---- editing -------------------------------------------------------------------

    private fun withClip(clipId: Long, f: (Clip) -> List<Clip>): List<Track> =
        tracks.map { t -> if (t.clips.none { it.id == clipId }) t else t.copy(clips = t.clips.flatMap { if (it.id == clipId) f(it) else listOf(it) }) }

    /** What is selected, which decides which tools and pages make sense to offer. */
    enum class Selection { NONE, VIDEO, SILENT_VIDEO, PICTURE, AUDIO }

    val selection: Selection
        get() {
            val l = selectedClipId?.let(::locate) ?: return Selection.NONE
            val a = assets[l.clip.assetId] ?: return Selection.NONE
            return when {
                tracks.firstOrNull { it.id == l.trackId }?.video != true -> Selection.AUDIO
                a.isImage -> Selection.PICTURE
                a.hasAudio -> Selection.VIDEO
                else -> Selection.SILENT_VIDEO
            }
        }

    /** The clip [split] would cut: the selected one if the playhead is inside it, otherwise the picture on top there. */
    private fun splitTarget(): Located? {
        val sel = selectedClipId?.let(::locate)?.takeIf { playheadUs > it.startUs && playheadUs < it.clip.endUs }
        val target = sel ?: topVideoAt(playheadUs) ?: return null
        val local = playheadUs - target.clip.startUs
        return target.takeIf { it.trackId !in lockedTracks && local >= MIN_CLIP_US && local <= it.clip.durationUs - MIN_CLIP_US }
    }

    /** False when the playhead is in a gap, on a locked lane, or too close to a clip's edge to leave two usable pieces. */
    val canSplit: Boolean get() = splitTarget() != null

    fun split() {
        val target = splitTarget() ?: return
        val c = target.clip
        val local = playheadUs - c.startUs
        // Both pieces keep the placement and sound; arrival effects stay with the left piece, departure ones go right.
        val cutSrc = c.srcAt(local)
        val right = c.copy(id = nextClipId++, inUs = cutSrc, startUs = c.startUs + local, transition = null, animIn = null, snd = c.snd.copy(fadeInUs = 0L))
        commit(withClip(c.id) { listOf(it.copy(outUs = cutSrc, animOut = null, snd = it.snd.copy(fadeOutUs = 0L)), right) })
        selectedClipId = right.id
    }

    fun deleteSelected() {
        val l = selectedClipId?.let(::locate) ?: return
        if (l.trackId in lockedTracks) return
        val removed = withClip(l.clip.id) { emptyList() }
        commit(if (l.trackId == V0) ripple(removed, l.clip.endUs, -l.clip.durationUs) else removed)
    }

    /**
     * Duplicate: a parallel copy at the same time, on the next lane of the same kind above this one. If that
     * lane has anything in the way (even partly), nothing there is moved: a new lane is opened directly
     * above this one for the copy instead.
     */
    /**
     * Duplicate: a parallel copy at the same time, on a new lane opened directly above this one, so nothing
     * already on the timeline is moved or covered.
     */
    fun duplicateSelected() {
        val l = selectedClipId?.let(::locate) ?: return
        if (l.trackId in lockedTracks) return
        val idx = tracks.indexOfFirst { it.id == l.trackId }
        val src = tracks[idx]
        // A transition belongs to the cut it sits on; the copy has no cut, so it starts plain.
        val copy = l.clip.copy(id = nextClipId++, transition = null)
        val list = tracks.toMutableList()
        list.add(idx + 1, Track(nextTrackId++, src.video, listOf(copy), nextName(src.video)))
        commit(list)
        selectedClipId = copy.id
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(tracks)
        apply(prev)
        saveHistorySoon()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(tracks)
        apply(next)
        saveHistorySoon()
    }

    private fun pushUndo(before: List<Track>) {
        undo.addLast(before)
        if (undo.size > 200) undo.removeFirst()
        redo.clear()
        saveHistorySoon()
    }

    // Undo history is kept with the project (its most recent steps), so it survives closing the app.
    private val saveHistoryLater = Runnable {
        val st = store ?: return@Runnable
        val u = undo.toList().takeLast(HISTORY_KEPT)
        val r = redo.toList().takeLast(HISTORY_KEPT)
        viewModelScope.launch(saver) { runCatching { st.saveHistory(u, r) } }
    }

    private fun saveHistorySoon() {
        main.removeCallbacks(saveHistoryLater)
        main.postDelayed(saveHistoryLater, 1200)
    }

    private fun commit(ts: List<Track>) {
        pushUndo(tracks)
        apply(ts)
    }

    /**
     * Rules 1, 2, 4 and 5: V0 always exists and is packed from zero, empty lanes close, no video layer
     * sits below V0, and with V0 empty the layer above it becomes V0. Lane order is otherwise kept.
     */
    private fun normalize(ts: List<Track>): List<Track> {
        var list = ts.filter { it.id == V0 || it.clips.isNotEmpty() }.map { t -> t.copy(clips = t.clips.sortedBy { it.startUs }) }
        if (list.none { it.id == V0 }) {
            val at = list.indexOfFirst { it.video }.let { if (it < 0) list.size else it }
            list = list.take(at) + Track(V0, true, emptyList()) + list.drop(at)
        }
        val base = list.indexOfFirst { it.id == V0 }
        val above = list.drop(base + 1).firstOrNull { it.video }
        if (list[base].clips.isEmpty() && above != null && above.id !in lockedTracks && V0 !in lockedTracks) {
            // The layer's eye and mute state follow it down to V0.
            fun carry(set: Set<Int>) = if (above.id in set) set - above.id + V0 else set - V0
            hiddenTracks = carry(hiddenTracks)
            mutedTracks = carry(mutedTracks)
            // Its gaps close as it becomes gapless, last gap first so earlier positions stay valid; the other lanes ripple along.
            val gaps = ArrayList<Pair<Long, Long>>()
            var cursor = 0L
            for (c in above.clips) {
                if (c.startUs > cursor) gaps += cursor to c.startUs
                cursor = c.endUs
            }
            for ((from, to) in gaps.asReversed()) list = ripple(list, to, from - to)
            list = list.filter { it.id != above.id }.map { if (it.id == V0) it.copy(clips = above.clips) else it }
        }
        val v0 = list.indexOfFirst { it.id == V0 }
        val stray = list.take(v0).filter { it.video }
        if (stray.isNotEmpty()) list = list.take(v0).filter { !it.video } + list[v0] + stray + list.drop(v0 + 1)
        return list.map { if (it.id == V0) it.copy(clips = pack(it.clips)) else it }
    }

    private fun apply(ts: List<Track>, save: Boolean = true) {
        tracks = normalize(ts)
        playheadUs = playheadUs.coerceIn(0L, totalUs)
        if (selectedClipId?.let(::locate) == null) selectedClipId = null
        val ids = tracks.map { it.id }.toSet()
        (layers.keys - ids).forEach {
            layers.remove(it)?.release()
            trackVisible.remove(it)
            layerActive.remove(it)
        }
        for (id in ids) if (id !in layers) {
            layerActive[id] = 0
            val video = tracks.first { it.id == id }.video
            layers[id] = LayerPlayer(getApplication(), audioOnly = !video, probeName = "T$id") { active -> layerActive[id] = active }
        }
        syncLayers(true)
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
        if (save) save()
    }

    /** False while a change is waiting to be written; the save button shows it. */
    var allSaved by mutableStateOf(true)
        private set
    /** When the project last reached storage (wall-clock millis). */
    var lastSavedAt by mutableLongStateOf(0L)
        private set
    /** Bumped when a save asked for with the save button has completed, for its confirmation. */
    var manualSaveTick by mutableIntStateOf(0)
        private set

    private fun uiState() = UiState(playheadUs, _pxPerSec, _laneScale, _snap, mutedTracks, hiddenTracks, lockedTracks)

    /** Every edit is written straight away, in the background. */
    private fun save() {
        main.removeCallbacks(saveLater)
        val st = store ?: return
        val m = meta
        val a = assets.values.toList()
        val t = tracks
        val ui = uiState()
        allSaved = false
        viewModelScope.launch(saver) {
            runCatching { st.save(a, t, ui, m) }.onSuccess {
                main.post {
                    lastSavedAt = System.currentTimeMillis()
                    allSaved = true
                }
            }
        }
    }

    // Small changes that happen in bursts (zooming, scrolling the playhead) are written once they settle.
    private val saveLater = Runnable { save() }

    private fun saveSoon() {
        allSaved = false
        main.removeCallbacks(saveLater)
        main.postDelayed(saveLater, 800)
    }

    /** Writes now, on this thread: used when the app goes to the background, so nothing is lost if it is then closed. */
    fun flush() {
        main.removeCallbacks(saveLater)
        if (main.hasCallbacks(saveHistoryLater)) {
            main.removeCallbacks(saveHistoryLater)
            store?.let { st -> runCatching { st.saveHistory(undo.toList().takeLast(HISTORY_KEPT), redo.toList().takeLast(HISTORY_KEPT)) } }
        }
        val st = store ?: return
        runCatching { st.save(assets.values.toList(), tracks, uiState(), meta) }.onSuccess {
            lastSavedAt = System.currentTimeMillis()
            allSaved = true
        }
    }

    /** The save button: writes at once and confirms it. */
    fun saveManually() {
        flush()
        manualSaveTick++
    }

    val clipCount: Int get() = tracks.sumOf { it.clips.size }

    fun toggleMute(trackId: Int) {
        mutedTracks = if (trackId in mutedTracks) mutedTracks - trackId else mutedTracks + trackId
        syncLayers(false)
        saveSoon()
    }

    fun toggleHidden(trackId: Int) {
        hiddenTracks = if (trackId in hiddenTracks) hiddenTracks - trackId else hiddenTracks + trackId
        saveSoon()
    }

    fun toggleLock(trackId: Int) {
        lockedTracks = if (trackId in lockedTracks) lockedTracks - trackId else lockedTracks + trackId
        saveSoon()
        if (selectedClipId?.let(::locate)?.trackId == trackId) selectedClipId = null
    }

    // ---- transport -----------------------------------------------------------------

    /**
     * What playback reads for [a]. Video always plays from a proxy, never the original: the edit proxy, or until
     * that is made the quick scrub proxy (picture only). With neither yet, there is nothing to play (null) and the
     * clip waits: the original is never played, since proxies are always made in the end. Sound files and pictures
     * are light and play as they are.
     */
    private fun sourceOf(a: MediaAsset): String? {
        if (a.isAudio || a.isImage) return originalOf(a)
        playProxies[a.id]?.let { return Uri.fromFile(File(it)).toString() }
        proxies[a.id]?.let { return Uri.fromFile(File(it)).toString() }
        return null
    }

    /** What the preview's pictures come from right now, for the small indicator beside the timecode. */
    enum class Source { SCRUB, EDIT, NATIVE, OPTIMIZING }

    val previewSource: Pair<Source, String>
        get() {
            optimizingHere?.let { (_, pct) -> return Source.OPTIMIZING to ("Optimizing" + (pct?.let { " $it%" } ?: "")) }
            if (scrubbing && showProxy) return Source.SCRUB to "Scrub proxy"
            if (isPlaying || scrubbing) return Source.EDIT to "Edit proxy"
            // At rest: how many of the video pictures on screen are already the originals' own frames.
            var visible = 0
            for (t in tracks) {
                if (!t.video || t.id in hiddenTracks) continue
                val c = t.clipAt(playheadUs) ?: continue
                val a = assets[c.assetId] ?: continue
                if (a.isImage || a.isAudio) continue
                visible++
            }
            val native = restReady.size
            return when {
                visible == 0 -> Source.NATIVE to "Native"
                native >= visible -> Source.NATIVE to "Native"
                native > 0 -> Source.NATIVE to "Native $native/$visible"
                else -> Source.EDIT to "Edit proxy"
            }
        }

    /** The clip on screen at the playhead is still waiting for its edit proxy: (asset name, percent or null). */
    val optimizingHere: Pair<String, Int?>?
        get() {
            for (t in tracks) if (t.video && t.id !in hiddenTracks) {
                val c = t.clipAt(playheadUs) ?: continue
                val a = assets[c.assetId] ?: continue
                if (a.isAudio || a.isImage || a.id in playProxies) continue
                return a.name to (if (jobAssetId == a.id && jobKind == ProxyBuilder.Kind.PLAY) jobPercent else null)
            }
            return null
        }

    /** Files that cannot be seeked get a seekable copy, made once without re-encoding; the original otherwise. */
    var seekables by mutableStateOf<Map<Int, String>>(emptyMap())
        private set

    fun originalOf(a: MediaAsset): String = seekables[a.id]?.let { Uri.fromFile(File(it)).toString() } ?: a.uri

    /**
     * What a lane's player loads for [c]. Clips either side of a transition are lengthened into their source
     * by half the transition, so both keep moving through the blend: the clip leaving runs on past the cut,
     * and the one arriving starts before it. Where the source has nothing more, it holds on its last frame.
     * The same lengthened item is used throughout, so the player never has to reload when a blend starts.
     */
    private fun item(c: Clip?, lane: Track? = null): LayerPlayer.Item? {
        if (c == null) return null
        // Made once per clip and kept until the timeline or a proxy changes: playback asks for it every frame.
        val stamp = itemStamp
        if (stamp == null || stamp.first !== tracks || stamp.second !== playProxies || stamp.third !== proxies) {
            itemCache.clear()
            itemStamp = Triple(tracks, playProxies, proxies)
        }
        val cacheKey = if (lane == null) c else Pair(lane.id, c)
        if (itemCache.containsKey(cacheKey)) return itemCache[cacheKey]
        return buildItem(c, lane).also { itemCache[cacheKey] = it }
    }

    private val itemCache = HashMap<Any, LayerPlayer.Item?>()
    private var itemStamp: Triple<List<Track>, Map<Int, String>, Map<Int, String>>? = null

    private fun buildItem(c: Clip?, lane: Track? = null): LayerPlayer.Item? {
        val a = c?.let { assets[it.assetId] } ?: return null
        var clip: Clip = c
        if (lane != null && lane.video) {
            val after = lane.clips.firstOrNull { it.startUs == c.endUs && it.transition != null }
            val tIn = c.transition?.takeIf { lane.clips.any { it.endUs == c.startUs } }
            val longest = if (a.isImage) Long.MAX_VALUE / 4 else a.durationUs
            if (after != null) clip = clip.copy(outUs = minOf(longest, clip.outUs + (after.transition!!.durationUs / 2 * clip.speed).toLong()))
            if (tIn != null) {
                // Half the transition before the cut, in source time at this clip's speed (pictures always have it).
                val want = (tIn.durationUs / 2 * clip.speed).toLong()
                val pre = if (a.isImage) want else minOf(want, clip.inUs)
                clip = clip.copy(inUs = clip.inUs - pre, startUs = clip.startUs - (pre / clip.speed).toLong())
            }
        }
        val source = sourceOf(a) ?: return null
        return LayerPlayer.Item(clip, source, clip.outUs >= a.durationUs, if (a.isImage) a.mime else null)
    }

    private fun playClips(t: Track): List<Clip> {
        joined[t.id]?.let { if (it.first === t.clips) return it.second }
        val out = ArrayList<Clip>(t.clips.size)
        // In time order, so the clip at any moment can be found by halving (see [laneAt]).
        for (c in t.clips.sortedBy { it.startUs }) {
            val last = out.lastOrNull()
            // Joined only when the sound carries straight on too: same settings and no fade at the join.
            val sameSound = c.snd == last?.snd && c.snd.fadeInUs == 0L && c.snd.fadeOutUs == 0L && c.speed == last.speed
            if (last != null && sameSound && c.transition == null && last.assetId == c.assetId && last.outUs == c.inUs && last.endUs == c.startUs) out[out.size - 1] = last.copy(outUs = c.outUs)
            else out += c
        }
        joined[t.id] = t.clips to out
        return out
    }

    /** Index of the first clip in [clips] (in time order) that ends after [us]: the one under it, or the next one. */
    private fun laneAt(clips: List<Clip>, us: Long): Int {
        var lo = 0
        var hi = clips.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (clips[mid].endUs <= us) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** What clip [c] sounds like at the playhead: volume, fades, mute and normalising, with its pan and equaliser. */
    private fun soundOf(c: Clip): SoundNow {
        val sn = c.snd
        var env = 1f
        if (sn.fadeInUs > 0) env *= ((playheadUs - c.startUs).toFloat() / sn.fadeInUs).coerceIn(0f, 1f)
        if (sn.fadeOutUs > 0) env *= ((c.endUs - playheadUs).toFloat() / sn.fadeOutUs).coerceIn(0f, 1f)
        val gain = if (sn.muted) 0f else sn.volume * env * (if (sn.normalize) normalizeGain(c) else 1f)
        return SoundNow(gain, sn.pan, sn.bass, sn.mid, sn.treble)
    }

    private val normCache = HashMap<String, Float>()

    /** The gain that brings [c]'s loudest moment to −1 dB, from its waveform (up to +18 dB, down to −20 dB). */
    fun normalizeGain(c: Clip): Float {
        val a = assets[c.assetId] ?: return 1f
        val key = "${a.uri}|${c.inUs}|${c.outUs}"
        normCache[key]?.let { return it }
        val peaks = waves.get(a) ?: return 1f
        val i0 = (c.inUs * WaveformCache.PEAKS_PER_SEC / 1_000_000L).toInt().coerceIn(0, peaks.size)
        val i1 = (c.outUs * WaveformCache.PEAKS_PER_SEC / 1_000_000L).toInt().coerceIn(i0, peaks.size)
        var m = 0
        for (i in i0 until i1) m = maxOf(m, peaks[i].toInt() and 0xFF)
        val g = (0.89f / (m / 255f).coerceAtLeast(0.02f)).coerceIn(0.1f, 8f)
        return g.also { normCache[key] = it }
    }

    /** Points every lane at the playhead: the clip under it, and the next one if it is close. */
    /** [only]: just that lane (a scrub spreads the lanes over successive frames); null for all of them. */
    private fun syncLayers(exact: Boolean, light: Boolean = false, only: Int? = null) {
        // While playback is starting, every lane is held at the playhead until all are ready, then all start together.
        val playing = isPlaying && !scrubbing && clockRunning
        // While paused, a clip up to ten seconds away is kept parked so that pressing play does not have to open a decoder.
        // While proxies are being made, nothing is parked ahead when paused: those codecs are the proxy job's.
        // Playing, a clip is parked only once it is close (its player then has well over a second to get ready): with many
        // lanes, parking further ahead would hold a decoder per lane for nothing and run the phone out of them.
        // Paused, nothing is parked ahead either: eight layers with a spare player each would hold more decoders than the
        // phone has, and the ones the system then takes back show garbled or black pictures. Play starts all lanes
        // together anyway, so a parked clip gains nothing at rest.
        val reach = if (playing) 1_500_000L else 0L
        for (t in tracks) {
            if (only != null && t.id != only) continue
            val lp = layers[t.id] ?: continue
            lp.setVolume(if (t.id in mutedTracks) 0f else 1f)
            val clips = playClips(t)
            // Found by halving the lane's clips (they are in time order), not by walking through all of them.
            val at = laneAt(clips, playheadUs)
            val current = clips.getOrNull(at)?.takeIf { playheadUs >= it.startUs }
            val next = clips.getOrNull(if (current != null) at + 1 else at)?.takeIf { it.startUs > playheadUs && it.startUs - playheadUs < reach }
            // In the second half of a transition the clip being left keeps playing underneath the one arriving.
            val tr = current?.transition?.takeIf { t.video }
            val fadeOut = tr?.crossfade != false
            val leaving = if (tr != null && playheadUs < current.startUs + tr.durationUs / 2) clips.getOrNull(at - 1)?.takeIf { it.endUs == current.startUs } else null
            // In the first half, the arriving clip is already rolling (it is "next" and starts early), so both are heard.
            val coming = next?.transition?.takeIf { t.video && next.startUs - playheadUs < it.durationUs / 2 && current?.endUs == next.startUs }
            // With the crossfade turned off, the sound cuts at the edit while the picture still blends.
            val fade = if (leaving != null) fadeOut else coming?.crossfade != false
            lp.sync(item(current, t), item(next, t), playheadUs, playing, exact, item(leaving, t), light)
            // How much of the crossfade the clip under the playhead has (the other player gets the rest).
            val mix = when {
                !fade -> null
                tr != null && leaving != null -> 0.5f + (playheadUs - current.startUs).toFloat() / tr.durationUs
                coming != null -> 0.5f - (next.startUs - playheadUs).toFloat() / coming.durationUs
                else -> null
            }?.let { if (coming != null && leaving == null) 1f - it else it }?.coerceIn(0f, 1f)
            val other = leaving ?: next?.takeIf { coming != null }
            val cur = current?.let { soundOf(it) }
            val oth = if (mix == null) null else other?.let { soundOf(it) }
            lp.setSound(
                cur?.let { it.copy(gain = it.gain * (mix ?: 1f)) }, current?.snd?.pitch ?: 0f,
                oth?.let { it.copy(gain = it.gain * (1f - (mix ?: 1f))) }, other?.snd?.pitch ?: 0f,
            )
            val visible = t.video && current != null
            if (trackVisible[t.id] != visible) trackVisible[t.id] = visible
        }
        if (only == null) updateStill()
        scheduleRest(!playing && !scrubbing && trimOrig == null && moveOrig == null && !proxyBusy)
    }

    /** Brings the full-resolution still to the playhead when at rest, and takes it away otherwise. */
    // ---- native frames at rest ---------------------------------------------------------


    /** Called on every change while at rest: once the playhead has settled, the originals' frames are fetched. */
    private fun scheduleRest(atRest: Boolean) {
        val key = if (atRest) "$playheadUs|${System.identityHashCode(tracks)}|${hiddenTracks}" else null
        if (key == restKey) return
        restKey = key
        restGen++
        restTries = 0
        restQueued.clear()
        // Anything moving: the decoders go to playback; thumbnails and exact frames wait.
        thumbs.paused = key == null
        if (key == null) {
            restExec.execute { grabbers[0].close() }
            restExec2.execute { grabbers[1].close() }
        }
        if (restReady.isNotEmpty()) restReady = emptyMap()
        main.removeCallbacks(restLater)
        if (key != null) main.postDelayed(restLater, 200)
    }

    private class RestJob(val trackId: Int, val key: String, val source: String, val srcUs: Long, val w: Int, val h: Int)

    /**
     * Fetches, for every picture lane showing a clip at the playhead, the exact frame from the original file, at the size
     * it is shown at, largest first, one at a time. Each lane switches from the proxy to it as it arrives.
     */
    private fun refineRest() {
        if (isPlaying || scrubbing || proxyBusy || trimOrig != null || moveOrig != null) return
        val gen = restGen
        val screenW = getApplication<Application>().resources.displayMetrics.widthPixels.toFloat()
        val canvasW = screenW
        val canvasH = screenW / meta.aspect.coerceAtLeast(0.1f)
        val jobs = ArrayList<Pair<Float, RestJob>>()
        var unsure = false
        for (t in tracks) {
            if (!t.video || t.id in hiddenTracks) continue
            if (restReady.containsKey(t.id) || t.id in restQueued) continue
            val c = t.clipAt(playheadUs) ?: continue
            if (laneFx(t) != null) continue
            val a = assets[c.assetId] ?: continue
            if (a.isImage || a.isAudio || a.width <= 0) continue
            val (bw, bh) = CanvasGeoMath.base(canvasW, canvasH, a.width.toFloat() / a.height, meta.scaling == "Fill")
            val scale = (centreScaleOf(c) * c.tf.scale).coerceAtLeast(0.05f)
            // Decoded at the size it is shown at, but never less than 270 pixels on its short side (small windows stay
            // crisp) and never more than the file has; in exactly the file's shape, so it fits the frame it replaces.
            val shownW = bw * scale
            val shortSide = minOf(a.width, a.height).toFloat()
            val want = maxOf(shownW / a.width * shortSide, 270f).coerceAtMost(shortSide)
            val w = (a.width * want / shortSide).roundToInt().coerceAtLeast(2)
            val h = (a.height * want / shortSide).roundToInt().coerceAtLeast(2)
            // The very frame the lane has on screen (players report it), so the swap shows the same picture, sharper;
            // the playhead's own time only if the player has not reported one.
            val srcUs = c.srcAt(playheadUs - c.startUs)
            val clips = playClips(t)
            val pc = clips.getOrNull(laneAt(clips, playheadUs))
            // The very frame on screen; if it cannot be told yet, this lane waits for the next pass rather than guess.
            val shown = pc?.let { item(it, t) }?.let { onScreenFrameUs(t, it) }?.takeIf { abs(it - srcUs) < 500_000L }
            if (shown == null) {
                unsure = true
                continue
            }
            val frameUs = shown
            restQueued += t.id
            jobs += (bw * bh * scale * scale) to RestJob(t.id, "${c.id}@$frameUs", originalOf(a), frameUs, w, h)
        }
        jobs.sortByDescending { it.first }
        if (unsure && restTries < 6) {
            restTries++
            main.postDelayed(restLater, 250)
        }
        val app = getApplication<Application>()
        val t0 = android.os.SystemClock.uptimeMillis()
        // Largest first, shared between the two workers in turn.
        for (w in 0 until REST_WORKERS) {
            val mine = jobs.filterIndexed { i, _ -> i % REST_WORKERS == w }.map { it.second }
            (if (w == 0) restExec else restExec2).execute {
                for ((n, j) in mine.withIndex()) {
                    if (gen != restGen) { grabbers[w].close(); return@execute }
                    // The exact frame, decoded by the file's own decoder (see ExactFrameGrabber: the platform's retriever
                    // hands back the keyframe before it instead, seconds away in long-keyframe footage).
                    val bmp = grabbers[w].grab(j.source, j.srcUs, j.w, j.h) { gen != restGen } ?: continue
                    main.post {
                        if (gen != restGen) return@post
                        // The lane switches to it only once it has been drawn, never showing a stale or empty picture first.
                        GradeGl.view("R${j.trackId}")?.showStill(bmp) {
                            if (gen != restGen) return@showStill
                            restReady = restReady + (j.trackId to j.key)
                            android.util.Log.d("PlayProbe", "REST lane ${j.trackId} native ${bmp.width}x${bmp.height} after ${android.os.SystemClock.uptimeMillis() - t0}ms")
                        }
                    }
                }
                // Done: the decoder goes back to the phone, for playback.
                grabbers[w].close()
            }
        }
    }

    /** A proxy is being made; the editor keeps its own codec use to a minimum meanwhile. */
    private val proxyBusy: Boolean get() = jobAssetId != -1

    private fun updateStill() {
        // The full-quality still is a decoder of the original (often 4K): not while a proxy job needs the hardware.
        // The single full-screen still is replaced by native frames for every lane (see refineRest); it stays off.
        val top: Located? = null
        val a = top?.let { assets[it.clip.assetId] }
        // Nothing to improve on if the lane is already showing the original; and a transition or animation
        // in progress is drawn by the lanes themselves, which a still on top would hide.
        // Only when that one picture fills the canvas as it is: the still covers the whole canvas, so with a
        // placed or cropped layer, or with more than one layer showing, it would hide what is underneath.
        val layersShowing = tracks.count { it.video && it.id !in hiddenTracks && it.clipAt(playheadUs) != null }
        if (top == null || a == null || a.isImage || a.id !in playProxies || tracks.any { it.video && laneFx(it) != null } ||
            !top.clip.tf.isIdentity || layersShowing > 1
        ) {
            stillWanted = false
            stillVisible = false
            return
        }
        stillWanted = true
        val ms = top.clip.srcAt(playheadUs - top.startUs) / 1000L
        val src = originalOf(a)
        if (stillLoaded != src || abs(still.currentPosition - ms) > 4L) stillAskedAt = System.nanoTime()
        if (stillLoaded != src) {
            stillLoaded = src
            stillSource = a.uri
            stillVisible = false
            still.setMediaItem(MediaItem.fromUri(src), ms)
            still.prepare()
        } else if (abs(still.currentPosition - ms) > 4L) {
            stillVisible = false
            still.seekTo(ms)
        }
    }

    /** Timeline time according to the first lane that is actually playing a clip. */
    private fun leadClock(): Long? {
        for (t in tracks) {
            val c = playClips(t).firstOrNull { playheadUs >= it.startUs && playheadUs < it.endUs } ?: continue
            val i = item(c, t) ?: continue
            layers[t.id]?.clockOf(i)?.let { return it }
        }
        return null
    }

    fun togglePlay() {
        if (isPlaying) {
            pause()
            return
        }
        if (totalUs <= 0L) return
        if (playheadUs >= totalUs - 50_000L) playheadUs = 0L
        isPlaying = true
        clockRunning = false
        lastFrameNanos = 0L
        // Give the players a moment to actually start before time moves, so nothing begins late.
        startHoldUntil = System.nanoTime() + 1_000_000_000L
        syncLayers(true)
    }

    fun pause() {
        // Stop on the frame that is on screen, not on the clock (which runs a little ahead of the pictures): the playhead
        // moves to the very frame the top picture is showing, so nothing jumps and the native frame is that same frame.
        if (isPlaying) shownFrameTime()?.let { playheadUs = it.coerceIn(0L, totalUs) }
        isPlaying = false
        stopAtUs = Long.MAX_VALUE
        saveSoon()
        layers.values.forEach { it.pause() }
        updateStill()
        scheduleRest(!scrubbing && trimOrig == null && moveOrig == null && !proxyBusy)
    }

    /**
     * Called once per display frame while playing. The clock is a lane that is really playing, so
     * sound and picture set the pace and that lane never needs correcting; in gaps, time just runs.
     */
    fun onFrame(nanos: Long) {
        val dtUs = if (lastFrameNanos == 0L) 0L else (nanos - lastFrameNanos) / 1000L
        lastFrameNanos = nanos
        if (isPlaying && dtUs > 30_000L) android.util.Log.d("PlayProbe", "UI FRAME GAP ${dtUs / 1000}ms")
        if (!isPlaying || scrubbing || trimOrig != null || moveOrig != null) return
        val somethingHere = tracks.any { it.clipAt(playheadUs) != null }
        playheadUs = if (!clockRunning) {
            // Starting: every lane is held, paused, at the playhead until all of them are ready (or a second has passed);
            // then the clock starts and they all start on the same frame, so none runs ahead and has to wait.
            if (allReady() || !somethingHere || nanos >= startHoldUntil) clockRunning = true
            playheadUs
        } else {
            // Running: the timeline keeps its own steady time, from the display's frame clock. Every lane follows
            // it; a lane that slips is brought back on its own and can never hold up the playhead or the others.
            playheadUs + dtUs.coerceAtMost(250_000L)
        }.coerceAtMost(totalUs)
        if (playheadUs >= totalUs || playheadUs >= stopAtUs) {
            pause()
            return
        }
        val t0 = System.nanoTime()
        syncLayers(false)
        val ms = (System.nanoTime() - t0) / 1e6
        if (ms > 2.0) android.util.Log.d("PlayProbe", "SLOW sync %.1fms".format(ms))
    }

    private fun allReady(): Boolean = tracks.all { t ->
        val clips = playClips(t)
        val c = clips.getOrNull(laneAt(clips, playheadUs))?.takeIf { playheadUs >= it.startUs } ?: return@all true
        val i = item(c, t) ?: return@all true
        layers[t.id]?.ready(i) != false
    }

    /**
     * The file time of the frame lane [t] really has on screen for [item]: the view's stamp on the frame it drew, matched to
     * the player's record of what it released with that stamp. Exact; null if it cannot be told.
     */
    private fun onScreenFrameUs(t: Track, item: LayerPlayer.Item): Long? {
        val lp = layers[t.id] ?: return null
        if (lp.shownFrameUs(item) == null) return null
        val k = lp.activeIndex
        val ns = GradeGl.view("L${t.id}-$k")?.lastFrameNs?.takeIf { it != 0L } ?: return null
        return lp.frameForStamp(k, ns)
    }

    /** Timeline time of the frame the top picture lane really has on screen, if it is close to the playhead. */
    private fun shownFrameTime(): Long? {
        for (t in tracks.asReversed()) {
            if (!t.video || t.id in hiddenTracks) continue
            val clips = playClips(t)
            val c = clips.getOrNull(laneAt(clips, playheadUs))?.takeIf { playheadUs >= it.startUs } ?: continue
            val i = item(c, t) ?: continue
            val pts = onScreenFrameUs(t, i) ?: return null
            val us = i.clip.startUs + ((pts - i.clip.inUs) / i.clip.speed).toLong()
            return us.takeIf { abs(it - playheadUs) < 300_000L }
        }
        return null
    }

    /** False while playback is starting and time waits for the lanes to roll. */
    private var clockRunning = false

    fun seek(us: Long) {
        pause()
        playheadUs = us.coerceIn(0L, totalUs)
        syncLayers(true)
    }

    fun stepFrames(n: Int) {
        val frameUs = 1_000_000.0 / fps
        seek((((playheadUs / frameUs).roundToLong() + n) * frameUs).roundToLong())
    }

    fun jumpClip(direction: Int) {
        val edges = (listOf(0L) + tracks.flatMap { t -> t.clips.flatMap { listOf(it.startUs, it.endUs) } }).sorted()
        val target = if (direction < 0) edges.lastOrNull { it < playheadUs - 1000L } ?: 0L
        else edges.firstOrNull { it > playheadUs + 1000L } ?: totalUs
        seek(target)
    }

    private var lastScrubSync = 0L
    private var scrubLane = 0

    fun scrubStart() {
        if (scrubbing) return
        pause()
        scrubbing = true
        updateStill()
    }

    fun scrubBy(deltaUs: Long): Boolean {
        val t0 = System.nanoTime()
        val target = (playheadUs + deltaUs).coerceIn(0L, totalUs)
        val moved = target != playheadUs
        playheadUs = target
        var fallback = false
        if (moved) {
            if (!showScrubFrame(target)) {
                fallback = true
                proxyActive = false
                showProxy = false
            }
            // Every layer follows the drag, not just the one the quick scrub frame stands in for: one lane per step, in
            // turn, so no single frame carries the work of all of them. The exact frame comes when the finger lets go.
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastScrubSync >= 12L) {
                lastScrubSync = now
                val video = tracks.filter { it.video }
                if (video.isNotEmpty()) {
                    scrubLane = (scrubLane + 1) % video.size
                    syncLayers(true, light = true, only = video[scrubLane].id)
                }
            }
        }
        val ms = (System.nanoTime() - t0) / 1e6
        if (ms > 2.0) android.util.Log.d("PlayProbe", "SLOW scrub %s %.2fms".format(if (fallback) "players" else "frame", ms))
        return moved
    }

    /** Shows the proxy frame of the picture on top at [us]; false in a gap or if that clip has no proxy yet. */
    /** Where the scrub frame's picture sits: the same place as the clip it stands in for. */
    var scrubTf by mutableStateOf(Tf())
        private set

    private fun showScrubFrame(us: Long): Boolean {
        // In a transition the lanes draw the blend themselves; a single scrub frame would hide it.
        if (tracks.any { it.video && laneFxAt(it, us) }) return false
        val top = topVideoAt(us) ?: return false
        scrubTf = top.clip.tf
        val p = proxies[top.clip.assetId] ?: return false
        assets[top.clip.assetId]?.let { overlayAspect = it.width.toFloat() / it.height }
        proxyActive = true
        scrub.show(p, top.clip.srcAt((us - top.startUs).coerceIn(0L, top.clip.durationUs - 1)))
        return true
    }

    fun scrubEnd() {
        if (!scrubbing) return
        scrubbing = false
        settle()
    }

    /** Scrolls the timeline during a drag (edge auto-scroll). The preview catches up in [settle]. */
    fun panBy(deltaUs: Long): Boolean {
        val target = (playheadUs + deltaUs).coerceIn(0L, totalUs)
        if (target == playheadUs) return false
        if (isPlaying) pause()
        playheadUs = target
        return true
    }

    /** Brings the full-quality players to the playhead and lets go of the scrub frame once they have had time to show theirs. */
    fun settle() {
        saveSoon()
        proxyActive = false
        syncLayers(true)
        main.postDelayed({ if (!scrubbing && trimOrig == null) showProxy = false }, 350)
    }

    fun zoomBy(factor: Float, min: Float, max: Float) {
        pxPerSec = (pxPerSec * factor).coerceIn(min, max)
    }

    /** The Trim tool: selects the clip under the playhead (the picture on top, else any lane) so its handles appear. */
    fun selectAtPlayhead() {
        val l = topVideoAt(playheadUs) ?: tracks.firstNotNullOfOrNull { t -> t.clipAt(playheadUs)?.let { Located(t.id, it, it.startUs) } } ?: return
        if (l.trackId !in lockedTracks) selectedClipId = l.clip.id
    }

    fun tapClip(clipId: Long?) {
        selectedClipId = if (clipId == null || clipId == selectedClipId) null else clipId
    }

    // ---- trimming ------------------------------------------------------------------

    fun trimStart(left: Boolean) {
        val l = selectedClipId?.let(::locate) ?: return
        val a = assets[l.clip.assetId] ?: return
        pause()
        trimOrig = l
        trimLeft = left
        trimOrigPlayhead = playheadUs
        trimSide = if (left) -1 else 1
        trimBefore = tracks
        updateStill()
        trimProxy = proxies[a.id]
        trimProxy?.let {
            overlayAspect = a.width.toFloat() / a.height
            proxyActive = true
            scrub.show(it, if (left) l.clip.inUs else l.clip.outUs - 1)
        }
    }

    /** [deltaUs] is the total finger travel since the trim began. The other edge stays where it is. */
    fun trimDrag(deltaUs: Long) {
        val l = trimOrig ?: return
        val orig = l.clip
        val a = assets[orig.assetId] ?: return
        val sp = orig.speed
        // The finger moves in timeline time; the source moves at the clip's speed.
        val srcDelta = (deltaUs * sp).toLong()
        if (l.trackId == V0) {
            // Rule 2: on V0 the clip keeps its place and the clips after it follow its new length.
            val longest = if (a.isImage) Long.MAX_VALUE / 4 else a.durationUs
            val updated = if (trimLeft) {
                val newIn = (orig.inUs + srcDelta).coerceIn(if (a.isImage) orig.outUs - longest else 0L, orig.outUs - MIN_CLIP_US)
                // Shift the view with the edit, so the edge being pulled stays under the finger.
                playheadUs = (trimOrigPlayhead - ((newIn - orig.inUs) / sp).toLong()).coerceAtLeast(0L)
                if (!a.isImage) trimProxy?.let { scrub.show(it, newIn) }
                orig.copy(inUs = newIn)
            } else {
                val newOut = (orig.outUs + srcDelta).coerceIn(orig.inUs + MIN_CLIP_US, longest)
                if (!a.isImage) trimProxy?.let { scrub.show(it, newOut - (1_000_000f / a.fps).toLong()) }
                orig.copy(outUs = newOut)
            }
            tracks = tracks.map { t -> if (t.id != V0) t else t.copy(clips = pack(t.clips.map { if (it.id == orig.id) updated else it })) }
            return
        }
        val others = tracks.first { it.id == l.trackId }.clips.filter { it.id != orig.id }
        val updated = if (a.isImage) {
            // Rule 3: a picture has no source length, so either edge can be pulled out as far as its neighbours allow.
            if (trimLeft) {
                val floor = others.filter { it.endUs <= orig.startUs }.maxOfOrNull { it.endUs } ?: 0L
                val newStart = (orig.startUs + deltaUs).coerceIn(floor, orig.endUs - MIN_CLIP_US)
                orig.copy(startUs = newStart, outUs = orig.inUs + (orig.endUs - newStart))
            } else {
                val ceiling = others.filter { it.startUs >= orig.endUs }.minOfOrNull { it.startUs } ?: Long.MAX_VALUE
                orig.copy(outUs = orig.inUs + ((orig.endUs + deltaUs).coerceIn(orig.startUs + MIN_CLIP_US, ceiling) - orig.startUs))
            }
        } else if (trimLeft) {
            // Not past the start of the source, not past the start of the timeline, not into the clip before.
            val room = orig.startUs - (others.filter { it.endUs <= orig.startUs }.maxOfOrNull { it.endUs } ?: 0L)
            val newIn = (orig.inUs + srcDelta).coerceIn(maxOf(0L, orig.inUs - (room * sp).toLong()), orig.outUs - MIN_CLIP_US)
            trimProxy?.let { scrub.show(it, newIn) }
            orig.copy(inUs = newIn, startUs = orig.startUs + ((newIn - orig.inUs) / sp).toLong())
        } else {
            val nextStart = others.filter { it.startUs >= orig.endUs }.minOfOrNull { it.startUs }
            val maxOut = if (nextStart == null) a.durationUs else minOf(a.durationUs, orig.outUs + ((nextStart - orig.endUs) * sp).toLong())
            val newOut = (orig.outUs + srcDelta).coerceIn(orig.inUs + MIN_CLIP_US, maxOf(orig.inUs + MIN_CLIP_US, maxOut))
            trimProxy?.let { scrub.show(it, newOut - (1_000_000f / a.fps).toLong()) }
            orig.copy(outUs = newOut)
        }
        tracks = withClip(orig.id) { listOf(updated) }
    }

    fun trimEnd() {
        val l = trimOrig ?: return
        trimOrig = null
        trimSide = 0
        val before = trimBefore
        val now = locate(l.clip.id)?.clip
        if (before != null && now != l.clip) pushUndo(before)
        // Rule 7: a trim on V0 moves everything after the clip's old end by the change in length.
        if (l.trackId == V0 && now != null) tracks = ripple(tracks, l.clip.endUs, now.durationUs - l.clip.durationUs)
        apply(tracks)
        settle()
    }

    // ---- transitions and clip animations ---------------------------------------------

    enum class FxSlot { TRANSITION, IN, OUT }
    class FxTarget(val clipId: Long, val slot: FxSlot)
    /** [clips] says which clip each of the lane's two players is showing, when that is not simply the current one. */
    class LaneFx(val looks: Array<Look?>, val backdrop: androidx.compose.ui.graphics.Color?, val clips: Array<Clip?> = arrayOfNulls(2))

    /**
     * The part of a lane's transition that stays the same from frame to frame: which player is on top, which clip
     * each shows, the backdrop. The preview recomposes only when this changes; the moving part (position, opacity,
     * wipe) is read per frame in the pictures' graphics layers, which costs no recomposition.
     */
    data class LaneFxShape(val z: List<Float>, val looking: List<Boolean>, val backdrop: androidx.compose.ui.graphics.Color?, val clips: List<Clip?>)

    fun laneFxShape(t: Track): LaneFxShape? = laneFx(t)?.let { fx ->
        LaneFxShape(fx.looks.map { it?.z ?: 0f }, fx.looks.map { it != null }, fx.backdrop, fx.clips.toList())
    }

    /** The clip and slot the transition sheet is editing, if it is open. */
    var fxTarget by mutableStateOf<FxTarget?>(null)
        private set
    private var fxBefore: List<Track>? = null
    private var stopAtUs = Long.MAX_VALUE

    /** The clip on the same lane that ends exactly where [c] starts: the other side of a cut. */
    fun clipBefore(c: Clip): Clip? = tracks.firstOrNull { t -> t.clips.any { it.id == c.id } }?.clips?.firstOrNull { it.endUs == c.startUs }

    fun fxOf(t: FxTarget): Fx? = locate(t.clipId)?.clip?.let {
        when (t.slot) {
            FxSlot.TRANSITION -> it.transition
            FxSlot.IN -> it.animIn
            FxSlot.OUT -> it.animOut
        }
    }

    /** A transition may take up to half of the shorter clip on each side of the cut; an animation up to half its clip. */
    fun fxMaxUs(t: FxTarget): Long {
        val c = locate(t.clipId)?.clip ?: return 100_000L
        val room = if (t.slot == FxSlot.TRANSITION) minOf(c.durationUs, clipBefore(c)?.durationUs ?: c.durationUs) else c.durationUs / 2
        return room.coerceIn(100_000L, 5_000_000L)
    }

    fun openFx(clipId: Long, slot: FxSlot) {
        val l = locate(clipId) ?: return
        // Transitions and animations act on pictures, so only clips on video layers have them.
        if (tracks.firstOrNull { it.id == l.trackId }?.video != true) return
        if (l.trackId in lockedTracks || (slot == FxSlot.TRANSITION && clipBefore(l.clip) == null)) return
        pause()
        fxBefore = tracks
        fxTarget = FxTarget(clipId, slot)
    }

    private fun writeFx(t: FxTarget, fx: Fx?) {
        tracks = withClip(t.clipId) { c ->
            listOf(
                when (t.slot) {
                    FxSlot.TRANSITION -> c.copy(transition = fx)
                    FxSlot.IN -> c.copy(animIn = fx)
                    FxSlot.OUT -> c.copy(animOut = fx)
                }
            )
        }
        syncLayers(true)
        saveSoon()
    }

    /** Picks a transition (null removes it) and plays it through once so it can be seen. */
    fun setFx(type: String?) {
        val t = fxTarget ?: return
        writeFx(t, type?.let { Fx(it, (fxOf(t)?.durationUs ?: 500_000L).coerceAtMost(fxMaxUs(t)), fxOf(t)?.crossfade ?: true) })
        if (type != null) previewFx()
    }

    fun setCrossfade(on: Boolean) {
        val t = fxTarget ?: return
        val cur = fxOf(t) ?: return
        writeFx(t, cur.copy(crossfade = on))
    }

    fun setFxDuration(us: Long) {
        val t = fxTarget ?: return
        val cur = fxOf(t) ?: return
        writeFx(t, cur.copy(durationUs = us.coerceIn(100_000L, fxMaxUs(t))))
    }

    /** Puts the open transition on every cut of every unlocked lane, shortened where a clip is too short for it. */
    fun applyFxToAllCuts() {
        val t = fxTarget ?: return
        val fx = fxOf(t) ?: return
        if (t.slot != FxSlot.TRANSITION) return
        tracks = tracks.map { tr ->
            if (tr.id in lockedTracks) tr else tr.copy(clips = tr.clips.map { c ->
                val prev = tr.clips.firstOrNull { it.endUs == c.startUs }
                if (prev == null) c else c.copy(transition = fx.copy(durationUs = fx.durationUs.coerceAtMost(minOf(c.durationUs, prev.durationUs))))
            })
        }
        syncLayers(true)
    }

    /** Plays from just before the open transition to just after it, then stops. */
    fun previewFx() {
        val t = fxTarget ?: return
        val c = locate(t.clipId)?.clip ?: return
        val d = fxOf(t)?.durationUs ?: return
        val (from, to) = when (t.slot) {
            FxSlot.TRANSITION -> c.startUs - d / 2 - 500_000L to c.startUs + d / 2 + 350_000L
            FxSlot.IN -> c.startUs - 150_000L to c.startUs + d + 350_000L
            FxSlot.OUT -> c.endUs - d - 500_000L to c.endUs
        }
        seek(from.coerceAtLeast(0L))
        togglePlay()
        stopAtUs = to
    }

    fun closeFx() {
        val before = fxBefore
        fxTarget = null
        fxBefore = null
        if (before != null && before != tracks) pushUndo(before)
        apply(tracks)
    }

    /** True if [us] falls inside a transition on lane [t]. */
    private fun laneFxAt(t: Track, us: Long): Boolean = t.clips.any { c ->
        val tr = c.transition ?: return@any false
        us >= c.startUs - tr.durationUs / 2 && us < c.startUs + tr.durationUs / 2 && t.clips.any { it.endUs == c.startUs }
    }

    /**
     * How lane [t]'s two players are shown right now, or null when it is just showing its current clip.
     * In a transition the player on the clip being left and the one on the clip arriving each get a look.
     */
    fun laneFx(t: Track): LaneFx? {
        val ph = playheadUs
        val active = layerActive[t.id] ?: 0
        for (c in t.clips) {
            val tr = c.transition ?: continue
            val start = c.startUs - tr.durationUs / 2
            if (ph < start || ph >= start + tr.durationUs) continue
            val def = Transitions.byId[tr.type] ?: continue
            if (t.clips.none { it.endUs == c.startUs }) continue
            val p = (ph - start).toFloat() / tr.durationUs
            // Before the cut the active player is still on the old clip; after it, on the new one.
            val leaving = if (ph < c.startUs) active else 1 - active
            val looks = arrayOfNulls<Look>(2)
            looks[leaving] = Look().also { def.out(it, p); it.z = if (def.outOnTop) 1f else 0f }
            looks[1 - leaving] = Look().also { def.into(it, p); it.z = if (def.outOnTop) 0f else 1f }
            val shown = arrayOfNulls<Clip>(2)
            shown[leaving] = t.clips.firstOrNull { it.endUs == c.startUs }
            shown[1 - leaving] = c
            return LaneFx(looks, def.backdrop, shown)
        }
        val c = t.clipAt(ph) ?: return null
        var look: Look? = null
        c.animIn?.let { fx ->
            val def = Transitions.byId[fx.type]
            val into = ph - c.startUs
            if (def != null && into < fx.durationUs) look = Look().also { def.into(it, into.toFloat() / fx.durationUs) }
        }
        c.animOut?.let { fx ->
            val def = Transitions.byId[fx.type]
            val left = c.endUs - ph
            if (def != null && left < fx.durationUs) look = (look ?: Look()).also { def.out(it, 1f - left.toFloat() / fx.durationUs) }
        }
        val only = look ?: return null
        return LaneFx(arrayOfNulls<Look>(2).also { it[active] = only }, null)
    }

    // ---- placing pictures on the canvas ----------------------------------------------

    private var canvasBefore: List<Track>? = null

    /** The selected clip if it is a picture under the playhead, which the canvas can place. */
    fun canvasClip(): Located? {
        val l = selectedClipId?.let(::locate) ?: return null
        if (tracks.firstOrNull { it.id == l.trackId }?.video != true || l.trackId in hiddenTracks) return null
        return l.takeIf { playheadUs >= it.clip.startUs && playheadUs < it.clip.endUs }
    }

    /** Video lanes from the highest down, with the clip each shows at the playhead: the order to test touches in. */
    fun canvasStack(): List<Pair<Track, Clip>> =
        tracks.asReversed().filter { it.video && it.id !in hiddenTracks && it.id !in lockedTracks }.mapNotNull { t -> t.clipAt(playheadUs)?.let { t to it } }

    fun selectOnCanvas(clipId: Long?) {
        selectedClipId = clipId
    }

    fun canvasEditStart() {
        pause()
        if (canvasBefore == null) canvasBefore = tracks
    }

    /** Live change during a gesture; [canvasEditEnd] makes it one undo step. */
    fun setTf(clipId: Long, tf: Tf) {
        tracks = withClip(clipId) { listOf(it.copy(tf = tf)) }
    }

    fun canvasEditEnd() {
        val before = canvasBefore ?: return
        canvasBefore = null
        if (before != tracks) {
            pushUndo(before)
            apply(tracks)
        }
    }

    /**
     * Sizes the selected picture, as cropped and turned, to the canvas and centres it: [cover] fills the
     * canvas (cutting off what overhangs), otherwise the whole picture fits inside it, with bars where the
     * shapes differ. Crop, quarter turns and mirroring are kept.
     */
    private fun placeToCanvas(cover: Boolean) {
        val l = canvasClip() ?: return
        val a = assets[l.clip.assetId] ?: return
        val t = l.clip.tf
        val ca = meta.aspect
        // Work in canvas units: width ca, height 1.
        val base = CanvasGeoMath.base(ca, 1f, a.width.toFloat() / a.height.coerceAtLeast(1), meta.scaling == "Fill")
        var cw = base.first * (1f - t.cropL - t.cropR)
        var chh = base.second * (1f - t.cropT - t.cropB)
        val quarter = ((t.rot / 90f).roundToInt() % 4 + 4) % 4
        val rot = quarter * 90f
        if (quarter % 2 == 1) cw = chh.also { chh = cw }
        val k = centreScaleOf(l.clip)
        val s = (if (cover) maxOf(ca / cw, 1f / chh) else minOf(ca / cw, 1f / chh)) / k
        // The middle of the kept part, from the middle of the picture, then turned and scaled as shown.
        val dx = (t.cropL - t.cropR) / 2f * base.first
        val dy = (t.cropT - t.cropB) / 2f * base.second
        val r = Math.toRadians(rot.toDouble())
        val ss = s * k
        val ox = ((dx * kotlin.math.cos(r) - dy * kotlin.math.sin(r)) * ss).toFloat()
        val oy = ((dx * kotlin.math.sin(r) + dy * kotlin.math.cos(r)) * ss).toFloat()
        canvasEditStart()
        setTf(l.clip.id, t.copy(scale = s, rot = rot, x = -ox / ca, y = -oy))
        canvasEditEnd()
    }

    fun fitCanvas() = placeToCanvas(cover = false)

    fun fillCanvas() = placeToCanvas(cover = true)

    // ---- colour ----------------------------------------------------------------------

    /** The Color page is open. The editor's tools and timeline give way to scopes, wheels and a scrub-only timeline. */
    var colorOpen by mutableStateOf(false)
        private set
    /** Shows every picture ungraded, to compare. */
    var colorBypass by mutableStateOf(false)
    var scopeSettings by mutableStateOf(ScopeSettings())
        private set
    /** The latest scope picture, and how the scope area should be laid out. */
    var scopeImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set
    @Volatile private var scopesWanted = false
    var scopeOptionsOpen by mutableStateOf(false)
    /** The scope drawn tall, at the cost of room for the controls. */
    var scopeBig by mutableStateOf(false)
    private val scopes = ScopeEngine { bmp -> if (colorOpen) scopeImage = bmp.asImageBitmap() }
    private var gradeBefore: List<Track>? = null

    init {
        GradeGl.scopeSink = { buf, w, h -> if (scopesWanted) scopes.submit(buf, w, h) }
    }

    fun setScopes(s: ScopeSettings) {
        scopeSettings = s
        scopes.settings = s
    }

    /**
     * The clip being graded: the selected picture if the playhead is on it (so a lower layer can be graded too),
     * otherwise the picture on top at the playhead, as in Resolve.
     */
    val gradeTarget: Located?
        get() {
            val sel = selectedClipId?.let(::locate)
            if (sel != null && tracks.firstOrNull { it.id == sel.trackId }?.video == true &&
                playheadUs >= sel.clip.startUs && playheadUs < sel.clip.endUs
            ) return sel
            return topVideoAt(playheadUs)
        }

    /** The picture on top at the playhead (the one the still and scrub frames stand in for). */
    val topClip: Clip? get() = topVideoAt(playheadUs)?.clip

    /** The grade a lane's picture is shown with right now (null: as it is), for the preview. */
    fun gradeFor(c: Clip?): Grade? = if (colorBypass || c == null) null else c.grade

    /** Which preview picture feeds the scopes: the one showing the clip being graded. */
    fun scopeKey(): String? {
        if (!colorOpen) return null
        val t = gradeTarget ?: return null
        val top = topVideoAt(playheadUs)
        if (top?.clip?.id == t.clip.id) {
            if (showProxy) return "scrub"
            if (stillVisible) return "still"
        }
        return "L${t.trackId}-${layerActive[t.trackId] ?: 0}"
    }

    fun openColor() {
        if (colorOpen) return
        val t = gradeTarget
        if (t != null) selectedClipId = t.clip.id
        colorOpen = true
        scopesWanted = true
        scopes.redraw()
    }

    fun closeColor() {
        gradeEnd()
        gradeGroup = emptySet()
        colorOpen = false
        scopesWanted = false
        colorBypass = false
        GradeGl.scopeTag = null
    }

    /** Starts one change to the grade (a drag, a reset…); [gradeEnd] makes it one undo step. */
    fun gradeStart() {
        if (gradeBefore == null) gradeBefore = tracks
    }

    /** Live: the preview redraws straight away, nothing is reloaded or saved until the change ends. */
    fun setGrade(update: (Grade) -> Grade) {
        val l = gradeTarget ?: return
        if (l.trackId in lockedTracks) return
        val g = update(l.clip.grade)
        if (g == l.clip.grade) return
        var ts = withClip(l.clip.id) { listOf(it.copy(grade = g)) }
        // Every other clip in the group gets the same change, applied to its own grade.
        for (id in gradeGroup) {
            if (id == l.clip.id) continue
            val other = locate(id) ?: continue
            if (other.trackId in lockedTracks) continue
            tracks = ts
            ts = withClip(id) { listOf(it.copy(grade = update(it.grade))) }
        }
        tracks = ts
    }

    /** Clips graded together with the one under the playhead (long-press clips on the Color page's timeline). */
    var gradeGroup by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** Adds [clipId] to the group, or takes it out; the clip being graded joins it first. */
    fun toggleGradeGroup(clipId: Long) {
        val base = if (gradeGroup.isEmpty()) setOfNotNull(gradeTarget?.clip?.id) else gradeGroup
        gradeGroup = if (clipId in base) base - clipId else base + clipId
        if (gradeGroup.size <= 1) gradeGroup = emptySet()
    }

    fun clearGradeGroup() {
        gradeGroup = emptySet()
    }

    fun gradeEnd() {
        val before = gradeBefore ?: return
        gradeBefore = null
        if (before == tracks) return
        pushUndo(before)
        canUndo = true
        canRedo = false
        saveSoon()
    }

    /** One step, for taps: a reset or a preset. */
    fun gradeOnce(update: (Grade) -> Grade) {
        gradeStart()
        setGrade(update)
        gradeEnd()
    }

    /** Moves the playhead into [clipId] (from the Color page's timeline), so it becomes the clip being graded. */
    fun gradeClip(clipId: Long) {
        val l = locate(clipId) ?: return
        selectedClipId = clipId
        if (playheadUs < l.clip.startUs || playheadUs >= l.clip.endUs) seek(l.clip.startUs + l.clip.durationUs / 2)
    }

    // ---- reverse ---------------------------------------------------------------------

    /** Reversals being made, as clip id → progress 0..1, for the timeline to show on the clip. */
    var reversing by mutableStateOf<Map<Long, Float>>(emptyMap())
        private set
    private val reverseJobs = HashMap<Long, kotlinx.coroutines.Job>()

    /** A short message for the screen to show once, with a counter so the same text can show again. */
    var notice by mutableStateOf<Pair<Int, String>?>(null)
        private set
    private fun tell(text: String) {
        notice = (notice?.first ?: 0) + 1 to text
    }

    /** True when the selected clip plays backwards. */
    val selectedReversed: Boolean get() = selectedClipId?.let(::locate)?.let { assets[it.clip.assetId]?.isReversed } == true

    /**
     * The Reverse tool. A forwards clip is switched to a reversed copy of the part it uses, made in the background
     * the first time (the clip shows the progress, and can be edited meanwhile); a reversed clip goes back to its
     * original. Either way it is one undo step, and placement, sound settings and speed stay as they were. Tapping
     * again while a copy is being made stops it.
     */
    fun reverseSelected() {
        val l = selectedClipId?.let(::locate) ?: return
        if (l.trackId in lockedTracks) return
        val c = l.clip
        val a = assets[c.assetId] ?: return
        if (a.isImage) return
        reverseJobs[c.id]?.let { job ->
            job.cancel()
            reverseJobs.remove(c.id)
            reversing = reversing - c.id
            tell("Reverse stopped")
            return
        }
        if (a.isReversed) {
            val orig = assets[a.reverseOf] ?: return
            commit(withClip(c.id) { listOf(flipped(it, a, orig)) })
            tell("Playing forwards")
            return
        }
        // A reversed copy that already covers this range is reused.
        assets.values.firstOrNull { it.reverseOf == a.id && it.revInUs <= c.inUs && it.revOutUs >= c.outUs }?.let { r ->
            commit(withClip(c.id) { listOf(flipped(it, a, r)) })
            tell("Reversed")
            return
        }
        val app = getApplication<Application>()
        val from = c.inUs
        val to = c.outUs
        val dir = File(app.filesDir, "reversed").apply { mkdirs() }
        val out = File(dir, "${a.uri.hashCode().toUInt()}_${from}_$to.${if (a.isAudio) "m4a" else "mp4"}")
        val src = Uri.parse(originalOf(a))
        val project = projectId
        reversing = reversing + (c.id to 0f)
        // Started only once it is on record, so the finally block below can tell it is the current job.
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (!out.exists()) Reverser.reverse(app, src, a, from, to, out) { p ->
                    main.post { if (c.id in reversing) reversing = reversing + (c.id to p) }
                }
                val uri = Uri.fromFile(out)
                val probed = withContext(Dispatchers.IO) {
                    MediaProbe.probe(app, uri)?.let { if (it.isAudio) it else it.copy(tier = DeviceProfiler.profile(app, uri, it).tier) }
                } ?: throw Reverser.Failed("The reversed copy could not be read")
                if (projectId != project) return@launch
                val r = probed.copy(
                    id = (assets.keys.maxOrNull() ?: 0) + 1, name = a.name, reverseOf = a.id, revInUs = from, revOutUs = to,
                    durationUs = minOf(probed.durationUs.takeIf { it > 0 } ?: (to - from), to - from),
                )
                assets = assets + (r.id to r)
                proxyBuilder.enqueue(r)
                waves.request(r)
                // The clip may have been trimmed or moved meanwhile; whatever it is now is what gets reversed.
                val now = locate(c.id)
                if (now != null && now.clip.assetId == a.id) {
                    commit(withClip(c.id) { listOf(flipped(it, a, r)) })
                    tell("Reversed")
                } else save()
            } catch (e: kotlinx.coroutines.CancellationException) {
                out.delete()
                throw e
            } catch (e: Exception) {
                out.delete()
                tell((e as? Reverser.Failed)?.message ?: "Couldn't reverse this clip")
            } finally {
                if (reverseJobs[c.id] === coroutineContext[kotlinx.coroutines.Job]) {
                    reverseJobs.remove(c.id)
                    reversing = reversing - c.id
                }
            }
        }
        reverseJobs[c.id] = job
        job.start()
    }

    /**
     * [c] moved from [from] onto [to], where one of the two is the reversed copy of the other: the same stretch of
     * picture, counted from the other end. Time t in the copy is revOut - t in the original.
     */
    private fun flipped(c: Clip, from: MediaAsset, to: MediaAsset): Clip {
        val rev = if (to.isReversed) to else from
        val len = rev.durationUs
        val (lo, hi) = if (to.isReversed) 0L to len else rev.revInUs to rev.revOutUs
        // The same formula works both ways round.
        val ni = (rev.revOutUs - c.outUs).coerceIn(lo, hi)
        val no = (rev.revOutUs - c.inUs).coerceIn(lo, hi).coerceAtLeast(ni + MIN_CLIP_US)
        return c.copy(assetId = to.id, inUs = ni, outUs = no)
    }

    // ---- sound -----------------------------------------------------------------------

    /** The clip whose Sound page is open, and the project as it was when it opened (for cancel and undo). */
    var soundClipId by mutableStateOf<Long?>(null)
        private set
    private var soundBefore: List<Track>? = null

    fun openSound(): Boolean {
        val l = selectedClipId?.let(::locate) ?: return false
        if (l.trackId in lockedTracks) return false
        pause()
        soundBefore = tracks
        soundClipId = l.clip.id
        return true
    }

    /**
     * Other clips playing this clip's sound at the same moment: the extracted copy of a video's sound, or the
     * video an extracted copy came from. With both sounding, a change to one is drowned out by the other.
     */
    fun sameSoundElsewhere(clipId: Long): List<Located> {
        val l = locate(clipId) ?: return emptyList()
        val c = l.clip
        return tracks.flatMap { t -> t.clips.map { Located(t.id, it, it.startUs) } }.filter { o ->
            o.clip.id != c.id && o.clip.assetId == c.assetId && !o.clip.snd.muted && o.trackId !in mutedTracks &&
                o.clip.speed == c.speed && abs((o.clip.startUs - o.clip.inUs / o.clip.speed) - (c.startUs - c.inUs / c.speed)) < 1000f &&
                o.clip.startUs < c.endUs && o.clip.endUs > c.startUs
        }
    }

    /** Mutes the other copies of the open clip's sound (from the Sound page), in the same undo step. */
    fun muteSameSoundElsewhere() {
        val id = soundClipId ?: return
        val others = sameSoundElsewhere(id).map { it.clip.id }.toSet()
        tracks = tracks.map { t -> t.copy(clips = t.clips.map { if (it.id in others) it.copy(snd = it.snd.copy(muted = true)) else it }) }
        syncLayers(false)
    }

    /** A live change from the Sound page; heard straight away, one undo step when the page is closed. */
    fun setSnd(update: (Snd) -> Snd) {
        val id = soundClipId ?: return
        tracks = withClip(id) { listOf(it.copy(snd = update(it.snd))) }
        syncLayers(false)
    }

    /** Plays the clip once, from its start, so the sound settings can be heard in place. */
    fun previewSound() {
        val c = soundClipId?.let(::locate)?.clip ?: return
        seek(c.startUs)
        togglePlay()
        stopAtUs = c.endUs
    }

    fun closeSound(keep: Boolean) {
        val before = soundBefore
        pause()
        soundClipId = null
        soundBefore = null
        if (before == null) return
        if (!keep) apply(before, save = true)
        else if (before != tracks) {
            pushUndo(before)
            apply(tracks)
        }
    }

    /**
     * Extract sound: the clip's sound gets a copy of its own, a clip of the same file on a new audio lane at the
     * same time. The video keeps its sound; mute it on its Sound page if only the extracted copy should be heard.
     */
    fun extractSound() {
        val l = selectedClipId?.let(::locate) ?: return
        val a = assets[l.clip.assetId] ?: return
        if (!a.hasAudio || a.isAudio || l.trackId in lockedTracks) return
        val copy = l.clip.copy(id = nextClipId++, transition = null, animIn = null, animOut = null, tf = Tf())
        val list = tracks.toMutableList()
        list.add(0, Track(nextTrackId++, false, listOf(copy), nextName(false)))
        commit(list)
        selectedClipId = copy.id
        confirmTick++
    }

    // ---- the crop page -----------------------------------------------------------------

    class CropSession(val clipId: Long, val asset: MediaAsset, val before: Tf)

    var cropSession by mutableStateOf<CropSession?>(null)
        private set
    /** The placement being edited on the crop page (crop, quarter turns, mirroring). */
    var cropTf by mutableStateOf(Tf())
    /** A still of the clip at the playhead, large enough to crop against. */
    var cropFrame by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set

    /** The Crop tool: opens the crop page for the selected picture, or the one on top at the playhead. */
    fun openCrop(): Boolean {
        val l = (canvasClip() ?: topVideoAt(playheadUs))?.takeIf { it.trackId !in lockedTracks } ?: return false
        val a = assets[l.clip.assetId] ?: return false
        pause()
        selectedClipId = l.clip.id
        cropSession = CropSession(l.clip.id, a, l.clip.tf)
        cropTf = l.clip.tf
        cropFrame = null
        val atUs = l.clip.srcAt((playheadUs - l.clip.startUs).coerceIn(0L, l.clip.durationUs - 1))
        viewModelScope.launch {
            cropFrame = withContext(Dispatchers.IO) { runCatching { stillOf(a, atUs) }.getOrNull() }
        }
        return true
    }

    private fun stillOf(a: MediaAsset, atUs: Long): androidx.compose.ui.graphics.ImageBitmap? {
        val app = getApplication<Application>()
        val uri = Uri.parse(originalOf(a))
        val bmp = if (a.isImage) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            app.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1600) sample *= 2
            app.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } else {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(app, uri)
                val long = maxOf(a.width, a.height).toFloat()
                val k = minOf(1f, 1600f / long)
                r.getScaledFrameAtTime(atUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST, (a.width * k).toInt(), (a.height * k).toInt())
                    ?: r.getFrameAtTime(atUs)
            } finally {
                r.release()
            }
        }
        return bmp?.asImageBitmap()
    }

    fun applyCrop() {
        val c = cropSession ?: return
        if (cropTf != c.before) commit(withClip(c.clipId) { listOf(it.copy(tf = cropTf)) })
        closeCrop()
    }

    fun closeCrop() {
        cropSession = null
        cropFrame = null
    }

    // ---- the trim page ---------------------------------------------------------------

    /**
     * A clip being trimmed on its own page.
     * (what follows moves along), and on other lanes up to the next clip.
     */
    /**
     * A clip being trimmed. [prev] and [next] are its neighbours on the same lane, shown on the trim page so
     * a gap can be seen and closed. On V0 the clips always touch (what follows moves along), so there are no
     * limits; on other lanes an edge may go up to its neighbour, or to the start of the timeline.
     */
    class TrimSession(val trackId: Int, val asset: MediaAsset, val orig: Clip, val prev: Clip?, val next: Clip?) {
        val onV0: Boolean get() = trackId == 0
    }

    var trimSession by mutableStateOf<TrimSession?>(null)
        private set
    var trimIn by mutableLongStateOf(0L)
        private set
    var trimOut by mutableLongStateOf(0L)
        private set
    /** Where the trim page's preview is, in source time. */
    var trimPos by mutableLongStateOf(0L)
        private set
    var trimPlaying by mutableStateOf(false)
        private set
    /** The speed being set on the trim page. */
    var trimSpeed by mutableFloatStateOf(1f)
        private set
    /** True when the trim page changes the clip's length by its speed rather than by cutting it. */
    var trimBySpeed by mutableStateOf(false)

    /** The selection's length on the timeline at the speed being set. */
    val trimLengthUs: Long get() = ((trimOut - trimIn) / trimSpeed).toLong()

    /** Where the clip starts on the timeline while it is being trimmed (a left edge pulled on a free lane moves it). */
    var trimStartUs by mutableLongStateOf(0L)
        private set
    val trimEndUs: Long get() = trimStartUs + trimLengthUs

    private val unlimited = Long.MAX_VALUE / 4

    /** Lengthening keeping the start has no limit: clips after it are pushed along. */
    private fun roomRight(t: TrimSession) = unlimited

    /**
     * Lengthening keeping the end (pulling the left edge on a free lane) pushes the clips before it back, as far as
     * the start of the timeline: they can be packed up against zero but no further.
     */
    private fun roomLeft(t: TrimSession): Long {
        if (t.onV0) return unlimited
        val before = tracks.first { it.id == t.trackId }.clips.filter { it.id != t.orig.id && it.endUs <= t.orig.startUs }
        return trimEndUs - before.sumOf { it.durationUs }
    }

    /**
     * The lane as it would be with the trim being made: this clip at its new place and length, and the clips either
     * side pushed out of its way (on V0 they are simply packed). Used both to draw the trim page and to apply it.
     */
    fun trimLaneNow(): List<Clip> {
        val t = trimSession ?: return emptyList()
        val me = t.orig.copy(inUs = trimIn, outUs = trimOut, speed = trimSpeed, startUs = if (t.onV0) t.orig.startUs else trimStartUs)
        val lane = tracks.first { it.id == t.trackId }.clips.filter { it.id != me.id }
        if (t.onV0) return pack((lane.filter { it.startUs < t.orig.startUs } + me + lane.filter { it.startUs > t.orig.startUs }).sortedBy { if (it.id == me.id) t.orig.startUs else it.startUs })
        var floor = me.endUs
        val after = lane.filter { it.startUs >= t.orig.endUs }.sortedBy { it.startUs }.map { c -> c.copy(startUs = maxOf(c.startUs, floor)).also { floor = it.endUs } }
        var ceil = me.startUs
        val before = lane.filter { it.endUs <= t.orig.startUs }.sortedByDescending { it.startUs }.map { c ->
            c.copy(startUs = minOf(c.startUs, ceil - c.durationUs).coerceAtLeast(0L)).also { ceil = it.startUs }
        }
        return before.reversed() + me + after
    }

    /** True while the trim is pushing a neighbour out of its place (for the trim page's cue). */
    fun trimPushing(prev: Boolean): Boolean {
        val t = trimSession ?: return false
        val now = trimLaneNow()
        val orig = if (prev) t.prev else t.next
        val moved = orig?.let { o -> now.firstOrNull { it.id == o.id } } ?: return false
        return moved.startUs != orig.startUs
    }

    /** How long the clip may become keeping its start (for the trim page's length presets). */
    fun trimRoomRight(): Long = trimSession?.let(::roomRight) ?: unlimited

    /** Sets the speed directly (0.1× to 10×), keeping the start, within the room the clip has on its lane. */
    fun changeTrimSpeed(v: Float) {
        val t = trimSession ?: return
        val floor = (trimOut - trimIn).toFloat() / roomRight(t)
        trimSpeed = v.coerceIn(maxOf(0.1f, floor), 10f)
    }

    /**
     * Speed side: makes the clip last [lenUs] on the timeline by changing its speed, cutting nothing. With [fromLeft]
     * (on a free lane) the end stays put and the start moves, as when its left edge is pulled.
     */
    fun setTrimLengthBySpeed(lenUs: Long, fromLeft: Boolean = false) {
        val t = trimSession ?: return
        val end = trimEndUs
        val len = lenUs.coerceIn(MIN_CLIP_US, if (fromLeft) roomLeft(t) else roomRight(t))
        trimSpeed = ((trimOut - trimIn).toFloat() / len).coerceIn(0.1f, 10f)
        if (fromLeft && !t.onV0) trimStartUs = end - trimLengthUs
    }

    /** The trim page's own player: the whole source, unclipped, so frames outside the clip can be reached. */
    val trimPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(getApplication()).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying && trimPlaying && playbackState == Player.STATE_ENDED) trimPlaying = false
                }
            })
        }
    }

    /** The source's frame length, for snapping and nudging. */
    fun trimFrameUs(): Long = (1_000_000f / (trimSession?.asset?.fps ?: fps).coerceAtLeast(1f)).toLong()

    /** The Trim tool: opens the trim page for the selected clip, or the one under the playhead. Pictures have nothing to trim. */
    // ---- the trim page's own undo ----
    // Steps taken on the trim page can be undone and redone there without touching anything else. When the page
    // closes with Done, all of them together become a single step of the main undo.

    private data class TrimSnap(val tracks: List<Track>, val clipId: Long, val inUs: Long, val outUs: Long, val speed: Float, val startUs: Long)

    private val trimHistory = ArrayList<TrimSnap>()
    private var trimAt = 0
    var canTrimUndo by mutableStateOf(false)
        private set
    var canTrimRedo by mutableStateOf(false)
        private set

    private fun trimSnap(): TrimSnap? {
        val t = trimSession ?: return null
        return TrimSnap(tracks, t.orig.id, trimIn, trimOut, trimSpeed, trimStartUs)
    }

    private fun trimHistoryChanged() {
        canTrimUndo = trimAt > 0
        canTrimRedo = trimAt < trimHistory.size - 1
    }

    /** Marks the end of one change on the trim page (a drag let go, a chip pressed, a frame step). */
    fun trimMark() {
        val s = trimSnap() ?: return
        if (trimHistory.getOrNull(trimAt) == s) return
        while (trimHistory.size > trimAt + 1) trimHistory.removeAt(trimHistory.size - 1)
        trimHistory += s
        trimAt = trimHistory.size - 1
        if (trimHistory.size > 100) {
            trimHistory.removeAt(0)
            trimAt--
        }
        trimHistoryChanged()
    }

    fun trimUndo() {
        if (trimAt <= 0) return
        trimAt--
        restoreTrim(trimHistory[trimAt])
    }

    fun trimRedo() {
        if (trimAt >= trimHistory.size - 1) return
        trimAt++
        restoreTrim(trimHistory[trimAt])
    }

    private fun restoreTrim(s: TrimSnap) {
        tracks = s.tracks
        val l = locate(s.clipId) ?: return
        val a = assets[l.clip.assetId] ?: return
        selectedClipId = s.clipId
        startTrimOn(l, a)
        trimIn = s.inUs
        trimOut = s.outUs
        trimSpeed = s.speed
        trimStartUs = s.startUs
        trimSeek(trimIn)
        trimHistoryChanged()
    }

    /** The project as it was when the trim page opened: what cancel goes back to, and the one undo step for it all. */
    private var trimBeforeAll: List<Track>? = null

    fun openTrim(bySpeed: Boolean = false): Boolean {
        trimBySpeed = bySpeed
        if (selectedClipId == null) selectAtPlayhead()
        val l = selectedClipId?.let(::locate) ?: return false
        val a = assets[l.clip.assetId] ?: return false
        if (a.isImage || l.trackId in lockedTracks) return false
        pause()
        trimBeforeAll = tracks
        startTrimOn(l, a)
        trimHistory.clear()
        trimAt = 0
        trimSnap()?.let { trimHistory += it }
        trimHistoryChanged()
        return true
    }

    private fun startTrimOn(l: Located, a: MediaAsset) {
        val lane = tracks.first { it.id == l.trackId }.clips
        val next = lane.filter { it.startUs >= l.clip.endUs && it.id != l.clip.id }.minByOrNull { it.startUs }
        val prev = lane.filter { it.endUs <= l.clip.startUs && it.id != l.clip.id }.maxByOrNull { it.endUs }
        trimSession = TrimSession(l.trackId, a, l.clip, prev, next)
        trimStartUs = l.clip.startUs
        trimIn = l.clip.inUs
        trimOut = l.clip.outUs
        trimPos = l.clip.inUs
        trimPlaying = false
        trimSpeed = l.clip.speed
        // The playback proxy is used where there is one: it seeks quickly, with the same timestamps as the original.
        val src = playProxies[a.id]?.let { Uri.fromFile(File(it)).toString() } ?: originalOf(a)
        trimPlayer.volume = 1f
        trimPlayer.setMediaItem(MediaItem.fromUri(src), trimIn / 1000L)
        trimPlayer.prepare()
    }

    /** Which clip of how many on its lane the trim page is on, for "2 / 5". */
    fun trimPlace(): Pair<Int, Int> {
        val t = trimSession ?: return 0 to 0
        val lane = tracks.first { it.id == t.trackId }.clips.sortedBy { it.startUs }
        return (lane.indexOfFirst { it.id == t.orig.id } + 1) to lane.size
    }

    /**
     * Moves the trim page to the previous or next clip on the same lane (pictures included in the order but
     * skipped, having nothing to trim). What was done to this clip is kept; it all becomes one undo step at the end.
     */
    fun trimSwitch(toNext: Boolean): Boolean {
        val t = trimSession ?: return false
        stageTrim()
        val lane = tracks.first { it.id == t.trackId }.clips.sortedBy { it.startUs }
        val here = lane.indexOfFirst { it.id == t.orig.id }
        val order = if (toNext) lane.drop(here + 1) else lane.take(here).reversed()
        val target = order.firstOrNull { assets[it.assetId]?.isImage == false } ?: run {
            // Nothing that way: stay, re-reading this clip as staged.
            locate(t.orig.id)?.let { l -> assets[l.clip.assetId]?.let { startTrimOn(l, it) } }
            return false
        }
        val a = assets[target.assetId] ?: return false
        selectedClipId = target.id
        startTrimOn(Located(t.trackId, target, target.startUs), a)
        trimMark()
        return true
    }

    /** Moves the trim page to clip [id] on the same lane (a tap on it in the strip). */
    fun trimSwitchTo(id: Long): Boolean {
        val t = trimSession ?: return false
        if (id == t.orig.id) return false
        stageTrim()
        val l = locate(id)?.takeIf { it.trackId == t.trackId } ?: return false
        val a = assets[l.clip.assetId]?.takeIf { !it.isImage } ?: return false
        selectedClipId = id
        startTrimOn(l, a)
        trimMark()
        return true
    }

    /** Writes the trim being made (and anything it pushed) into the project, without an undo step of its own. */
    private fun stageTrim() {
        val t = trimSession ?: return
        val updated = t.orig.copy(inUs = trimIn, outUs = trimOut, speed = trimSpeed, startUs = if (t.onV0) t.orig.startUs else trimStartUs)
        if (updated == t.orig) return
        val lane = trimLaneNow()
        var ts = tracks.map { tr -> if (tr.id == t.trackId) tr.copy(clips = lane) else tr }
        if (t.trackId == V0) ts = ripple(ts, t.orig.endUs, updated.durationUs - t.orig.durationUs)
        tracks = normalize(ts)
    }

    private fun snapFrame(us: Long): Long {
        val f = trimFrameUs()
        return (us + f / 2) / f * f
    }

    /** Moves the start of the selection; the end stays. [preview] shows the frame there. */
    fun setTrimIn(us: Long, preview: Boolean = true, snap: Boolean = true) {
        val t = trimSession ?: return
        val end = trimEndUs
        val maxSrcLen = (minOf(roomLeft(t), unlimited) * trimSpeed).toLong().coerceAtLeast(0L)
        val v = if (snap) snapFrame(us) else us
        trimIn = v.coerceIn(maxOf(0L, trimOut - maxSrcLen), trimOut - MIN_CLIP_US)
        // On a free lane the right edge stays where it is and the start moves.
        if (!t.onV0) trimStartUs = end - trimLengthUs
        if (preview) trimSeek(trimIn, fast = true)
    }

    fun setTrimOut(us: Long, preview: Boolean = true, snap: Boolean = true) {
        val t = trimSession ?: return
        val maxSrcLen = (minOf(roomRight(t), unlimited) * trimSpeed).toLong()
        val v = if (snap) snapFrame(us) else us
        trimOut = v.coerceIn(trimIn + MIN_CLIP_US, minOf(t.asset.durationUs, trimIn + maxSrcLen))
        if (preview) trimSeek(trimOut - trimFrameUs(), fast = true)
    }

    /** A preset length: kept from the start where it fits, otherwise pulled back from the end of the source. */
    fun setTrimDuration(d: Long) {
        val t = trimSession ?: return
        if (trimBySpeed) {
            setTrimLengthBySpeed(d)
            return
        }
        // [d] is timeline length; at this speed it takes d × speed of the source.
        val len = (minOf(d, roomRight(t)) * trimSpeed).toLong().coerceIn(MIN_CLIP_US, t.asset.durationUs)
        var inUs = trimIn
        if (inUs + len > t.asset.durationUs) inUs = t.asset.durationUs - len
        trimIn = inUs
        trimOut = inUs + len
        trimSeek(trimIn)
    }

    fun resetTrim() {
        val t = trimSession ?: return
        trimSpeed = 1f
        trimIn = 0L
        trimOut = minOf(t.asset.durationUs, roomRight(t))
        trimSeek(trimIn)
    }

    /** Shows source time [us]. [fast] lands on the nearest keyframe, for keeping up with a moving finger. */
    fun trimSeek(us: Long, fast: Boolean = false) {
        val t = trimSession ?: return
        trimPos = us.coerceIn(0L, t.asset.durationUs)
        trimPlayer.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        trimPlayer.seekTo(trimPos / 1000L)
    }

    /** After a drag: the exact frame. */
    fun trimSettle() = trimSeek(trimPos)

    fun trimTogglePlay() {
        if (trimPlaying) {
            trimPlayer.pause()
            trimPlaying = false
            return
        }
        if (trimPos < trimIn || trimPos >= trimOut - trimFrameUs()) trimSeek(trimIn)
        trimPlayer.playbackParameters = androidx.media3.common.PlaybackParameters(trimSpeed)
        trimPlayer.play()
        trimPlaying = true
    }

    /** Each frame while the trim page plays: follows the player and loops back at the end of the selection. */
    fun trimTick() {
        if (!trimPlaying) return
        trimPos = trimPlayer.currentPosition * 1000L
        if (trimPos >= trimOut) {
            trimPlayer.pause()
            trimPlaying = false
            trimSeek(trimIn)
        }
    }

    /** Keeps the new trim. On V0 everything after the clip moves by the change in length, as with any V0 trim. */
    /** Keeps everything done on the trim page, across all the clips visited, as one undo step. */
    fun applyTrim() {
        val t = trimSession ?: return
        stageTrim()
        val before = trimBeforeAll
        trimBeforeAll = null
        if (before != null && before != tracks) {
            pushUndo(before)
            apply(tracks)
        }
        selectedClipId = t.orig.id
        endTrimPage()
    }

    /** Cancel: everything on the lane goes back to how it was when the trim page opened. */
    fun closeTrim() {
        trimBeforeAll?.let { apply(it, save = true) }
        trimBeforeAll = null
        endTrimPage()
    }

    private fun endTrimPage() {
        trimPlayer.pause()
        trimPlayer.clearMediaItems()
        trimPlaying = false
        trimSession = null
        syncLayers(true)
    }

    // ---- reordering lanes ----------------------------------------------------------

    /** The lane being dragged up or down by its header, if any. */
    var reorderingTrack by mutableStateOf<Int?>(null)
        private set
    private var reorderBefore: List<Track>? = null

    /** Long-press on a lane's header picks the lane up. V0 is the base and stays put. */
    fun reorderStart(trackId: Int): Boolean {
        if (trackId == V0 || tracks.none { it.id == trackId }) return false
        pause()
        reorderBefore = tracks
        reorderingTrack = trackId
        return true
    }

    /** The dragged lane is over lane [overId]: it takes that lane's place if rule 1 allows it. */
    fun reorderOver(overId: Int) {
        val id = reorderingTrack ?: return
        if (overId == id) return
        val list = tracks.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = list.indexOfFirst { it.id == overId }
        if (from < 0 || to < 0) return
        list.add(to, list.removeAt(from))
        // No video layer below the base.
        if (list.take(list.indexOfFirst { it.id == V0 }).any { it.video }) return
        tracks = list
        hapticTick++
    }

    fun reorderEnd() {
        val before = reorderBefore
        reorderBefore = null
        reorderingTrack = null
        if (before != null && before.map { it.id } != tracks.map { it.id }) {
            pushUndo(before)
            confirmTick++
            apply(tracks)
        }
    }

    // ---- picking a clip up and putting it down somewhere else ------------------------

    /** Long-press on a clip picks it up. It stays where it is, greyed, until it is dropped. */
    fun moveStart(clipId: Long): Boolean {
        val l = locate(clipId) ?: return false
        if (l.trackId in lockedTracks) return false
        pause()
        moveOrig = l
        movingClipId = clipId
        selectedClipId = clipId
        updateStill()
        return true
    }

    /**
     * The picked-up clip has travelled [deltaUs] sideways and the finger is over lane [laneKey]
     * (null: stay on its own lane). Video and audio clips stay on lanes of their own kind.
     */
    fun moveDrag(deltaUs: Long, laneKey: Int?) {
        val l = moveOrig ?: return
        val video = tracks.first { it.id == l.trackId }.video
        val target = if (laneKey == null) l.trackId else targetLane(video, laneKey, l.clip.id)
        drop = slot(target, l.clip.startUs + deltaUs, l.clip.durationUs, l.clip.id)
    }

    fun moveEnd() {
        val l = moveOrig ?: return
        val d = drop
        moveOrig = null
        movingClipId = null
        clearDrop()
        if (d == null) return
        val c = l.clip
        val v0 = tracks.first { it.id == V0 }.clips
        if (d.trackId == l.trackId && (if (l.trackId == V0) d.index == v0.indexOfFirst { it.id == c.id } else d.startUs == c.startUs)) return
        val video = tracks.first { it.id == l.trackId }.video
        var ts = withClip(c.id) { emptyList() }
        if (d.trackId == V0) {
            ts = insertV0(ts, c, d.index)
            // Rule 7: V0 grew at the cut. (Reordering within V0 leaves its length alone, so nothing ripples.)
            if (l.trackId != V0) ts = ripple(ts, v0.take(d.index).sumOf { it.durationUs }, c.durationUs)
        } else {
            ts = insert(pushed(ts, d, c.id), c, d.trackId, video, d.startUs)
            // Rule 7: V0 shrank where the clip used to be.
            if (l.trackId == V0) ts = ripple(ts, c.endUs, -c.durationUs)
        }
        confirmTick++
        commit(ts)
        selectedClipId = c.id
    }

    override fun onCleared() {
        layers.values.forEach { it.release() }
        still.release()
        scrub.release()
        proxyBuilder.release()
        scopes.release()
        GradeGl.scopeSink = null
        saver.close()
    }
}

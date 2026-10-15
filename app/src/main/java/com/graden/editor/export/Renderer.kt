package com.graden.editor.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.graden.editor.CanvasGeoMath
import com.graden.editor.Clip
import com.graden.editor.GradeShader
import com.graden.editor.Look
import com.graden.editor.MediaAsset
import com.graden.editor.ProjectMeta
import com.graden.editor.Track
import com.graden.editor.Transitions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A frozen copy of the project for one export: nothing the editor does meanwhile can change what is rendered. */
class ExportProject(
    val meta: ProjectMeta,
    val tracks: List<Track>,
    val assets: Map<Int, MediaAsset>,
    /** The original file each asset is read from (a seekable copy where one was made). */
    val sources: Map<Int, String>,
    val muted: Set<Int>,
    val hidden: Set<Int>,
    /** Per clip: the gain that normalising its loudest moment gives, where normalising is on. */
    val normGain: Map<Long, Float>,
) {
    val totalUs: Long = tracks.maxOfOrNull { it.endUs } ?: 0L

    /**
     * Each picture clip as its lane plays it: lengthened into its source by half of any transition on either side, so
     * both pictures keep moving through the blend (as in the preview).
     */
    val extended: Map<Long, Clip> = buildMap {
        for (t in tracks) {
            if (!t.video) continue
            for (c in t.clips) {
                val a = assets[c.assetId] ?: continue
                var clip = c
                val after = t.clips.firstOrNull { it.startUs == c.endUs && it.transition != null }
                val tIn = c.transition?.takeIf { t.clips.any { it.endUs == c.startUs } }
                val longest = if (a.isImage) Long.MAX_VALUE / 4 else a.durationUs
                if (after != null) clip = clip.copy(outUs = minOf(longest, clip.outUs + (after.transition!!.durationUs / 2 * clip.speed).toLong()))
                if (tIn != null) {
                    val want = (tIn.durationUs / 2 * clip.speed).toLong()
                    val pre = if (a.isImage) want else minOf(want, clip.inUs)
                    clip = clip.copy(inUs = clip.inUs - pre, startUs = clip.startUs - (pre / clip.speed).toLong())
                }
                put(c.id, clip)
            }
        }
    }

    /** "Centre" scaling: the picture at its own pixel size on the canvas. */
    fun centreScale(c: Clip): Float {
        if (meta.scaling != "Centre") return 1f
        val a = assets[c.assetId] ?: return 1f
        if (a.width <= 0 || a.height <= 0) return 1f
        return maxOf(a.width.toFloat() / meta.width, a.height.toFloat() / meta.height)
    }
}

/** One picture to draw: which clip (as played), with what transition look, in what order on its lane. */
class Pic(val clip: Clip, val played: Clip, val asset: MediaAsset, val look: Look?, val z: Float)

/** What a lane shows at [us]: an optional backdrop colour, then pictures in drawing order. Mirrors the preview. */
fun lanePictures(p: ExportProject, t: Track, us: Long): Pair<Int?, List<Pic>> {
    fun pic(c: Clip, look: Look?, z: Float): Pic? {
        val a = p.assets[c.assetId] ?: return null
        if (a.isAudio) return null
        return Pic(c, p.extended[c.id] ?: c, a, look, z)
    }
    for (c in t.clips) {
        val tr = c.transition ?: continue
        val start = c.startUs - tr.durationUs / 2
        if (us < start || us >= start + tr.durationUs) continue
        val def = Transitions.byId[tr.type] ?: continue
        val prev = t.clips.firstOrNull { it.endUs == c.startUs } ?: continue
        val prog = (us - start).toFloat() / tr.durationUs
        val outLook = Look().also { def.out(it, prog) }
        val inLook = Look().also { def.into(it, prog) }
        val pics = listOfNotNull(
            pic(prev, outLook, if (def.outOnTop) 1f else 0f),
            pic(c, inLook, if (def.outOnTop) 0f else 1f),
        ).sortedBy { it.z }
        val back = def.backdrop?.let { android.graphics.Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
        return back to pics
    }
    val c = t.clipAt(us) ?: return null to emptyList()
    var look: Look? = null
    c.animIn?.let { fx ->
        val def = Transitions.byId[fx.type]
        val into = us - c.startUs
        if (def != null && into < fx.durationUs) look = Look().also { def.into(it, into.toFloat() / fx.durationUs) }
    }
    c.animOut?.let { fx ->
        val def = Transitions.byId[fx.type]
        val left = c.endUs - us
        if (def != null && left < fx.durationUs) look = (look ?: Look()).also { def.out(it, 1f - left.toFloat() / fx.durationUs) }
    }
    return null to listOfNotNull(pic(c, look, 0f))
}

/** Frame-available signals from every decoder's SurfaceTexture arrive on this thread. */
private object FrameSignals {
    val thread = HandlerThread("export-frames").apply { start() }
    val handler = Handler(thread.looper)
}

/**
 * A video file decoded forwards on the GPU. A thread of its own keeps the decoder running ahead of what is asked for,
 * throwing away frames that will never be shown (those before [floorUs]), so when [advance] asks for a frame it is
 * usually already decoded. [advance] then releases the frame showing at that time to the texture; [latch], on the GL
 * thread, takes it.
 */
class VideoSource(context: Context, path: String, private val asset: MediaAsset, startUs: Long) {
    val oes: Int
    private val texture: SurfaceTexture
    private val surface: Surface
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val frameUs: Long
    /** 0 SDR, 1 PQ (HDR10), 2 HLG. */
    val hdr: Int
    val width: Int
    val height: Int
    val texM = FloatArray(16)

    private val frameLock = Object()
    private var ready = false
    /** Guards the codec, the extractor and [queue]. */
    private val lock = Object()
    private var inputDone = false
    private var outputDone = false
    /** Decoded frames not yet released: (buffer index, time). */
    private val queue = ArrayDeque<LongArray>()
    /** No frame before this time will be asked for again: older ones are dropped as soon as a newer one is in. */
    @Volatile private var floorUs = startUs
    @Volatile private var running = true
    private val feeder: Thread
    private val info = MediaCodec.BufferInfo()

    /** The time of the frame now in the texture; -1 before the first. */
    var shownUs = -1L
        private set
    @Volatile private var pending = false
    @Volatile private var pendingUs = -1L
    /** The newest frame released (taken or not). */
    private var lastAdvancedUs = -1L

    init {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        oes = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        texture = SurfaceTexture(oes)
        texture.setOnFrameAvailableListener({
            synchronized(frameLock) {
                ready = true
                frameLock.notifyAll()
            }
        }, FrameSignals.handler)
        surface = Surface(texture)
        if (path.startsWith("content:")) extractor.setDataSource(context, Uri.parse(path), null)
        else extractor.setDataSource(if (path.startsWith("file:")) Uri.parse(path).path!! else path)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        extractor.selectTrack(track)
        val f = extractor.getTrackFormat(track)
        hdr = when (if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0) {
            MediaFormat.COLOR_TRANSFER_ST2084 -> 1
            MediaFormat.COLOR_TRANSFER_HLG -> 2
            else -> if (asset.hdr) 1 else 0
        }
        val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
        val fw = f.getInteger(MediaFormat.KEY_WIDTH)
        val fh = f.getInteger(MediaFormat.KEY_HEIGHT)
        width = if (rot % 180 == 0) fw else fh
        height = if (rot % 180 == 0) fh else fw
        val fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { asset.fps.takeIf { it > 1f } ?: 30f }
        frameUs = (1_000_000f / fps).toLong()
        f.setInteger(MediaFormat.KEY_PRIORITY, 1)
        // As fast as the hardware goes, not at playback pace.
        f.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        codec = runCatching { openDecoder(f) }.getOrElse {
            f.removeKey(MediaFormat.KEY_OPERATING_RATE)
            openDecoder(f)
        }
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        feeder = Thread({ feed() }, "export-decoder").apply { priority = Thread.NORM_PRIORITY + 1; start() }
    }

    /** The phone's preferred decoder; a software one if the hardware has none free. */
    private fun openDecoder(f: MediaFormat): MediaCodec {
        val mime = f.getString(MediaFormat.KEY_MIME)!!
        val names = ArrayList<String>()
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f)?.let { names += it }
        for (i in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (i.isEncoder || i.supportedTypes.none { it.equals(mime, true) } || i.name in names) continue
            names += i.name
        }
        var last: Exception? = null
        for (n in names) {
            val c = runCatching { MediaCodec.createByCodecName(n) }.getOrNull() ?: continue
            try {
                c.configure(f, surface, null, 0)
                c.start()
                return c
            } catch (e: Exception) {
                last = e
                runCatching { c.release() }
            }
        }
        throw IllegalStateException("No decoder could open ${asset.name}", last)
    }

    /** The decoding thread: keeps input flowing and collects frames, dropping the ones nobody will see. */
    private fun feed() {
        while (running) {
            var idle = true
            synchronized(lock) {
                if (!running) return
                if (!inputDone) {
                    val i = runCatching { codec.dequeueInputBuffer(0) }.getOrDefault(-1)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        idle = false
                    }
                }
                if (!outputDone) {
                    val o = runCatching { codec.dequeueOutputBuffer(info, 0) }.getOrDefault(-1)
                    if (o >= 0) {
                        idle = false
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (eos) outputDone = true
                        if (info.size == 0 && eos) codec.releaseOutputBuffer(o, false)
                        else queue.addLast(longArrayOf(o.toLong(), info.presentationTimeUs))
                        // A frame older than one that is itself not after the floor will never be shown.
                        val eps = frameUs / 2
                        while (queue.size >= 2 && queue.elementAt(1)[1] <= floorUs + eps) codec.releaseOutputBuffer(queue.removeFirst()[0].toInt(), false)
                        (lock as Object).notifyAll()
                    }
                }
            }
            if (idle) Thread.sleep(2)
        }
    }

    /** On the GL thread: takes the frame [advance] released into the texture. False if there is none at all. */
    fun latch(): Boolean {
        if (pending) {
            texture.updateTexImage()
            texture.getTransformMatrix(texM)
            shownUs = pendingUs
            pending = false
        }
        return shownUs >= 0
    }

    /** Releases to the texture the frame showing at [us] in the file: the last whose time is not after it. */
    fun advance(us: Long) {
        val target = us.coerceAtLeast(0L)
        val eps = frameUs / 2
        val at = lastAdvancedUs
        var chosen = -1
        var chosenUs = 0L
        synchronized(lock) {
            // Backwards, or far ahead: start again from the keyframe before.
            if ((at >= 0 && target < at - eps) || (at >= 0 && target > at + 3_000_000L && !outputDone)) {
                for (q in queue) runCatching { codec.releaseOutputBuffer(q[0].toInt(), false) }
                queue.clear()
                codec.flush()
                extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                inputDone = false
                outputDone = false
            }
            floorUs = target
            // Wait until a frame after the target is in (so the one showing is known), or the file has ended.
            // Waits as long as decoding there takes (a far keyframe can mean seconds of 4K); only a decoder that has
            // stopped producing anything for half a minute is given up on.
            var lastQ = -1L
            var lastChange = System.currentTimeMillis()
            while (queue.none { it[1] > target + eps } && !outputDone) {
                val newest = queue.lastOrNull()?.get(1) ?: -1L
                if (newest != lastQ) { lastQ = newest; lastChange = System.currentTimeMillis() }
                if (System.currentTimeMillis() - lastChange > 30_000) break
                (lock as Object).wait(5)
            }
            while (queue.isNotEmpty() && queue.first()[1] <= target + eps) {
                val q = queue.removeFirst()
                if (chosen >= 0) codec.releaseOutputBuffer(chosen, false)
                chosen = q[0].toInt()
                chosenUs = q[1]
            }
        }
        if (chosen < 0) return
        synchronized(frameLock) { ready = false }
        synchronized(lock) { codec.releaseOutputBuffer(chosen, true) }
        synchronized(frameLock) {
            val until = System.currentTimeMillis() + 1000
            while (!ready && System.currentTimeMillis() < until) frameLock.wait(20)
        }
        pendingUs = chosenUs
        pending = true
        lastAdvancedUs = chosenUs
    }

    fun release() {
        running = false
        runCatching { feeder.join(500) }
        synchronized(lock) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
        runCatching { surface.release() }
        runCatching { texture.release() }
        GLES20.glDeleteTextures(1, intArrayOf(oes), 0)
    }
}

/**
 * Draws the timeline's frames at full output resolution, from the original files, exactly as the preview composes them:
 * each lane bottom to top, pictures placed by fit/fill, moved, scaled, turned, flipped and cropped by hand, shaped by
 * transitions and animations, and graded. Edges are smoothed with 4× multisampling.
 */
class Compositor(private val context: Context, private val project: ExportProject, val outW: Int, val outH: Int, tenBitWanted: Boolean = false) {
    /** Rendering in half float (for 10-bit output); false if this GPU cannot draw into float buffers. */
    var tenBit = tenBitWanted
        private set
    /** The resolved frame as a texture (10-bit: sampled into the packed buffer), and the packed 10-bit buffer. */
    private var resolveTex = 0
    private var packFbo = 0
    private var packProg = 0
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var msaaFbo = 0
    private var msaaRb = 0
    private var smallFbo = 0
    /** The frame resolved from the multisampled buffer: what goes to the encoder and to the preview. */
    private var resolveFbo = 0
    private var resolveRb = 0
    private var smallTex = 0
    val previewW = 320
    val previewH: Int = (320f * outH / outW).toInt().coerceAtLeast(2) / 2 * 2

    private var gradeProg = 0
    private var copyProg = 0
    private var fillProg = 0
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    /** Canvas inside the output (the same unless the output's shape differs from the project's: then it is fitted). */
    private val canvas: IntArray

    private val videos = HashMap<Long, VideoSource>()
    private fun srcAt(p: Pic, us: Long) = p.played.srcAt(us - p.played.startUs).coerceIn(0L, (p.asset.durationUs - 1).coerceAtLeast(0L))

    private fun open(c: Clip, a: MediaAsset, startUs: Long): VideoSource? {
        val path = project.sources[a.id] ?: return null
        return runCatching { VideoSource(context, path, a, startUs) }.getOrNull()?.also { videos[c.id] = it }
    }

    /**
     * How long before its start a clip's decoder is opened: enough to decode from the keyframe before its first frame
     * (measured once per clip from the file's index), assuming the decoder runs at about twice real time.
     */
    private val leads = HashMap<Long, Long>()

    private fun leadOf(c: Clip): Long = leads.getOrPut(c.id) {
        val a = project.assets[c.assetId] ?: return@getOrPut PREFETCH_US
        val path = project.sources[a.id] ?: return@getOrPut PREFETCH_US
        val inUs = (project.extended[c.id] ?: c).inUs
        val dist = runCatching {
            val ex = MediaExtractor()
            try {
                if (path.startsWith("content:")) ex.setDataSource(context, Uri.parse(path), null)
                else ex.setDataSource(if (path.startsWith("file:")) Uri.parse(path).path!! else path)
                val tr = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ex.selectTrack(tr)
                ex.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                (inUs - ex.sampleTime).coerceAtLeast(0L)
            } finally {
                ex.release()
            }
        }.getOrDefault(0L)
        (PREFETCH_US + dist / 2).coerceAtMost(30_000_000L)
    }

    /** Clips that start within their look-ahead: their decoders are kept. */
    private fun prefetched(us: Long): Set<Long> {
        val out = HashSet<Long>()
        for (t in project.tracks) if (t.video) for (c in t.clips) {
            val st = (project.extended[c.id] ?: c).startUs
            if (st > us && st <= us + 30_000_000L && st <= us + leadOf(c)) out += c.id
        }
        return out
    }

    private val pool = java.util.concurrent.Executors.newFixedThreadPool(8) { r -> Thread(r, "export-decode").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private val images = HashMap<Int, IntArray>() // assetId -> (tex, w, h)
    /** Per clip: a mip-mapped copy of its current frame (tex, fbo, w, h). */
    private val copies = HashMap<Long, IntArray>()

    init {
        val pa = project.meta.width.toFloat() / project.meta.height
        val oa = outW.toFloat() / outH
        canvas = if (abs(pa - oa) < 0.01f) intArrayOf(0, 0, outW, outH)
        else if (pa > oa) { val h = (outW / pa).toInt(); intArrayOf(0, (outH - h) / 2, outW, h) }
        else { val w = (outH * pa).toInt(); intArrayOf((outW - w) / 2, 0, w, outH) }
    }

    /** Sets up the GL context, drawing into [encoderSurface] (or off-screen when null). */
    fun start(encoderSurface: Surface?) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
            ), 0, configs, 0, 1, n, 0,
        )
        config = configs[0] ?: error("No GL configuration for export")
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        if (encoderSurface != null) window = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        val target = if (window != EGL14.EGL_NO_SURFACE) window else pbuffer
        check(EGL14.eglMakeCurrent(display, target, target, eglContext)) { "Could not start GL for export" }

        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        if (tenBit && !ext.contains("GL_EXT_color_buffer_half_float") && !ext.contains("GL_EXT_color_buffer_float")) tenBit = false
        val colour = if (tenBit) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, max, 0)
        val samples = min(4, max[0]).coerceAtLeast(1)
        val ids = IntArray(1)
        GLES30.glGenRenderbuffers(1, ids, 0); msaaRb = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msaaRb)
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, colour, outW, outH)
        GLES30.glGenFramebuffers(1, ids, 0); msaaFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msaaRb)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Export picture buffer could not be made at ${outW}×$outH" }
        GLES20.glGenTextures(1, ids, 0); resolveTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resolveTex)
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, colour, outW, outH)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES30.glGenFramebuffers(1, ids, 0); resolveFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resolveFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, resolveTex, 0)
        if (tenBit) {
            // The half-float frame is drawn into a 10-bit buffer to be read back at 4 bytes a pixel.
            GLES30.glGenRenderbuffers(1, ids, 0); resolveRb = ids[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, resolveRb)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGB10_A2, outW, outH)
            GLES30.glGenFramebuffers(1, ids, 0); packFbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, packFbo)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, resolveRb)
        }
        // A small copy for the progress preview.
        GLES20.glGenTextures(1, ids, 0); smallTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, smallTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, previewW, previewH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES30.glGenFramebuffers(1, ids, 0); smallFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, smallFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, smallTex, 0)

        gradeProg = link(PIC_VERTEX, gradeFragment())
        copyProg = link(COPY_VERTEX, COPY_FRAGMENT)
        packProg = link(COPY_VERTEX, PACK_FRAGMENT)
        fillProg = link(COPY_VERTEX, FILL_FRAGMENT)
    }

    /** Renders the frame at timeline time [us] into the picture buffer, then to the encoder with time [ptsNs]. */
    private val acc = LongArray(4)
    private var accN = 0

    fun render(us: Long, ptsNs: Long) {
        val t0 = System.nanoTime()
        val used = HashSet<Long>()
        val pics = ArrayList<Pair<Int?, List<Pic>>>()
        for (t in project.tracks) {
            if (!t.video || t.id in project.hidden) continue
            val lp = lanePictures(project, t, us)
            pics += lp
            for (p in lp.second) used += p.clip.id
        }
        // Every lane's decoder moves to its frame at once, each on its own thread; then the frames are taken on this one.
        val jobs = ArrayList<java.util.concurrent.Future<*>>()
        for ((_, list) in pics) for (p in list) {
            if (p.asset.isImage) continue
            val srcUs = srcAt(p, us)
            val v = videos[p.clip.id] ?: open(p.clip, p.asset, srcUs) ?: continue
            jobs += pool.submit { v.advance(srcUs) }
        }
        // Clips starting soon are opened now, so their decoders warm up (seek and decode to their first frame) in the
        // background while this stretch renders.
        for (t in project.tracks) {
            if (!t.video || t.id in project.hidden || videos.size >= MAX_DECODERS) continue
            for (c in t.clips) {
                val played = project.extended[c.id] ?: c
                if (played.startUs <= us || played.startUs > us + 30_000_000L || videos.containsKey(c.id)) continue
                if (played.startUs > us + leadOf(c)) continue
                val a = project.assets[c.assetId] ?: continue
                if (a.isImage || a.isAudio) continue
                open(c, a, played.inUs)
                used += c.id
            }
        }
        for (j in jobs) j.get()
        val tA = System.nanoTime()
        // Decoders of clips no longer on screen (or about to be) are closed, so the phone's decoders are never all taken.
        for (id in videos.keys.toList()) if (id !in used && id !in prefetched(us)) {
            videos.remove(id)?.release()
            copies.remove(id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it[0]), 0); GLES30.glDeleteFramebuffers(1, intArrayOf(it[1]), 0) }
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(canvas[0], outH - canvas[1] - canvas[3], canvas[2], canvas[3])
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for ((backdrop, list) in pics) {
            backdrop?.let { fill(it) }
            for (p in list) drawPicture(p, us)
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glFinish()
        val tB = System.nanoTime()
        // Resolve the samples once; the resolved frame goes to the encoder (and the preview, when asked).
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msaaFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFbo)
        GLES30.glBlitFramebuffer(0, 0, outW, outH, 0, 0, outW, outH, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST)
        if (window != EGL14.EGL_NO_SURFACE) {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, resolveFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
            GLES30.glBlitFramebuffer(0, 0, outW, outH, 0, 0, outW, outH, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST)
            EGLExt.eglPresentationTimeANDROID(display, window, ptsNs)
            EGL14.eglSwapBuffers(display, window)
        }
        val tC = System.nanoTime()
        acc[0] += tA - t0; acc[1] += tB - tA; acc[2] += tC - tB
        if (++accN == 60) {
            android.util.Log.d("ExportProbe", "per frame: decode %.1f ms, draw %.1f ms, swap %.1f ms, decoders %d".format(acc[0] / 6e7, acc[1] / 6e7, acc[2] / 6e7, videos.size))
            acc.fill(0); accN = 0
        }
    }

    /** A small picture of the last frame, for the progress screen. */
    fun preview(): Bitmap {
        drawTexture(resolveTex, smallFbo, previewW, previewH)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, smallFbo)
        val buf = ByteBuffer.allocateDirect(previewW * previewH * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, previewW, previewH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
        buf.position(0)
        bmp.copyPixelsFromBuffer(buf)
        // GL rows run bottom to top; the frame was drawn the right way up for the encoder, so this copy is flipped.
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(bmp, 0, 0, previewW, previewH, m, false)
    }

    /**
     * Reads the whole frame, bottom row first, 4 bytes a pixel: RGBA 8-bit, or (10-bit) packed RGB 10:10:10 — see
     * [SoftEncoder.FMT_RGB10]. Returns the format.
     */
    fun readFrame(into: ByteBuffer): Int {
        into.position(0)
        if (tenBit) {
            drawTexture(resolveTex, packFbo, outW, outH)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, packFbo)
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, into)
            return SoftEncoder.FMT_RGB10
        }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, resolveFbo)
        GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, into)
        return SoftEncoder.FMT_RGBA8
    }

    /** Draws texture [tex] over the whole of framebuffer [fbo] ([w]×[h]). */
    private fun drawTexture(tex: Int, fbo: Int, w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glUseProgram(packProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(packProg, "uSrc"), 0)
        drawQuad(packProg)
    }

    private fun fill(color: Int) {
        GLES20.glViewport(canvas[0], outH - canvas[1] - canvas[3], canvas[2], canvas[3])
        GLES20.glUseProgram(fillProg)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(fillProg, "uColor"),
            android.graphics.Color.red(color) / 255f, android.graphics.Color.green(color) / 255f, android.graphics.Color.blue(color) / 255f, 1f)
        drawQuad(fillProg)
    }

    private fun drawPicture(p: Pic, us: Long) {
        val a = p.asset
        val cw = canvas[2].toFloat()
        val ch = canvas[3].toFloat()
        val aspect = if (a.width > 0 && a.height > 0) a.width.toFloat() / a.height else 16f / 9f
        val fillMode = project.meta.scaling == "Fill"
        val (bw, bh) = CanvasGeoMath.base(cw, ch, aspect, fillMode)
        val bx = (cw - bw) / 2f
        val by = (ch - bh) / 2f
        val tf = p.clip.tf
        val look = p.look
        val s = project.centreScale(p.clip) * tf.scale
        val sx = s * (look?.sx ?: 1f) * (if (tf.flipH) -1f else 1f)
        val sy = s * (look?.sy ?: 1f) * (if (tf.flipV) -1f else 1f)
        val rot = Math.toRadians((tf.rot + (look?.rot ?: 0f)).toDouble())
        val tx = (tf.x + (look?.tx ?: 0f)) * cw
        val ty = (tf.y + (look?.ty ?: 0f)) * ch
        val alpha = (look?.alpha ?: 1f).coerceIn(0f, 1f)
        if (alpha <= 0.001f || abs(sx) < 1e-4f || abs(sy) < 1e-4f) return
        // Where the picture lands, in output pixels, for choosing how much of the source to keep.
        val shownW = abs(bw * sx)
        val shownH = abs(bh * sy)

        val src = sourceTexture(p, us, shownW, shownH) ?: return

        // Local (canvas) point q to canvas: c + t + R·S·(q - c).
        val cx = cw / 2f
        val cy = ch / 2f
        val cr = cos(rot).toFloat()
        val sr = sin(rot).toFloat()
        val m = floatArrayOf(
            cr * sx, sr * sx, 0f,
            -sr * sy, cr * sy, 0f,
            cx + tx - (cr * sx * cx - sr * sy * cy), cy + ty - (sr * sx * cx + cr * sy * cy), 1f,
        )
        GLES20.glViewport(canvas[0], outH - canvas[1] - canvas[3], canvas[2], canvas[3])
        GLES20.glUseProgram(gradeProg)
        fun u(n: String) = GLES20.glGetUniformLocation(gradeProg, n)
        GLES20.glUniformMatrix3fv(u("uM"), 1, false, m, 0)
        GLES20.glUniform2f(u("uCanvas"), cw, ch)
        GLES20.glUniform4f(u("uBase"), bx, by, bx + bw, by + bh)
        val cl = bx + tf.cropL * bw
        val ct = by + tf.cropT * bh
        GLES20.glUniform4f(u("uCrop"), cl, ct, bx + bw - tf.cropR * bw, by + bh - tf.cropB * bh)
        if (look != null && look.circle >= 0f) {
            GLES20.glUniform4f(u("uClip"), -1e6f, -1e6f, 1e6f, 1e6f)
            GLES20.glUniform3f(u("uCircle"), cx, cy, look.circle * hypot(cw, ch) / 2f)
        } else {
            if (look != null && look.clipped) GLES20.glUniform4f(u("uClip"), look.left * cw, look.top * ch, look.right * cw, look.bottom * ch)
            else GLES20.glUniform4f(u("uClip"), -1e6f, -1e6f, 1e6f, 1e6f)
            GLES20.glUniform3f(u("uCircle"), 0f, 0f, -1f)
        }
        GLES20.glUniform1f(u("uAlpha"), alpha)
        val g = p.clip.grade
        GLES20.glUniform1f(u("uOn"), if (g.isIdentity) 0f else 1f)
        GLES20.glUniform4fv(u("uG"), 7, g.uniforms(), 0)
        // Mid/Detail measures its blur in pixels of the picture as the phone's preview shows it, so it looks the same.
        val previewScale = 1080f / max(cw, ch)
        GLES20.glUniform2f(u("uTexel"), 1f / (shownW * previewScale).coerceAtLeast(1f), 1f / (shownH * previewScale).coerceAtLeast(1f))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src)
        GLES20.glUniform1i(u("uSrc"), 0)
        drawQuad(gradeProg)
    }

    /**
     * The picture to sample for [p] at [us], as a mip-mapped 2D texture: images as uploaded; video frames copied out of
     * the decoder at no more than half their size (an exact 2×2 average, HDR tone-mapped to SDR on the way), so any later
     * reduction is filtered smoothly instead of shimmering.
     */
    private fun sourceTexture(p: Pic, us: Long, shownW: Float, shownH: Float): Int? {
        val a = p.asset
        val path = project.sources[a.id] ?: return null
        if (a.isImage) {
            images[a.id]?.let { return it[0] }
            val bmp = loadImage(path) ?: return null
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            mip()
            images[a.id] = intArrayOf(t[0], bmp.width, bmp.height)
            bmp.recycle()
            return t[0]
        }
        val v = videos[p.clip.id] ?: return null
        if (!v.latch()) return null
        // Copy size: the source, halved once when it is large and shown smaller than half.
        val half = minOf(v.width, v.height) > 1080 && shownW < v.width / 2f && shownH < v.height / 2f
        val w = if (half) v.width / 2 else v.width
        val h = if (half) v.height / 2 else v.height
        var c = copies[p.clip.id]
        if (c == null || c[2] != w || c[3] != h) {
            c?.let { GLES20.glDeleteTextures(1, intArrayOf(it[0]), 0); GLES30.glDeleteFramebuffers(1, intArrayOf(it[1]), 0) }
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            val levels = 1 + (Math.log(max(w, h).toDouble()) / Math.log(2.0)).toInt()
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, levels, if (tenBit) GLES30.GL_RGBA16F else GLES30.GL_RGBA8, w, h)
            val f = IntArray(1)
            GLES30.glGenFramebuffers(1, f, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
            c = intArrayOf(t[0], f[0], w, h)
            copies[p.clip.id] = c
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, c[1])
        GLES20.glViewport(0, 0, w, h)
        val blend = GLES20.glIsEnabled(GLES20.GL_BLEND)
        val scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glUseProgram(copyProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.oes)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProg, "uSrc"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyProg, "uTex"), 1, false, v.texM, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(copyProg, "uHdr"), v.hdr.toFloat())
        drawQuad(copyProg)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, c[0])
        mip()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
        if (blend) GLES20.glEnable(GLES20.GL_BLEND)
        if (scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        return c[0]
    }

    private fun mip() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
    }

    private fun loadImage(path: String): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        if (path.startsWith("content:")) context.contentResolver.openInputStream(Uri.parse(path))!!.use { BitmapFactory.decodeStream(it, null, o) }
        else BitmapFactory.decodeFile(if (path.startsWith("file:")) Uri.parse(path).path else path, o)
    }.getOrNull()

    private fun drawQuad(prog: Int) {
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun link(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Export shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    fun release() {
        pool.shutdownNow()
        videos.values.forEach { it.release() }
        videos.clear()
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        /** How far ahead clips are opened, and how many decoders may be open at once (the encoder needs one too). */
        const val PREFETCH_US = 1_500_000L
        const val MAX_DECODERS = 12

        const val PIC_VERTEX = """
attribute vec2 aPos;
uniform mat3 uM;
uniform vec2 uCanvas;
uniform vec4 uBase;
varying vec2 vLocal;
void main() {
    vec2 p = mix(uBase.xy, uBase.zw, aPos);
    vLocal = p;
    vec3 q = uM * vec3(p, 1.0);
    gl_Position = vec4(q.x / uCanvas.x * 2.0 - 1.0, 1.0 - q.y / uCanvas.y * 2.0, 0.0, 1.0);
}
"""

        /** The preview's grade, word for word, reading a 2D texture and placed, cropped and masked for export. */
        fun gradeFragment(): String {
            val g = GradeShader.FRAGMENT
            var body = g.substring(g.indexOf("const vec3 LUMA"))
            body = body.replace("vec3 at(vec2 p) { return texture2D(uSrc, (uTex * vec4(p, 0.0, 1.0)).xy).rgb; }", "vec3 at(vec2 p) { return texture2D(uSrc, p).rgb; }")
            body = body.replace("gl_FragColor = vec4(src, 1.0); return;", "return vec4(src, 1.0);")
            body = body.replace("void main()", "vec4 gradeMain()")
            body = body.replace("gl_FragColor = ", "return ")
            check(body.contains("vec4 gradeMain()") && body.contains("texture2D(uSrc, p)")) { "Grade shader changed; export needs updating" }
            return """
#extension GL_OES_standard_derivatives : enable
precision highp float;
varying vec2 vLocal;
vec2 vUv;
uniform sampler2D uSrc;
uniform vec4 uG[7];
uniform float uOn;
uniform vec2 uTexel;
uniform vec4 uBase;
uniform vec4 uCrop;
uniform vec4 uClip;
uniform vec3 uCircle;
uniform float uAlpha;
""" + body + """
float edge(vec4 r, vec2 p, float aa) {
    float d = min(min(p.x - r.x, r.z - p.x), min(p.y - r.y, r.w - p.y));
    return clamp(d / aa + 0.5, 0.0, 1.0);
}
void main() {
    vec2 p = vLocal;
    float aa = max(length(fwidth(p)), 1e-4);
    float cover = edge(uCrop, p, aa) * edge(uClip, p, aa);
    if (uCircle.z >= 0.0) cover *= clamp((uCircle.z - length(p - uCircle.xy)) / aa + 0.5, 0.0, 1.0);
    if (cover <= 0.0) discard;
    vUv = (p - uBase.xy) / (uBase.zw - uBase.xy);
    vec4 c = gradeMain();
    float a = uAlpha * cover;
    gl_FragColor = vec4(c.rgb * a, a);
}
"""
        }

        const val COPY_VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos;
    gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);
}
"""

        /** Copies a decoded frame upright into a 2D texture; HDR (PQ or HLG) is tone-mapped to SDR Rec.709. */
        const val COPY_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vUv;
uniform samplerExternalOES uSrc;
uniform mat4 uTex;
uniform float uHdr;

vec3 pq(vec3 e) {
    vec3 p = pow(max(e, 0.0), vec3(1.0 / 78.84375));
    return pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(1.0 / 0.1593017578125)) * 10000.0;
}
vec3 hlg(vec3 e) {
    vec3 a = e * e / 3.0;
    vec3 b = (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
    vec3 s = mix(a, b, step(0.5, e));
    float y = dot(s, vec3(0.2627, 0.6780, 0.0593));
    return s * pow(max(y, 1e-6), 0.2) * 1000.0;
}
vec3 toSdr(vec3 nits) {
    // BT.2020 to BT.709 primaries, then a smooth roll-off with diffuse white at 203 nits.
    vec3 c = mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187) * nits;
    vec3 x = max(c, 0.0) / 203.0;
    float L = 1000.0 / 203.0;
    vec3 y = x * (1.0 + x / (L * L)) / (1.0 + x);
    return pow(clamp(y, 0.0, 1.0), vec3(1.0 / 2.4));
}
void main() {
    // The decoder's frames are stored bottom row first; this copy is top row first, like an image.
    vec3 c = texture2D(uSrc, (uTex * vec4(vUv.x, 1.0 - vUv.y, 0.0, 1.0)).xy).rgb;
    if (uHdr > 1.5) c = toSdr(hlg(c));
    else if (uHdr > 0.5) c = toSdr(pq(c));
    gl_FragColor = vec4(c, 1.0);
}
"""

        /** Copies a texture as it is (into the packed 10-bit buffer, or the small preview). */
        const val PACK_FRAGMENT = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uSrc;
void main() { gl_FragColor = vec4(clamp(texture2D(uSrc, vUv).rgb, 0.0, 1.0), 1.0); }
"""

        const val FILL_FRAGMENT = """
precision mediump float;
uniform vec4 uColor;
void main() { gl_FragColor = uColor; }
"""
    }
}

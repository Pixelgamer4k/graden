package com.graden.editor

import android.content.Context
import android.media.MediaCodecInfo
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.math.min

/**
 * Builds proxies in the background, one at a time. Must be used from the main thread.
 *  - SCRUB: every file gets one. H.264, 240p short side, ten frames a second, every frame a keyframe, no audio.
 *  - PLAY: every video gets this edit proxy. H.264 with audio, 540p on the short side (432p for files the profiler
 *    found heavy for this device), at most 30 frames a second, and a keyframe every quarter of a second. Ordinary
 *    files keep a keyframe only every second or more, so every seek, every clip parked ready for a cut and every
 *    resync while playing has to decode up to a second of frames nobody sees; with several layers that work piles
 *    up on the decoder and shows as stalls. Here any frame is at most a few frames away. Playback always uses it;
 *    the original is shown only while paused, and is what export will read.
 * HDR sources are tone-mapped to SDR in both.
 */
@OptIn(UnstableApi::class)
class ProxyBuilder(
    private val context: Context,
    private val onReady: (assetId: Int, kind: Kind, path: String) -> Unit,
    /** Current job as (assetId, kind, percent); assetId is -1 when idle. */
    private val onJob: (assetId: Int, kind: Kind?, percent: Int) -> Unit,
    /** A job did not finish and will be tried again: (assetId, kind, attempt, why). Nothing is ever given up. */
    private val onRetry: (assetId: Int, kind: Kind, attempt: Int, reason: String) -> Unit,
    /** Hardware codecs ran short: the editor should let go of the ones it is not using right now. */
    private val onCodecPressure: () -> Unit,
) {
    enum class Kind { SCRUB, PLAY, SEEKABLE }

    /**
     * [attempt] counts tries so far; each retry escalates the strategy (see [Strategy]). [noAudio] sticks once the
     * sound turned out to be a format the phone cannot decode: the picture is what playback needs.
     */
    private class Job(val asset: MediaAsset, val kind: Kind, val attempt: Int = 0, val noAudio: Boolean = false)

    /** How a try is made. Later attempts trade a little speed or size for whatever works on this phone right now. */
    private class Strategy(val noAudio: Boolean, val shortSide: Int?, val decoderFallback: Boolean, val softwareEncoderFirst: Boolean)

    private fun strategyFor(job: Job): Strategy {
        val level = if (job.attempt == 0) 0 else 1 + (job.attempt - 1) % 5
        // Sound is dropped only when it is known to be the problem (or for the scrub proxy, which never has it).
        val noAudio = job.noAudio || job.kind == Kind.SCRUB
        return when (level) {
            0, 1 -> Strategy(noAudio, null, false, false)            // as designed (1: again, after codecs were freed)
            2 -> Strategy(noAudio, null, true, false)               // software decoders allowed as a fallback
            3 -> Strategy(noAudio, 432, true, false)                // a smaller picture is lighter on every codec
            4 -> Strategy(noAudio, 432, true, true)                 // the software encoder first
            else -> Strategy(true, 360, true, true)                 // the lightest form that still serves playback
        }
    }

    /** No job starts before this time: after a codec shortage the whole queue waits, instead of failing in a row. */
    private var pausedUntil = 0L

    private val dir = File(context.filesDir, "proxies").apply { mkdirs() }
    private val queue = ArrayDeque<Job>()
    private val main = Handler(Looper.getMainLooper())
    private var current: Transformer? = null
    private var currentJob: Job? = null
    private val progress = ProgressHolder()

    /** The proxy file of [a]; [hdr] proxies (kept HDR, 10-bit HEVC) end in "h". */
    private fun fileFor(a: MediaAsset, kind: Kind, hdr: Boolean = false): File {
        val base = "${a.uri.hashCode().toUInt()}_${a.durationUs}"
        val h = if (hdr) "h" else ""
        return File(
            dir,
            when (kind) {
                Kind.SCRUB -> "${base}_r${REVISION}_s$SCRUB_SHORT_SIDE$h.mp4"
                Kind.PLAY -> "${base}_e${PLAY_REVISION}_p${DeviceProfiler.proxyShortSide(a.tier)}$h.mp4"
                Kind.SEEKABLE -> "${base}_seek.mp4"
            },
        )
    }

    /**
     * Proxies of [a] are kept in HDR: it is HDR and this phone has a hardware HEVC Main 10 encoder (only standard
     * codec queries). Elsewhere HDR clips get SDR proxies, tone-mapped, as before.
     */
    private fun keepsHdr(a: MediaAsset): Boolean = a.hdr && hevc10

    private val hevc10: Boolean by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && (android.os.Build.VERSION.SDK_INT < 29 || info.isHardwareAccelerated) &&
                    info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, true) } &&
                    info.getCapabilitiesForType(MimeTypes.VIDEO_H265).profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
            }
        }.getOrDefault(false)
    }

    /** The file says it is HDR (PQ or HLG). */
    private fun isHdrFile(f: File): Boolean = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(f.path)
            val fmt = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.first { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val t = if (fmt.containsKey(android.media.MediaFormat.KEY_COLOR_TRANSFER)) fmt.getInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER) else 0
            t == android.media.MediaFormat.COLOR_TRANSFER_ST2084 || t == android.media.MediaFormat.COLOR_TRANSFER_HLG
        } finally {
            ex.release()
        }
    }.getOrDefault(false)

    init {
        // Proxies made with earlier settings carry another revision (or none) in their name; they are no
        // longer used, so reclaim the space.
        // Play proxies of the previous kind ("_r3_p") stay until their replacement is ready, so playback never
        // falls back to the heavy original meanwhile.
        dir.listFiles()?.forEach { f ->
            val keep = "_r${REVISION}_s" in f.name || "_e${PLAY_REVISION}_p" in f.name || "_r${REVISION}_p" in f.name || "_seek." in f.name
            if (!keep) f.delete()
        }
    }

    /** The play proxy made before edit proxies, if one is still on disk. */
    private fun legacyPlay(a: MediaAsset): File =
        File(dir, "${a.uri.hashCode().toUInt()}_${a.durationUs}_r${REVISION}_p${DeviceProfiler.proxyShortSide(a.tier)}.mp4")

    /**
     * Queues whatever [a] still needs; proxies that already exist are reported straight away. [clipByClip] (an import)
     * keeps the file's two proxies together at the end of the queue, so each clip is finished before the next starts.
     */
    fun enqueue(a: MediaAsset, clipByClip: Boolean = false) {
        if (a.isAudio || a.isImage) return
        enqueue(a, Kind.SCRUB, clipByClip)
        if (DeviceProfiler.needsPlayProxy(a)) enqueue(a, Kind.PLAY, clipByClip)
        // A seekable copy made earlier is picked up again.
        fileFor(a, Kind.SEEKABLE).takeIf { it.exists() }?.let { onReady(a.id, Kind.SEEKABLE, it.path) }
    }

    /**
     * For a file that cannot be seeked (some fragmented MP4s): rewrites it into an ordinary MP4. The picture
     * and sound are copied as they are, not re-encoded, so nothing is lost; it goes ahead of other jobs.
     */
    fun makeSeekable(a: MediaAsset) {
        val f = fileFor(a, Kind.SEEKABLE)
        if (f.exists()) {
            onReady(a.id, Kind.SEEKABLE, f.path)
            return
        }
        fun same(j: Job?) = j != null && j.asset.uri == a.uri && j.kind == Kind.SEEKABLE
        if (same(currentJob) || queue.any { same(it) }) return
        queue.addFirst(Job(a, Kind.SEEKABLE))
        pump()
    }

    private fun enqueue(a: MediaAsset, kind: Kind, clipByClip: Boolean) {
        val hdr = keepsHdr(a)
        val h = fileFor(a, kind, hdr = true)
        val f = fileFor(a, kind)
        if (hdr && h.exists()) {
            onReady(a.id, kind, h.path)
            return
        }
        if (f.exists()) {
            onReady(a.id, kind, f.path)
            // An SDR proxy of an HDR clip serves until its HDR one is made.
            if (!hdr) return
        }
        // Until its edit proxy is made, an older play proxy keeps playback light.
        if (kind == Kind.PLAY) legacyPlay(a).takeIf { it.exists() }?.let { onReady(a.id, kind, it.path) }
        fun same(j: Job?) = j != null && j.asset.id == a.id && j.kind == kind
        if (same(currentJob) || queue.any { same(it) }) return
        // Scrub proxies first: they are quick and make the timeline smooth for every clip.
        if (kind == Kind.SCRUB && !clipByClip) queue.add(queue.indexOfFirst { it.kind == Kind.PLAY }.let { if (it < 0) queue.size else it }, Job(a, kind))
        else queue.addLast(Job(a, kind))
        pump()
    }

    private fun pump() {
        if (current != null) return
        val wait = pausedUntil - android.os.SystemClock.uptimeMillis()
        if (wait > 0) {
            main.removeCallbacks(pumpLater)
            main.postDelayed(pumpLater, wait)
            return
        }
        val job = queue.removeFirstOrNull()
        if (job == null) {
            onJob(-1, null, 0)
            return
        }
        try {
            start(job)
        } catch (e: Exception) {
            // Even a failure to set the job up is only a reason to try again.
            retry(job, "setup: ${e.message}", resource = true)
        }
    }

    private val pumpLater = Runnable { pump() }

    /** Whether the hardware H.264 encoder (the one a proxy gets) can hold a constant bitrate. */
    private val cbrH264: Boolean by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MimeTypes.VIDEO_H264, true) } && (android.os.Build.VERSION.SDK_INT < 29 || it.isHardwareAccelerated) }
                .firstOrNull()?.getCapabilitiesForType(MimeTypes.VIDEO_H264)?.encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }.getOrNull() ?: false
    }

    private fun start(job: Job) {
        val a = job.asset
        // HDR clips keep their HDR in the proxy where the phone can encode it; after two failed tries, SDR.
        val hdr = job.kind != Kind.SEEKABLE && keepsHdr(a) && job.attempt < 2
        var out = fileFor(a, job.kind, hdr)
        val tmp = File(out.path + ".tmp")
        tmp.delete()
        currentTmp = tmp
        expectedBytes = 0L
        // Proxies come first: before every job the editor lets go of the codecs it is not showing, so this job has room
        // and is not the one the system takes them back from halfway through.
        onCodecPressure()
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                // Named by what was really written: some encoders quietly fall back to SDR.
                if (hdr && !isHdrFile(tmp)) {
                    android.util.Log.w("ProxyBuilder", "${a.name}: HDR proxy came out SDR")
                    out = fileFor(a, job.kind)
                }
                if (tmp.renameTo(out) && out.length() > 0) {
                    onReady(a.id, job.kind, out.path)
                    // The SDR stand-in is no longer needed.
                    if (out.name.endsWith("h.mp4")) fileFor(a, job.kind).delete()
                    if (job.kind == Kind.PLAY) legacyPlay(a).delete()
                    next()
                } else {
                    tmp.delete()
                    current = null
                    currentJob = null
                    retry(job, "could not save the result", resource = false)
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                tmp.delete()
                current = null
                currentJob = null
                val code = exportException.errorCode
                android.util.Log.w("ProxyBuilder", "${job.kind} try ${job.attempt + 1} failed for ${a.name}: ${exportException.errorCodeName} ${exportException.message}")
                // A sound format with no decoder on this phone (Dolby TrueHD, say): carry on with the picture alone.
                val audioProblem = exportException.message?.contains("AudioDecoder") == true || exportException.message?.contains("audio/") == true
                if (audioProblem && job.kind == Kind.PLAY && !job.noAudio) {
                    queue.addFirst(Job(a, job.kind, job.attempt, noAudio = true))
                    pump()
                    return
                }
                // Codecs that could not be created or were taken away mean the hardware is busy, not that the file is bad.
                val resource = code == ExportException.ERROR_CODE_DECODER_INIT_FAILED || code == ExportException.ERROR_CODE_ENCODER_INIT_FAILED ||
                    code == ExportException.ERROR_CODE_DECODING_FAILED || code == ExportException.ERROR_CODE_ENCODING_FAILED ||
                    code == ExportException.ERROR_CODE_UNSPECIFIED
                retry(job, exportException.errorCodeName, resource)
            }
        }
        if (job.kind == Kind.SEEKABLE) {
            val t = Transformer.Builder(context).addListener(listener).build()
            current = t
            currentJob = job
            t.start(EditedMediaItem.Builder(MediaItem.fromUri(a.uri)).build(), tmp.path)
            poll()
            return
        }
        val st = strategyFor(job)
        val designed = if (job.kind == Kind.SCRUB) SCRUB_SHORT_SIDE else DeviceProfiler.proxyShortSide(a.tier)
        val target = minOf(designed, st.shortSide ?: designed)
        val scale = min(1f, target / min(a.width, a.height).toFloat())
        val outH = ((a.height * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val outW = (a.width * scale).toInt()
        // The two proxies together are budgeted at about 500 MB per hour of footage:
        //  - scrub: every frame a keyframe, which is costly, so it is small and keeps only [SCRUB_FPS] frames a second.
        //    That is still a new picture every tenth of a second while dragging.
        //  - play: ordinary H.264 at no more than 30 frames a second, with its sound at a modest rate.
        val scrub = job.kind == Kind.SCRUB
        val fps = if (scrub) SCRUB_FPS else min(a.fps, 30f)
        val settings = VideoEncoderSettings.Builder()
        if (scrub) settings.setBitrate((outW * outH * fps * 0.35f).toInt().coerceIn(200_000, 600_000)).setiFrameIntervalSeconds(0f)
        // Constant bitrate: left to itself the phone's encoder ignores a low target and spends about 1 Mbps anyway.
        // The short keyframe interval costs some size; the bitrate is raised a little to keep the picture as clean.
        // Where the phone's H.264 encoder has no constant-bitrate mode (MediaTek's, for one), variable: asked for a mode
        // it does not have, it refuses the job outright.
        else settings.setBitrate((outW * outH * fps * 0.062f).toInt().coerceIn(400_000, 1_400_000)).setiFrameIntervalSeconds(PLAY_KEYFRAME_S)
            .setBitrateMode(if (cbrH264) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        val effects = ArrayList<androidx.media3.common.Effect>()
        if (a.fps > fps + 1f) effects += FrameDropEffect.createDefaultFrameDropEffect(fps)
        effects += Presentation.createForHeight(outH)
        val videoBps = if (scrub) (outW * outH * fps * 0.35f).toLong().coerceIn(200_000, 600_000) else (outW * outH * fps * 0.062f).toLong().coerceIn(400_000, 1_400_000)
        expectedBytes = (videoBps + if (scrub || st.noAudio) 0 else 96_000) * a.durationUs / 8_000_000L
        val item = EditedMediaItem.Builder(MediaItem.fromUri(a.uri))
            .setRemoveAudio(scrub || st.noAudio)
            .setEffects(Effects(emptyList(), effects))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setHdrMode(if (hdr) Composition.HDR_MODE_KEEP_HDR else Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(settings.build())
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(96_000).build())
            .setEnableFallback(true)
        if (st.softwareEncoderFirst) encoders.setVideoEncoderSelector { mime ->
            // Software encoders (c2.android / OMX.google) ahead of the hardware ones, which may all be busy.
            val all = androidx.media3.transformer.EncoderUtil.getSupportedEncoders(mime)
            com.google.common.collect.ImmutableList.copyOf(all.sortedBy { info -> if (info.name.startsWith("c2.android") || info.name.startsWith("OMX.google")) 0 else 1 })
        }
        val builder = Transformer.Builder(context)
            .setVideoMimeType(if (hdr) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoders.build())
            .addListener(listener)
        if (st.decoderFallback) builder.setAssetLoaderFactory(
            androidx.media3.transformer.DefaultAssetLoaderFactory(
                context,
                androidx.media3.transformer.DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build(),
                androidx.media3.common.util.Clock.DEFAULT,
                null,
            )
        )
        val t = builder.build()
        current = t
        currentJob = job
        t.start(composition, tmp.path)
        poll()
    }

    /**
     * Puts [job] back at the front, to be tried again with the next strategy. When codecs ran short, the editor is asked
     * to free the ones it is not using and the whole queue waits a little (longer each time, at most a minute), so
     * jobs never fail one after another while the hardware is busy. There is no last attempt.
     */
    private fun retry(job: Job, reason: String, resource: Boolean) {
        val nextTry = Job(job.asset, job.kind, job.attempt + 1, job.noAudio)
        onRetry(job.asset.id, job.kind, nextTry.attempt, reason)
        if (resource) onCodecPressure()
        val backoff = (2_000L shl min(job.attempt, 5)).coerceAtMost(60_000L)
        pausedUntil = android.os.SystemClock.uptimeMillis() + backoff
        queue.addFirst(nextTry)
        pump()
    }

    /** Drops every job for these files, stopping the one running now if it is theirs. */
    fun cancel(ids: Set<Int>) {
        queue.removeAll { it.asset.id in ids }
        val j = currentJob ?: return
        if (j.asset.id !in ids) return
        current?.cancel()
        currentTmp?.delete()
        next()
    }

    private fun next() {
        current = null
        currentJob = null
        pump()
    }

    private fun poll() {
        val t = current ?: return
        val job = currentJob ?: return
        // Some ways of converting (with software decoders, for one) report no progress at all; then it is judged by how
        // much of the expected file has been written, so a running job never shows a frozen 0%.
        val pct = if (t.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) progress.progress
        else if (expectedBytes > 0) (currentTmp?.length() ?: 0L).times(100).div(expectedBytes).toInt().coerceIn(0, 99)
        else 0
        onJob(job.asset.id, job.kind, pct)
        main.postDelayed(::poll, 500)
    }

    /** The file being written and roughly how big it will be, for the progress estimate. */
    private var currentTmp: File? = null
    private var expectedBytes = 0L

    fun release() {
        queue.clear()
        current?.cancel()
        current = null
        main.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val SCRUB_SHORT_SIDE = 240
        const val SCRUB_FPS = 10f
        /** Bumped whenever proxy settings change, so old files are rebuilt and removed. */
        const val REVISION = 3
        /** Revision of the play (edit) proxy, which has its own so the scrub proxies need not be rebuilt. */
        const val PLAY_REVISION = 4
        const val PLAY_KEYFRAME_S = 0.25f
    }
}

package com.graden.editor.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** The one export running (or last finished), watched by the export screen and the notification. */
object ExportJob {
    /** Testing only (GradenLab): 10-bit goes the old way, read back as P010, to compare against the surface route. */
    @Volatile var forceP010 = false

    enum class Phase { IDLE, AUDIO, VIDEO, SAVING, DONE, FAILED, CANCELLED }

    var phase by mutableStateOf(Phase.IDLE)
        internal set
    /** 0..1 over the whole job. */
    var progress by mutableStateOf(0f)
        internal set
    var framesDone by mutableStateOf(0)
        internal set
    var framesTotal by mutableStateOf(0)
        internal set
    /** Frames rendered per second, averaged. */
    var speed by mutableStateOf(0f)
        internal set
    var etaSec by mutableStateOf(-1)
        internal set
    var preview by mutableStateOf<Bitmap?>(null)
        internal set
    var result by mutableStateOf<Uri?>(null)
        internal set
    var resultName by mutableStateOf("")
        internal set
    var resultBytes by mutableStateOf(0L)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    var settings by mutableStateOf<ExportSettings?>(null)
        internal set
    /** When the job started (elapsed-realtime ms), for the elapsed time shown. */
    var startedAt by mutableStateOf(0L)
        internal set
    /** Stabilisation: clips stabilised, how long it took to prepare, and the speed stabilised frames render at. */
    var stabClips by mutableStateOf(0)
        internal set
    var stabPrepMs by mutableStateOf(0L)
        internal set
    var stabFrames by mutableStateOf(0)
        internal set
    var stabFps by mutableStateOf(0f)
        internal set
    /** Frames per second of the project, for the timecode of the frame being rendered. */
    var fps by mutableStateOf(30f)
        internal set

    @Volatile internal var cancel = false
    private var thread: Thread? = null

    val running: Boolean get() = phase == Phase.AUDIO || phase == Phase.VIDEO || phase == Phase.SAVING

    fun start(context: Context, project: ExportProject, s: ExportSettings, enc: EncoderInfo) {
        if (running) return
        cancel = false
        phase = Phase.AUDIO
        progress = 0f; framesDone = 0; framesTotal = 0; speed = 0f; etaSec = -1
        preview = null; result = null; error = null; resultBytes = 0L
        settings = s
        startedAt = android.os.SystemClock.elapsedRealtime()
        fps = project.meta.fps
        val app = context.applicationContext
        ExportService.start(app)
        thread = Thread({ ExportEngine(app, project, s, enc).run() }, "export").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    fun cancel() {
        cancel = true
    }

    fun reset() {
        if (!running) phase = Phase.IDLE
    }
}

/**
 * Renders one export: the sound first (mixed and encoded to a side file), then every frame of the picture through the
 * chosen encoder, the two interleaved into the file as they go. The file is made in the app's own storage and then
 * saved to Movies/Graden.
 */
class ExportEngine(
    private val context: Context,
    private val project: ExportProject,
    private val s: ExportSettings,
    private val enc: EncoderInfo,
) {
    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) = main.post(block)

    fun run() {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val video = File(dir, "render.${s.container.ext}")
        val audioFile = File(dir, "audio.bin")
        try {
            val fps = project.meta.fps
            val audioShare = if (s.audio) 0.12f else 0f
            // 1. Sound.
            val audioFormat = if (s.audio) mixAudio(
                context, project, s.audioBitrate, s.audioRate, if (s.normalizeLoudness) s.loudnessTarget else null, audioFile,
                progress = { p -> ui { ExportJob.progress = p * audioShare } }, cancelled = { ExportJob.cancel },
            ) else null
            if (ExportJob.cancel) throw Cancelled()

            // 2. Picture.
            ui { ExportJob.phase = ExportJob.Phase.VIDEO }
            val total = ((project.totalUs * fps) / 1_000_000.0).toLong().coerceAtLeast(1L).toInt()
            ui { ExportJob.framesTotal = total }
            val out = com.graden.editor.ColorSpaces.output(s.output)
            // HDR needs 10 bits; it is only offered where the encoder can do it.
            val tenBit = (s.bitDepth == 10 || out.hdr) && enc.tenBit
            if (out.hdr && !tenBit) error("This encoder cannot write HDR; choose an SDR output")
            // The ways to the encoder: the GPU draws straight into a hardware encoder's surface (8-bit, or 10-bit
            // RGBA1010102 where the GPU can: every chip tested); else 10-bit is read back as P010 and queued; or the
            // frame is read back and given to x264 / x265.
            var route = if (enc.soft) Route.SOFT else if (tenBit) (if (ExportJob.forceP010) Route.P010 else Route.SURFACE10) else Route.SURFACE
            val comp = Compositor(context, project, s.width, s.height, tenBit)
            val muxer = MediaMuxer(video.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrack = -1
            var audioTrack = -1
            var started = false
            val samples = if (audioFormat != null) AudioSamples(audioFile) else null
            samples?.next()
            val audioBuf = ByteBuffer.allocateDirect(64 * 1024)
            val audioInfo = MediaCodec.BufferInfo()

            fun startMuxer(videoFormat: MediaFormat) {
                // 10-bit asked of a hardware HEVC encoder: the stream must really be Main 10 (an encoder may quietly
                // make 8-bit Main instead, which would turn HDR into banded SDR).
                if (tenBit && !enc.soft && s.codec == VideoCodec.H265) {
                    val idc = hevcProfileIdc(videoFormat)
                    if (idc == 1) error("This phone's ${enc.label} encoder made 8-bit video when asked for 10-bit, so HDR cannot be written here; choose SDR")
                }
                videoTrack = muxer.addTrack(videoFormat)
                if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat)
                muxer.start()
                started = true
            }

            var lastAudioUs = -1L
            /** Writes sound up to [untilUs], keeping the file interleaved. */
            fun writeAudio(untilUs: Long) {
                val a = samples ?: return
                // Long.MAX_VALUE is the reader's end mark, never a sample: written, it overflowed Samsung's MP4 writer.
                while (started && a.timeUs != Long.MAX_VALUE && a.timeUs <= untilUs) {
                    audioBuf.clear()
                    audioBuf.put(a.data)
                    audioBuf.flip()
                    // Samsung's MP4 writer aborts the whole app (an overflow check in its audio thread) on a sample
                    // whose time does not move forward; others let it pass. Every sample is written strictly later.
                    var t = a.timeUs
                    if (t <= lastAudioUs) {
                        android.util.Log.w("Export", "audio time ${a.timeUs} not after $lastAudioUs: moved on")
                        t = lastAudioUs + 1
                    }
                    lastAudioUs = t
                    audioInfo.set(0, a.data.size, t, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(audioTrack, audioBuf, audioInfo)
                    if (!a.next()) break
                }
            }

            var codec: MediaCodec? = null
            var input: android.view.Surface? = null
            var soft: SoftEncoder? = null
            val info = MediaCodec.BufferInfo()
            /** Collects what a hardware encoder has finished; with [endOfStream], everything. */
            fun drain(endOfStream: Boolean) {
                val c = codec ?: return
                while (true) {
                    val o = c.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!endOfStream) return else continue }
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { startMuxer(c.outputFormat); continue }
                    if (o < 0) continue
                    val b = c.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        writeAudio(info.presentationTimeUs)
                        muxer.writeSampleData(videoTrack, b, info)
                    }
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    c.releaseOutputBuffer(o, false)
                    if (end) return
                }
            }

            val (fpsNum, fpsDen) = fraction(fps)
            val frameBuf = if (route == Route.SOFT) ByteBuffer.allocateDirect(s.width * s.height * 4).order(java.nio.ByteOrder.nativeOrder()) else null
            val outBuf = if (route == Route.SOFT) ByteBuffer.allocateDirect(s.width * s.height * 2 + 1_000_000) else null
            val meta = LongArray(2)
            val softInfo = MediaCodec.BufferInfo()
            fun frameUs(n: Long) = n * 1_000_000L * fpsDen / fpsNum

            /** Writes what x264 / x265 put out (bytes in [outBuf]). */
            fun writeSoft(n: Int) {
                if (n <= 0) return
                val ptsUs = frameUs(meta[0])
                outBuf!!.position(0)
                outBuf.limit(n)
                softInfo.set(0, n, ptsUs, if (meta[1] != 0L) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                writeAudio(ptsUs)
                muxer.writeSampleData(videoTrack, outBuf, softInfo)
                outBuf.clear()
            }

            try {
                when (route) {
                    Route.SURFACE -> {
                        val c = MediaCodec.createByCodecName(enc.name)
                        codec = c
                        val format = encoderFormat(fps, false, out)
                        configure(c, format)
                        input = c.createInputSurface()
                        c.start()
                        comp.start(input)
                    }
                    Route.SURFACE10 -> {
                        val c = MediaCodec.createByCodecName(enc.name)
                        codec = c
                        configure(c, encoderFormat(fps, true, out, surface = true))
                        input = c.createInputSurface()
                        c.start()
                        if (!comp.start(input, tenBitSurface = true)) {
                            // This GPU cannot draw 10 bits into the encoder: the frame is read back as P010 instead.
                            android.util.Log.i("ExportProbe", "no 10-bit encoder surface; P010 read-back")
                            runCatching { c.stop() }; c.release(); input?.release(); input = null
                            route = Route.P010
                            val c2 = MediaCodec.createByCodecName(enc.name)
                            codec = c2
                            configure(c2, encoderFormat(fps, true, out))
                            c2.start()
                            comp.start(null)
                            if (!comp.tenBit) error("This phone's GPU cannot render in 10-bit; choose 8-bit")
                        }
                    }
                    Route.P010 -> {
                        val c = MediaCodec.createByCodecName(enc.name)
                        codec = c
                        configure(c, encoderFormat(fps, true, out))
                        c.start()
                        comp.start(null)
                        if (!comp.tenBit) error("This phone's GPU cannot render in 10-bit; choose 8-bit")
                    }
                    Route.SOFT -> {
                        val rc = when {
                            s.quality == QualityMode.CONSTANT -> 2
                            s.rateMode == RateMode.CBR -> 1
                            else -> 0
                        }
                        val value = if (rc == 2) s.cq.coerceIn(0, 51) else s.videoBitrate(fps, enc) / 1000
                        val e = SoftEncoder(s.codec, s.width, s.height, fpsNum, fpsDen, if (tenBit) 10 else 8, rc, value, s.keyframeEvery, s.speedPreset)
                        if (!e.ok) error("${enc.name} could not start at ${s.width}×${s.height}")
                        soft = e
                        comp.start(null)
                        if (tenBit && !comp.tenBit) error("This phone's GPU cannot render in 10-bit; choose 8-bit")
                        startMuxer(softFormat(e.headers))
                    }
                }

                val t0 = SystemClock.elapsedRealtime()
                var lastUi = 0L
                var packNs = 0L
                var stabNs = 0L
                var stabN = 0
                ui { ExportJob.stabClips = project.stabs.size; ExportJob.stabPrepMs = project.stabPrepMs; ExportJob.stabFrames = 0; ExportJob.stabFps = 0f }
                for (n in 0 until total) {
                    if (ExportJob.cancel) throw Cancelled()
                    val tf = System.nanoTime()
                    val us = frameUs(n.toLong())
                    comp.render(us, us * 1000L)
                    when (route) {
                        Route.SURFACE, Route.SURFACE10 -> drain(false)
                        Route.SOFT -> {
                            val fmt = comp.readFrame(frameBuf!!)
                            writeSoft(soft!!.encode(frameBuf, fmt, outBuf!!, meta))
                        }
                        Route.P010 -> {
                            val c = codec!!
                            var idx: Int
                            while (true) {
                                idx = c.dequeueInputBuffer(20_000)
                                if (idx >= 0) break
                                drain(false)
                                if (ExportJob.cancel) throw Cancelled()
                            }
                            val inFmt = c.inputFormat
                            val stride = inFmt.getIntegerOr(MediaFormat.KEY_STRIDE, s.width * 2).coerceAtLeast(s.width * 2)
                            val slice = inFmt.getIntegerOr(MediaFormat.KEY_SLICE_HEIGHT, s.height).coerceAtLeast(s.height)
                            val ib = c.getInputBuffer(idx)!!
                            val tp = System.nanoTime()
                            comp.packP010(ib, out == com.graden.editor.ColorSpaces.REC2020 || out.hdr, stride, slice)
                            packNs += System.nanoTime() - tp
                            if (n % 60 == 59) { android.util.Log.d("ExportProbe", "P010 read back and packed: %.1f ms a frame".format(packNs / 6e7)); packNs = 0L }
                            c.queueInputBuffer(idx, 0, stride * slice * 3 / 2, us, 0)
                            drain(false)
                        }
                    }
                    // Stabilised frames: the whole time each took (decode, warp, grade, encode), for their own speed.
                    if (comp.lastStabilised) { stabNs += System.nanoTime() - tf; stabN++ }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi > 400 || n == total - 1) {
                        lastUi = now
                        val bmp = if (now - t0 < 1500 || n % 15 == 0) comp.preview() else null
                        val done = n + 1
                        val rate = done * 1000f / (now - t0).coerceAtLeast(1)
                        val sN = stabN; val sFps = if (stabNs > 0) (stabN * 1e9 / stabNs).toFloat() else 0f
                        ui {
                            ExportJob.stabFrames = sN
                            ExportJob.stabFps = sFps
                            ExportJob.framesDone = done
                            ExportJob.speed = rate
                            ExportJob.etaSec = ((total - done) / rate.coerceAtLeast(0.01f)).toInt()
                            ExportJob.progress = audioShare + (1f - audioShare - 0.02f) * done / total
                            bmp?.let { ExportJob.preview = it }
                        }
                    }
                }
                // The end of the stream: whatever the encoder still holds comes out.
                when (route) {
                    Route.SURFACE, Route.SURFACE10 -> { codec!!.signalEndOfInputStream(); drain(true) }
                    Route.P010 -> {
                        val c = codec!!
                        var idx: Int
                        while (true) { idx = c.dequeueInputBuffer(20_000); if (idx >= 0) break; drain(false) }
                        c.queueInputBuffer(idx, 0, 0, frameUs(total.toLong()), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        drain(true)
                    }
                    Route.SOFT -> while (true) {
                        val n = soft!!.encode(null, 0, outBuf!!, meta)
                        if (n < 0) break
                        writeSoft(n)
                    }
                }
                writeAudio(Long.MAX_VALUE)
            } finally {
                comp.release()
                codec?.let { runCatching { it.stop() }; it.release() }
                input?.release()
                soft?.close()
                samples?.close()
                if (started) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
            if (!started) error("The encoder produced no picture")

            // 3. Save.
            ui { ExportJob.phase = ExportJob.Phase.SAVING }
            if (s.container == Container.MOV) markQuickTime(video)
            val uri = saveToGallery(video)
            val size = video.length()
            video.delete()
            audioFile.delete()
            ui {
                ExportJob.result = uri
                ExportJob.resultName = "${s.fileName}.${s.container.ext}"
                ExportJob.resultBytes = size
                ExportJob.progress = 1f
                ExportJob.phase = ExportJob.Phase.DONE
            }
        } catch (e: Cancelled) {
            video.delete(); audioFile.delete()
            ui { ExportJob.phase = ExportJob.Phase.CANCELLED }
        } catch (e: Throwable) {
            android.util.Log.e("Export", "export failed", e)
            video.delete(); audioFile.delete()
            ui {
                ExportJob.error = e.message ?: e.javaClass.simpleName
                ExportJob.phase = ExportJob.Phase.FAILED
            }
        } finally {
            ui { ExportService.stop(context) }
        }
    }

    private class Cancelled : Exception()

    /** general_profile_idc of the HEVC stream whose headers are in [f] (1 Main, 2 Main 10), or -1 when not found. */
    private fun hevcProfileIdc(f: MediaFormat): Int {
        val csd = f.getByteBuffer("csd-0") ?: return -1
        val b = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
        var i = 0
        while (i + 6 < b.size) {
            // Start code, then the NAL header: type 33 is the SPS; after its first byte comes the profile.
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val type = (b[i + 3].toInt() shr 1) and 0x3F
                if (type == 33) return b[i + 6].toInt() and 0x1F
                i += 3
            } else i++
        }
        return -1
    }

    /** The encoder's settings, exactly as chosen: nothing is lowered behind the user's back. */
    private enum class Route { SURFACE, SURFACE10, SOFT, P010 }

    /** Tags [f] with what the picture is: primaries, transfer and range, as players read them. */
    private fun colourTags(f: MediaFormat, out: com.graden.editor.ColorSpaces.Space) {
        val wide = out == com.graden.editor.ColorSpaces.REC2020 || out.hdr
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD, if (wide) MediaFormat.COLOR_STANDARD_BT2020 else MediaFormat.COLOR_STANDARD_BT709)
        f.setInteger(
            MediaFormat.KEY_COLOR_TRANSFER, when (out) {
                com.graden.editor.ColorSpaces.PQ -> MediaFormat.COLOR_TRANSFER_ST2084
                com.graden.editor.ColorSpaces.HLG -> MediaFormat.COLOR_TRANSFER_HLG
                else -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            },
        )
        f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    }

    /**
     * HDR10 static metadata (CTA-861.3 / SMPTE ST 2086): Rec.2020 mastering display, peak [nits], and the content
     * light levels, as the 25 bytes MediaFormat takes.
     */
    private fun hdrStaticInfo(nits: Float): ByteBuffer {
        val b = ByteBuffer.allocate(25).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put(0)
        fun xy(x: Double, y: Double) { b.putShort((x / 0.00002).toInt().toShort()); b.putShort((y / 0.00002).toInt().toShort()) }
        xy(0.708, 0.292); xy(0.170, 0.797); xy(0.131, 0.046); xy(0.3127, 0.3290)
        b.putShort(nits.toInt().toShort())
        b.putShort(50)
        b.putShort(nits.toInt().toShort())
        b.putShort(minOf(400, nits.toInt()).toShort())
        b.flip()
        return b
    }

    /** Configures [c] with [format]; some encoders refuse B-frames or a profile, so the plain form is tried next. */
    private fun configure(c: MediaCodec, format: MediaFormat) {
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            format.removeKey(MediaFormat.KEY_MAX_B_FRAMES)
            if (s.bitDepth != 10) format.removeKey(MediaFormat.KEY_PROFILE)
            format.removeKey(MediaFormat.KEY_LEVEL)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, def: Int) = if (containsKey(key)) getInteger(key) else def

    /** The frame rate as a fraction: 29.97 is 30000/1001, 30 is 30/1. */
    private fun fraction(fps: Float): Pair<Int, Int> {
        val ntsc = Math.round(fps * 1.001f)
        if (fps % 1f != 0f && kotlin.math.abs(fps * 1.001f - ntsc) < 0.01f) return ntsc * 1000 to 1001
        val k = Math.round(fps * 1000f)
        var a = k; var b = 1000
        while (b != 0) { val t = a % b; a = b; b = t }
        return k / a to 1000 / a
    }

    /** The track format for x264 / x265 output, with its parameter sets. */
    private fun softFormat(headers: ByteArray): MediaFormat {
        val f = MediaFormat.createVideoFormat(s.codec.mime, s.width, s.height)
        // Split the Annex B headers into NAL units (each keeping its start code).
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < headers.size) {
            if (headers[i].toInt() == 0 && headers[i + 1].toInt() == 0 && (headers[i + 2].toInt() == 1 || (headers[i + 2].toInt() == 0 && headers[i + 3].toInt() == 1))) {
                starts += i
                i += 3
            } else i++
        }
        starts += headers.size
        val nals = (0 until starts.size - 1).map { headers.copyOfRange(starts[it], starts[it + 1]) }
        fun type(n: ByteArray): Int {
            val off = if (n[2].toInt() == 1) 3 else 4
            return if (s.codec == VideoCodec.H264) n[off].toInt() and 0x1F else (n[off].toInt() shr 1) and 0x3F
        }
        if (s.codec == VideoCodec.H264) {
            nals.firstOrNull { type(it) == 7 }?.let { f.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            nals.firstOrNull { type(it) == 8 }?.let { f.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        } else {
            // VPS, SPS and PPS together.
            val ps = nals.filter { type(it) in 32..34 }.fold(ByteArray(0)) { acc, b -> acc + b }
            f.setByteBuffer("csd-0", ByteBuffer.wrap(ps))
        }
        colourTags(f, com.graden.editor.ColorSpaces.output(s.output))
        return f
    }

    /** [p010]: 10-bit (a 10-bit profile), from P010 buffers, or from the surface when [surface]. */
    private fun encoderFormat(fps: Float, p010: Boolean, out: com.graden.editor.ColorSpaces.Space, surface: Boolean = false): MediaFormat = MediaFormat.createVideoFormat(s.codec.mime, s.width, s.height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, if (p010 && !surface) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 else MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setFloat(MediaFormat.KEY_FRAME_RATE, fps)
        if (s.quality == QualityMode.CONSTANT && enc.supportsCq) {
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
            setInteger(MediaFormat.KEY_QUALITY, s.cq)
        } else {
            setInteger(MediaFormat.KEY_BITRATE_MODE, s.rateMode.value)
            setInteger(MediaFormat.KEY_BIT_RATE, s.videoBitrate(fps, enc))
        }
        // Keyframes: as chosen, else one a second.
        if (s.keyframeEvery > 0) setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, s.keyframeEvery / fps) else setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val profile = if (out == com.graden.editor.ColorSpaces.PQ) (if (enc.hdr10 || !enc.unlistedMain10) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            else if (s.profile != 0 && enc.profiles.any { it.value == s.profile && it.tenBit == p010 }) s.profile
            else enc.profiles.filter { it.tenBit == p010 }.maxByOrNull { if (it.label.startsWith("High") || it.label.startsWith("Main")) 2 else 1 }?.value ?: 0
        if (profile != 0) setInteger(MediaFormat.KEY_PROFILE, profile)
        if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain ||
            profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 2)
        colourTags(this, out)
        if (out == com.graden.editor.ColorSpaces.PQ) setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo(project.nits))
        // Not real time: the encoder may take as long as it needs for each frame.
        setInteger(MediaFormat.KEY_PRIORITY, 1)
    }

    /** Marks the file as QuickTime (brand "qt  "), as MOV files are; the boxes inside are the same. */
    private fun markQuickTime(f: File) {
        RandomAccessFile(f, "rw").use { r ->
            val head = ByteArray(8)
            r.readFully(head)
            if (String(head, 4, 4, Charsets.US_ASCII) != "ftyp") return
            val size = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
            r.seek(8)
            r.write("qt  ".toByteArray(Charsets.US_ASCII))
            r.write(byteArrayOf(0x20, 0x05, 0x03, 0x00))
            // The compatible brands become "qt  " too, keeping the box the same size.
            var p = 16L
            while (p + 4 <= size) {
                r.seek(p)
                r.write("qt  ".toByteArray(Charsets.US_ASCII))
                p += 4
            }
        }
    }

    /** Copies the finished file into Movies/Graden, where the gallery and other apps find it. */
    private fun saveToGallery(f: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${s.fileName}.${s.container.ext}")
            put(MediaStore.Video.Media.MIME_TYPE, s.container.mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Graden")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Could not create the file in Movies/Graden")
        try {
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 20) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}

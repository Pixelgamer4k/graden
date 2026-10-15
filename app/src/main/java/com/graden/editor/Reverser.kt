package com.graden.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Writes a copy of part of a file that plays backwards, picture and sound.
 *
 * The picture is first re-encoded with every frame a keyframe, so each frame stands on its own; the frames
 * can then simply be written out last to first, without decoding anything again. The sound is decoded to
 * plain samples, turned around sample by sample, and encoded again. Only the range the clip uses is
 * reversed, so a short clip from a long recording costs little.
 */
@OptIn(UnstableApi::class)
object Reverser {
    class Failed(message: String) : Exception(message)

    /** Short side of the reversed picture; bigger sources are scaled down to it. */
    private const val SHORT_SIDE = 1080
    private const val MAX_FPS = 60f

    /**
     * Reverses [fromUs]..[toUs] of [a] (read from [src]) into [out]. [progress] gets 0..1. Runs on any
     * dispatcher; the parts that need the main thread move there themselves.
     */
    suspend fun reverse(context: Context, src: Uri, a: MediaAsset, fromUs: Long, toUs: Long, out: File, progress: (Float) -> Unit) {
        val work = File(out.path + ".work").apply { deleteRecursively(); mkdirs() }
        try {
            val picture = !a.isAudio
            val intra = File(work, "intra.mp4")
            val pcm = File(work, "sound.pcm")
            // How each stage counts towards the whole.
            val pictureShare = if (picture) 0.75f else 0f
            val soundShare = if (a.hasAudio || a.isAudio) (if (picture) 0.15f else 0.9f) else 0f
            if (picture) withContext(Dispatchers.Main) { intraCopy(context, src, a, fromUs, toUs, intra) { progress(it * pictureShare) } }
            var sound: Encoded? = null
            if (a.hasAudio || a.isAudio) withContext(Dispatchers.IO) {
                val fmt = decodeSound(context, src, fromUs, toUs, pcm) { progress(pictureShare + it * soundShare * 0.5f) }
                if (fmt != null) sound = encodeBackwards(pcm, fmt) { progress(pictureShare + soundShare * (0.5f + it * 0.5f)) }
                pcm.delete()
            }
            if (!picture && sound == null) throw Failed("There is no sound to reverse")
            val tmp = File(out.path + ".tmp")
            withContext(Dispatchers.IO) {
                writeBackwards(if (picture) intra else null, sound, tmp) { progress(pictureShare + soundShare + it * (1f - pictureShare - soundShare)) }
            }
            if (!tmp.renameTo(out)) throw Failed("Could not save the reversed copy")
            progress(1f)
        } finally {
            work.deleteRecursively()
            File(out.path + ".tmp").delete()
        }
    }

    // ---- picture: an every-frame-a-keyframe copy of the range ------------------------------------

    private suspend fun intraCopy(context: Context, src: Uri, a: MediaAsset, fromUs: Long, toUs: Long, out: File, progress: (Float) -> Unit) {
        val scale = min(1f, SHORT_SIDE / min(a.width, a.height).toFloat().coerceAtLeast(1f))
        val outH = ((a.height * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val outW = (a.width * scale).toInt()
        val fps = min(if (a.fps > 0f) a.fps else 30f, MAX_FPS)
        val settings = VideoEncoderSettings.Builder()
            .setBitrate((outW * outH * fps * 0.22f).toInt().coerceIn(2_000_000, 24_000_000))
            .setiFrameIntervalSeconds(0f)
            .build()
        val effects = ArrayList<androidx.media3.common.Effect>()
        if (a.fps > fps + 1f) effects += FrameDropEffect.createDefaultFrameDropEffect(fps)
        if (scale < 1f) effects += Presentation.createForHeight(outH)
        val media = MediaItem.Builder().setUri(src)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(fromUs / 1000).setEndPositionMs((toUs + 999) / 1000).build()
            ).build()
        val item = EditedMediaItem.Builder(media).setRemoveAudio(true).setEffects(Effects(emptyList(), effects)).build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        coroutineScope {
            var transformer: Transformer? = null
            val poll = launch {
                val holder = ProgressHolder()
                while (true) {
                    delay(300)
                    val t = transformer ?: continue
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val t = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(settings).build())
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(Failed("The picture could not be prepared"))
                            }
                        })
                        .build()
                    transformer = t
                    cont.invokeOnCancellation { t.cancel() }
                    t.start(composition, out.path)
                }
            } finally {
                poll.cancel()
            }
        }
    }

    // ---- sound ---------------------------------------------------------------------------------

    private class Pcm(val sampleRate: Int, val channels: Int)

    /** Decodes [fromUs]..[toUs] of the first sound track to 16-bit samples in [out]. Null when there is no sound. */
    private suspend fun decodeSound(context: Context, src: Uri, fromUs: Long, toUs: Long, out: File, progress: (Float) -> Unit): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, src, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val span = (toUs - fromUs).coerceAtLeast(1L)
            BufferedOutputStream(out.outputStream(), 1 shl 16).use { sink ->
                var scratch = ByteArray(0)
                while (true) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val i = dec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val buf = dec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            val t = ex.sampleTime
                            if (n < 0 || t > toUs) {
                                dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(i, 0, n, t, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        val buf = dec.getOutputBuffer(o)!!
                        buf.position(info.offset).limit(info.offset + info.size)
                        // Converted to 16-bit, and cut to the range at sample accuracy.
                        val inBytes = if (float) 4 else 2
                        val frames = info.size / (inBytes * channels)
                        val dt = 1_000_000.0 / rate
                        val first = ((fromUs - info.presentationTimeUs) / dt).toInt().coerceIn(0, frames)
                        val last = ((toUs - info.presentationTimeUs) / dt).toInt().coerceIn(0, frames)
                        val count = (last - first) * channels
                        if (count > 0) {
                            if (scratch.size < count * 2) scratch = ByteArray(count * 2)
                            val ord = buf.order(ByteOrder.LITTLE_ENDIAN)
                            ord.position(info.offset + first * channels * inBytes)
                            for (k in 0 until count) {
                                val s = if (float) (ord.float.coerceIn(-1f, 1f) * 32767f).toInt() else ord.short.toInt()
                                scratch[2 * k] = s.toByte()
                                scratch[2 * k + 1] = (s shr 8).toByte()
                            }
                            sink.write(scratch, 0, count * 2)
                        }
                        progress(((info.presentationTimeUs - fromUs).toFloat() / span).coerceIn(0f, 1f))
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return Pcm(rate, channels)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    private class Encoded(val format: MediaFormat, val samples: List<Sample>)

    /** Encodes the samples in [pcm] from last to first, as AAC kept in memory (about a megabyte a minute). */
    private suspend fun encodeBackwards(pcm: File, p: Pcm, progress: (Float) -> Unit): Encoded {
        val fmt = MediaFormat.createAudioFormat(MimeTypes.AUDIO_AAC, p.sampleRate, p.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val enc = MediaCodec.createEncoderByType(MimeTypes.AUDIO_AAC)
        val frameBytes = 2 * p.channels
        val samples = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            RandomAccessFile(pcm, "r").use { raf ->
                val total = raf.length() / frameBytes
                var left = total
                var written = 0L
                var inputDone = false
                val info = MediaCodec.BufferInfo()
                var chunk = ByteArray(0)
                while (true) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val i = enc.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val buf = enc.getInputBuffer(i)!!
                            if (left <= 0) {
                                enc.queueInputBuffer(i, 0, 0, written * 1_000_000L / p.sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val n = min((buf.capacity() / frameBytes).toLong(), min(left, 2048L)).toInt()
                                val bytes = n * frameBytes
                                if (chunk.size < bytes) chunk = ByteArray(bytes)
                                raf.seek((left - n) * frameBytes)
                                raf.readFully(chunk, 0, bytes)
                                // Frames last to first; each frame's channels stay in order.
                                buf.clear()
                                for (f in n - 1 downTo 0) buf.put(chunk, f * frameBytes, frameBytes)
                                enc.queueInputBuffer(i, 0, bytes, written * 1_000_000L / p.sampleRate, 0)
                                left -= n
                                written += n
                                progress(written.toFloat() / total.coerceAtLeast(1L))
                            }
                        }
                    }
                    val o = enc.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = enc.outputFormat
                    else if (o >= 0) {
                        val buf = enc.getOutputBuffer(o)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(data)
                            samples += Sample(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { enc.stop() }
            enc.release()
        }
        return Encoded(outFormat ?: throw Failed("The sound could not be encoded"), samples)
    }

    // ---- writing it out backwards --------------------------------------------------------------

    /**
     * Writes the frames of [intra] last to first, with the reversed [sound] woven in alongside. The frames are
     * read a second's worth at a time from the end, so memory stays small whatever the length.
     */
    private suspend fun writeBackwards(intra: File?, sound: Encoded?, out: File, progress: (Float) -> Unit) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val ex = MediaExtractor()
        var started = false
        try {
            var videoTrack = -1
            val times = ArrayList<Long>()
            if (intra != null) {
                ex.setDataSource(intra.path)
                val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?: throw Failed("The prepared picture is missing")
                val vf = ex.getTrackFormat(t)
                ex.selectTrack(t)
                // First pass: every frame's time, and a check that each one really is a keyframe.
                while (true) {
                    val st = ex.sampleTime
                    if (st < 0) break
                    if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) throw Failed("This phone's encoder can't prepare the picture for reversing")
                    times += st
                    ex.advance()
                }
                if (times.isEmpty()) throw Failed("The prepared picture is empty")
                if (vf.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION))
                videoTrack = muxer.addTrack(vf)
            }
            val soundTrack = sound?.let { muxer.addTrack(it.format) } ?: -1
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var nextSound = 0
            fun soundUpTo(us: Long) {
                val s = sound ?: return
                while (nextSound < s.samples.size && s.samples[nextSound].ptsUs <= us) {
                    val x = s.samples[nextSound++]
                    info.set(0, x.data.size, x.ptsUs, x.flags)
                    muxer.writeSampleData(soundTrack, ByteBuffer.wrap(x.data), info)
                }
            }
            if (intra != null) {
                val lastT = times.last()
                var buf = ByteBuffer.allocateDirect(1 shl 20)
                var end = times.size
                val chunk = 30
                while (end > 0) {
                    coroutineContext.ensureActive()
                    val start = (end - chunk).coerceAtLeast(0)
                    ex.seekTo(times[start], MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    while (ex.sampleTime in 0 until times[start]) ex.advance()
                    val frames = ArrayList<ByteArray>(end - start)
                    for (k in start until end) {
                        val size = ex.sampleSize.toInt()
                        if (size > buf.capacity()) buf = ByteBuffer.allocateDirect(size * 2)
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        val data = ByteArray(n)
                        buf.position(0)
                        buf.get(data, 0, n)
                        frames += data
                        ex.advance()
                    }
                    for (k in frames.indices.reversed()) {
                        val pts = lastT - times[start + k]
                        soundUpTo(pts)
                        info.set(0, frames[k].size, pts, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(videoTrack, ByteBuffer.wrap(frames[k]), info)
                    }
                    end = start
                    progress(1f - end.toFloat() / times.size)
                }
            }
            soundUpTo(Long.MAX_VALUE)
        } finally {
            ex.release()
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}

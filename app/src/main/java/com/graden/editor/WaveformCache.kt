package com.graden.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Audio peaks for drawing waveforms: one byte per 1/[PEAKS_PER_SEC] s, built once per file
 * in the background by decoding its audio track, then kept on disk.
 */
class WaveformCache(private val context: Context) {
    /** Bumped on the main thread when a waveform becomes available; read it in draw to redraw. */
    var version by mutableIntStateOf(0)
        private set

    private val dir = File(context.filesDir, "waves").apply { mkdirs() }
    // Keyed by the file, since asset ids repeat from one project to the next.
    private val peaks = ConcurrentHashMap<String, ByteArray>()
    private val requested = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "waveform").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }
    private val main = Handler(Looper.getMainLooper())

    fun get(a: MediaAsset): ByteArray? = peaks[a.uri]

    fun request(a: MediaAsset) {
        if (!a.hasAudio || !requested.add(a.uri)) return
        worker.execute {
            val f = File(dir, "${a.uri.hashCode().toUInt()}_${a.durationUs}.pk")
            val data = if (f.exists()) f.readBytes() else build(Uri.parse(a.uri))?.also { f.writeBytes(it) }
            if (data != null && data.isNotEmpty()) {
                peaks[a.uri] = data
                main.post { version++ }
            }
        }
    }

    private fun build(uri: Uri): ByteArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    ex.selectTrack(i)
                    format = f
                    break
                }
            }
            val f = format ?: return null
            codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(f, null, null, 0)
            codec.start()
            var rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var peak = 0
            var inBucket = 0
            var idle = 0
            while (idle < 500) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    idle = 0
                    val perBucket = rate / PEAKS_PER_SEC
                    val pcm = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = info.size / 2 / channels
                    var k = 0
                    // One channel is enough for a picture of the sound.
                    for (n in 0 until frames) {
                        val v = abs(pcm.get(k).toInt())
                        k += channels
                        if (v > peak) peak = v
                        if (++inBucket >= perBucket) {
                            out.write(minOf(255, peak * 255 / 32768))
                            peak = 0
                            inBucket = 0
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else idle++
            }
            return out.toByteArray()
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    companion object {
        const val PEAKS_PER_SEC = 50
    }
}

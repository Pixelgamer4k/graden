package com.graden.editor

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shows single frames from all-intra proxy files while the user scrubs or trims.
 * Because every proxy frame is a keyframe, a frame costs one sample in and one frame out:
 * no codec flush and no decode-from-previous-keyframe, whatever the source codec was.
 * Requests are coalesced so the decoder always works on the newest playhead position.
 */
class ScrubDecoder(private val onFrame: () -> Unit) {
    private val thread = HandlerThread("scrub-decoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var wantPath: String? = null
    private var wantUs = 0L
    private var scheduled = false

    // Decoder-thread state
    private var surface: Surface? = null
    private var path: String? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()

    /** Blocks until the old surface is no longer in use, so the caller may destroy it. */
    fun setSurface(s: Surface?) {
        val done = CountDownLatch(1)
        handler.post {
            releaseCodec()
            surface = s
            done.countDown()
        }
        done.await(1, TimeUnit.SECONDS)
    }

    fun show(path: String, sourceUs: Long) {
        synchronized(lock) {
            wantPath = path
            wantUs = sourceUs
            if (scheduled) return
            scheduled = true
        }
        handler.post(::run)
    }

    private fun run() {
        val p: String
        val t: Long
        synchronized(lock) {
            scheduled = false
            p = wantPath ?: return
            t = wantUs
        }
        val s = surface ?: return
        try {
            if (codec == null || path != p) open(p, s)
            if (decode(t)) onFrame()
        } catch (e: Exception) {
            releaseCodec()
        }
    }

    private fun open(p: String, s: Surface) {
        releaseCodec()
        val ex = MediaExtractor().apply { setDataSource(p) }
        extractor = ex
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue
            ex.selectTrack(i)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(f, s, null, 0)
                start()
            }
            path = p
            return
        }
        throw IllegalStateException("no video track")
    }

    private fun decode(us: Long): Boolean {
        val c = codec ?: return false
        val ex = extractor ?: return false
        ex.seekTo(us, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        var queued = false
        repeat(40) {
            if (!queued) {
                val i = c.dequeueInputBuffer(2000)
                if (i >= 0) {
                    val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        c.queueInputBuffer(i, 0, 0, 0, 0)
                        return false
                    }
                    c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                    queued = true
                }
            }
            val o = c.dequeueOutputBuffer(info, 4000)
            if (o >= 0) {
                c.releaseOutputBuffer(o, true)
                if (queued) return true
            }
        }
        return false
    }

    private fun releaseCodec() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
        path = null
    }

    fun release() {
        handler.post { releaseCodec() }
        thread.quitSafely()
    }
}

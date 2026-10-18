package com.graden.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decoding a clip for the stabiliser: each frame small and grey, fed to [StabTracker]. Shared, file for file, by Graden
 * and the StabLab test app, so what is measured there is what ships.
 */
object StabDecode {
    /** The long side of the picture the motion is measured on. */
    private const val LONG = 512

    /**
     * Measures the motion of [path] between [inUs] and [outUs], reporting progress 0..1; [cancelled] stops it. Logs how
     * long each stage takes a frame (decoding, the grey picture, tracking).
     */
    fun analyse(context: Context, path: String, rotation: Int, inUs: Long, outUs: Long, detailed: Boolean,
                progress: (Float) -> Unit, cancelled: () -> Boolean): StabAnalysis? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val pool = Executors.newFixedThreadPool(max(2, Runtime.getRuntime().availableProcessors() - 1))
        try {
            if (path.startsWith("content:")) ex.setDataSource(context, Uri.parse(path), null)
            else ex.setDataSource(if (path.startsWith("file:")) Uri.parse(path).path!! else path)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val rot = if (fmt.containsKey("rotation-degrees")) fmt.getInteger("rotation-degrees") else rotation
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            ex.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            val tracker = StabTracker(detailed, pool)
            val times = ArrayList<Long>()
            val steps = ArrayList<FloatArray>()
            val mesh = ArrayList<FloatArray>()
            val quality = ArrayList<Float>()
            var inDone = false
            var aspect = 16f / 9f
            val t0 = android.os.SystemClock.uptimeMillis()
            var tDecode = 0L; var tGray = 0L; var tTrack = 0L
            var tMark = System.nanoTime()
            while (true) {
                if (cancelled()) return null
                if (!inDone) {
                    val i = c.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > outUs + 50_000) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER || o < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { c.releaseOutputBuffer(o, false); break }
                val t = info.presentationTimeUs
                if (t < inUs - 1000) { c.releaseOutputBuffer(o, false); continue }
                val img = c.getOutputImage(o)
                if (img == null) { c.releaseOutputBuffer(o, false); continue }
                val ta = System.nanoTime(); tDecode += ta - tMark
                val gray = toGray(img, rot)
                img.close()
                c.releaseOutputBuffer(o, false)
                val tb = System.nanoTime(); tGray += tb - ta
                aspect = gray.w.toFloat() / gray.h
                val r = tracker.next(gray)
                tMark = System.nanoTime(); tTrack += tMark - tb
                times += t; steps += r.step; mesh += r.mesh; quality += r.quality
                progress(((t - inUs).toFloat() / max(1L, outUs - inUs)).coerceIn(0f, 1f))
            }
            val n = times.size
            if (n < 2) return null
            android.util.Log.i("Stabilizer", "measured $n frames in ${android.os.SystemClock.uptimeMillis() - t0} ms: decode ${tDecode / 1_000_000 / n} ms, grey ${tGray / 1_000_000 / n} ms, tracking ${tTrack / 1_000_000 / n} ms a frame")
            val nv2 = StabMesh.NV * 2
            val a = StabAnalysis(LongArray(n) { times[it] }, FloatArray(n * 9) { steps[it / 9][it % 9] }, FloatArray(n * nv2) { mesh[it / nv2][it % nv2] },
                FloatArray(n) { quality[it] }, aspect)
            return a
        } catch (e: Exception) {
            android.util.Log.w("Stabilizer", "analysis failed", e)
            return null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            ex.release()
            pool.shutdownNow()
        }
    }

    private var plane = ByteArray(0)

    /** The luma plane, averaged down to [LONG] on the long side and turned upright. */
    private fun toGray(img: android.media.Image, rotation: Int): Gray {
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride; val ps = p.pixelStride
        val crop = img.cropRect
        val sw = crop.width(); val sh = crop.height()
        val cx0 = crop.left; val cy0 = crop.top
        // 10-bit frames keep 16 bits a sample: read the high byte.
        val hi = if (ps >= 2) 1 else 0
        // The plane copied out in one go: reading it a byte at a time through the buffer is far slower.
        val len = buf.remaining()
        if (plane.size < len) plane = ByteArray(len)
        buf.get(plane, 0, len)
        val pl = plane
        val upright = rotation == 90 || rotation == 270
        val uw = if (upright) sh else sw; val uh = if (upright) sw else sh
        val ow: Int; val oh: Int
        if (uw >= uh) { ow = LONG; oh = (LONG * uh.toFloat() / uw).roundToInt() } else { oh = LONG; ow = (LONG * uw.toFloat() / uh).roundToInt() }
        // Each output pixel averages stored pixels around its centre, so fine detail does not alias.
        val step = max(1, (sw.toFloat() / (if (upright) oh else ow) / 2f).roundToInt())
        val out = FloatArray(ow * oh)
        val inv = 1f / 1020f
        for (y in 0 until oh) {
            val v = (y + 0.5f) / oh
            for (x in 0 until ow) {
                val u = (x + 0.5f) / ow
                val su: Float; val sv: Float
                when (rotation) { 90 -> { su = v; sv = 1 - u }; 180 -> { su = 1 - u; sv = 1 - v }; 270 -> { su = 1 - v; sv = u }; else -> { su = u; sv = v } }
                val sx = (su * sw - step * 0.5f).toInt().coerceIn(0, sw - 1 - step) + cx0
                val sy = (sv * sh - step * 0.5f).toInt().coerceIn(0, sh - 1 - step) + cy0
                val r0 = sy * rs; val r1 = (sy + step) * rs
                val c0 = sx * ps + hi; val c1 = (sx + step) * ps + hi
                out[y * ow + x] = ((pl[r0 + c0].toInt() and 255) + (pl[r0 + c1].toInt() and 255) + (pl[r1 + c0].toInt() and 255) + (pl[r1 + c1].toInt() and 255)) * inv
            }
        }
        return Gray(ow, oh, out)
    }
}

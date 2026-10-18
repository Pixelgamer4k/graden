package com.graden.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** The native point tracker (vgyro_jni.cpp, on OpenCV). */
object VGyroNative {
    /** Whether the tracker is here (ARM builds only). */
    val available: Boolean = runCatching { System.loadLibrary("vgyro") }.isSuccess
    @JvmStatic external fun create(w: Int, h: Int, maxPts: Int): Long
    @JvmStatic external fun release(handle: Long)
    @JvmStatic external fun shrink(handle: Long, buf: java.nio.ByteBuffer, off: Int, sw: Int, sh: Int, rs: Int, ps: Int, dst: java.nio.ByteBuffer)
    @JvmStatic external fun pair(handle: Long, a: java.nio.ByteBuffer, b: java.nio.ByteBuffer, out: FloatArray): Int
}

/**
 * A clip's tracked points, as the sensor stored the frames (rows in the order they were read): for each frame k, the
 * pairs followed into it from frame k-1, as px, py, qx, qy in pixels of a [w]×[h] picture and how far (px) the pair
 * is from the frame's overall motion (large: moving on its own, or much nearer or further than most). [cut]: frame k starts a
 * new shot. [rotation]: how the stored frames are turned to be upright.
 */
class VGyroAnalysis(val times: LongArray, val w: Int, val h: Int, val rotation: Int, val pairs: Array<FloatArray>, val cut: BooleanArray) {
    val size get() = times.size
    /** The lens's horizontal field of view (degrees) and the sensor's readout (frames) found from the footage; 0 / -1: not yet. */
    var fov = 0.0
    var readout = -1.0

    /** The frames between file times [inUs] and [outUs] (a little either side), as an analysis of their own. */
    fun slice(inUs: Long, outUs: Long): VGyroAnalysis {
        val lo = if (inUs == Long.MIN_VALUE) Long.MIN_VALUE else inUs - 20_000
        val hi = if (outUs == Long.MAX_VALUE) Long.MAX_VALUE else outUs + 20_000
        val idx = (0 until size).filter { times[it] in lo..hi }
        if (idx.size == size) return this
        return VGyroAnalysis(LongArray(idx.size) { times[idx[it]] }, w, h, rotation, Array(idx.size) { if (it == 0) FloatArray(0) else pairs[idx[it]] },
            BooleanArray(idx.size) { it > 0 && cut[idx[it]] }).also { it.fov = fov; it.readout = readout }
    }

    fun save(f: File) = DataOutputStream(f.outputStream().buffered()).use { o ->
        o.writeInt(4); o.writeInt(size); o.writeInt(w); o.writeInt(h); o.writeInt(rotation); o.writeDouble(fov); o.writeDouble(readout)
        for (i in 0 until size) {
            o.writeLong(times[i]); o.writeBoolean(cut[i]); o.writeInt(pairs[i].size)
            for (v in pairs[i]) o.writeFloat(v)
        }
    }

    companion object {
        fun load(f: File): VGyroAnalysis? = runCatching {
            DataInputStream(f.inputStream().buffered()).use { s ->
                require(s.readInt() == 4)
                val n = s.readInt(); val w = s.readInt(); val h = s.readInt(); val rot = s.readInt()
                val fov = s.readDouble(); val ro = s.readDouble()
                val t = LongArray(n); val c = BooleanArray(n)
                val p = Array(n) { i -> t[i] = s.readLong(); c[i] = s.readBoolean(); FloatArray(s.readInt()) { s.readFloat() } }
                VGyroAnalysis(t, w, h, rot, p, c).also { it.fov = fov; it.readout = ro }
            }
        }.getOrNull()
    }
}

/** A unit quaternion (w, x, y, z): a rotation. */
class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z, w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x, w * o.z + x * o.y - y * o.x + z * o.w)
    fun inv() = Quat(w, -x, -y, -z)
    fun norm(): Quat { val n = sqrt(w * w + x * x + y * y + z * z); return Quat(w / n, x / n, y / n, z / n) }
    fun dot(o: Quat) = w * o.w + x * o.x + y * o.y + z * o.z
    fun neg() = Quat(-w, -x, -y, -z)
    /** The rotation vector (axis × angle). */
    fun log(): DoubleArray {
        val q = if (w < 0) neg() else this
        val s = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
        if (s < 1e-12) return doubleArrayOf(2 * q.x, 2 * q.y, 2 * q.z)
        val a = 2 * kotlin.math.atan2(s, q.w) / s
        return doubleArrayOf(q.x * a, q.y * a, q.z * a)
    }
    /** Row-major 3×3. */
    fun matrix() = doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
        2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
        2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y))

    companion object {
        val ID = Quat(1.0, 0.0, 0.0, 0.0)
        fun exp(a: Double, b: Double, c: Double): Quat {
            val t = sqrt(a * a + b * b + c * c)
            if (t < 1e-12) return Quat(1.0, a / 2, b / 2, c / 2).norm()
            val s = sin(t / 2) / t
            return Quat(cos(t / 2), a * s, b * s, c * s)
        }
        /** The weighted (chordal) mean of [q]. */
        fun mean(q: List<Quat>, wt: DoubleArray): Quat {
            val ref = q[q.size / 2]
            var w = 0.0; var x = 0.0; var y = 0.0; var z = 0.0
            for (i in q.indices) { val p = if (q[i].dot(ref) < 0) q[i].neg() else q[i]; w += p.w * wt[i]; x += p.x * wt[i]; y += p.y * wt[i]; z += p.z * wt[i] }
            return Quat(w, x, y, z).norm()
        }
    }
}

/**
 * The visual gyro: the camera's rotation (and zoom) through the clip as a continuous curve, measured from the tracked
 * points with every row stamped at the moment the rolling shutter read it, the lens's field of view and the sensor's
 * readout found from the footage itself; then a calm path for it (L1-optimal: still, even moves, eased changes) kept
 * within the crop; then, for each output row, the one rotation that takes the calm camera to the real one at the
 * moment that row was read. Nothing in it can bend the picture: no mesh is fitted, no free perspective, so straight
 * lines stay straight and nothing wobbles, and rolling-shutter lean and jello are undone exactly.
 */
object VGyro {
    /** The long side of the picture the points are tracked on. */
    const val LONG = 960
    private const val MAX_PTS = 500
    /** How many frames the lens and readout are found from. */
    private const val CAL_FRAMES = 76
    private const val NP = 4   // rotation about x (pitch), y (yaw), z (roll), log zoom

    /** What to hold steadiest when near and far things move differently (the camera moved, not just turned). */
    const val FOCUS_BACKGROUND = 0; const val FOCUS_BALANCED = 1; const val FOCUS_SUBJECT = 2

    class Settings(
        val smooth: Float = 0.5f,
        /** The most the picture may be zoomed in to hide the edges. */
        val maxZoom: Float = 1.15f,
        val focus: Int = FOCUS_BALANCED,
        val lock: Boolean = false,
        val horizon: Boolean = false,
        /** Horizontal field of view in degrees and readout in frames; 0 / negative: found from the footage. */
        val fov: Float = 0f,
        val readout: Float = -1f,
        /** How much of the shake is taken out (1: all). */
        val strength: Float = 1f,
        /** [Stab.CINEMATIC] (L1: still, even moves, eased changes) or [Stab.NATURAL] (the path gently softened). */
        val method: Int = Stab.CINEMATIC,
        /** How the edges are dealt with ([Stab.FRAMINGS]), the zoom for [Stab.FIXED_ZOOM], and whether auto zoom follows each moment. */
        val framing: Int = Stab.AUTO_ZOOM,
        val zoom: Float = 1f,
        val dynamicZoom: Boolean = false,
    ) {
        companion object {
            /** Graden's stabilisation settings, for the visual gyro. Crop less 0..1 maps to a largest zoom of 1.25..1.05. */
            fun of(s: Stab) = Settings(smooth = s.smooth, maxZoom = 1.25f - 0.2f * s.cropLess.coerceIn(0f, 1f), focus = s.focus,
                lock = s.lock, horizon = s.horizon, strength = s.strength, method = s.method, framing = s.framing, zoom = s.zoom,
                dynamicZoom = s.dynamicZoom)
        }
    }

    /** The solved motion: Ω (integrated rotation rates, and log zoom) at each frame, the focal length (px) and readout (frames). */
    class Motion(val om: DoubleArray, val f: Double, val ro: Double, val residual: Double, val cost: Double)

    // ------------------------------------------------------------------ analysis

    /**
     * Decodes [path] between [inUs] and [outUs] as fast as the decoder goes and tracks it ([VGyroNative]) on another
     * thread at the same time; progress 0..1; [cancelled] stops it.
     */
    /** The analysis resolutions offered (long side, px); [LONG] is the default. */
    val RESOLUTIONS = intArrayOf(480, 640, 960, 1280, 1920, 2880, 3840)

    /** Measures at [longSide] px on the picture's long side (never more than the footage has). */
    fun analyse(context: Context, path: String, rotation: Int, inUs: Long, outUs: Long, detailed: Boolean = false,
                progress: (Float) -> Unit, cancelled: () -> Boolean, longSide: Int = LONG): VGyroAnalysis? {
        val maxPts = if (detailed) MAX_PTS * 2 else MAX_PTS
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var handle = 0L
        try {
            if (path.startsWith("content:")) ex.setDataSource(context, Uri.parse(path), null)
            else ex.setDataSource(if (path.startsWith("file:")) Uri.parse(path).path!! else path)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val rot = if (fmt.containsKey("rotation-degrees")) fmt.getInteger("rotation-degrees") else rotation
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = openDecoder(fmt)
            codec = c
            ex.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            val times = ArrayList<Long>()
            var w = 0; var h = 0
            var inDone = false
            val t0 = android.os.SystemClock.uptimeMillis()
            var tWait = 0L; var tShrink = 0L; var tTake = 0L
            // Each frame is shrunk here, on the decoder's thread; each pair of frames is then tracked by one of a few
            // long-lived workers (its own native tracker each) while decoding goes on. A frame's buffer goes back to the
            // pool once both pairs it is in are done.
            val workers = max(2, min(3, Runtime.getRuntime().availableProcessors() - 3))
            var pool: java.util.concurrent.ExecutorService? = null
            val handles = java.util.concurrent.ConcurrentLinkedQueue<Long>()
            val local = ThreadLocal<Pair<Long, FloatArray>>()
            class Slot(val b: java.nio.ByteBuffer) { val refs = java.util.concurrent.atomic.AtomicInteger() }
            val free = LinkedBlockingQueue<Slot>()
            fun unref(sl: Slot) { if (sl.refs.decrementAndGet() == 0) free.put(sl) }
            val results = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()
            val cutAt = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
            val jobs = ArrayList<java.util.concurrent.Future<*>>()
            var prevBuf: Slot? = null
            // The lens and readout are found from the first frames while the rest are still being decoded.
            val early = CAL_FRAMES
            var calib: java.util.concurrent.Future<Pair<Double, Double>>? = null
            val calPool = Executors.newSingleThreadExecutor()
            var failure: Throwable? = null
            while (true) {
                if (cancelled()) return null
                // Feed every free input slot without waiting; then wait for a decoded frame.
                while (!inDone) {
                    val ii = c.dequeueInputBuffer(0)
                    if (ii < 0) break
                    run {
                        val n = ex.readSampleData(c.getInputBuffer(ii)!!, 0)
                        if (n < 0 || ex.sampleTime > outUs + 50_000) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else { c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val tw = System.nanoTime()
                val o = c.dequeueOutputBuffer(info, 5_000)
                tWait += System.nanoTime() - tw
                if (o < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { c.releaseOutputBuffer(o, false); break }
                val t = info.presentationTimeUs
                if (t < inUs - 1000) { c.releaseOutputBuffer(o, false); continue }
                val img = c.getOutputImage(o)
                if (img == null) { c.releaseOutputBuffer(o, false); continue }
                val p = img.planes[0]; val cr = img.cropRect
                if (handle == 0L) {
                    val sw = cr.width(); val sh = cr.height()
                    val L = longSide.coerceIn(320, max(sw, sh)) / 2 * 2
                    if (sw >= sh) { w = L; h = (L * sh.toFloat() / sw).roundToInt() / 2 * 2 } else { h = L; w = (L * sw.toFloat() / sh).roundToInt() / 2 * 2 }
                    handle = VGyroNative.create(w, h, maxPts)
                    repeat(workers * 2 + 4) { free.put(Slot(java.nio.ByteBuffer.allocateDirect(w * h))) }
                    val ww = w; val hh = h
                    pool = Executors.newFixedThreadPool(workers) { r -> Thread {
                        val hd = VGyroNative.create(ww, hh, maxPts); handles += hd
                        local.set(hd to FloatArray(maxPts * 5))
                        r.run()
                    } }
                }
                val tk = System.nanoTime()
                val b = free.take()
                val ts = System.nanoTime(); tTake += ts - tk
                VGyroNative.shrink(handle, p.buffer, cr.top * p.rowStride + cr.left * p.pixelStride, cr.width(), cr.height(), p.rowStride, p.pixelStride, b.b)
                tShrink += System.nanoTime() - ts
                img.close()
                c.releaseOutputBuffer(o, false)
                val k = times.size
                times += t
                b.refs.set(2)   // this pair and the next
                val a0 = prevBuf
                if (a0 != null) jobs += pool!!.submit {
                    try {
                        val (hd, out) = local.get()!!
                        val m = VGyroNative.pair(hd, a0.b, b.b, out)
                        cutAt[k] = m < 0
                        results[k] = if (m > 0) out.copyOf(m * 5) else FloatArray(0)
                        progress(((t - inUs).toFloat() / max(1L, outUs - inUs)).coerceIn(0f, 1f))
                    } catch (e: Throwable) { failure = e } finally { unref(a0); unref(b) }
                } else unref(b)   // the first frame is in one pair only
                prevBuf = b
                failure?.let { throw it }
                if (calib == null && k >= early + workers * 2 && (1 until early).all { results.containsKey(it) }) {
                    val part = VGyroAnalysis(LongArray(early) { times[it] }, w, h, rot, Array(early) { results[it] ?: FloatArray(0) }, BooleanArray(early) { cutAt[it] == true })
                    calib = calPool.submit(Callable { calibrate(part, Settings()) })
                }
            }
            jobs.forEach { it.get() }
            pool?.shutdown()
            handles.forEach { VGyroNative.release(it) }
            failure?.let { throw it }
            val n = times.size
            if (n < 3) return null
            val pairs = Array(n) { results[it] ?: FloatArray(0) }
            val cuts = BooleanArray(n) { cutAt[it] == true }
            val cal = (calib ?: calPool.submit(Callable { calibrate(VGyroAnalysis(LongArray(n) { times[it] }, w, h, rot, pairs, cuts), Settings()) })).get()
            calPool.shutdown()
            android.util.Log.i("VGyro", "analysed $n frames in ${android.os.SystemClock.uptimeMillis() - t0} ms (waiting for the decoder ${tWait / 1_000_000} ms, for the trackers ${tTake / 1_000_000} ms, shrinking ${tShrink / 1_000_000} ms; $workers trackers, ${pairs.sumOf { it.size / 5 } / n} points)")
            return VGyroAnalysis(LongArray(n) { times[it] }, w, h, rot, pairs, cuts).also { it.fov = cal.first; it.readout = cal.second }
        } catch (e: Exception) {
            android.util.Log.w("VGyro", "analysis failed", e)
            return null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            ex.release()
            if (handle != 0L) VGyroNative.release(handle)
        }
    }

    /**
     * A decoder for [fmt], as fast as it will go: the platform's choice first, then any other that says it can, then,
     * for Dolby Vision, the HEVC or AVC decoders its base layer is made for (only the brightness is used here, so the
     * base layer is enough). Each tried with the speed asked for, then without.
     */
    private fun openDecoder(fmt: MediaFormat): MediaCodec {
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
        val tries = LinkedHashMap<String, MediaFormat>()
        fun add(f: MediaFormat) {
            runCatching { list.findDecoderForFormat(f) }.getOrNull()?.let { tries.putIfAbsent(it, f) }
            val m = f.getString(MediaFormat.KEY_MIME)
            for (info in list.codecInfos) if (!info.isEncoder && info.supportedTypes.any { it.equals(m, true) }) tries.putIfAbsent(info.name, f)
        }
        add(fmt)
        if (mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) for (base in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)) {
            val f = MediaFormat(fmt)
            f.setString(MediaFormat.KEY_MIME, base)
            f.removeKey(MediaFormat.KEY_PROFILE); f.removeKey(MediaFormat.KEY_LEVEL)
            add(f)
        }
        var last: Throwable? = null
        for ((name, f) in tries) for (fast in listOf(true, false)) {
            val c = runCatching { MediaCodec.createByCodecName(name) }.getOrElse { last = it; null } ?: break
            try {
                val g = MediaFormat(f)
                if (fast) { g.setInteger(MediaFormat.KEY_PRIORITY, 1); g.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) }
                c.configure(g, null, null, 0)
                c.start()
                return c
            } catch (e: Exception) { last = e; runCatching { c.release() } }
        }
        throw IllegalStateException("no decoder for $mime", last)
    }

    // ------------------------------------------------------------------ motion

    /**
     * Ω(t), piecewise linear between frame times, from every tracked pair: a point read at time tp that turns up at
     * time tq has moved by what the rotation (and zoom) between those moments does to it. Solved as one banded least
     * squares problem over the whole clip, reweighted (Cauchy) so what moves on its own counts for little.
     */
    fun solve(a: VGyroAnalysis, f: Double, ro: Double, s: Settings, frames: IntRange = 1 until a.size, sub: Int = 0, iters: Int = 5): Motion {
        val n = a.size
        val nu = n * NP
        val B = 16
        val cx = a.w / 2.0; val cy = a.h / 2.0
        val sig = (if (s.focus == FOCUS_SUBJECT) 0.01 else 0.0015) * a.w
        val area = if (s.focus == FOCUS_BALANCED) 1.0 else 0.0
        val near = if (s.focus == FOCUS_SUBJECT) 1.0 else 0.0
        // The points close enough to the frame's overall motion: for subject focus, near things too (further off, since
        // they move more), but not what moves on its own.
        val lim = (if (s.focus == FOCUS_SUBJECT) 0.02f else 0.006f) * a.w
        val ks = frames.filter { it in 1 until n && !a.cut[it] && a.pairs[it].isNotEmpty() }

        /** Accumulator for one slice of frames: the banded normal equations, and the robust cost. */
        class Acc { val M = Array(B + 1) { DoubleArray(nu) }; val r = DoubleArray(nu); var cost = 0.0; var count = 0; val sample = ArrayList<Double>() }

        // One frame's equations, made afresh each round (nothing per point is kept, so any length of clip fits):
        // each point read at time tp turning up at tq; its weight from the last round's fit ([x], null at first).
        val ju = DoubleArray(NP); val jv = DoubleArray(NP)
        fun frame(k: Int, x: DoubleArray?, acc: Acc, ju: DoubleArray, jv: DoubleArray, coef: DoubleArray) {
            val pr = a.pairs[k]; val m = pr.size / 5
            val stride = if (sub > 0 && m > sub) m / sub else 1
            val cnt = IntArray(48)
            fun cell(i: Int): Int { val gx = ((pr[i * 5] + pr[i * 5 + 2]) / 2 / a.w * 8).toInt().coerceIn(0, 7); val gy = ((pr[i * 5 + 1] + pr[i * 5 + 3]) / 2 / a.h * 6).toInt().coerceIn(0, 5); return gy * 8 + gx }
            var i = 0
            while (i < m) { if (pr[i * 5 + 4] < lim) cnt[cell(i)]++; i += stride }
            var med = 1.0
            if (near > 0) {
                val mags = ArrayList<Double>()
                i = 0
                while (i < m) { if (pr[i * 5 + 4] < lim) { mags += abs((pr[i * 5 + 2] - pr[i * 5]).toDouble()); mags += abs((pr[i * 5 + 3] - pr[i * 5 + 1]).toDouble()) }; i += stride }
                if (mags.isNotEmpty()) med = mags.sorted()[mags.size / 2] + 1e-6
            }
            i = 0
            while (i < m) {
                if (pr[i * 5 + 4] >= lim) { i += stride; continue }
                val px = pr[i * 5] - cx; val py = pr[i * 5 + 1] - cy; val qx = pr[i * 5 + 2] - cx; val qy = pr[i * 5 + 3] - cy
                val tp = (k - 1) + ro * ((py + cy) / a.h - 0.5); val tq = k + ro * ((qy + cy) / a.h - 0.5)
                val mx = (px + qx) / 2; val my = (py + qy) / 2
                ju[0] = -(mx * my / f); ju[1] = f + mx * mx / f; ju[2] = -my; ju[3] = mx
                jv[0] = -(f + my * my / f); jv[1] = mx * my / f; jv[2] = mx; jv[3] = my
                val jp = floor(tp).toInt().coerceIn(0, n - 2); val ap = tp - jp
                val jq = floor(tq).toInt().coerceIn(0, n - 2); val aq = tq - jq
                val k0 = min(jp, jq)
                val base = k0 * NP
                val wa = if (area > 0) Math.pow(cnt[cell(i)].toDouble(), -area) else 1.0
                for (comp in 0..1) {
                    val J = if (comp == 0) ju else jv
                    val d = if (comp == 0) qx - px else qy - py
                    java.util.Arrays.fill(coef, 0.0)
                    fun add(knot: Int, wgt: Double) { val o = (knot - k0) * NP; for (c in 0 until NP) coef[o + c] += J[c] * wgt }
                    add(jq, 1 - aq); add(jq + 1, aq); add(jp, -(1 - ap)); add(jp + 1, -ap)
                    var wi = wa * (if (near > 0) Math.pow((abs(d) / med).coerceIn(0.25, 4.0), near) else 1.0)
                    if (x != null) {
                        var pred = 0.0
                        for (p in coef.indices) { val u = base + p; if (u < nu) pred += coef[p] * x[u] }
                        val e = (pred - d) / sig
                        acc.cost += ln(1 + e * e); acc.count++
                        if (acc.sample.size < 4000 && (acc.count and 7) == 0) acc.sample += abs(pred - d)
                        wi /= (1 + e * e)
                    }
                    if (wi < 1e-9) continue
                    for (p in coef.indices) {
                        val cp = coef[p]; if (cp == 0.0) continue
                        val up = base + p
                        if (up >= nu) continue
                        val wc = wi * cp
                        acc.r[up] += wc * d
                        for (q in p until coef.size) {
                            val uq = base + q
                            if (uq >= nu) break
                            acc.M[q - p][up] += wc * coef[q]
                        }
                    }
                }
                i += stride
            }
        }

        // Rounds, the frames shared out among a few threads (each its own accumulator, summed after).
        val threads = if (ks.size > 60) max(1, min(6, Runtime.getRuntime().availableProcessors() - 1)) else 1
        val pool = if (threads > 1) Executors.newFixedThreadPool(threads) else null
        fun round(x: DoubleArray?): Acc {
            fun part(from: Int, to: Int): Acc {
                val acc = Acc(); val a1 = DoubleArray(NP); val a2 = DoubleArray(NP); val coef = DoubleArray(4 * NP)
                for (t in from until to) frame(ks[t], x, acc, a1, a2, coef)
                return acc
            }
            if (pool == null) return part(0, ks.size)
            val parts = (0 until threads).map { t -> pool.submit(Callable { part(t * ks.size / threads, (t + 1) * ks.size / threads) }) }.map { it.get() }
            val acc = parts[0]
            for (pi in 1 until parts.size) {
                val o = parts[pi]
                for (d in 0..B) for (u in 0 until nu) acc.M[d][u] += o.M[d][u]
                for (u in 0 until nu) acc.r[u] += o.r[u]
                acc.cost += o.cost; acc.count += o.count; acc.sample += o.sample
            }
            return acc
        }
        var x: DoubleArray? = null
        var last = Acc()
        try {
            repeat(iters + 1) { it2 ->
                val acc = round(x)
                last = acc
                if (it2 == iters) return@repeat   // the last round only measures the fit
                val M = acc.M
                // A tiny pull to zero and a smoothness prior (second differences), so a frame with nothing tracked is
                // still sensible; the first frame's rotation is where the path starts.
                for (u in 0 until nu) M[0][u] += 1e-6
                for (k in 0 until n - 2) for (c in 0 until NP) {
                    val i0 = k * NP + c
                    M[0][i0] += 1e-2; M[0][i0 + NP] += 4e-2; M[0][i0 + 2 * NP] += 1e-2
                    M[NP][i0] += -2e-2; M[NP][i0 + NP] += -2e-2; M[2 * NP][i0] += 1e-2
                }
                for (c in 0 until NP) M[0][c] += 1e6
                x = bandSolve(M, acc.r, B)
            }
        } finally { pool?.shutdown() }
        val med = last.sample.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        return Motion(x ?: DoubleArray(nu), f, ro, med, last.cost / max(1, last.count))
    }

    /** Banded symmetric positive-definite solve (Cholesky), [M] holding the main diagonal and B above it. */
    private fun bandSolve(M: Array<DoubleArray>, r: DoubleArray, B: Int): DoubleArray {
        val n = r.size
        val L = Array(B + 1) { DoubleArray(n) }   // L[d][i] = L(i+d, i)
        for (j in 0 until n) {
            var d = M[0][j]
            for (k in max(0, j - B) until j) { val l = L[j - k][k]; d -= l * l }
            val dj = sqrt(max(d, 1e-12))
            L[0][j] = dj
            for (dd in 1..B) {
                val i = j + dd; if (i >= n) break
                var v = M[dd][j]
                for (k in max(0, i - B) until j) v -= L[i - k][k] * L[j - k][k]
                L[dd][j] = v / dj
            }
        }
        val y = r.copyOf()
        for (i in 0 until n) { var v = y[i]; for (k in max(0, i - B) until i) v -= L[i - k][k] * y[k]; y[i] = v / L[0][i] }
        for (i in n - 1 downTo 0) { var v = y[i]; for (k in i + 1..min(n - 1, i + B)) v -= L[k - i][i] * y[k]; y[i] = v / L[0][i] }
        return y
    }

    /** The field of view and readout that explain the footage best, unless [s] gives them. */
    fun calibrate(a: VGyroAnalysis, s: Settings): Pair<Double, Double> {
        val fovs = if (s.fov > 0) listOf(s.fov.toDouble()) else listOf(50.0, 60.0, 70.0, 80.0, 95.0)
        val ros = if (s.readout >= 0) listOf(s.readout.toDouble()) else listOf(0.0, 0.25, 0.5, 0.75, 0.9)
        if (fovs.size == 1 && ros.size == 1) return fovs[0] to ros[0]
        val frames = 1 until min(a.size, CAL_FRAMES)
        val pool = Executors.newFixedThreadPool(max(2, Runtime.getRuntime().availableProcessors() - 2))
        try {
            val jobs = fovs.flatMap { fv -> ros.map { ro -> fv to ro } }.map { (fv, ro) ->
                pool.submit(Callable {
                    val f = (a.w / 2.0) / tan(Math.toRadians(fv) / 2)
                    val m = solveCost(a, f, ro, s, frames)
                    Triple(m, fv, ro)
                })
            }
            val best = jobs.map { it.get() }.minBy { it.first }
            // a finer look around the best field of view
            val fine = if (s.fov > 0) emptyList() else listOf(best.second - 5, best.second + 5).map { fv ->
                pool.submit(Callable { Triple(solveCost(a, (a.w / 2.0) / tan(Math.toRadians(fv) / 2), best.third, s, frames), fv, best.third) })
            }.map { it.get() }
            val b = (fine + best).minBy { it.first }
            return b.second to b.third
        } finally { pool.shutdown() }
    }

    private fun solveCost(a: VGyroAnalysis, f: Double, ro: Double, s: Settings, frames: IntRange) =
        solve(a, f, ro, s, frames, sub = 160, iters = 3).cost

    // ------------------------------------------------------------------ path and output

    /** The camera's orientation at any time, from Ω, built up on a fine time grid. */
    private class Orient(m: Motion, val n: Int) {
        val sub = 16
        val q: Array<Quat>
        val z: DoubleArray
        init {
            val g = (n - 1) * sub + 1
            q = Array(g) { Quat.ID }; z = DoubleArray(g)
            var prev = omega(m, 0.0)
            z[0] = prev[3]
            for (i in 1 until g) {
                val o = omega(m, i.toDouble() / sub)
                q[i] = (Quat.exp(o[0] - prev[0], o[1] - prev[1], o[2] - prev[2]) * q[i - 1]).norm()
                z[i] = o[3]; prev = o
            }
        }
        private fun omega(m: Motion, t: Double): DoubleArray {
            val j = floor(t).toInt().coerceIn(0, n - 2); val al = t - j
            return DoubleArray(NP) { c -> m.om[j * NP + c] * (1 - al) + m.om[(j + 1) * NP + c] * al }
        }
        fun at(t: Double): Int = (t * sub).roundToInt().coerceIn(0, q.size - 1)
    }

    /**
     * The stabilised clip: settings [s], at [fps], as a [StabTrack] for Graden's shader - each frame's zoom as its matrix
     * and, as its mesh, where each output point is read from (the per-row rotation, evaluated at the mesh's corners).
     */
    fun track(a0: VGyroAnalysis, s: Settings, fps: Float, inUs: Long = Long.MIN_VALUE, outUs: Long = Long.MAX_VALUE, log: (String) -> Unit = {}): StabTrack {
        val a = a0.slice(inUs, outUs)
        val n = a.size
        if (n < 3) return StabTrack(a.times.copyOf(), FloatArray(n * 9) { if (it % 4 == 0) 1f else 0f }, null, s.framing, FloatArray(n * 3), FloatArray(n * 3), FloatArray(n) { 1f })
        val t0 = System.currentTimeMillis()
        val (fov, ro) = if ((s.fov > 0 || a.fov > 0) && (s.readout >= 0 || a.readout >= 0))
            (if (s.fov > 0) s.fov.toDouble() else a.fov) to (if (s.readout >= 0) s.readout.toDouble() else a.readout)
        else calibrate(a, s)
        val f = (a.w / 2.0) / tan(Math.toRadians(fov) / 2)
        val t1 = System.currentTimeMillis()
        val mo = solve(a, f, ro, s)
        val t2 = System.currentTimeMillis()
        log("fov %.0f°, readout %.1f ms, fit %.3f px; calibrate %d ms, solve %d ms".format(fov, ro / fps * 1000, mo.residual, t1 - t0, t2 - t1))
        val or = Orient(mo, n)
        val A = Array(n) { or.q[or.at(it.toDouble())] }
        val Z = DoubleArray(n) { or.z[or.at(it.toDouble())] }
        val cx = a.w / 2.0; val cy = a.h / 2.0
        val starts = (0 until n).filter { it == 0 || a.cut[it] }.toIntArray()

        // The calm path: a slow reference (2 s), the camera's orientation relative to it as small angles, each given an
        // L1-optimal path within its share of the crop; zoom the same way. Where a frame would still need more zoom
        // than allowed, its freedom shrinks and the path is solved again.
        fun gauss(sig: Double): Pair<Array<Quat>, DoubleArray> {
            val r = (3 * sig).toInt() + 1
            val zs = DoubleArray(n)
            val q = Array(n) { k ->
                val lo = max(0, k - r); val hi = min(n - 1, k + r)
                val wt = DoubleArray(hi - lo + 1) { exp(-0.5 * ((lo + it - k) / sig).let { v -> v * v }) }
                zs[k] = (lo..hi).sumOf { Z[it] * wt[it - lo] } / wt.sum()
                Quat.mean((lo..hi).map { A[it] }, wt)
            }
            return q to zs
        }
        val (G, zG) = gauss(2.0 * fps)
        val loc = Array(n) { (G[it].inv() * A[it]).log() }
        val zl = DoubleArray(n) { Z[it] - zG[it] }
        val cap = s.maxZoom.coerceIn(1.02f, 2f).toDouble()
        val marg = (1 - 1 / cap) / 2
        val bnd = doubleArrayOf(marg * a.h * 0.8 / f, marg * a.w * 0.8 / f, marg * a.h * 0.6 / hypot(cx, cy), 0.4 * marg)
        val lam0 = 0.5 * Math.pow(10.0, -4.0 * s.smooth.coerceIn(0f, 1f).toDouble())
        val scale = DoubleArray(n) { 1.0 }
        var As = A; var zs = Z
        var need = DoubleArray(n) { 1.0 }
        val axes = Executors.newFixedThreadPool(4)
        try { for (pass in 0 until 4) {
            // The four paths are independent: solved side by side.
            val k = s.strength.coerceIn(0f, 1f)
            val ps = (0 until 4).map { c -> axes.submit(Callable {
                val col = FloatArray(n) { if (c < 3) loc[it][c].toFloat() else zl[it].toFloat() }
                val lim = FloatArray(n) { (bnd[c] * scale[it]).toFloat() }
                val p = when {
                    s.lock -> FloatArray(n) { i -> var st = i; while (st > 0 && !(st in starts)) st--; col[st] }
                    c == 2 && s.horizon -> FloatArray(n) { (-(G[it].log()[2])).toFloat() }
                    s.method == Stab.NATURAL -> natural(col, lim, starts, (0.08f + s.smooth.coerceIn(0f, 1f) * 1.9f) * fps)
                    else -> StabCore.l1Path(col, starts, lim, lam0)
                }
                FloatArray(n) { col[it] + (p[it] - col[it]) * k }
            }) }.map { it.get() }
            As = Array(n) { (G[it] * Quat.exp(ps[0][it].toDouble(), ps[1][it].toDouble(), ps[2][it].toDouble())).norm() }
            zs = DoubleArray(n) { zG[it] + ps[3][it] }
            need = DoubleArray(n) { k -> needZoom(a, or, f, ro, As[k], zs[k], k) }
            if (s.lock) break
            val over = (0 until n).filter { need[it] > cap * 1.003 }
            if (over.isEmpty()) break
            val fac = DoubleArray(n) { 1.0 }
            for (k in over) fac[k] = ((cap - 1) / (need[k] - 1)).coerceIn(0.05, 1.0)
            for (k in 0 until n) { var m = 1.0; for (d in -3..3) m = min(m, fac[(k + d).coerceIn(0, n - 1)]); scale[k] *= m }
        }
        } finally { axes.shutdown() }
        // The zoom: auto (enough for the whole clip, or as each moment needs), a set one, or none (the edges filled).
        val zooms = when {
            s.framing == Stab.FIXED_ZOOM -> FloatArray(n) { s.zoom.coerceIn(1f, 2f) }
            s.framing != Stab.AUTO_ZOOM -> FloatArray(n) { 1f }
            !s.dynamicZoom -> FloatArray(n) { need.max().toFloat().coerceIn(1f, 3f) }
            else -> {
                // Each moment's need, held over the half second around it and smoothed, so the zoom breathes slowly and
                // never falls short of what a frame needs.
                val r = (fps / 2).toInt().coerceAtLeast(1)
                val held = FloatArray(n) { i -> var m = 1.0; for (j in max(0, i - r)..min(n - 1, i + r)) m = max(m, need[j]); m.toFloat() }
                val g = StabCore.gaussian(held, fps / 2, starts)
                FloatArray(n) { max(g[it], need[it].toFloat()).coerceIn(1f, 3f) }
            }
        }
        val zoom = zooms.max()
        val t3 = System.currentTimeMillis()

        // Each frame's mesh: for every corner of the 16 × 9 grid (upright picture, already zoomed by the matrix), the
        // point of the stored frame it is read from, at the moment its row was read.
        val nv = StabMesh.NV
        val grids = FloatArray(n * nv * 2)
        val tmp = DoubleArray(2)
        for (k in 0 until n) for (v in 0 until nv) {
            val ux = (v % StabMesh.VX).toDouble() / StabMesh.GX; val uy = (v / StabMesh.VX).toDouble() / StabMesh.GY
            toStored(ux, uy, a.rotation, tmp)
            val sx = tmp[0] * a.w - cx; val sy = tmp[1] * a.h - cy
            val inp = mapPoint(or, f, ro, a.h, As[k], zs[k], k, sx, sy)
            fromStored((inp[0] + cx) / a.w, (inp[1] + cy) / a.h, a.rotation, tmp)
            grids[(k * nv + v) * 2] = (tmp[0] - ux).toFloat(); grids[(k * nv + v) * 2 + 1] = (tmp[1] - uy).toFloat()
        }
        val mats = FloatArray(n * 9) { i -> val z = zooms[i / 9]; floatArrayOf(1f / z, 0f, 0.5f - 0.5f / z, 0f, 1f / z, 0.5f - 0.5f / z, 0f, 0f, 1f)[i % 9] }
        // For the page's graph: the camera's path and the calm one, as sideways and up-down shift (fractions of the
        // picture) and turn (radians), from the first frame.
        fun path(q: Quat, out: FloatArray, i: Int) {
            val l = q.log()
            out[i * 3] = (l[1] * f / a.w).toFloat(); out[i * 3 + 1] = (l[0] * f / a.h).toFloat(); out[i * 3 + 2] = l[2].toFloat()
        }
        val rawP = FloatArray(n * 3); val smP = FloatArray(n * 3)
        for (i in 0 until n) { path(A[i], rawP, i); path(As[i], smP, i) }
        // For the page's 3D camera: each orientation turned camera-to-world (the inverse) and into the upright
        // picture's axes (stored x, y = R · upright x, y, so the rotation vector is −Rᵀ · log q).
        val rawR = FloatArray(n * 3); val smR = FloatArray(n * 3)
        fun upright(q: Quat, out: FloatArray, i: Int) {
            val l = q.log(); val x = -l[0]; val y = -l[1]
            val (ux, uy) = when (a.rotation) { 90 -> -y to x; 180 -> -x to -y; 270 -> y to -x; else -> x to y }
            out[i * 3] = ux.toFloat(); out[i * 3 + 1] = uy.toFloat(); out[i * 3 + 2] = (-l[2]).toFloat()
        }
        for (i in 0 until n) { upright(A[i], rawR, i); upright(As[i], smR, i) }
        val upW = if (a.rotation % 180 == 0) a.w else a.h; val upH = if (a.rotation % 180 == 0) a.h else a.w
        val hfov = (2 * kotlin.math.atan(upW / 2.0 / f)).toFloat()
        log("zoom %.3f; path %d ms, mesh %d ms".format(zoom, t3 - t2, System.currentTimeMillis() - t3))
        return StabTrack(a.times.copyOf(), mats, grids, s.framing, rawP, smP, zooms, rawR, smR, hfov, upW.toFloat() / upH)
    }

    /**
     * The path gently softened: [m] blurred over [sigma] frames within each shot, then held within [lim] of [m] and
     * softened again lightly, so where it has to give way it does so smoothly.
     */
    private fun natural(m: FloatArray, lim: FloatArray, starts: IntArray, sigma: Float): FloatArray {
        var p = StabCore.gaussian(m, sigma, starts)
        repeat(3) {
            val c = FloatArray(m.size) { p[it].coerceIn(m[it] - lim[it], m[it] + lim[it]) }
            p = StabCore.gaussian(c, 3f, starts)
        }
        return FloatArray(m.size) { p[it].coerceIn(m[it] - lim[it], m[it] + lim[it]) }
    }

    /** Where output point (sx, sy) of frame k (stored pixels, centred) is read from, the row's own moment found twice. */
    private fun mapPoint(or: Orient, f: Double, ro: Double, h: Int, As: Quat, zs: Double, k: Int, sx: Double, sy: Double): DoubleArray {
        val inv = As.inv().matrix()
        val d0x = inv[0] * sx / f + inv[1] * sy / f + inv[2]; val d0y = inv[3] * sx / f + inv[4] * sy / f + inv[5]; val d0z = inv[6] * sx / f + inv[7] * sy / f + inv[8]
        var row = sy + h / 2.0
        var px = 0.0; var py = 0.0
        repeat(2) {
            val i = or.at(k + ro * (row / h - 0.5))
            val m = or.q[i].matrix()
            val X = m[0] * d0x + m[1] * d0y + m[2] * d0z; val Y = m[3] * d0x + m[4] * d0y + m[5] * d0z; val W = m[6] * d0x + m[7] * d0y + m[8] * d0z
            val sc = exp(or.z[i] - zs)
            px = X / W * f * sc; py = Y / W * f * sc
            row = py + h / 2.0
        }
        return doubleArrayOf(px, py)
    }

    /** The least zoom that keeps frame k inside the real picture. */
    private fun needZoom(a: VGyroAnalysis, or: Orient, f: Double, ro: Double, As: Quat, zs: Double, k: Int): Double {
        val cx = a.w / 2.0; val cy = a.h / 2.0
        val bx = ArrayList<Double>(); val by = ArrayList<Double>()
        for (i in 0..8) { val t = i / 8.0; bx += (t - 0.5) * a.w; by += -cy; bx += (t - 0.5) * a.w; by += cy }
        for (i in 1..5) { val t = i / 6.0; bx += -cx; by += (t - 0.5) * a.h; bx += cx; by += (t - 0.5) * a.h }
        var lo = 1.0; var hi = 3.0
        repeat(12) {
            val z = (lo + hi) / 2
            var ok = true
            for (i in bx.indices) {
                val p = mapPoint(or, f, ro, a.h, As, zs, k, bx[i] / z, by[i] / z)
                if (abs(p[0]) > cx || abs(p[1]) > cy) { ok = false; break }
            }
            if (ok) hi = z else lo = z
        }
        return hi
    }

    /** Upright picture point (u, v) to the stored frame, as Graden's shader turns it (uRot). */
    private fun toStored(u: Double, v: Double, rot: Int, out: DoubleArray) {
        when (rot) {
            90 -> { out[0] = v; out[1] = 1 - u }
            180 -> { out[0] = 1 - u; out[1] = 1 - v }
            270 -> { out[0] = 1 - v; out[1] = u }
            else -> { out[0] = u; out[1] = v }
        }
    }

    private fun fromStored(x: Double, y: Double, rot: Int, out: DoubleArray) {
        when (rot) {
            90 -> { out[0] = 1 - y; out[1] = x }
            180 -> { out[0] = 1 - x; out[1] = 1 - y }
            270 -> { out[0] = y; out[1] = 1 - x }
            else -> { out[0] = x; out[1] = y }
        }
    }
}

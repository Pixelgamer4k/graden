package com.graden.editor

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The stabiliser's engine, in plain Kotlin (no Android), so the phone and the desktop test bench run the very same
 * code.
 *
 * Analysis follows a few hundred corners from frame to frame (pyramidal Lucas–Kanade, every point checked by
 * tracking it back), fits the camera's motion robustly (RANSAC homography, falling back to similarity or position
 * when the picture says too little), and measures what the single motion leaves over across the picture on a
 * 16 × 9 mesh: parallax, lens and the rolling-shutter jello of phone sensors.
 *
 * [StabCore.track] then smooths the camera path (or locks it), per shot, and works out the correction for each frame:
 * a global transform (translation, similarity, perspective) or, in Warp, a smooth per-mesh-point warp that holds the
 * whole picture still rather than only its middle (as Premiere's subspace warp and MeshFlow do).
 */

/**
 * How a clip is stabilised: what kind of motion is followed ([mode]), whether the camera is smoothed or locked
 * ([lock]), how much ([smooth], [strength]), whether the horizon is held level, how the edges that open up are dealt
 * with ([framing], [zoom]), the trade-off between cropping and smoothing ([cropLess]), rolling-shutter (jello)
 * correction for the global modes, and how thoroughly the motion was measured ([detailed]).
 */
data class Stab(
    val on: Boolean = true,
    val mode: Int = GYRO,
    val lock: Boolean = false,
    val smooth: Float = 0.5f,
    val strength: Float = 1f,
    val horizon: Boolean = false,
    val framing: Int = AUTO_ZOOM,
    val zoom: Float = 1f,
    val dynamicZoom: Boolean = false,
    val cropLess: Float = 0.5f,
    val rollingShutter: Float = 0f,
    val detailed: Boolean = false,
    /** How the path is smoothed: 0 cinematic (L1: still, steady glides and eases, as a tripod, dolly or crane), 1 natural (a gentle blur of the path). */
    val method: Int = CINEMATIC,
    /** Gyro: what to hold steadiest when near and far move differently ([VGyro.FOCUS_BACKGROUND], balanced, subject). */
    val focus: Int = 1,
) {
    companion object {
        const val CINEMATIC = 0; const val NATURAL = 1
        val METHODS = listOf("Cinematic", "Natural")
        const val TRANSLATION = 0; const val SIMILARITY = 1; const val PERSPECTIVE = 2; const val WARP = 3
        /** The visual gyro (see VGyro.kt): the camera's own 3D turn, row by row. */
        const val GYRO = 4
        val MODES = listOf("Translation", "Similarity", "Perspective", "Warp", "Gyro")
        val FOCUSES = listOf("Background", "Balanced", "Subject")
        const val AUTO_ZOOM = 0; const val FIXED_ZOOM = 1; const val MIRROR = 2; const val STRETCH = 3; const val BLACK = 4
        val FRAMINGS = listOf("Auto zoom", "Fixed zoom", "Mirror", "Stretch", "Black")
    }
}

/** A grey picture, values 0..1, row by row from the top. */
class Gray(val w: Int, val h: Int, val v: FloatArray)

/** The mesh the residual motion is measured and corrected on: cells across and down, and their corners. */
object StabMesh {
    const val GX = 16
    const val GY = 9
    const val VX = GX + 1
    const val VY = GY + 1
    const val NV = VX * VY
}

/**
 * The measured motion of a clip, frame by frame (by file time): the camera's motion from the previous frame as a
 * homography in picture coordinates (x across, y down, 0..1), the motion at each mesh point, and how well it is known
 * (0..1; below 0 marks a cut, where a new shot starts).
 */
class StabAnalysis(val times: LongArray, val steps: FloatArray, val mesh: FloatArray, val quality: FloatArray, val aspect: Float) {
    val size get() = times.size

    fun save(f: File) {
        f.parentFile?.mkdirs()
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(VERSION); o.writeInt(size); o.writeFloat(aspect)
            for (t in times) o.writeLong(t)
            for (v in steps) o.writeFloat(v)
            for (v in mesh) o.writeFloat(v)
            for (q in quality) o.writeFloat(q)
        }
    }

    companion object {
        const val VERSION = 4
        fun load(f: File): StabAnalysis? = runCatching {
            DataInputStream(f.inputStream().buffered()).use { i ->
                if (i.readInt() != VERSION) return null
                val n = i.readInt(); val aspect = i.readFloat()
                StabAnalysis(LongArray(n) { i.readLong() }, FloatArray(n * 9) { i.readFloat() }, FloatArray(n * StabMesh.NV * 2) { i.readFloat() },
                    FloatArray(n) { i.readFloat() }, aspect)
            }
        }.getOrNull()
    }
}

/**
 * A stabilised clip ready to draw. For each frame (by file time): [mats], the matrix from the output picture to a
 * picture position (row-major 3×3, picture coordinates, y down), and in Warp [grids], the displacement to add there
 * (on the mesh, x and y per point) to find where to read the frame. Also the camera's path before and after (x, y,
 * turn per frame) and the zoom, for the motion graph.
 */
class StabTrack(
    val times: LongArray, val mats: FloatArray, val grids: FloatArray?, val framing: Int,
    val rawPath: FloatArray, val smoothPath: FloatArray, val zooms: FloatArray,
) {
    /** The frame nearest file time [us]. */
    fun index(us: Long): Int {
        if (times.isEmpty()) return -1
        var lo = 0; var hi = times.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (times[mid] < us) lo = mid + 1 else hi = mid }
        return if (lo > 0 && abs(times[lo - 1] - us) < abs(times[lo] - us)) lo - 1 else lo
    }

    /** The matrix for the frame at file time [us], into [out] (9 floats); returns its index (-1: none). */
    fun at(us: Long, out: FloatArray): Int {
        val i = index(us)
        if (i < 0) { identity(out); return -1 }
        System.arraycopy(mats, i * 9, out, 0, 9)
        return i
    }

    companion object {
        fun identity(out: FloatArray) { out.fill(0f); out[0] = 1f; out[4] = 1f; out[8] = 1f }
    }
}

/** 3×3 matrices, row-major, as float arrays. */
internal object M3 {
    fun ident() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** [a] then [b]: b × a. */
    fun mul(b: FloatArray, a: FloatArray) = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        b[r * 3] * a[c] + b[r * 3 + 1] * a[3 + c] + b[r * 3 + 2] * a[6 + c]
    }

    fun ax(m: FloatArray, x: Float, y: Float) = (m[0] * x + m[1] * y + m[2]) / (m[6] * x + m[7] * y + m[8])
    fun ay(m: FloatArray, x: Float, y: Float) = (m[3] * x + m[4] * y + m[5]) / (m[6] * x + m[7] * y + m[8])

    fun inv(m: FloatArray): FloatArray {
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble(); val d = m[3].toDouble(); val e = m[4].toDouble()
        val f = m[5].toDouble(); val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-12) return ident()
        return floatArrayOf(
            ((e * i - f * h) / det).toFloat(), ((c * h - b * i) / det).toFloat(), ((b * f - c * e) / det).toFloat(),
            ((f * g - d * i) / det).toFloat(), ((a * i - c * g) / det).toFloat(), ((c * d - a * f) / det).toFloat(),
            ((d * h - e * g) / det).toFloat(), ((b * g - a * h) / det).toFloat(), ((a * e - b * d) / det).toFloat(),
        )
    }

    fun norm(m: FloatArray): FloatArray = if (abs(m[8]) < 1e-9f) m else FloatArray(9) { m[it] / m[8] }

    /** The camera's path as (x, y, angle, log-scale) of where the picture's centre and axes went. */
    fun decompose(m: FloatArray, asp: Float): FloatArray {
        val cx = ax(m, 0.5f, 0.5f); val cy = ay(m, 0.5f, 0.5f)
        val dx = (ax(m, 0.6f, 0.5f) - cx) * asp; val dy = ay(m, 0.6f, 0.5f) - cy
        return floatArrayOf(cx, cy, atan2(dy, dx), ln(max(hypot(dx, dy) / (0.1f * asp), 1e-4f)))
    }

    /** The similarity with that centre, angle and scale. */
    fun compose(p: FloatArray, asp: Float): FloatArray {
        val s = exp(p[3]); val c = cos(p[2]) * s; val sn = sin(p[2]) * s
        val b = -sn / asp
        return floatArrayOf(c, b, p[0] - (c * 0.5f + b * 0.5f), sn * asp, c, p[1] - (sn * asp * 0.5f + c * 0.5f), 0f, 0f, 1f)
    }

    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (abs(m[p][c]) < 1e-14) return null
            val t = m[p]; m[p] = m[c]; m[c] = t
            for (r in 0 until n) if (r != c) {
                val f = m[r][c] / m[c][c]
                if (f != 0.0) for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}

/**
 * Follows the picture from frame to frame. Feed it every frame in order ([next]); each call returns the motion from
 * the previous frame. [pool] spreads the point tracking over the phone's cores.
 */
class StabTracker(private val detailed: Boolean, private val pool: ExecutorService? = null) {
    class Result(val step: FloatArray, val mesh: FloatArray, val quality: Float)

    private class Level(val w: Int, val h: Int, val v: FloatArray, val gx: FloatArray, val gy: FloatArray)

    private val levels = 4
    private val r = 5
    private var prev: Array<Level>? = null
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private val rnd = java.util.Random(11)
    /** Where the background has been, see [StabFit.prior]. */
    private val prior = FloatArray(StabFit.PRIOR * StabFit.PRIOR) { 1f }

    private fun blur(v: FloatArray, w: Int, h: Int): FloatArray {
        val t = FloatArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) t[o + x] = (v[o + max(x - 1, 0)] + 2 * v[o + x] + v[o + min(x + 1, w - 1)]) * 0.25f
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val a = max(y - 1, 0) * w; val b = y * w; val c = min(y + 1, h - 1) * w
            for (x in 0 until w) out[b + x] = (t[a + x] + 2 * t[b + x] + t[c + x]) * 0.25f
        }
        return out
    }

    private fun level(w: Int, h: Int, v: FloatArray): Level {
        val gx = FloatArray(w * h); val gy = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            gx[i] = (v[y * w + min(x + 1, w - 1)] - v[y * w + max(x - 1, 0)]) * 0.5f
            gy[i] = (v[min(y + 1, h - 1) * w + x] - v[max(y - 1, 0) * w + x]) * 0.5f
        }
        return Level(w, h, v, gx, gy)
    }

    private fun pyramid(g: Gray): Array<Level> {
        val out = ArrayList<Level>()
        var w = g.w; var h = g.h
        var v = blur(g.v, w, h)
        out += level(w, h, v)
        repeat(levels - 1) {
            val nw = w / 2; val nh = h / 2
            val nv = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                val i = (y * 2) * w + x * 2
                nv[y * nw + x] = (v[i] + v[i + 1] + v[i + w] + v[i + w + 1]) * 0.25f
            }
            w = nw; h = nh; v = blur(nv, w, h)
            out += level(w, h, v)
        }
        return out.toTypedArray()
    }

    /** Corners (the smaller eigenvalue of the structure tensor), the best one in each cell of a grid, so they spread. */
    private fun corners(l: Level): Pair<FloatArray, FloatArray> {
        val w = l.w; val h = l.h
        // Integral images of the gradient products.
        val iw = w + 1
        val sxx = DoubleArray(iw * (h + 1)); val syy = DoubleArray(iw * (h + 1)); val sxy = DoubleArray(iw * (h + 1))
        for (y in 0 until h) {
            var ax = 0.0; var ay = 0.0; var axy = 0.0
            for (x in 0 until w) {
                val gx = l.gx[y * w + x].toDouble(); val gy = l.gy[y * w + x].toDouble()
                ax += gx * gx; ay += gy * gy; axy += gx * gy
                val o = (y + 1) * iw + x + 1
                sxx[o] = sxx[o - iw] + ax; syy[o] = syy[o - iw] + ay; sxy[o] = sxy[o - iw] + axy
            }
        }
        val k = 3
        fun box(s: DoubleArray, x: Int, y: Int) = s[(y + k + 1) * iw + x + k + 1] - s[(y - k) * iw + x + k + 1] - s[(y + k + 1) * iw + x - k] + s[(y - k) * iw + x - k]
        val m = 12
        val resp = FloatArray(w * h)
        var top = 0f
        for (y in m until h - m) for (x in m until w - m) {
            val a = box(sxx, x, y); val c = box(syy, x, y); val b = box(sxy, x, y)
            val e = ((a + c) / 2 - sqrt(((a - c) / 2) * ((a - c) / 2) + b * b)).toFloat()
            resp[y * w + x] = e
            if (e > top) top = e
        }
        val cols = if (detailed) 32 else 24; val rows = if (detailed) 18 else 14
        val floor = max(top * 0.008f, 1e-6f)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (rr in 0 until rows) for (cc in 0 until cols) {
            val x0 = max(m, cc * w / cols); val x1 = min(w - m, (cc + 1) * w / cols)
            val y0 = max(m, rr * h / rows); val y1 = min(h - m, (rr + 1) * h / rows)
            var best = floor; var bx = -1; var by = -1
            for (y in y0 until y1) for (x in x0 until x1) { val e = resp[y * w + x]; if (e > best) { best = e; bx = x; by = y } }
            if (bx >= 0) { xs += bx.toFloat(); ys += by.toFloat() }
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun samp(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xc = if (x < 0f) 0f else if (x > w - 1.001f) w - 1.001f else x
        val yc = if (y < 0f) 0f else if (y > h - 1.001f) h - 1.001f else y
        val xi = xc.toInt(); val yi = yc.toInt()
        val fx = xc - xi; val fy = yc - yi
        val i = yi * w + xi
        val top = a[i] + (a[i + 1] - a[i]) * fx
        val bot = a[i + w] + (a[i + w + 1] - a[i + w]) * fx
        return top + (bot - top) * fy
    }

    /**
     * Lucas–Kanade, coarse to fine: where point ([x0], [y0]) of [a] went in [b], into [out] (x, y, mean difference).
     * False when it was lost.
     */
    private fun lk(a: Array<Level>, b: Array<Level>, x0: Float, y0: Float, out: FloatArray, ti: FloatArray, tx: FloatArray, ty: FloatArray): Boolean {
        var gx = 0f; var gy = 0f
        val side = 2 * r + 1
        for (l in levels - 1 downTo 0) {
            val s = 1f / (1 shl l)
            val ax = (x0 + 0.5f) * s - 0.5f; val ay = (y0 + 0.5f) * s - 0.5f
            val la = a[l]; val lb = b[l]
            var sxx = 0f; var syy = 0f; var sxy = 0f
            var k = 0
            for (dy in -r..r) for (dx in -r..r) {
                val qx = ax + dx; val qy = ay + dy
                ti[k] = samp(la.v, la.w, la.h, qx, qy)
                val ix = samp(la.gx, la.w, la.h, qx, qy); val iy = samp(la.gy, la.w, la.h, qx, qy)
                tx[k] = ix; ty[k] = iy
                sxx += ix * ix; syy += iy * iy; sxy += ix * iy
                k++
            }
            val det = sxx * syy - sxy * sxy
            var vx = 0f; var vy = 0f
            if (det > 1e-10f && det > (sxx + syy) * (sxx + syy) * 1e-3f) {
                for (it in 0 until 12) {
                    var bx = 0f; var by = 0f
                    k = 0
                    val ox = ax + gx + vx; val oy = ay + gy + vy
                    for (dy in -r..r) for (dx in -r..r) {
                        val d = ti[k] - samp(lb.v, lb.w, lb.h, ox + dx, oy + dy)
                        bx += d * tx[k]; by += d * ty[k]
                        k++
                    }
                    val ux = (syy * bx - sxy * by) / det; val uy = (sxx * by - sxy * bx) / det
                    vx += ux; vy += uy
                    if (ux * ux + uy * uy < 1e-4f) break
                }
            } else if (l == 0) return false
            if (l > 0) { gx = (gx + vx) * 2f; gy = (gy + vy) * 2f } else { gx += vx; gy += vy }
        }
        val l0 = b[0]
        val fx = x0 + gx; val fy = y0 + gy
        if (fx < 1f || fy < 1f || fx > l0.w - 2f || fy > l0.h - 2f) return false
        var err = 0f
        var k = 0
        for (dy in -r..r) for (dx in -r..r) { err += abs(ti[k] - samp(l0.v, l0.w, l0.h, fx + dx, fy + dy)); k++ }
        out[0] = fx; out[1] = fy; out[2] = err / (side * side)
        return true
    }

    /** The motion from the last frame to [g]. */
    fun next(g: Gray): Result {
        val pyr = pyramid(g)
        val pv = prev
        prev = pyr
        if (pv == null) {
            val (xs, ys) = corners(pyr[0]); px = xs; py = ys
            return Result(M3.ident(), FloatArray(StabMesh.NV * 2), 1f)
        }
        val n = px.size
        val bx = FloatArray(n); val by = FloatArray(n); val ok = BooleanArray(n)
        val fb = if (detailed) 0.25f else 0.4f
        fun work(from: Int, to: Int) {
            val o = FloatArray(3); val back = FloatArray(3)
            val side = 2 * r + 1
            val ti = FloatArray(side * side); val tx = FloatArray(side * side); val ty = FloatArray(side * side)
            for (i in from until to) {
                if (!lk(pv, pyr, px[i], py[i], o, ti, tx, ty)) continue
                if (o[2] > 0.09f) continue
                // Every point is followed back again: one that does not return to where it started was lost.
                if (!lk(pyr, pv, o[0], o[1], back, ti, tx, ty)) continue
                if (hypot(back[0] - px[i], back[1] - py[i]) > fb) continue
                bx[i] = o[0]; by[i] = o[1]; ok[i] = true
            }
        }
        val p = pool
        if (p != null && n > 64) {
            val parts = 8
            val fs = (0 until parts).map { k -> p.submit { work(k * n / parts, (k + 1) * n / parts) } }
            fs.forEach { it.get() }
        } else work(0, n)
        val w = g.w.toFloat(); val h = g.h.toFloat()
        val asp = w / h
        val idx = (0 until n).filter { ok[it] }
        val m = idx.size
        val ax = FloatArray(m) { (px[idx[it]] + 0.5f) / w }; val ay = FloatArray(m) { (py[idx[it]] + 0.5f) / h }
        val qx = FloatArray(m) { (bx[idx[it]] + 0.5f) / w }; val qy = FloatArray(m) { (by[idx[it]] + 0.5f) / h }
        // Fresh corners from this frame for the next.
        val (xs, ys) = corners(pyr[0]); px = xs; py = ys
        // Almost nothing followed: a cut (or a flash, or the lens covered). A new shot starts here.
        if (n >= 30 && m < max(10, (n * 0.15f).toInt())) { prior.fill(1f); return Result(M3.ident(), FloatArray(StabMesh.NV * 2), -1f) }
        val fit = StabFit(ax, ay, qx, qy, asp, rnd, if (detailed) 900 else 500, prior.takeIf { StabCore.Tune.prior > 0.5f })
        val step = fit.best()
        // The parts of the picture that moved with the camera this time are trusted more next time.
        val ag = fit.agreement(step)
        for (c in prior.indices) if (ag[c] >= 0f) prior[c] = prior[c] * 0.5f + ag[c] * 0.5f
        val mesh = fit.mesh(step)
        return Result(step, mesh, (fit.agree / max(1f, m.toFloat())).coerceIn(0f, 1f) * (m / max(1f, n.toFloat())).coerceIn(0f, 1f).let { sqrt(it) })
    }
}

/**
 * The camera's motion between two frames, from matched points (a → b, picture coordinates), robustly: RANSAC picks
 * the motion most points agree with, so a subject moving on its own (a hand, a car) is left out.
 */
internal class StabFit(
    private val ax: FloatArray, private val ay: FloatArray, private val bx: FloatArray, private val by: FloatArray,
    private val asp: Float, private val rnd: java.util.Random, private val iters: Int,
    /** Where in the picture the camera's motion was found lately ([PRIOR] × [PRIOR] cells, 0..1), as Google's temporal IRLS mask. */
    private val prior: FloatArray? = null,
) {
    private val n = ax.size
    /** Each point's say in choosing the motion: more where the background has been, so a near object sweeping in cannot take over. */
    private val wt = FloatArray(n) { i ->
        if (prior == null) 1f else 0.2f + prior[cellOf(ax[i], ay[i])]
    }
    /** How many points agree with the chosen motion. */
    var agree = 0f
        private set

    companion object {
        /** How far (in picture heights) a point may be from where the motion puts it and still agree. */
        const val THR = 0.0045f
        const val PRIOR = 8
        fun cellOf(x: Float, y: Float) = (y * PRIOR).toInt().coerceIn(0, PRIOR - 1) * PRIOR + (x * PRIOR).toInt().coerceIn(0, PRIOR - 1)
    }

    private fun err(m: FloatArray, i: Int): Float {
        val w = m[6] * ax[i] + m[7] * ay[i] + m[8]
        val x = (m[0] * ax[i] + m[1] * ay[i] + m[2]) / w
        val y = (m[3] * ax[i] + m[4] * ay[i] + m[5]) / w
        return hypot((x - bx[i]) * asp, y - by[i])
    }

    private fun count(m: FloatArray, thr: Float = THR): Int { var c = 0; for (i in 0 until n) if (err(m, i) < thr) c++; return c }

    private fun inliers(m: FloatArray, thr: Float = THR) = (0 until n).filter { err(m, it) < thr }.toIntArray()

    /** Per-point weights for the least-squares fits (IRLS); null: all equal. */
    private var fitW: FloatArray? = null

    private fun homography(idx: IntArray): FloatArray? {
        if (idx.size < 4) return null
        val fw = fitW
        val ata = Array(8) { DoubleArray(8) }; val atb = DoubleArray(8)
        val r1 = DoubleArray(8); val r2 = DoubleArray(8)
        for (i in idx) {
            val x = ax[i].toDouble(); val y = ay[i].toDouble(); val u = bx[i].toDouble(); val v = by[i].toDouble()
            r1[0] = x; r1[1] = y; r1[2] = 1.0; r1[3] = 0.0; r1[4] = 0.0; r1[5] = 0.0; r1[6] = -u * x; r1[7] = -u * y
            r2[0] = 0.0; r2[1] = 0.0; r2[2] = 0.0; r2[3] = x; r2[4] = y; r2[5] = 1.0; r2[6] = -v * x; r2[7] = -v * y
            val wi = fw?.get(i)?.toDouble() ?: 1.0
            for (p in 0 until 8) { r1[p] *= kotlin.math.sqrt(wi); r2[p] *= kotlin.math.sqrt(wi) }
            val uu = u * kotlin.math.sqrt(wi); val vvv = v * kotlin.math.sqrt(wi)
            for (p in 0 until 8) {
                val a1 = r1[p]; val a2 = r2[p]
                for (q in 0 until 8) ata[p][q] += a1 * r1[q] + a2 * r2[q]
                atb[p] += a1 * uu + a2 * vvv
            }
        }
        val h = M3.solve(ata, atb) ?: return null
        return FloatArray(9) { if (it < 8) h[it].toFloat() else 1f }
    }

    private fun similarity(idx: IntArray): FloatArray? {
        val fw = fitW
        var sx = 0.0; var sy = 0.0; var sbx = 0.0; var sby = 0.0; var k = 0.0
        for (i in idx) { val wi = fw?.get(i)?.toDouble() ?: 1.0; sx += wi * ax[i] * asp; sy += wi * ay[i]; sbx += wi * bx[i] * asp; sby += wi * by[i]; k += wi }
        val mx = sx / k; val my = sy / k; val mbx = sbx / k; val mby = sby / k
        var n1 = 0.0; var n2 = 0.0; var den = 0.0
        for (i in idx) {
            val wi = fw?.get(i)?.toDouble() ?: 1.0
            val x = ax[i] * asp - mx; val y = ay[i] - my; val u = bx[i] * asp - mbx; val v = by[i] - mby
            n1 += wi * (x * u + y * v); n2 += wi * (x * v - y * u); den += wi * (x * x + y * y)
        }
        if (den < 1e-10) return null
        val a = n1 / den; val b = n2 / den
        val tx = mbx - (a * mx - b * my); val ty = mby - (b * mx + a * my)
        return floatArrayOf(a.toFloat(), (-b / asp).toFloat(), (tx / asp).toFloat(), (b * asp).toFloat(), a.toFloat(), ty.toFloat(), 0f, 0f, 1f)
    }

    private fun translation(idx: IntArray): FloatArray {
        val dx = idx.map { bx[it] - ax[it] }.sorted(); val dy = idx.map { by[it] - ay[it] }.sorted()
        return floatArrayOf(1f, 0f, dx[dx.size / 2], 0f, 1f, dy[dy.size / 2], 0f, 0f, 1f)
    }

    private fun spread(idx: IntArray, minD: Float): Boolean {
        for (p in idx.indices) for (q in p + 1 until idx.size)
            if (hypot((ax[idx[p]] - ax[idx[q]]) * asp, ay[idx[p]] - ay[idx[q]]) < minD) return false
        return true
    }

    /** Is [m] something a camera can do between two frames? */
    private fun plausible(m: FloatArray): Boolean {
        val d = M3.decompose(m, asp)
        if (abs(d[2]) > 0.06f || abs(d[3]) > 0.04f) return false
        if (hypot((d[0] - 0.5f) * asp, d[1] - 0.5f) > 0.3f) return false
        // Not folded or bent beyond a frame's worth of perspective: the corners stay close to a similarity's.
        val s = M3.compose(d, asp)
        for ((x, y) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
            if (hypot((M3.ax(m, x, y) - M3.ax(s, x, y)) * asp, M3.ay(m, x, y) - M3.ay(s, x, y)) > 0.03f) return false
        }
        return true
    }

    private fun score(m: FloatArray): Float { var c = 0f; for (i in 0 until n) if (err(m, i) < THR) c += wt[i]; return c }

    private fun ransac(need: Int, minD: Float, model: (IntArray) -> FloatArray?): Pair<FloatArray, Int>? {
        if (n < need + 2) return null
        var best: FloatArray? = null; var bestC = 0f
        val total = wt.sum()
        val pick = IntArray(need)
        for (it in 0 until iters) {
            for (k in 0 until need) pick[k] = rnd.nextInt(n)
            if (!spread(pick, minD)) continue
            val m = model(pick) ?: continue
            val c = score(m)
            if (c > bestC) { bestC = c; best = m; if (c > total * 0.92f) break }
        }
        var m = best ?: return null
        // Refined on the points that agree, twice.
        repeat(2) {
            val inl = inliers(m)
            if (inl.size >= need) model(inl)?.let { m = it }
        }
        // Blend (Tune.blend > 0): reweighted least squares over all points with soft (Cauchy) weights of this width,
        // so layers moving differently (parallax) are averaged in rather than left out.
        // Support is judged on the robust fit, before any blend moves it off the dominant plane.
        val support = count(m)
        val bl = StabCore.Tune.blend
        if (bl > 0f) {
            val all = IntArray(n) { it }
            val sg = THR * bl
            // Area (Tune.area > 0): each part of the picture gets an equal say, however many points it has, so a
            // detailed background cannot outvote a plain near object.
            val ar = StabCore.Tune.area
            val cnt = FloatArray(PRIOR * PRIOR); for (i in 0 until n) cnt[cellOf(ax[i], ay[i])] += 1f
            val aw = FloatArray(n) { i -> if (ar > 0f) Math.pow(cnt[cellOf(ax[i], ay[i])].toDouble(), -ar.toDouble()).toFloat() else 1f }
            // Near first (Tune.near > 0), as Google Photos does: when the camera shakes, nearer things move further
            // across the picture, so points that moved more than most get more say, by (move / median move)^near.
            val nr = StabCore.Tune.near
            if (nr > 0f) {
                val mv = FloatArray(n) { i -> hypot((bx[i] - ax[i]) * asp, by[i] - ay[i]) }
                val med = mv.sorted()[n / 2].coerceAtLeast(1e-4f)
                for (i in 0 until n) aw[i] *= Math.pow((mv[i] / med).coerceIn(0.25f, 4f).toDouble(), nr.toDouble()).toFloat()
            }
            repeat(4) {
                fitW = FloatArray(n) { i -> val e = err(m, i) / sg; aw[i] / (1f + e * e) }
                model(all)?.let { m = it }
            }
            fitW = null
        }
        return m to support
    }

    /** Per prior cell, the share of its points that agree with [m] (-1 where there are none). */
    fun agreement(m: FloatArray): FloatArray {
        val inl = FloatArray(PRIOR * PRIOR); val all = FloatArray(PRIOR * PRIOR)
        for (i in 0 until n) { val c = cellOf(ax[i], ay[i]); all[c] += 1f; if (err(m, i) < THR) inl[c] += 1f }
        return FloatArray(PRIOR * PRIOR) { if (all[it] > 0f) inl[it] / all[it] else -1f }
    }

    /** The motion: perspective where enough of the picture agrees on it, else similarity, else position alone. */
    fun best(): FloatArray {
        val hom = ransac(4, 0.2f) { homography(it) }
        if (hom != null && hom.second >= max(14, (n * 0.3f).toInt()) && plausible(hom.first)) { agree = hom.second.toFloat(); return hom.first }
        val sim = ransac(2, 0.25f) { similarity(it) }
        if (sim != null && sim.second >= max(8, (n * 0.25f).toInt()) && plausible(sim.first)) { agree = sim.second.toFloat(); return sim.first }
        if (n >= 4) {
            val all = IntArray(n) { it }
            val t = translation(all)
            val inl = inliers(t, THR * 3)
            if (inl.size >= max(4, n / 5)) { agree = inl.size * 0.5f; return translation(inl) }
        }
        agree = 0f
        return M3.ident()
    }

    /**
     * What [step] leaves over, on the mesh: at each mesh point, the median motion of the points near it beyond what
     * [step] says (parallax, lens, jello), smoothed across the mesh, plus [step]'s own motion there. Points far off
     * the camera's motion (a subject moving on its own) are left out.
     */
    fun mesh(step: FloatArray): FloatArray {
        val gx = StabMesh.GX; val gy = StabMesh.GY; val vx = StabMesh.VX; val vy = StabMesh.VY; val nv = StabMesh.NV
        val cap = 48
        val bucketX = FloatArray(nv * cap); val bucketY = FloatArray(nv * cap); val cnt = IntArray(nv)
        val rad = StabCore.Tune.meshRad
        for (i in 0 until n) {
            val w = step[6] * ax[i] + step[7] * ay[i] + step[8]
            val rx = bx[i] - (step[0] * ax[i] + step[1] * ay[i] + step[2]) / w
            val ry = by[i] - (step[3] * ax[i] + step[4] * ay[i] + step[5]) / w
            if (hypot(rx * asp, ry) > StabCore.Tune.meshThr) continue
            val cx = ax[i] * gx; val cy = ay[i] * gy
            for (j in max(0, ceil(cy - rad).toInt())..min(vy - 1, floor(cy + rad).toInt()))
                for (k in max(0, ceil(cx - rad).toInt())..min(vx - 1, floor(cx + rad).toInt())) {
                    val dx = cx - k; val dy = cy - j
                    if (dx * dx + dy * dy > rad * rad) continue
                    val v = j * vx + k
                    if (cnt[v] < cap) { bucketX[v * cap + cnt[v]] = rx; bucketY[v * cap + cnt[v]] = ry; cnt[v]++ }
                }
        }
        val res = FloatArray(nv * 2) { Float.NaN }
        val tmp = FloatArray(cap)
        fun median(b: FloatArray, v: Int, c: Int): Float {
            System.arraycopy(b, v * cap, tmp, 0, c)
            java.util.Arrays.sort(tmp, 0, c)
            return tmp[c / 2]
        }
        for (v in 0 until nv) if (cnt[v] >= 3) { res[v * 2] = median(bucketX, v, cnt[v]); res[v * 2 + 1] = median(bucketY, v, cnt[v]) }
        // Mesh points with nothing near them take their neighbours' value; with nothing anywhere, none.
        repeat(24) {
            var missing = false
            val next = res.copyOf()
            for (j in 0 until vy) for (k in 0 until vx) {
                val v = j * vx + k
                if (!res[v * 2].isNaN()) continue
                var sx = 0f; var sy = 0f; var c = 0
                for ((dj, dk) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val jj = j + dj; val kk = k + dk
                    if (jj < 0 || kk < 0 || jj >= vy || kk >= vx) continue
                    val u = jj * vx + kk
                    if (!res[u * 2].isNaN()) { sx += res[u * 2]; sy += res[u * 2 + 1]; c++ }
                }
                if (c > 0) { next[v * 2] = sx / c; next[v * 2 + 1] = sy / c } else missing = true
            }
            System.arraycopy(next, 0, res, 0, res.size)
            if (!missing) return@repeat
        }
        for (i in res.indices) if (res[i].isNaN()) res[i] = 0f
        // A median across the mesh takes out single odd points, a blur keeps the warp smooth.
        val med = FloatArray(nv * 2)
        val nine = FloatArray(9)
        for (c in 0..1) for (j in 0 until vy) for (k in 0 until vx) {
            var q = 0
            for (dj in -1..1) for (dk in -1..1) {
                val jj = (j + dj).coerceIn(0, vy - 1); val kk = (k + dk).coerceIn(0, vx - 1)
                nine[q++] = res[(jj * vx + kk) * 2 + c]
            }
            nine.sort()
            med[(j * vx + k) * 2 + c] = nine[4]
        }
        // Rolling shutter reads the sensor row by row, so what a single motion leaves over is, above all, a function of
        // the row: as Google's homography mixtures, the residual is taken per row of the mesh (the median across it),
        // so jello and skew are put right without bending objects locally (which looks like wobble).
        if (StabCore.Tune.rows > 0.5f) {
            val col = FloatArray(vx)
            for (c in 0..1) for (j in 0 until vy) {
                for (k in 0 until vx) col[k] = med[(j * vx + k) * 2 + c]
                col.sort()
                val r = col[vx / 2]
                for (k in 0 until vx) med[(j * vx + k) * 2 + c] = r
            }
        }
        val sm = StabCore.blurMesh(StabCore.blurMesh(med))
        val out = FloatArray(nv * 2)
        for (j in 0 until vy) for (k in 0 until vx) {
            val v = j * vx + k
            val x = k.toFloat() / gx; val y = j.toFloat() / gy
            var rx = sm[v * 2]; var ry = sm[v * 2 + 1]
            val len = hypot(rx * asp, ry)
            val cl = StabCore.Tune.meshClamp
            if (len > cl) { rx *= cl / len; ry *= cl / len }
            out[v * 2] = M3.ax(step, x, y) - x + rx
            out[v * 2 + 1] = M3.ay(step, x, y) - y + ry
        }
        return out
    }
}

/** Smoothing the measured motion into the correction for each frame. */
object StabCore {
    /** Tuning, measured on the bench (StabBench); "stab.<name>" system properties override them there. */
    internal object Tune {
        private fun p(n: String, d: Float) = System.getProperty("stab.$n")?.toFloatOrNull() ?: d
        val resSigma get() = p("resSigma", 0.25f)
        val lam0 get() = p("lam0", 1f)
        val lim get() = p("lim", 1f)
        val w1 get() = p("w1", 10f)
        val w2 get() = p("w2", 1f)
        val w3 get() = p("w3", 100f)
        val meshBlur get() = p("meshBlur", 1f)
        val spike get() = p("spike", 4f)
        val prior get() = p("prior", 0f)
        val rows get() = p("rows", 0f)
        /** How much of each mesh point's own wobble is taken out (0: none, the global correction only). */
        val local get() = p("local", 1f)
        /** Mesh: how far off the camera's motion a point may be and still count (near objects move a lot more). */
        val meshThr get() = p("meshThr", 0.012f)
        /** Mesh: the largest correction a mesh point may have of its own. */
        val meshClamp get() = p("meshClamp", 0.008f)
        /** Mesh: how far (in cells) a point reaches the mesh points around it. */
        val meshRad get() = p("meshRad", 1.3f)
        /** Motion fit: 0 picks the dominant plane (RANSAC); above 0, layers are blended in by reweighting this wide. */
        val blend get() = p("blend", 10f)
        /** Dynamic zoom: smoothing (s) of the needed zoom, and the margin on top (Google Photos: 0.5 s, 5%). */
        val zoomSigma get() = p("zoomSigma", 0.5f)
        val zoomMargin get() = p("zoomMargin", 1.0525f)
        /** The most dynamic zoom may reach; past it the correction gives way instead (Google Photos tops out ~1.27). */
        val zoomMax get() = p("zoomMax", 1.28f)
        /** Perspective mode: the most a step's corners may stray from its similarity before it counts as a glitch. */
        val pGate get() = p("pGate", 0.015f)
        val ease get() = p("ease", 1f)
        /** Blend: how far each picture cell's say is evened out (0: by point count, 1: every cell alike). */
        val area get() = p("area", 0.5f)
        /** Blend: how much more say points that moved further (nearer things) get; 0 none. */
        val near get() = p("near", 2f)
    }

    /** A 1-2-1 blur of a mesh field (x and y per point) across the mesh. */
    fun blurMesh(f: FloatArray): FloatArray {
        val vx = StabMesh.VX; val vy = StabMesh.VY
        val t = FloatArray(f.size); val o = FloatArray(f.size)
        for (c in 0..1) for (j in 0 until vy) for (k in 0 until vx) {
            val a = f[(j * vx + max(k - 1, 0)) * 2 + c]; val b = f[(j * vx + k) * 2 + c]; val d = f[(j * vx + min(k + 1, vx - 1)) * 2 + c]
            t[(j * vx + k) * 2 + c] = (a + 2 * b + d) * 0.25f
        }
        for (c in 0..1) for (j in 0 until vy) for (k in 0 until vx) {
            val a = t[(max(j - 1, 0) * vx + k) * 2 + c]; val b = t[(j * vx + k) * 2 + c]; val d = t[(min(j + 1, vy - 1) * vx + k) * 2 + c]
            o[(j * vx + k) * 2 + c] = (a + 2 * b + d) * 0.25f
        }
        return o
    }

    /** The mesh field [f] at picture point (x, y), bilinear, into [out]. */
    fun meshAt(f: FloatArray, off: Int, x: Float, y: Float, out: FloatArray) {
        val gx = (x * StabMesh.GX).coerceIn(0f, StabMesh.GX - 0.0001f); val gy = (y * StabMesh.GY).coerceIn(0f, StabMesh.GY - 0.0001f)
        val k = gx.toInt(); val j = gy.toInt(); val fx = gx - k; val fy = gy - j
        val vx = StabMesh.VX
        for (c in 0..1) {
            val a = f[off + (j * vx + k) * 2 + c]; val b = f[off + (j * vx + k + 1) * 2 + c]
            val d = f[off + ((j + 1) * vx + k) * 2 + c]; val e = f[off + ((j + 1) * vx + k + 1) * 2 + c]
            out[c] = (a + (b - a) * fx) + ((d + (e - d) * fx) - (a + (b - a) * fx)) * fy
        }
    }

    /** Gaussian smoothing of [v], within each shot (from one start in [starts] to the next), mirrored at the ends. */
    fun gaussian(v: FloatArray, sigma: Float, starts: IntArray): FloatArray {
        val out = v.copyOf()
        if (sigma < 0.5f) return out
        val r = (sigma * 3).toInt().coerceAtLeast(1)
        val w = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)).toFloat() / (2 * sigma * sigma)) }
        for (s in starts.indices) {
            val a = starts[s]; val b = if (s + 1 < starts.size) starts[s + 1] else v.size
            val len = b - a
            if (len < 2) continue
            for (i in 0 until len) {
                var sum = 0f; var ws = 0f
                for (k in -r..r) {
                    var j = i + k
                    // Mirrored about the ends (odd reflection: the path carries on its way), so the ends do not drift.
                    val ref: Float
                    if (j < 0) { j = (-j).coerceAtMost(len - 1); ref = 2 * v[a] - v[a + j] }
                    else if (j >= len) { j = (2 * len - j - 2).coerceAtLeast(0); ref = 2 * v[b - 1] - v[a + j] }
                    else ref = v[a + j]
                    sum += ref * w[k + r]; ws += w[k + r]
                }
                out[a + i] = sum / ws
            }
        }
        return out
    }

    /**
     * The L1-optimal camera path (Grundmann, Kwatra and Essa, "Auto-Directed Video Stabilization with Robust L1 Optimal
     * Camera Paths", CVPR 2011): the path that stays within [lim] of the measured one [m] while having as few changes
     * of position, speed and acceleration as it can. Such a path is made of pieces that hold still, move at an even
     * speed or ease in and out, as a camera on a tripod, a dolly or a crane does, so held shots stay perfectly still
     * and moves are clean. Each shot (from one of [starts] to the next) is solved on its own.
     *
     * Solved by ADMM with a banded system (seven diagonals), a few hundred milliseconds for an hour of footage.
     * [lam0] is a small pull towards the measured path (less: freer, smoother).
     */
    fun l1Path(m: FloatArray, starts: IntArray, lim: Float, lam0: Double, iters: Int = 400): FloatArray =
        l1Path(m, starts, FloatArray(m.size) { lim }, lam0, iters)

    /** As above, with each frame's own limit [lim]. */
    fun l1Path(m: FloatArray, starts: IntArray, lim: FloatArray, lam0: Double, iters: Int = 400): FloatArray {
        val out = m.copyOf()
        for (si in starts.indices) {
            val a = starts[si]; val b = if (si + 1 < starts.size) starts[si + 1] else m.size
            val L = b - a
            if (L < 5) continue
            // In units of the shot's typical frame-to-frame motion, so the weights mean the same for any motion.
            val dif = DoubleArray(L - 1) { abs((m[a + it + 1] - m[a + it]).toDouble()) }.sorted()
            val sc = max(dif[dif.size / 2], 1e-5)
            val mm = DoubleArray(L) { m[a + it] / sc }
            val box = DoubleArray(L) { lim[a + it] / sc }
            val lam = doubleArrayOf(Tune.w1.toDouble(), Tune.w2.toDouble(), Tune.w3.toDouble())
            val st = arrayOf(doubleArrayOf(-1.0, 1.0), doubleArrayOf(1.0, -2.0, 1.0), doubleArrayOf(-1.0, 3.0, -3.0, 1.0))
            val rho = 1.0
            // A = (lam0 + rho) I + rho Σ DkᵀDk, as 4 bands (main and 3 above).
            val band = Array(4) { DoubleArray(L) }
            for (i in 0 until L) band[0][i] = lam0 + rho
            for (k in 0..2) {
                val c = st[k]
                for (r in 0 until L - k - 1) for (p in c.indices) for (q in p until c.size) band[q - p][r + p] += rho * c[p] * c[q]
            }
            // Banded LDLᵀ.
            val d = DoubleArray(L); val l = Array(4) { DoubleArray(L) }
            for (i in 0 until L) {
                var di = band[0][i]
                for (j in max(0, i - 3) until i) { val lij = l[i - j][j]; di -= lij * lij * d[j] }
                d[i] = di
                for (o in 1..3) {
                    val r = i + o; if (r >= L) break
                    var v = band[o][i]
                    for (j in max(0, r - 3) until i) v -= l[r - j][j] * l[i - j][j] * d[j]
                    l[o][i] = v / di
                }
            }
            fun solve(rhs: DoubleArray): DoubleArray {
                val y = rhs.copyOf()
                for (i in 0 until L) for (o in 1..3) { val j = i - o; if (j >= 0) y[i] -= l[o][j] * y[j] }
                for (i in 0 until L) y[i] /= d[i]
                for (i in L - 1 downTo 0) for (o in 1..3) { val j = i + o; if (j < L) y[i] -= l[o][i] * y[j] }
                return y
            }
            fun dk(k: Int, x: DoubleArray) = DoubleArray(L - k - 1) { r -> var v = 0.0; for (p in st[k].indices) v += st[k][p] * x[r + p]; v }
            fun dkT(k: Int, x: DoubleArray, acc: DoubleArray) { for (r in x.indices) for (p in st[k].indices) acc[r + p] += st[k][p] * x[r] }
            var sv = mm.copyOf()
            val z = Array(3) { dk(it, sv) }; val y = Array(3) { DoubleArray(L - it - 1) }
            val u = sv.copyOf(); val v = DoubleArray(L)
            repeat(iters) {
                val rhs = DoubleArray(L) { lam0 * mm[it] + rho * (u[it] - v[it]) }
                for (k in 0..2) dkT(k, DoubleArray(z[k].size) { rho * (z[k][it] - y[k][it]) }, rhs)
                sv = solve(rhs)
                for (k in 0..2) {
                    val ds = dk(k, sv)
                    val t = lam[k] / rho
                    for (r in ds.indices) {
                        val w = ds[r] + y[k][r]
                        z[k][r] = if (w > t) w - t else if (w < -t) w + t else 0.0
                        y[k][r] += ds[r] - z[k][r]
                    }
                }
                for (i in 0 until L) {
                    val w = sv[i] + v[i]
                    u[i] = w.coerceIn(mm[i] - box[i], mm[i] + box[i])
                    v[i] += sv[i] - u[i]
                }
            }
            // The box is met exactly: what is returned is the projected path.
            for (i in 0 until L) out[a + i] = (u[i] * sc).toFloat()
        }
        return out
    }

    /**
     * Steps from frame to frame with the glitches taken out: a step far from what its neighbours did (a fit that went
     * wrong, a flash) gets their motion instead, so one bad frame cannot throw the rest of the clip off.
     */
    fun despike(d: FloatArray, floor: Float, cap: Float, starts: BooleanArray) {
        val n = d.size
        val near = FloatArray(8)
        val col = d.copyOf()
        for (i in 0 until n) {
            if (starts[i]) continue
            var c = 0
            for (j in max(0, i - 4)..min(n - 1, i + 4)) if (j != i && !starts[j]) near[c++] = col[j]
            if (c == 0) continue
            java.util.Arrays.sort(near, 0, c)
            val med = near[c / 2]
            for (q in 0 until c) near[q] = abs(near[q] - med)
            java.util.Arrays.sort(near, 0, c)
            val spread = near[c / 2]
            if (abs(col[i]) > cap || abs(col[i] - med) > max(spread * 6f, floor)) d[i] = if (abs(med) > cap) 0f else med
        }
    }

    private fun coverZoom(read: (Float, Float, FloatArray) -> Unit): Float {
        val o = FloatArray(2)
        val pts = ArrayList<Pair<Float, Float>>()
        for (k in 0..8) { val t = k / 8f; pts += t to 0f; pts += t to 1f; pts += 0f to t; pts += 1f to t }
        var lo = 1f; var hi = 3f
        repeat(16) {
            val z = (lo + hi) / 2
            val ok = pts.all { (x, y) ->
                read(0.5f + (x - 0.5f) / z, 0.5f + (y - 0.5f) / z, o)
                o[0] in -0.002f..1.002f && o[1] in -0.002f..1.002f
            }
            if (ok) hi = z else lo = z
        }
        return hi
    }

    /**
     * The stabilised clip for [a] with settings [s] at [fps], over the file times [inUs]..[outUs].
     */
    fun track(a: StabAnalysis, s: Stab, fps: Float, inUs: Long = Long.MIN_VALUE, outUs: Long = Long.MAX_VALUE): StabTrack {
        val lo = if (inUs == Long.MIN_VALUE) Long.MIN_VALUE else inUs - 20_000
        val hi = if (outUs == Long.MAX_VALUE) Long.MAX_VALUE else outUs + 20_000
        val all = (0 until a.size).filter { a.times[it] in lo..hi }
        val n = all.size
        if (n == 0) return StabTrack(LongArray(0), FloatArray(0), null, s.framing, FloatArray(0), FloatArray(0), FloatArray(0))
        val asp = a.aspect
        val nv = StabMesh.NV
        // Shots: the first frame, and every cut.
        val isStart = BooleanArray(n) { it == 0 || a.quality[all[it]] < 0f }
        val starts = (0 until n).filter { isStart[it] }.toIntArray()
        fun step(i: Int) = if (isStart[i]) M3.ident() else a.steps.copyOfRange(all[i] * 9, all[i] * 9 + 9)

        // The global path: each step as a similarity (fitted to where the step moves a grid of points), as
        // centre, angle and scale steps; glitches out; added up within each shot.
        val grid = (0..4).flatMap { j -> (0..4).map { k -> k / 4f to j / 4f } }
        val d = Array(4) { FloatArray(n) }
        for (i in 0 until n) {
            if (isStart[i]) continue
            val st = step(i)
            var sx = 0.0; var sy = 0.0; var su = 0.0; var sv = 0.0
            for ((x, y) in grid) { sx += x * asp; sy += y; su += M3.ax(st, x, y) * asp; sv += M3.ay(st, x, y) }
            val k = grid.size.toDouble()
            val mx = sx / k; val my = sy / k; val mu = su / k; val mv = sv / k
            var n1 = 0.0; var n2 = 0.0; var den = 0.0
            for ((x, y) in grid) {
                val px = x * asp - mx; val py = y - my; val u = M3.ax(st, x, y) * asp - mu; val v = M3.ay(st, x, y) - mv
                n1 += px * u + py * v; n2 += px * v - py * u; den += px * px + py * py
            }
            val sc = hypot(n1 / den, n2 / den)
            // Centre motion, in picture units; turn; zoom.
            d[0][i] = (mu - mx).toFloat() / asp
            d[1][i] = (mv - my).toFloat()
            d[2][i] = atan2(n2 / den, n1 / den).toFloat()
            d[3][i] = ln(sc).toFloat()
        }
        val floors = floatArrayOf(0.006f, 0.006f, 0.004f, 0.002f)
        val caps = floatArrayOf(0.25f, 0.25f, 0.06f, 0.04f)
        for (c in 0 until 4) despike(d[c], floors[c] * Tune.spike, caps[c], isStart)
        val raw = Array(n) { FloatArray(4) }
        for (i in 0 until n) for (c in 0 until 4) raw[i][c] = if (isStart[i]) (if (c < 2) 0.5f else 0f) else raw[i - 1][c] + d[c][i]
        // The path keeps the camera's own motion between steps: centre moves add up in the turned, zoomed frame.
        val k = s.strength.coerceIn(0f, 1f)
        val sigma = (0.08f + s.smooth.coerceIn(0f, 1f) * 1.9f) * fps
        // The crop window, as Google's method has it: decided first (crop less: 95% of the frame kept, smooth more:
        // 69%), and every correction has to keep it inside the real frame. Its margin is shared out between position,
        // turn and zoom, so neither the zoom can grow past the crop nor the horizon tip far.
        val crop = 0.95f - 0.26f * (1f - s.cropLess.coerceIn(0f, 1f))
        val mx = (1f - crop) / 2f
        val halfDiag = 0.5f * hypot(asp, 1f)
        val limit = mx * Tune.lim
        // Cinematic: how far the path may stray from the camera's, per kind of motion (position across the picture,
        // up and down, turn, zoom), and how loosely it is held (smoothness).
        val lims = floatArrayOf(mx * 0.55f, mx * 0.55f, 0.3f * mx / halfDiag, 0.2f * mx / halfDiag).map { it * Tune.lim }.toFloatArray()
        val lam0 = 0.5 * Math.pow(10.0, -4.0 * s.smooth.coerceIn(0f, 1f).toDouble()) * Tune.lam0
        val smooth = Array(n) { FloatArray(4) }
        for (c in 0 until 4) {
            val col = FloatArray(n) { raw[it][c] }
            val follow = when (s.mode) { Stab.TRANSLATION -> c < 2; else -> true }
            val sm = when {
                !follow -> col
                s.lock -> FloatArray(n) { i -> var st = i; while (!isStart[st]) st--; col[st] }
                s.method == Stab.CINEMATIC -> l1Path(col, starts, lims[c], lam0)
                else -> gaussian(col, sigma, starts)
            }
            for (i in 0 until n) smooth[i][c] = col[i] + (sm[i] - col[i]) * k
        }
        if (s.horizon) for (i in 0 until n) smooth[i][2] = 0f
        val id = M3.ident()

        var grids: FloatArray? = null
        val mats: Array<FloatArray>
        if (s.mode == Stab.WARP) {
            // Each mesh point's own path: where the picture at that point has gone, step by step, within the shot.
            val g = FloatArray(n * nv * 2)
            val steps = Array(nv * 2) { FloatArray(n) }
            for (i in 0 until n) if (!isStart[i]) for (q in 0 until nv * 2) steps[q][i] = a.mesh[all[i] * nv * 2 + q]
            for (q in 0 until nv * 2) despike(steps[q], 0.006f * Tune.spike, 0.25f, isStart)
            val path = Array(nv * 2) { FloatArray(n) }
            for (q in 0 until nv * 2) for (i in 0 until n) path[q][i] = if (isStart[i]) 0f else path[q][i - 1] + steps[q][i]
            // Where the camera's own path puts each mesh point, as measured and as smoothed; what a point does beyond
            // that (parallax, jello, lens) is its own wobble, smoothed lightly so the picture holds together.
            val gRaw = Array(n) { M3.compose(raw[it], asp) }
            val gSm = Array(n) { M3.compose(smooth[it], asp) }
            val corr = Array(n) { FloatArray(nv * 2) }
            val resSigma = Tune.resSigma * fps
            for (vtx in 0 until nv) {
                val x = (vtx % StabMesh.VX).toFloat() / StabMesh.GX; val y = (vtx / StabMesh.VX).toFloat() / StabMesh.GY
                for (c in 0..1) {
                    val q = vtx * 2 + c
                    val p = path[q]
                    val gr = FloatArray(n) { i -> (if (c == 0) M3.ax(gRaw[i], x, y) - x else M3.ay(gRaw[i], x, y) - y) }
                    val res = FloatArray(n) { p[it] - gr[it] }
                    val resSm = if (s.lock) FloatArray(n) { i -> var st = i; while (!isStart[st]) st--; res[st] } else gaussian(res, resSigma, starts)
                    for (i in 0 until n) {
                        val gs = if (c == 0) M3.ax(gSm[i], x, y) - x else M3.ay(gSm[i], x, y) - y
                        corr[i][q] = (gs + (resSm[i] - res[i]) * Tune.local + res[i] - p[i]) * k
                    }
                }
            }
            val bs = Array(n) { i -> if (Tune.meshBlur > 0.5f) StabCore.blurMesh(corr[i]) else corr[i] }
            // Crop less, smooth more: the correction is held within this much of the frame. What goes past it is let
            // through slowly (the camera drifts), never frame by frame, so the shake stays out.
            run {
                val ex = Array(nv * 2) { FloatArray(n) }
                for (i in 0 until n) {
                    var big = 0f
                    for (v in 0 until nv) big = max(big, hypot(bs[i][v * 2] * asp, bs[i][v * 2 + 1]))
                    if (big > limit) { val f = 1f - limit / big; for (q in 0 until nv * 2) ex[q][i] = bs[i][q] * f }
                }
                for (q in 0 until nv * 2) {
                    val lp = gaussian(spread(ex[q], (fps * 0.5f).toInt()), fps * 0.5f, starts)
                    for (i in 0 until n) bs[i][q] -= lp[i]
                }
            }
            for (i in 0 until n) {
                val b = bs[i]
                // b moves the picture's content; the shader reads backwards: for output point x, the input y with
                // y + b(y) = x, found by a few rounds of y = x − b(y).
                val o = FloatArray(2)
                for (v in 0 until nv) {
                    val x = (v % StabMesh.VX).toFloat() / StabMesh.GX; val y = (v / StabMesh.VX).toFloat() / StabMesh.GY
                    var qx = x; var qy = y
                    repeat(5) { meshAt(b, 0, qx, qy, o); qx = x - o[0]; qy = y - o[1] }
                    g[i * nv * 2 + v * 2] = qx - x; g[i * nv * 2 + v * 2 + 1] = qy - y
                }
            }
            grids = g
            mats = Array(n) { id.copyOf() }
        } else {
            mats = Array(n) { i ->
                // The difference between the paths, turned about the picture's centre. (Composing the two paths as
                // they stand turns about where the path started, which a pan leaves far off the picture: a small
                // roll there would swing the picture sideways.)
                var t = M3.compose(floatArrayOf(0.5f + raw[i][0] - smooth[i][0], 0.5f + raw[i][1] - smooth[i][1],
                    raw[i][2] - smooth[i][2], raw[i][3] - smooth[i][3]), asp)
                if (s.mode == Stab.PERSPECTIVE) {
                    // The perspective the similarity does not explain, smoothed on its own and taken out too.
                    t = perspectiveFix(a, all, isStart, starts, raw, sigma, k, asp, i, t)
                }
                t
            }
            // Crop less, smooth more, as in Warp: the part past the limit eased in slowly.
            if (Tune.ease > 0.5f) run {
                val ex = Array(9) { FloatArray(n) }
                for (i in 0 until n) {
                    val t = mats[i]
                    val off = hypot((M3.ax(t, 0.5f, 0.5f) - 0.5f) * asp, M3.ay(t, 0.5f, 0.5f) - 0.5f)
                    if (off > limit) { val f = 1f - limit / off; for (j in 0 until 9) ex[j][i] = (t[j] - id[j]) * f }
                }
                val lp = Array(9) { j -> gaussian(spread(ex[j], (fps * 0.5f).toInt()), fps * 0.5f, starts) }
                for (i in 0 until n) mats[i] = FloatArray(9) { j -> mats[i][j] - lp[j][i] }
            }
            // Rolling shutter: rows are read top to bottom, so motion during the read leans (sideways) or squeezes
            // (up and down) the picture; this undoes it.
            if (s.rollingShutter > 0f) for (i in 0 until n) {
                val j0 = max(0, i - 1); val j1 = min(n - 1, i + 1)
                val span = max(1, j1 - j0).toFloat()
                val vx = (raw[j1][0] - raw[j0][0]) / span; val vy = (raw[j1][1] - raw[j0][1]) / span
                val rr = s.rollingShutter * 0.85f
                mats[i] = mul(mats[i], floatArrayOf(1f, vx * rr, -vx * rr * 0.5f, 0f, 1f + vy * rr, -vy * rr * 0.5f, 0f, 0f, 1f))
            }
        }
        // Zoom: enough that the picture still covers the frame; one for the clip, or (dynamic) as each moment needs.
        val need = FloatArray(n) { i ->
            if (s.framing != Stab.AUTO_ZOOM) 1f
            else {
                val g = grids
                val o = FloatArray(2)
                if (g != null) coverZoom { x, y, out -> meshAt(g, i * nv * 2, x, y, o); out[0] = x + o[0]; out[1] = y + o[1] }
                else coverZoom { x, y, out -> out[0] = M3.ax(mats[i], x, y); out[1] = M3.ay(mats[i], x, y) }
            }
        }
        val zooms = when {
            s.framing == Stab.FIXED_ZOOM -> FloatArray(n) { s.zoom.coerceIn(1f, 2f) }
            s.framing != Stab.AUTO_ZOOM -> FloatArray(n) { 1f }
            !s.dynamicZoom -> FloatArray(n) { need.max() }
            else -> {
                // Google Photos' rule, measured on its own output (both test clips agree): the least zoom each frame
                // needs, smoothed over half a second, with 5% to spare.
                val cap = Tune.zoomMax / Tune.zoomMargin
                val g = gaussian(FloatArray(n) { min(need[it], cap) }, fps * Tune.zoomSigma, intArrayOf(0))
                FloatArray(n) { (g[it] * Tune.zoomMargin).coerceIn(1f, Tune.zoomMax) }
            }
        }
        // Where a frame needs more than that zoom gives, its correction is eased back just enough that no edge shows.
        if (s.framing == Stab.AUTO_ZOOM && s.dynamicZoom) for (i in 0 until n) {
            if (need[i] <= zooms[i]) continue
            val g = grids
            val m0 = mats[i].copyOf()
            val g0 = g?.copyOfRange(i * nv * 2, (i + 1) * nv * 2)
            fun at(f: Float): Float {
                val m = FloatArray(9) { id[it] + (m0[it] - id[it]) * f }
                val o = FloatArray(2)
                return if (g0 != null) coverZoom { x, y, out -> meshAt(g0, 0, x, y, o); out[0] = x + o[0] * f; out[1] = y + o[1] * f }
                else coverZoom { x, y, out -> out[0] = M3.ax(m, x, y); out[1] = M3.ay(m, x, y) }
            }
            var lo = 0f; var hi = 1f
            repeat(12) { val mid = (lo + hi) / 2; if (at(mid) <= zooms[i]) lo = mid else hi = mid }
            mats[i] = FloatArray(9) { id[it] + (m0[it] - id[it]) * lo }
            if (g != null && g0 != null) for (q in g0.indices) g[i * nv * 2 + q] = g0[q] * lo
        }
        val out = FloatArray(n * 9)
        for (i in 0 until n) {
            val z = zooms[i].coerceIn(1f, 3f)
            val zm = floatArrayOf(1f / z, 0f, 0.5f - 0.5f / z, 0f, 1f / z, 0.5f - 0.5f / z, 0f, 0f, 1f)
            System.arraycopy(mul(mats[i], zm), 0, out, i * 9, 9)
        }
        val rawP = FloatArray(n * 3) { raw[it / 3][it % 3] }
        val smP = FloatArray(n * 3) { smooth[it / 3][it % 3] }
        return StabTrack(LongArray(n) { a.times[all[it]] }, out, grids, s.framing, rawP, smP, zooms)
    }

    private fun mul(b: FloatArray, a: FloatArray) = M3.mul(b, a)

    /** Each value replaced by the largest (by size, keeping its sign) within [r] of it, so a blur of it still covers it. */
    private fun spread(v: FloatArray, r: Int): FloatArray = FloatArray(v.size) { i ->
        var m = 0f
        for (j in max(0, i - r)..min(v.size - 1, i + r)) if (abs(v[j]) > abs(m)) m = v[j]
        m
    }

    /** Perspective mode: the homography path beyond the similarity path, smoothed, folded into [t] for frame [i]. */
    private var perspCache: Pair<Any, Array<FloatArray>>? = null

    private fun perspectiveFix(a: StabAnalysis, all: List<Int>, isStart: BooleanArray, starts: IntArray, raw: Array<FloatArray>,
                               sigma: Float, k: Float, asp: Float, i: Int, t: FloatArray): FloatArray {
        val key = listOf(a, all.first(), all.size, sigma, k)
        val res = perspCache?.takeIf { it.first == key }?.second ?: run {
            val n = all.size
            // Each step's perspective beyond its similarity (both taken about the picture's centre), added up per
            // shot in the picture's own coordinates. A step that strays far from its similarity is a glitch or
            // parallax, not lens perspective, and adds nothing, so the path cannot run away.
            val p = Array(n) { M3.ident() }
            for (q in 0 until n) if (!isStart[q]) {
                val st = a.steps.copyOfRange(all[q] * 9, all[q] * 9 + 9)
                val ss = M3.compose(floatArrayOf(0.5f + raw[q][0] - raw[q - 1][0], 0.5f + raw[q][1] - raw[q - 1][1],
                    raw[q][2] - raw[q - 1][2], raw[q][3] - raw[q - 1][3]), asp)
                var dev = 0f
                for (cy in 0..1) for (cx in 0..1) dev = max(dev, hypot((M3.ax(st, cx.toFloat(), cy.toFloat()) - M3.ax(ss, cx.toFloat(), cy.toFloat())) * asp,
                    M3.ay(st, cx.toFloat(), cy.toFloat()) - M3.ay(ss, cx.toFloat(), cy.toFloat())))
                val r = if (dev > Tune.pGate) M3.ident() else M3.norm(M3.mul(st, M3.inv(ss)))
                p[q] = M3.norm(M3.mul(r, p[q - 1]))
            }
            val sm = Array(n) { FloatArray(9) }
            for (e in 0 until 9) {
                val col = FloatArray(n) { p[it][e] }
                val g = gaussian(col, sigma, starts)
                for (q in 0 until n) sm[q][e] = col[q] + (g[q] - col[q]) * k
            }
            // Per frame: the residual as measured, then undone as smoothed.
            val fix = Array(n) { q -> M3.norm(M3.mul(p[q], M3.inv(sm[q]))) }
            perspCache = key to fix
            fix
        }
        // Output → smoothed similarity frame (t) → measured perspective on top.
        return M3.norm(M3.mul(res[i], t))
    }
}

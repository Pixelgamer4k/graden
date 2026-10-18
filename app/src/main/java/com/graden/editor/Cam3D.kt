package com.graden.editor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small 3D scene for the Stabilize page: a modelled camera turned exactly as the visual gyro measured it, the way
 * it points (its view, as a frustum), where the stabilised clip points, and a floor grid fixed in the world.
 *
 * Plain maths, no Android: [render] returns flat shapes in screen pixels, painted back to front, so the page only has
 * to draw them (and anything else can, too).
 *
 * Frames: everything uses the camera's own axes - x right, y down, z forward (into the scene) - the same as the
 * picture. The world is the camera's frame at the clip's first moment. A rotation is a rotation vector (axis × angle,
 * radians) taking camera directions to world directions.
 */
object Cam3D {
    /** A flat shape: [n] points in [xy] (screen pixels), ARGB [color]; [stroke] > 0 outlines it that wide instead. */
    class Shape(val xy: FloatArray, val n: Int, val color: Int, val stroke: Float = 0f, val closed: Boolean = true)

    /** The viewer: turned [yaw] about the vertical and tilted down by [pitch] (radians), [dist] from the camera. */
    class View(val yaw: Double = 0.42, val pitch: Double = 0.32, val dist: Double = 4.9)

    private class Face(val p: DoubleArray, val color: Int)

    /** The camera body, lens and controls, in the camera's frame (one unit is about 10 cm). */
    private val model: List<Face> by lazy { build() }

    // --------------------------------------------------------------------------------------------- the camera

    private const val GRAPHITE = 0xFF3D414B.toInt()
    private const val BODY = 0xFF2E3139.toInt()
    private const val RUBBER = 0xFF1F2026.toInt()
    private const val METAL = 0xFF8A8F9B.toInt()
    private const val GLASS = 0xFF10233A.toInt()
    private const val SCREEN = 0xFF121A26.toInt()
    private const val ACCENT = 0xFFF0872B.toInt()

    private fun build(): List<Face> {
        val f = ArrayList<Face>()
        // Body, grip (front right, as held), the viewfinder hump on top, and the screen on the back.
        f += slab(-0.78, -0.46, 0.78, 0.46, -0.30, 0.30, 0.13, GRAPHITE)
        f += slab(0.40, -0.40, 0.77, 0.45, 0.24, 0.52, 0.12, RUBBER)
        f += slab(-0.30, -0.70, 0.30, -0.40, -0.27, 0.16, 0.06, BODY)
        f += box(-0.24, -0.66, -0.31, 0.24, -0.50, -0.27, SCREEN)
        f += box(-0.58, -0.34, -0.33, 0.34, 0.34, -0.30, SCREEN)
        // Lens: mount, barrel, focus ring, a coloured ring, the hood's lip and the glass.
        f += cyl(0.0, 0.0, 0.30, 0.38, 0.37, 32, METAL, caps = false)
        f += cyl(0.0, 0.0, 0.38, 1.02, 0.33, 32, BODY, caps = false)
        f += cyl(0.0, 0.0, 0.52, 0.78, 0.35, 32, RUBBER, caps = false)
        f += cyl(0.0, 0.0, 0.90, 0.94, 0.338, 32, ACCENT, caps = false)
        f += cyl(0.0, 0.0, 1.02, 1.08, 0.36, 32, BODY, caps = false, back = true)
        f += disc(0.0, 0.0, 1.02, 0.31, 32, GLASS)
        // Shutter button and dial on top, right side.
        f += vcyl(0.56, -0.46, -0.53, 0.12, 0.07, 16, METAL)
        f += vcyl(0.28, -0.46, -0.52, -0.08, 0.11, 20, BODY)
        return f
    }

    private fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray, col: Int) = Face(a + b + c + d, col)

    /** A rounded-corner block: the rectangle (x0, y0)–(x1, y1) with corners of radius [r], from z0 to z1. */
    private fun slab(x0: Double, y0: Double, x1: Double, y1: Double, z0: Double, z1: Double, r: Double, col: Int): List<Face> {
        val per = 5
        val ring = ArrayList<DoubleArray>()
        val cs = listOf(Triple(x1 - r, y1 - r, 0.0), Triple(x0 + r, y1 - r, 0.5 * PI), Triple(x0 + r, y0 + r, PI), Triple(x1 - r, y0 + r, 1.5 * PI))
        for ((cx, cy, a0) in cs) for (i in 0..per) { val a = a0 + 0.5 * PI * i / per; ring += doubleArrayOf(cx + r * cos(a), cy + r * sin(a)) }
        val c = doubleArrayOf((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
        val f = ArrayList<Face>()
        val n = ring.size
        for (i in 0 until n) {
            val a = ring[i]; val b = ring[(i + 1) % n]
            f += outward(quad(doubleArrayOf(a[0], a[1], z0), doubleArrayOf(b[0], b[1], z0), doubleArrayOf(b[0], b[1], z1), doubleArrayOf(a[0], a[1], z1), col), doubleArrayOf(c[0], c[1], (z0 + z1) / 2))
        }
        for (z in listOf(z0, z1)) {
            val p = DoubleArray(n * 3); for (i in 0 until n) { p[i * 3] = ring[i][0]; p[i * 3 + 1] = ring[i][1]; p[i * 3 + 2] = z }
            f += outward(Face(p, col), c)
        }
        return f
    }

    /** An axis-aligned box, faces wound outwards. */
    private fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, col: Int): List<Face> {
        fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
        val c = doubleArrayOf((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
        return listOf(
            quad(p(x0, y0, z0), p(x1, y0, z0), p(x1, y1, z0), p(x0, y1, z0), col),
            quad(p(x0, y0, z1), p(x0, y1, z1), p(x1, y1, z1), p(x1, y0, z1), col),
            quad(p(x0, y0, z0), p(x0, y1, z0), p(x0, y1, z1), p(x0, y0, z1), col),
            quad(p(x1, y0, z0), p(x1, y0, z1), p(x1, y1, z1), p(x1, y1, z0), col),
            quad(p(x0, y0, z0), p(x0, y0, z1), p(x1, y0, z1), p(x1, y0, z0), col),
            quad(p(x0, y1, z0), p(x1, y1, z0), p(x1, y1, z1), p(x0, y1, z1), col),
        ).map { outward(it, c) }
    }

    /** A cylinder along z, centred at (cx, cy); [back] also gives it an inner wall (a hood seen from in front). */
    private fun cyl(cx: Double, cy: Double, z0: Double, z1: Double, r: Double, seg: Int, col: Int, caps: Boolean = true, back: Boolean = false): List<Face> {
        val f = ArrayList<Face>()
        fun pt(i: Int, z: Double, rr: Double = r) = doubleArrayOf(cx + rr * cos(2 * PI * i / seg), cy + rr * sin(2 * PI * i / seg), z)
        for (i in 0 until seg) {
            val a = pt(i, z0); val b = pt(i + 1, z0); val c = pt(i + 1, z1); val d = pt(i, z1)
            val mid = doubleArrayOf(cx, cy, (a[2] + c[2]) / 2)
            f += outward(quad(a, b, c, d, col), mid)
            if (back) f += inward(quad(pt(i, z0, r * 0.9), pt(i + 1, z0, r * 0.9), pt(i + 1, z1, r * 0.9), pt(i, z1, r * 0.9), shade(col, 0.7)), mid)
        }
        if (back) for (i in 0 until seg) f += outward(quad(pt(i, z1, r * 0.9), pt(i + 1, z1, r * 0.9), pt(i + 1, z1), pt(i, z1), col), doubleArrayOf(cx, cy, z0))
        if (caps) { f += disc(cx, cy, z1, r, seg, col); f += disc(cx, cy, z0, r, seg, col, facing = -1.0) }
        return f
    }

    /** A flat round face at depth z, facing +z (or −z). */
    private fun disc(cx: Double, cy: Double, z: Double, r: Double, seg: Int, col: Int, facing: Double = 1.0): Face {
        val p = DoubleArray(seg * 3)
        for (i in 0 until seg) { val a = 2 * PI * i / seg; p[i * 3] = cx + r * cos(a); p[i * 3 + 1] = cy + r * sin(a); p[i * 3 + 2] = z }
        return outward(Face(p, col), doubleArrayOf(cx, cy, z - facing))
    }

    /** A cylinder standing on the camera's top (along y, from y0 up to y1), with its cap. */
    private fun vcyl(cx: Double, y0: Double, y1: Double, cz: Double, r: Double, seg: Int, col: Int): List<Face> =
        cyl(cx, cz, y0, y1, r, seg, col).map { fc ->
            val p = DoubleArray(fc.p.size)
            for (i in 0 until fc.p.size / 3) { p[i * 3] = fc.p[i * 3]; p[i * 3 + 1] = fc.p[i * 3 + 2]; p[i * 3 + 2] = fc.p[i * 3 + 1] }
            Face(p, fc.color)
        }.let { fs -> val c = doubleArrayOf(cx, (y0 + y1) / 2, cz); fs.map { outward(it, c) } }

    private fun normal(p: DoubleArray): DoubleArray {
        // Newell's method: robust for any flat polygon.
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        val n = p.size / 3
        for (i in 0 until n) {
            val j = (i + 1) % n
            nx += (p[i * 3 + 1] - p[j * 3 + 1]) * (p[i * 3 + 2] + p[j * 3 + 2])
            ny += (p[i * 3 + 2] - p[j * 3 + 2]) * (p[i * 3] + p[j * 3])
            nz += (p[i * 3] - p[j * 3]) * (p[i * 3 + 1] + p[j * 3 + 1])
        }
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12)
        return doubleArrayOf(nx / l, ny / l, nz / l)
    }

    private fun centre(p: DoubleArray): DoubleArray {
        val n = p.size / 3; val c = DoubleArray(3)
        for (i in 0 until n) for (k in 0..2) c[k] += p[i * 3 + k] / n
        return c
    }

    private fun reversed(f: Face): Face {
        val n = f.p.size / 3; val p = DoubleArray(f.p.size)
        for (i in 0 until n) for (k in 0..2) p[i * 3 + k] = f.p[(n - 1 - i) * 3 + k]
        return Face(p, f.color)
    }

    /** [f], wound so its normal points away from [inside]. */
    private fun outward(f: Face, inside: DoubleArray): Face {
        val n = normal(f.p); val c = centre(f.p)
        return if (n[0] * (c[0] - inside[0]) + n[1] * (c[1] - inside[1]) + n[2] * (c[2] - inside[2]) >= 0) f else reversed(f)
    }

    private fun inward(f: Face, axisPt: DoubleArray): Face = reversed(outward(f, axisPt))

    // --------------------------------------------------------------------------------------------- rotations

    /** The 3×3 matrix (row-major) of rotation vector (a, b, c), scaled by [k]. */
    fun matrix(a: Double, b: Double, c: Double, k: Double = 1.0): DoubleArray {
        val x = a * k; val y = b * k; val z = c * k
        val t = sqrt(x * x + y * y + z * z)
        if (t < 1e-12) return doubleArrayOf(1.0, -z, y, z, 1.0, -x, -y, x, 1.0)
        val ux = x / t; val uy = y / t; val uz = z / t
        val co = cos(t); val si = sin(t); val v = 1 - co
        return doubleArrayOf(
            co + ux * ux * v, ux * uy * v - uz * si, ux * uz * v + uy * si,
            uy * ux * v + uz * si, co + uy * uy * v, uy * uz * v - ux * si,
            uz * ux * v - uy * si, uz * uy * v + ux * si, co + uz * uz * v)
    }

    /** Pitch (up +), yaw (right +) and roll (clockwise as held +), in degrees, of camera-to-world matrix [m]. */
    fun angles(m: DoubleArray): DoubleArray {
        val fx = m[2]; val fy = m[5]; val fz = m[8]
        val yaw = atan2(fx, fz)
        val pitch = atan2(-fy, hypot(fx, fz))
        // Roll: the camera's right axis against the horizontal, around its own view direction.
        val rx = m[0]; val ry = m[3]; val rz = m[6]
        val hx = cos(yaw); val hz = -sin(yaw)
        val roll = atan2(ry, rx * hx + rz * hz)
        return doubleArrayOf(Math.toDegrees(pitch), Math.toDegrees(yaw), Math.toDegrees(roll))
    }

    /** The rotation vector at frame position [t] (fractional) in [rv] (3 per frame), linearly between frames. */
    fun at(rv: FloatArray, t: Double): DoubleArray {
        val n = rv.size / 3
        if (n == 0) return DoubleArray(3)
        val tt = t.coerceIn(0.0, (n - 1).toDouble())
        val i = tt.toInt().coerceAtMost(n - 2).coerceAtLeast(0); val a = tt - i
        val j = min(i + 1, n - 1)
        return DoubleArray(3) { rv[i * 3 + it] * (1 - a) + rv[j * 3 + it] * a }
    }

    // --------------------------------------------------------------------------------------------- drawing

    private class Projector(val w: Float, val h: Float, view: View) {
        val cy = cos(view.yaw); val sy = sin(view.yaw); val cp = cos(view.pitch); val sp = sin(view.pitch)
        val d = view.dist
        val focal = min(w, h) * 1.25
        val ox = w * 0.42; val oy = h * 0.58
        /** World point to view space (x right, y down, z away from the eye). */
        fun view(x: Double, y: Double, z: Double, out: DoubleArray) {
            val x1 = cy * x + sy * z; val z1 = -sy * x + cy * z
            val y2 = cp * y - sp * z1; val z2 = sp * y + cp * z1
            out[0] = x1; out[1] = y2; out[2] = z2 + d
        }
        fun sx(v: DoubleArray) = (ox + v[0] / v[2] * focal).toFloat()
        fun sy(v: DoubleArray) = (oy + v[1] / v[2] * focal).toFloat()
    }

    private fun shade(c: Int, k: Double): Int {
        val r = ((c shr 16 and 255) * k).toInt().coerceIn(0, 255); val g = ((c shr 8 and 255) * k).toInt().coerceIn(0, 255); val b = ((c and 255) * k).toInt().coerceIn(0, 255)
        return (c and -0x1000000) or (r shl 16) or (g shl 8) or b
    }

    private fun alpha(c: Int, a: Double) = ((a.coerceIn(0.0, 1.0) * 255).toInt() shl 24) or (c and 0xFFFFFF)

    /**
     * The scene for one moment: the camera turned by [shot] (its rotation vector as filmed) and, when given, the
     * stabilised view [calm]; [trailShot] and [trailCalm] are where each pointed over the last moments (rotation
     * vectors, oldest first). [k] scales every rotation (1 = as measured), [aspect] is the picture's width / height and
     * [hfov] its horizontal field of view (radians).
     */
    fun render(
        w: Float, h: Float, view: View, shot: DoubleArray, calm: DoubleArray?, trailShot: List<DoubleArray>, trailCalm: List<DoubleArray>,
        k: Double, aspect: Double, hfov: Double, dp: Float, edge: Int = 0xFFE9ECF4.toInt(),
    ): List<Shape> {
        val pr = Projector(w, h, view)
        val out = ArrayList<Shape>()
        val v = DoubleArray(3)
        val M = matrix(shot[0], shot[1], shot[2], k)
        fun rot(m: DoubleArray, x: Double, y: Double, z: Double, o: DoubleArray) {
            o[0] = m[0] * x + m[1] * y + m[2] * z; o[1] = m[3] * x + m[4] * y + m[5] * z; o[2] = m[6] * x + m[7] * y + m[8] * z
        }

        // The floor, fixed in the world: lines fading with distance, and a brighter one straight ahead.
        val floorY = 1.35
        val tmp = DoubleArray(3)
        fun line(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, col: Int, width: Float) {
            pr.view(x0, y0, z0, v); if (v[2] < 0.3) return
            val ax = pr.sx(v); val ay = pr.sy(v)
            pr.view(x1, y1, z1, tmp); if (tmp[2] < 0.3) return
            out += Shape(floatArrayOf(ax, ay, pr.sx(tmp), pr.sy(tmp)), 2, col, width, closed = false)
        }
        val R = 5
        for (i in -R..R) for (s in 0 until 2 * R) {
            // Short segments, so each fades on its own.
            val a = -R + s.toDouble(); val b = a + 1
            val fade = 1 - (hypot(i.toDouble(), a + 0.5) / (R + 0.5)).coerceIn(0.0, 1.0)
            if (fade <= 0.02) continue
            val col = alpha(0xFF8A90A0.toInt(), 0.22 * fade * fade)
            line(i.toDouble(), floorY, a, i.toDouble(), floorY, b, col, 1f * dp)
            line(a, floorY, i.toDouble(), b, floorY, i.toDouble(), col, 1f * dp)
        }
        line(0.0, floorY, 0.0, 0.0, floorY, R.toDouble(), alpha(ACCENT, 0.18), 1.2f * dp)
        // The camera's shadow on the floor: its footprint, turned with it.
        run {
            val pts = FloatArray(8); var ok = true
            for ((i, c) in listOf(-0.85 to -0.4, 0.85 to -0.4, 0.85 to 1.05, -0.85 to 1.05).withIndex()) {
                rot(M, c.first, 0.0, c.second, tmp); pr.view(tmp[0], floorY, tmp[2], v); if (v[2] < 0.3) ok = false
                pts[i * 2] = pr.sx(v); pts[i * 2 + 1] = pr.sy(v)
            }
            if (ok) out += Shape(pts, 4, 0x38000000)
        }

        // What it sees: the frustum from the lens to a picture-shaped frame ahead; the calm view's frame drawn over it.
        val depth = 2.3; val hw = kotlin.math.tan(hfov / 2) * depth; val hh = hw / aspect
        fun frustum(m: DoubleArray, fill: Int, edge: Int, width: Float): Pair<List<Shape>, Double> {
            val lens = DoubleArray(3); rot(m, 0.0, 0.0, 1.08, lens)
            val corners = listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh).map { (x, y) -> DoubleArray(3).also { rot(m, x, y, 1.08 + depth, it) } }
            val sh = ArrayList<Shape>()
            pr.view(lens[0], lens[1], lens[2], v); val lx = pr.sx(v); val ly = pr.sy(v)
            val far = FloatArray(8); var z = 0.0
            for ((i, c) in corners.withIndex()) { pr.view(c[0], c[1], c[2], v); far[i * 2] = pr.sx(v); far[i * 2 + 1] = pr.sy(v); z += v[2] / 4 }
            if (fill != 0) for (i in 0 until 4) { val j = (i + 1) % 4; sh += Shape(floatArrayOf(lx, ly, far[i * 2], far[i * 2 + 1], far[j * 2], far[j * 2 + 1]), 3, fill) }
            for (i in 0 until 4) sh += Shape(floatArrayOf(lx, ly, far[i * 2], far[i * 2 + 1]), 2, alpha(edge, ((edge ushr 24) / 255.0) * 0.55), width * 0.7f, closed = false)
            sh += Shape(far, 4, edge, width)
            // A cross at the centre of the frame: the exact point it is aimed at.
            val cc = DoubleArray(3); rot(m, 0.0, 0.0, 1.08 + depth, cc); pr.view(cc[0], cc[1], cc[2], v)
            val cx = pr.sx(v); val cyy = pr.sy(v); val s = 5f * dp
            sh += Shape(floatArrayOf(cx - s, cyy, cx + s, cyy), 2, edge, width, closed = false)
            sh += Shape(floatArrayOf(cx, cyy - s, cx, cyy + s), 2, edge, width, closed = false)
            return sh to z
        }
        // Trails: where the view's centre has been.
        fun trail(list: List<DoubleArray>, col: Int, width: Float): List<Shape> {
            val sh = ArrayList<Shape>()
            if (list.size < 2) return sh
            var px = 0f; var py = 0f
            for ((i, r) in list.withIndex()) {
                val m = matrix(r[0], r[1], r[2], k)
                rot(m, 0.0, 0.0, 1.08 + depth, tmp); pr.view(tmp[0], tmp[1], tmp[2], v)
                val x = pr.sx(v); val y = pr.sy(v)
                if (i > 0) sh += Shape(floatArrayOf(px, py, x, y), 2, alpha(col, 0.85 * i / list.size), width, closed = false)
                px = x; py = y
            }
            return sh
        }
        val shotF = frustum(M, alpha(0xFFFFFFFF.toInt(), 0.03), alpha(edge, 0.6), 1.2f * dp)
        val calmF = calm?.let { frustum(matrix(it[0], it[1], it[2], k), 0, ACCENT, 2f * dp) }
        val tr = trail(trailShot, 0xFFB9BECC.toInt(), 1.2f * dp) + trail(trailCalm, ACCENT, 1.6f * dp)

        // The camera itself: each face turned, lit from above left, hidden faces dropped, painted far to near.
        val L = doubleArrayOf(-0.45, -0.78, -0.43).let { val l = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]); DoubleArray(3) { i -> it[i] / l } }
        class Drawn(val z: Double, val s: Shape)
        val faces = ArrayList<Drawn>(model.size)
        val wp = DoubleArray(3)
        var bodyZ = 0.0
        for (fc in model) {
            val n = fc.p.size / 3
            val vs = DoubleArray(fc.p.size)
            for (i in 0 until n) {
                rot(M, fc.p[i * 3], fc.p[i * 3 + 1], fc.p[i * 3 + 2], wp)
                pr.view(wp[0], wp[1], wp[2], v)
                vs[i * 3] = v[0]; vs[i * 3 + 1] = v[1]; vs[i * 3 + 2] = v[2]
            }
            val nn = normal(vs); val c = centre(vs)
            if (nn[0] * c[0] + nn[1] * c[1] + nn[2] * c[2] >= 0) continue
            val lam = max(0.0, -(nn[0] * L[0] + nn[1] * L[1] + nn[2] * L[2]))
            // A soft rim from behind, so the dark body still reads against the dark well.
            val rim = max(0.0, nn[1] * -0.2 + nn[0] * 0.25).let { it * it }
            val col = shade(fc.color, 0.62 + 1.25 * lam + 0.6 * rim)
            val xy = FloatArray(n * 2)
            for (i in 0 until n) { xy[i * 2] = (pr.ox + vs[i * 3] / vs[i * 3 + 2] * pr.focal).toFloat(); xy[i * 2 + 1] = (pr.oy + vs[i * 3 + 1] / vs[i * 3 + 2] * pr.focal).toFloat() }
            faces += Drawn(c[2], Shape(xy, n, col))
            bodyZ += c[2]
        }
        faces.sortByDescending { it.z }
        // The glass: a highlight across it, when it faces the viewer.
        val glassFace = run {
            val g = DoubleArray(3); rot(M, 0.0, 0.0, 1.0, g)
            val vv = DoubleArray(3); pr.view(g[0], g[1], g[2], vv); pr.view(0.0, 0.0, 0.0, v)
            vv[2] - v[2] < -0.3
        }
        bodyZ = if (faces.isEmpty()) view.dist else faces.sumOf { it.z } / faces.size
        val (fs, fz) = shotF
        if (fz > bodyZ) { out += fs; calmF?.let { out += it.first } ; out += tr }
        for (d in faces) out += d.s
        if (glassFace) {
            val pts = FloatArray(2 * 12)
            for (i in 0 until 12) {
                val a = PI * (0.62 + 0.5 * i / 11.0)
                rot(M, 0.22 * cos(a), 0.22 * sin(a), 1.025, wp); pr.view(wp[0], wp[1], wp[2], v)
                pts[i * 2] = pr.sx(v); pts[i * 2 + 1] = pr.sy(v)
            }
            out += Shape(pts, 12, 0x66A9C8FF, 2.2f * dp, closed = false)
        }
        if (fz <= bodyZ) { out += fs; calmF?.let { out += it.first }; out += tr }
        return out
    }

    /** Angles, for the read-out, rounded the way the page shows them. */
    fun fmt(d: Double) = (if (d >= 0) "+" else "−") + "%.1f°".format(abs(d))
}

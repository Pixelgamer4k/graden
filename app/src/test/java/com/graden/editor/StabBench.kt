package com.graden.editor

import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Measures the stabiliser on real footage, on the desktop, with the phone's own engine. Give it grey frames
 * (ffmpeg -i clip.mp4 -vf scale=512:-2,format=gray -f rawvideo frames.raw) as -Pbench=frames.raw,width,height,fps.
 *
 * Each method's output is made frame by frame (the shader's mapping, done on the CPU), then measured again with the
 * tracker. Reported per method, in pixels of the 512-wide picture:
 *  - shake: how much the picture still jerks from frame to frame (the change in motion between frames);
 *  - wobble: how much parts of the picture move differently from one another (swimming, jello);
 *  - zoom: the zoom it needed.
 */
class StabBench {
    private fun frames(spec: String): Triple<List<Gray>, Float, Pair<Int, Int>> {
        val (path, w, h, fps) = spec.split(",")
        val W = w.toInt(); val H = h.toInt()
        val b = File(path).readBytes()
        val n = b.size / (W * H)
        return Triple((0 until n).map { i -> Gray(W, H, FloatArray(W * H) { (b[i * W * H + it].toInt() and 255) / 255f }) }, fps.toFloat(), W to H)
    }

    private fun analyse(fr: List<Gray>, detailed: Boolean): StabAnalysis {
        val pool = Executors.newFixedThreadPool(6)
        val t = StabTracker(detailed, pool)
        val rs = fr.map { t.next(it) }
        pool.shutdown()
        val n = fr.size; val nv2 = StabMesh.NV * 2
        return StabAnalysis(LongArray(n) { it * 33_333L }, FloatArray(n * 9) { rs[it / 9].step[it % 9] }, FloatArray(n * nv2) { rs[it / nv2].mesh[it % nv2] },
            FloatArray(n) { rs[it].quality }, fr[0].w.toFloat() / fr[0].h)
    }

    /** The output frame [i] of [tr]: for each output pixel, where the shader reads the input. */
    private fun render(g: Gray, tr: StabTrack, i: Int): Gray {
        val m = tr.mats.copyOfRange(i * 9, i * 9 + 9)
        val o = FloatArray(2)
        val out = FloatArray(g.w * g.h)
        for (y in 0 until g.h) for (x in 0 until g.w) {
            val u = (x + 0.5f) / g.w; val v = (y + 0.5f) / g.h
            var px = M3.ax(m, u, v); var py = M3.ay(m, u, v)
            tr.grids?.let { StabCore.meshAt(it, i * StabMesh.NV * 2, px.coerceIn(0f, 1f), py.coerceIn(0f, 1f), o); px += o[0]; py += o[1] }
            val sx = (px * g.w - 0.5f).coerceIn(0f, g.w - 1.001f); val sy = (py * g.h - 0.5f).coerceIn(0f, g.h - 1.001f)
            val xi = sx.toInt(); val yi = sy.toInt(); val fx = sx - xi; val fy = sy - yi
            val k = yi * g.w + xi
            val top = g.v[k] + (g.v[k + 1] - g.v[k]) * fx; val bot = g.v[k + g.w] + (g.v[k + g.w + 1] - g.v[k + g.w]) * fx
            out[y * g.w + x] = top + (bot - top) * fy
        }
        return Gray(g.w, g.h, out)
    }

    /** Shake and wobble of a sequence, measured by the tracker's own mesh. */
    private fun measure(fr: List<Gray>): Pair<Float, Float> {
        val a = analyse(fr, true)
        val n = a.size; val nv = StabMesh.NV
        val W = fr[0].w.toFloat(); val H = fr[0].h.toFloat()
        // Only the inner mesh (the edges are where the zoom or the frame edge is).
        val inner = (0 until nv).filter { v -> val k = v % StabMesh.VX; val j = v / StabMesh.VX; k in 2..StabMesh.GX - 2 && j in 2..StabMesh.GY - 2 }
        var shake = 0.0; var wobble = 0.0; var c = 0
        for (i in 2 until n) {
            if (a.quality[i] < 0 || a.quality[i - 1] < 0) continue
            var mx = 0.0; var my = 0.0
            for (v in inner) { mx += a.mesh[i * nv * 2 + v * 2]; my += a.mesh[i * nv * 2 + v * 2 + 1] }
            mx /= inner.size; my /= inner.size
            var pmx = 0.0; var pmy = 0.0
            for (v in inner) { pmx += a.mesh[(i - 1) * nv * 2 + v * 2]; pmy += a.mesh[(i - 1) * nv * 2 + v * 2 + 1] }
            pmx /= inner.size; pmy /= inner.size
            shake += hypot((mx - pmx) * W, (my - pmy) * H).let { it * it }
            for (v in inner) {
                val dx = (a.mesh[i * nv * 2 + v * 2] - mx) * W; val dy = (a.mesh[i * nv * 2 + v * 2 + 1] - my) * H
                wobble += dx * dx + dy * dy
            }
            c++
        }
        return sqrt(shake / c).toFloat() to sqrt(wobble / (c * inner.size)).toFloat()
    }

    @Test
    fun bench() {
        val spec = System.getProperty("bench")
        Assume.assumeTrue(!spec.isNullOrEmpty())
        val (fr, fps, _) = frames(spec!!)
        val t0 = System.currentTimeMillis()
        val quick = analyse(fr, System.getProperty("benchDetailed") != null)
        println("BENCH ${fr.size} frames ${fr[0].w}x${fr[0].h}: analysis ${System.currentTimeMillis() - t0} ms (${(System.currentTimeMillis() - t0) / fr.size} ms/frame)")
        // The measurement's own floor: one frame moved by pure shifts has no wobble at all.
        run {
            val rnd = java.util.Random(3)
            val m = FloatArray(9)
            val syn = (0 until 90).map { _ ->
                StabTrack.identity(m); m[2] = (rnd.nextFloat() - 0.5f) * 0.02f; m[5] = (rnd.nextFloat() - 0.5f) * 0.02f
                render(fr[0], StabTrack(LongArray(1), m.copyOf(), null, 0, FloatArray(0), FloatArray(0), FloatArray(1)), 0)
            }
            val (sf, wf) = measure(syn)
            println("BENCH floor (shifted still) shake %.2f  wobble %.2f".format(sf, wf))
        }
        if (System.getProperty("benchSynthetic") != null) { synthetic(fr[fr.size / 3], fps); return }
        val (s0, w0) = measure(fr)
        println("BENCH as shot       shake %.2f  wobble %.2f".format(s0, w0))
        // Match Google: score our per-frame transform (output -> input) against Google's recovered one, over a 5x5 grid
        // of the output, in px of a 1920-wide picture; for a sweep of settings, best first.
        System.getProperty("benchMatch")?.let { path ->
            val arr = org.json.JSONArray(java.io.File(path).readText())
            val gh = (0 until arr.length()).map { i -> arr.getJSONObject(i).getJSONArray("H").let { h -> FloatArray(9) { h.getDouble(it).toFloat() } } }
            val hgt = 1920f / quick.aspect
            fun score(tr: StabTrack): FloatArray {
                val n = minOf(gh.size, tr.times.size)
                var e = 0.0; var et = 0.0; var ez = 0.0; var c = 0
                for (i in 0 until n) {
                    val m = tr.mats.copyOfRange(i * 9, i * 9 + 9)
                    for (gy in 0..4) for (gx in 0..4) {
                        val x = 0.1f + 0.2f * gx; val y = 0.1f + 0.2f * gy
                        var ux = M3.ax(m, x, y); var uy = M3.ay(m, x, y)
                        tr.grids?.let { g -> val o = FloatArray(2); StabCore.meshAt(g, i * StabMesh.NV * 2, ux.coerceIn(0f, 1f), uy.coerceIn(0f, 1f), o); ux += o[0]; uy += o[1] }
                        val vx = M3.ax(gh[i], x, y); val vy = M3.ay(gh[i], x, y)
                        e += hypot(((ux - vx) * 1920f).toDouble(), ((uy - vy) * hgt).toDouble()); c++
                    }
                    // the centre alone (where the picture points) and the scale alone
                    et += hypot(((M3.ax(m, .5f, .5f) - M3.ax(gh[i], .5f, .5f)) * 1920f).toDouble(), ((M3.ay(m, .5f, .5f) - M3.ay(gh[i], .5f, .5f)) * hgt).toDouble())
                    val zo = hypot(M3.ax(m, .6f, .5f) - M3.ax(m, .5f, .5f), M3.ay(m, .6f, .5f) - M3.ay(m, .5f, .5f))
                    val zg = hypot(M3.ax(gh[i], .6f, .5f) - M3.ax(gh[i], .5f, .5f), M3.ay(gh[i], .6f, .5f) - M3.ay(gh[i], .5f, .5f))
                    ez += kotlin.math.abs(zo / zg - 1f)
                }
                return floatArrayOf((e / c).toFloat(), (et / n).toFloat(), (ez / n * 100).toFloat())
            }
            System.getProperty("benchMatchDump")?.let { out ->
                val tr = StabCore.track(quick, Stab(mode = Stab.PERSPECTIVE, method = Stab.NATURAL, smooth = (System.getProperty("stab.dsm") ?: "0.05").toFloat(), cropLess = 0.8f, dynamicZoom = true), fps)
                java.io.File(out).writeText((0 until minOf(gh.size, tr.times.size)).joinToString("\n") { i ->
                    val m = tr.mats.copyOfRange(i * 9, i * 9 + 9); val g = gh[i]
                    fun d(h: FloatArray) = M3.decompose(h, quick.aspect).let { v -> "%.5f,%.5f,%.5f,%.5f".format(v[0], v[1], v[2], v[3]) }
                    d(m) + "," + d(g)
                })
            }
            val results = ArrayList<Pair<String, FloatArray>>()
            for (lim in listOf(1f, 2f)) {
                System.setProperty("stab.lim", lim.toString())
                for (mode in listOf(Stab.SIMILARITY, Stab.PERSPECTIVE)) for (method in listOf(Stab.CINEMATIC, Stab.NATURAL))
                    for (sm in listOf(0.05f, 0.15f, 0.25f, 0.4f, 0.55f, 0.7f)) for (cl in listOf(0.4f, 0.6f, 0.8f, 0.95f)) for (dz in listOf(true)) {
                        val st = Stab(mode = mode, method = method, smooth = sm, cropLess = cl, dynamicZoom = dz)
                        val tr = StabCore.track(quick, st, fps)
                        results += "${Stab.MODES[mode]} ${Stab.METHODS[method]} smooth=$sm cropLess=$cl dynZoom=$dz lim=$lim" to score(tr)
                    }
            }
            System.clearProperty("stab.lim")
            results.sortBy { it.second[0] }
            System.getProperty("benchMatchAll")?.let { f -> java.io.File(f).writeText(results.joinToString("\n") { "%.3f|%s".format(it.second[0], it.first) }) }
            for ((k, v) in results.take(12)) println("BENCH match %6.2f px (centre %6.2f px, zoom %5.2f%%)  %s".format(v[0], v[1], v[2], k))
            return
        }
        // Each frame's measured step: quality (below 0: a cut), and the centre move, turn and zoom.
        System.getProperty("benchDump")?.let { out ->
            val asp = quick.aspect
            java.io.File(out).writeText((0 until quick.size).joinToString("\n") { i ->
                val st = quick.steps.copyOfRange(i * 9, i * 9 + 9)
                val d = M3.decompose(st, asp)
                "%d,%.3f,%.5f,%.5f,%.5f,%.5f".format(i, quick.quality[i], d[0] - 0.5f, d[1] - 0.5f, d[2], d[3])
            })
            println("BENCH dump written")
        }
        // Each frame's correction (Similarity), with the raw and smoothed path, to find where it goes wrong.
        System.getProperty("benchCorr")?.let { out ->
            val tr = StabCore.track(quick, Stab(mode = Stab.SIMILARITY), fps)
            java.io.File(out).writeText((0 until tr.times.size).joinToString("\n") { i ->
                val d = M3.decompose(tr.mats.copyOfRange(i * 9, i * 9 + 9), quick.aspect)
                "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f".format(i, d[0] - 0.5f, d[1] - 0.5f, d[2], d[3],
                    tr.rawPath[i * 3], tr.rawPath[i * 3 + 1], tr.rawPath[i * 3 + 2], tr.smoothPath[i * 3], tr.smoothPath[i * 3 + 1], tr.smoothPath[i * 3 + 2])
            })
        }
        // The measured camera path (centre x, y in picture units, turn), for checking against a known truth.
        System.getProperty("benchPath")?.let { out ->
            val tr = StabCore.track(quick, Stab(mode = Stab.SIMILARITY, strength = 0f), fps)
            java.io.File(out).writeText((0 until tr.times.size).joinToString("\n") { i -> "%.7f,%.7f,%.7f".format(tr.rawPath[i * 3], tr.rawPath[i * 3 + 1], tr.rawPath[i * 3 + 2]) })
            println("BENCH path written to $out")
            if (System.getProperty("benchOut") == null) return
        }
        // Write one method's output as raw grey frames, for an outside measuring tool.
        System.getProperty("benchOut")?.let { out ->
            val mode = (System.getProperty("benchOutMode") ?: "3").toInt()
            val tr = StabCore.track(quick, Stab(mode = mode), fps)
            java.io.FileOutputStream(out).buffered().use { o ->
                for (i in fr.indices) { val g = render(fr[i], tr, i); val b = ByteArray(g.v.size) { (g.v[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }; o.write(b) }
            }
            println("BENCH wrote ${fr.size} frames of ${Stab.MODES[mode]} to $out")
            return
        }
        val modes = (System.getProperty("benchModes") ?: "1,2,3").let { if (it == "none") emptyList() else it.split(",").map { m -> m.toInt() } }
        for (mode in modes) for (lock in listOf(false, true)) {
            val st = Stab(mode = mode, lock = lock)
            val t1 = System.currentTimeMillis()
            val tr = StabCore.track(quick, st, fps)
            val tt = System.currentTimeMillis() - t1
            val out = fr.indices.map { render(fr[it], tr, it) }
            val (s, w) = measure(out)
            println("BENCH %-11s %-6s shake %.2f  wobble %.2f  zoom %.0f%%  (track %d ms)".format(Stab.MODES[mode], if (lock) "lock" else "smooth", s, w, tr.zooms.max() * 100, tt))
        }
    }

    /**
     * A still frame shaken the way a hand shakes a phone: a slow wander, fast jitter, roll, zoom pulses, perspective
     * tilt, and rolling shutter (each row a moment later than the one above). The ideal output holds still.
     */
    private fun synthetic(base: Gray, fps: Float) {
        val n = 150
        val rnd = java.util.Random(5)
        fun walk(scale: Float, smoothK: Float): FloatArray {
            val v = FloatArray(n); var x = 0f; var vel = 0f
            for (i in 0 until n) { vel = vel * smoothK + (rnd.nextFloat() - 0.5f) * scale; x += vel; v[i] = x }
            return v
        }
        val tx = walk(0.004f, 0.7f); val ty = walk(0.004f, 0.7f); val rot = walk(0.004f, 0.6f); val zm = walk(0.0015f, 0.5f)
        val kx = walk(0.0006f, 0.6f); val ky = walk(0.0006f, 0.6f)
        val g = base
        val asp = g.w.toFloat() / g.h
        fun pose(t: Float): FloatArray {
            val i0 = t.toInt().coerceIn(0, n - 2); val f = (t - i0).coerceIn(0f, 1f)
            fun at(a: FloatArray) = a[i0] + (a[i0 + 1] - a[i0]) * f
            val sim = M3.compose(floatArrayOf(0.5f + at(tx), 0.5f + at(ty), at(rot), at(zm)), asp)
            // Perspective about the centre.
            val p = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, at(kx), at(ky), 1f - 0.5f * at(kx) - 0.5f * at(ky))
            return M3.norm(M3.mul(sim, p))
        }
        // Shown zoomed in 1.25×, so the shaken picture never runs off the edge.
        val z = 1.25f
        val shaken = (0 until n).map { i ->
            val out = FloatArray(g.w * g.h)
            for (y in 0 until g.h) {
                // Rolling shutter: this row was read a quarter-frame later than the top.
                val m = M3.inv(pose(i + 0.25f * y / g.h))
                for (x in 0 until g.w) {
                    val u = 0.5f + ((x + 0.5f) / g.w - 0.5f) / z; val v = 0.5f + ((y + 0.5f) / g.h - 0.5f) / z
                    val sx = (M3.ax(m, u, v) * g.w - 0.5f).coerceIn(0f, g.w - 1.001f); val sy = (M3.ay(m, u, v) * g.h - 0.5f).coerceIn(0f, g.h - 1.001f)
                    val xi = sx.toInt(); val yi = sy.toInt(); val fx = sx - xi; val fy = sy - yi
                    val k = yi * g.w + xi
                    val top = g.v[k] + (g.v[k + 1] - g.v[k]) * fx; val bot = g.v[k + g.w] + (g.v[k + g.w + 1] - g.v[k + g.w]) * fx
                    out[y * g.w + x] = top + (bot - top) * fy
                }
            }
            Gray(g.w, g.h, out)
        }
        val (s0, w0) = measure(shaken)
        println("BENCH synthetic as shot  shake %.2f  wobble %.2f".format(s0, w0))
        val a = analyse(shaken, false)
        for (mode in listOf(1, 2, 3)) for (lock in listOf(false, true)) {
            val tr = StabCore.track(a, Stab(mode = mode, lock = lock, rollingShutter = if (mode < 3) 0.5f else 0f), fps)
            val (s, w) = measure(shaken.indices.map { render(shaken[it], tr, it) })
            println("BENCH synthetic %-11s %-6s shake %.2f  wobble %.2f  zoom %.0f%%".format(Stab.MODES[mode], if (lock) "lock" else "smooth", s, w, tr.zooms.max() * 100))
        }
    }
}

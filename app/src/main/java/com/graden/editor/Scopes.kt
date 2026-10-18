package com.graden.editor

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class ScopeType(val label: String) { WAVEFORM("Waveform"), PARADE("Parade"), VECTOR("Vectorscope"), HISTOGRAM("Histogram"), CIE("CIE"), HDR("HDR") }

/** Which traces a waveform or histogram draws. */
enum class WaveMode(val label: String) { Y("Y"), RGB("RGB"), YRGB("YRGB") }
enum class ParadeMode(val label: String) { RGB("RGB"), YRGB("YRGB"), YCBCR("YCbCr") }
enum class ScopeScale(val label: String) { AUTO("Auto"), TEN_BIT("10-bit"), PERCENT("%"), NITS("Nits") }
enum class CieSpace(val label: String) { XY("CIE 1931 xy"), UV("CIE 1976 u′v′") }

/**
 * Every option the scopes have, as in Resolve's scope settings. Channel switches apply to waveform, parade and
 * histogram; colorize to waveform (Y) and vectorscope.
 */
data class ScopeSettings(
    val type: ScopeType = ScopeType.WAVEFORM,
    val wave: WaveMode = WaveMode.Y,
    val parade: ParadeMode = ParadeMode.RGB,
    val showY: Boolean = true,
    val showR: Boolean = true,
    val showG: Boolean = true,
    val showB: Boolean = true,
    val colorize: Boolean = false,
    val extents: Boolean = false,
    val lowPass: Boolean = false,
    /** Trace brightness and graticule brightness, 0..1. */
    val brightness: Float = 0.6f,
    val graticule: Float = 0.5f,
    /** Auto: nits for an HDR signal, 10-bit for SDR. */
    val scale: ScopeScale = ScopeScale.AUTO,
    /** HDR reference lines: SDR reference white (203 nits), the master's peak, this screen's peak. */
    val refSdrWhite: Boolean = true,
    val refPeak: Boolean = true,
    val refScreen: Boolean = false,
    /** HDR scope: shade the SDR, HDR and over-peak zones; count on a log scale. */
    val hdrZones: Boolean = true,
    val hdrLog: Boolean = true,
    /** What the read-back signal is (set by the editor): Rec.2100 PQ when true, else Rec.709 SDR. */
    val hdr: Boolean = false,
    /** The master's peak and the screen's, in nits. */
    val peakNits: Int = 1000,
    val screenNits: Int = 540,
    /** "Highs and lows": reference lines at these 10-bit levels. */
    val refLevels: Boolean = false,
    val refHigh: Int = 940,
    val refLow: Int = 64,
    val vecZoom2x: Boolean = false,
    val vecTargets75: Boolean = false,
    val skinLine: Boolean = true,
    val histLog: Boolean = false,
    val cie: CieSpace = CieSpace.XY,
    val gamut709: Boolean = true,
    val gamutP3: Boolean = false,
    val gamut2020: Boolean = true,
)

/**
 * Turns the graded picture read back from the GPU into a scope image, on its own thread. Frames that arrive while
 * one is being worked on are dropped, so the scopes never fall behind the picture. The last picture is kept, so a
 * changed setting redraws straight away even when paused.
 */
class ScopeEngine(private val onImage: (Bitmap) -> Unit) {
    /** Light-level figures of the latest picture, for the HDR scope. */
    class Stats(val maxCll: Float, val maxFall: Float, val aboveSdr: Float, val abovePeak: Float)
    @Volatile var onStats: ((Stats) -> Unit)? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "scopes").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile var settings = ScopeSettings()
        set(v) {
            field = v
            redraw()
        }
    private var frame = ByteArray(0)
    private var fw = 0
    private var fh = 0
    private val lock = Any()
    // Three bitmaps per size, used in turn, so the one on screen is never written to.
    private val pool = HashMap<Long, Array<Bitmap>>()
    private var flip = 0

    /** Called on the GL thread with the read-back pixels (RGBA, bottom row first). */
    fun submit(buf: ByteBuffer, w: Int, h: Int) {
        if (busy.get()) {
            // Keep the newest picture for when the worker frees up.
            synchronized(lock) { copy(buf, w, h) }
            pendingFresh = true
            return
        }
        synchronized(lock) { copy(buf, w, h) }
        run()
    }

    @Volatile private var pendingFresh = false

    private fun copy(buf: ByteBuffer, w: Int, h: Int) {
        if (frame.size != w * h * 4) frame = ByteArray(w * h * 4)
        buf.position(0)
        buf.get(frame)
        fw = w
        fh = h
    }

    fun redraw() {
        if (fw == 0) return
        if (busy.get()) {
            pendingFresh = true
            return
        }
        run()
    }

    private fun run() {
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                do {
                    pendingFresh = false
                    val s = settings
                    val bmp = synchronized(lock) { render(s, frame, fw, fh) }
                    main.post { onImage(bmp) }
                } while (pendingFresh)
            } finally {
                busy.set(false)
            }
        }
    }

    fun release() = worker.shutdown()

    /** The picture's colour at ([x], [y]) (0..1, y down), as the scopes last saw it; null before any picture. */
    fun sample(x: Float, y: Float): FloatArray? = synchronized(lock) {
        if (fw == 0 || fh == 0) return null
        val px = (x * fw).toInt().coerceIn(0, fw - 1)
        // Read back bottom row first.
        val py = ((1f - y) * fh).toInt().coerceIn(0, fh - 1)
        // A 3×3 average, so one noisy pixel does not decide it.
        val out = FloatArray(3)
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = (px + dx).coerceIn(0, fw - 1); val yy = (py + dy).coerceIn(0, fh - 1)
            val i = (yy * fw + xx) * 4
            for (c in 0..2) out[c] += (frame[i + c].toInt() and 255) / 255f
            n++
        }
        for (c in 0..2) out[c] /= n
        out
    }

    private fun bitmap(w: Int, h: Int): Bitmap {
        val k = (w.toLong() shl 32) or h.toLong()
        val pair = pool.getOrPut(k) { Array(3) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } }
        flip = (flip + 1) % 3
        return pair[flip]
    }

    // ---- rendering --------------------------------------------------------------------------------

    private var px = IntArray(0)
    private fun pixels(n: Int): IntArray {
        if (px.size != n) px = IntArray(n)
        return px
    }

    private fun render(s: ScopeSettings, f: ByteArray, w: Int, h: Int): Bitmap = when (s.type) {
        ScopeType.WAVEFORM -> waveform(s, f, w, h, parade = false)
        ScopeType.PARADE -> waveform(s, f, w, h, parade = true)
        ScopeType.VECTOR -> vector(s, f, w, h)
        ScopeType.HISTOGRAM -> histogram(s, f, w, h)
        ScopeType.CIE -> cie(s, f, w, h)
        ScopeType.HDR -> hdr(s, f, w, h)
    }

    // Per code value: its luma weights and its light in nits, for the signal the scopes are given.
    private val pqNits = FloatArray(256) { v -> pqToNits(v / 255f) }
    private val sdrNits = FloatArray(256) { v -> (v / 255f).pow(2.4f) * 100f }

    private fun pqToNits(e: Float): Float {
        val p = e.toDouble().pow(1.0 / 78.84375)
        return (Math.pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), 1.0 / 0.1593017578125) * 10000.0).toFloat()
    }

    /**
     * The HDR scope: how much of the picture sits at each brightness, on the PQ scale from 0 to 10,000 nits (bars across,
     * brightness up), with the SDR, HDR and over-peak zones; and the frame's MaxCLL, MaxFALL and share above SDR white.
     */
    private fun hdr(s: ScopeSettings, f: ByteArray, w: Int, h: Int): Bitmap {
        val bins = 256
        val hist = IntArray(bins)
        val nits = if (s.hdr) pqNits else sdrNits
        var maxCll = 0f
        var sum = 0.0
        var above = 0
        var over = 0
        for (i in 0 until w * h) {
            val o = i * 4
            val m = max(f[o].toInt() and 255, max(f[o + 1].toInt() and 255, f[o + 2].toInt() and 255))
            val n = nits[m]
            if (n > maxCll) maxCll = n
            sum += n
            if (n > 203f) above++
            if (n > s.peakNits) over++
            // Binned by PQ code, so the bars line up with the nits scale whatever the signal.
            val code = if (s.hdr) m else (nitsToPq(n) * 255f).toInt().coerceIn(0, 255)
            hist[code]++
        }
        val total = (w * h).coerceAtLeast(1)
        onStats?.let { cb -> main.post { cb(Stats(maxCll, (sum / total).toFloat(), above * 100f / total, over * 100f / total)) } }
        val ow = 256
        val oh = 256
        val out = pixels(ow * oh)
        java.util.Arrays.fill(out, 0)
        var peak = 1
        for (v in hist) peak = max(peak, v)
        val sdrRow = (nitsToPq(203f) * (bins - 1)).toInt()
        val peakRow = (nitsToPq(s.peakNits.toFloat()) * (bins - 1)).toInt()
        val level = 0.5f + s.brightness * 0.5f
        for (code in 0 until bins) {
            val v = hist[code]
            val y = oh - 1 - code * (oh - 1) / (bins - 1)
            if (s.hdrZones) {
                // Faint bands behind the bars: HDR range gold, beyond the master's peak red.
                val band = when { code > peakRow -> Trace(60, 14, 14); code > sdrRow -> Trace(40, 30, 8); else -> null }
                if (band != null) for (x in 0 until ow) add(out, y * ow + x, band.r, band.g, band.b)
            }
            if (v == 0) continue
            val t = if (s.hdrLog) ln(1f + v) / ln(1f + peak) else v.toFloat() / peak
            val len = (t * (ow - 1)).toInt().coerceAtLeast(1)
            val ink = when { code > peakRow -> red; code > sdrRow -> Trace(240, 190, 80); else -> white }
            for (x in 0 until len) add(out, y * ow + x, (ink.r * level).toInt(), (ink.g * level).toInt(), (ink.b * level).toInt())
        }
        return finish(out, ow, oh)
    }

    private fun nitsToPq(n: Float): Float {
        val y = (n / 10000f).coerceIn(0f, 1f).toDouble().pow(0.1593017578125)
        return ((0.8359375 + 18.8515625 * y) / (1 + 18.6875 * y)).pow(78.84375).toFloat()
    }

    private class Trace(val r: Int, val g: Int, val b: Int)

    private val red = Trace(255, 64, 58)
    private val green = Trace(70, 240, 96)
    private val blue = Trace(84, 128, 255)
    private val white = Trace(225, 235, 230)
    private val cbInk = Trace(90, 150, 255)
    private val crInk = Trace(255, 90, 110)

    /**
     * Waveform and parade. Each channel is binned by column and level; the bin's count becomes brightness through
     * an exposure curve, so a thin trace is visible and a dense one does not burn out.
     */
    private fun waveform(s: ScopeSettings, f: ByteArray, w: Int, h: Int, parade: Boolean): Bitmap {
        // Luma weights of the signal: Rec.2020 for HDR, Rec.709 otherwise.
        val kr = if (s.hdr) 0.2627f else 0.2126f
        val kb = if (s.hdr) 0.0593f else 0.0722f
        // Channels: 0 Y, 1 R, 2 G, 3 B, 4 Cb, 5 Cr.
        val chans = ArrayList<Int>()
        if (parade) when (s.parade) {
            ParadeMode.RGB -> { if (s.showR) chans += 1; if (s.showG) chans += 2; if (s.showB) chans += 3 }
            ParadeMode.YRGB -> { if (s.showY) chans += 0; if (s.showR) chans += 1; if (s.showG) chans += 2; if (s.showB) chans += 3 }
            ParadeMode.YCBCR -> { chans += 0; chans += 4; chans += 5 }
        } else when (s.wave) {
            WaveMode.Y -> chans += 0
            WaveMode.RGB -> { if (s.showR) chans += 1; if (s.showG) chans += 2; if (s.showB) chans += 3 }
            WaveMode.YRGB -> { if (s.showY) chans += 0; if (s.showR) chans += 1; if (s.showG) chans += 2; if (s.showB) chans += 3 }
        }
        // One output column per picture column, so the trace has no gaps; a parade packs each channel into its share.
        val ow = if (parade) 512 else w
        val oh = 256
        val sections = if (parade) chans.size.coerceAtLeast(1) else 1
        val secW = ow / sections
        val counts = Array(chans.size) { IntArray(ow * oh) }
        val colorizeY = s.colorize && !parade && s.wave == WaveMode.Y
        val sumR = if (colorizeY) IntArray(ow * oh) else null
        val sumG = if (colorizeY) IntArray(ow * oh) else null
        val sumB = if (colorizeY) IntArray(ow * oh) else null
        val lo = IntArray(chans.size * ow) { oh }
        val hi = IntArray(chans.size * ow) { -1 }
        for (y in 0 until h) {
            var pr = -1f; var pg = -1f; var pb = -1f
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                var r = (f[i].toInt() and 255) / 255f
                var g = (f[i + 1].toInt() and 255) / 255f
                var b = (f[i + 2].toInt() and 255) / 255f
                if (s.lowPass && pr >= 0f) {
                    // A gentle low-pass along the line, as on a hardware scope: noise no longer fattens the trace.
                    r = (r + pr) * 0.5f; g = (g + pg) * 0.5f; b = (b + pb) * 0.5f
                }
                pr = r; pg = g; pb = b
                val yl = kr * r + (1f - kr - kb) * g + kb * b
                for ((k, ch) in chans.withIndex()) {
                    val v = when (ch) {
                        0 -> yl
                        1 -> r
                        2 -> g
                        3 -> b
                        4 -> (b - yl) / (2f * (1f - kb)) + 0.5f
                        else -> (r - yl) / (2f * (1f - kr)) + 0.5f
                    }
                    val col = (if (parade) k * secW else 0) + x * (if (parade) secW else ow) / w
                    val row = oh - 1 - (v * (oh - 1)).toInt().coerceIn(0, oh - 1)
                    val bin = row * ow + col
                    counts[k][bin]++
                    if (sumR != null) {
                        sumR[bin] += (r * 255).toInt(); sumG!![bin] += (g * 255).toInt(); sumB!![bin] += (b * 255).toInt()
                    }
                    val e = k * ow + col
                    if (row < lo[e]) lo[e] = row
                    if (row > hi[e]) hi[e] = row
                }
            }
        }
        val out = pixels(ow * oh)
        java.util.Arrays.fill(out, 0)
        // Exposure: how many hits a bin needs to look fully lit, from the density of hits per bin.
        val perColumn = h.toFloat() * max(1f, w.toFloat() / (ow / sections))
        val gain = (40f / perColumn.coerceAtLeast(1f)) * 256f * (0.15f + s.brightness * 1.7f).pow(2f)
        val traceDim = if (s.extents) 0.22f else 1f
        for ((k, ch) in chans.withIndex()) {
            val ink = when (ch) {
                0 -> white
                1 -> red
                2 -> green
                3 -> blue
                4 -> cbInk
                else -> crInk
            }
            val c = counts[k]
            for (bin in c.indices) {
                val n = c[bin]
                if (n == 0) continue
                val a = (1f - exp(-n * gain / 256f)) * traceDim
                if (sumR != null) {
                    // Colorize: each part of the trace in the colour of the pixels that made it, brightened to read.
                    val rr = sumR[bin] / n; val gg = sumG!![bin] / n; val bb = sumB!![bin] / n
                    val m = max(rr, max(gg, bb)).coerceAtLeast(1)
                    val lift = 255f / m
                    add(out, bin, (rr * lift * a).toInt(), (gg * lift * a).toInt(), (bb * lift * a).toInt())
                } else add(out, bin, (ink.r * a).toInt(), (ink.g * a).toInt(), (ink.b * a).toInt())
            }
            if (s.extents) {
                // Extents: the top and bottom of each column's spread, drawn as a solid outline.
                for (col in 0 until ow) {
                    val e = k * ow + col
                    if (hi[e] < 0) continue
                    for (row in intArrayOf(lo[e], hi[e])) for (d in 0..1) {
                        val rr = (row + if (row == lo[e]) d else -d).coerceIn(0, oh - 1)
                        add(out, rr * ow + col, ink.r, ink.g, ink.b)
                    }
                }
            }
        }
        return finish(out, ow, oh)
    }

    private fun add(out: IntArray, i: Int, r: Int, g: Int, b: Int) {
        val p = out[i]
        val nr = min(255, ((p shr 16) and 255) + r)
        val ng = min(255, ((p shr 8) and 255) + g)
        val nb = min(255, (p and 255) + b)
        val na = max(nr, max(ng, nb))
        out[i] = (na shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun finish(out: IntArray, w: Int, h: Int): Bitmap {
        val bmp = bitmap(w, h)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Vectorscope: Cb across, Cr up, as on a broadcast scope; colorized or in the classic green-white. */
    private fun vector(s: ScopeSettings, f: ByteArray, w: Int, h: Int): Bitmap {
        val n = 320
        val counts = IntArray(n * n)
        val sr = IntArray(n * n); val sg = IntArray(n * n); val sb = IntArray(n * n)
        val zoom = if (s.vecZoom2x) 2f else 1f
        // Chroma 0.6 reaches the circle (0.45 of the image), so every 100% bar target sits inside it.
        val k = n * VEC_SCALE * zoom
        // An HDR signal is Rec.2020: its own luma and colour-difference weights.
        val kr = if (s.hdr) 0.2627f else 0.2126f
        val kb = if (s.hdr) 0.0593f else 0.0722f
        val step = if (s.lowPass) 2 else 1
        for (y in 0 until h step step) for (x in 0 until w step step) {
            val i = (y * w + x) * 4
            val r = (f[i].toInt() and 255) / 255f
            val g = (f[i + 1].toInt() and 255) / 255f
            val b = (f[i + 2].toInt() and 255) / 255f
            val yl = kr * r + (1f - kr - kb) * g + kb * b
            val cb = (b - yl) / (2f * (1f - kb))
            val cr = (r - yl) / (2f * (1f - kr))
            val px = (n / 2 + cb * k).toInt()
            val py = (n / 2 - cr * k).toInt()
            if (px !in 0 until n || py !in 0 until n) continue
            val bin = py * n + px
            counts[bin]++
            sr[bin] += (r * 255).toInt(); sg[bin] += (g * 255).toInt(); sb[bin] += (b * 255).toInt()
        }
        val out = pixels(n * n)
        java.util.Arrays.fill(out, 0)
        val gain = (n * n).toFloat() / (w * h / (step * step)).coerceAtLeast(1) * (0.1f + s.brightness * 1.5f).pow(2f) * 1.6f
        for (bin in counts.indices) {
            val c = counts[bin]
            if (c == 0) continue
            val a = 1f - exp(-c * gain)
            if (s.colorize) {
                val rr = sr[bin] / c; val gg = sg[bin] / c; val bb = sb[bin] / c
                val m = max(rr, max(gg, bb)).coerceAtLeast(1)
                val lift = 255f / m
                add(out, bin, (rr * lift * a).toInt(), (gg * lift * a).toInt(), (bb * lift * a).toInt())
            } else add(out, bin, (190 * a).toInt(), (250 * a).toInt(), (200 * a).toInt())
        }
        return finish(out, n, n)
    }

    /** Histogram: how many pixels sit at each level, per channel, from black on the left to white on the right. */
    private fun histogram(s: ScopeSettings, f: ByteArray, w: Int, h: Int): Bitmap {
        val bins = 256
        val hist = Array(4) { IntArray(bins) }
        for (i in 0 until w * h) {
            val o = i * 4
            val r = f[o].toInt() and 255
            val g = f[o + 1].toInt() and 255
            val b = f[o + 2].toInt() and 255
            hist[0][(if (s.hdr) 0.2627f * r + 0.6780f * g + 0.0593f * b else 0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)]++
            hist[1][r]++
            hist[2][g]++
            hist[3][b]++
        }
        val show = booleanArrayOf(
            s.wave != WaveMode.RGB && s.showY,
            s.wave != WaveMode.Y && s.showR,
            s.wave != WaveMode.Y && s.showG,
            s.wave != WaveMode.Y && s.showB,
        )
        val ow = 512
        val oh = 256
        // Scaled to the tallest bin, leaving out pure black and white, which would flatten everything else.
        var peak = 1
        for (c in 0..3) if (show[c]) for (i in 1 until bins - 1) peak = max(peak, hist[c][i])
        val inks = arrayOf(white, red, green, blue)
        val out = pixels(ow * oh)
        java.util.Arrays.fill(out, 0)
        val level = 0.45f + s.brightness * 0.55f
        for (c in 0..3) {
            if (!show[c]) continue
            val ink = inks[c]
            for (x in 0 until ow) {
                val v = hist[c][x * bins / ow]
                val t = if (s.histLog) ln(1f + v) / ln(1f + peak) else v.toFloat() / peak
                val top = oh - (t.coerceIn(0f, 1f) * (oh - 2)).toInt()
                for (y in top until oh) {
                    // Solid at the top edge, fading towards the floor.
                    val a = if (y < top + 2) level else level * 0.42f
                    add(out, y * ow + x, (ink.r * a).toInt(), (ink.g * a).toInt(), (ink.b * a).toInt())
                }
            }
        }
        return finish(out, ow, oh)
    }

    /** CIE chromaticity: every pixel placed by its chromaticity (Rec.709 assumed), in its own colour. */
    private fun cie(s: ScopeSettings, f: ByteArray, w: Int, h: Int): Bitmap {
        val n = 320
        val counts = IntArray(n * n)
        val sr = IntArray(n * n); val sg = IntArray(n * n); val sb = IntArray(n * n)
        val lin = if (s.hdr) FloatArray(256) { v -> pqNits[v] / 10000f }
            else FloatArray(256) { v -> val c = v / 255f; if (c < 0.081f) c / 4.5f else ((c + 0.099f) / 1.099f).pow(1f / 0.45f) }
        // Rec.2020 or Rec.709 primaries to XYZ.
        val m = if (s.hdr) floatArrayOf(0.6370f, 0.1446f, 0.1689f, 0.2627f, 0.6780f, 0.0593f, 0.0f, 0.0281f, 1.0610f)
            else floatArrayOf(0.4124f, 0.3576f, 0.1805f, 0.2126f, 0.7152f, 0.0722f, 0.0193f, 0.1192f, 0.9505f)
        val uv = s.cie == CieSpace.UV
        for (i in 0 until w * h) {
            val o = i * 4
            val r8 = f[o].toInt() and 255; val g8 = f[o + 1].toInt() and 255; val b8 = f[o + 2].toInt() and 255
            val r = lin[r8]; val g = lin[g8]; val b = lin[b8]
            val X = m[0] * r + m[1] * g + m[2] * b
            val Y = m[3] * r + m[4] * g + m[5] * b
            val Z = m[6] * r + m[7] * g + m[8] * b
            val sum = X + Y + Z
            if (sum < (if (s.hdr) 0.00002f else 0.002f)) continue
            val (cx, cy) = if (uv) {
                val d = X + 15 * Y + 3 * Z
                4 * X / d to 9 * Y / d
            } else X / sum to Y / sum
            val p = CieMap.toPx(cx, cy, uv, n.toFloat())
            val px = p.first.toInt(); val py = p.second.toInt()
            if (px !in 0 until n || py !in 0 until n) continue
            val bin = py * n + px
            counts[bin]++
            sr[bin] += r8; sg[bin] += g8; sb[bin] += b8
        }
        val out = pixels(n * n)
        java.util.Arrays.fill(out, 0)
        val gain = (n * n).toFloat() / (w * h).coerceAtLeast(1) * (0.15f + s.brightness * 1.5f).pow(2f) * 3f
        for (bin in counts.indices) {
            val c = counts[bin]
            if (c == 0) continue
            val a = (1f - exp(-c * gain)).coerceAtLeast(0.35f)
            val rr = sr[bin] / c; val gg = sg[bin] / c; val bb = sb[bin] / c
            val m = max(rr, max(gg, bb)).coerceAtLeast(1)
            val lift = 255f / m
            add(out, bin, (rr * lift * a).toInt(), (gg * lift * a).toInt(), (bb * lift * a).toInt())
        }
        return finish(out, n, n)
    }
}

/** Pixels per unit of chroma in the vectorscope, as a share of its side: chroma 0.6 lands on the circle. */
const val VEC_SCALE = 0.75f

/** Where chromaticity coordinates land in the CIE scope's square, shared by the engine and the graticule. */
object CieMap {
    /** The square shows x 0..0.8, y 0..0.9 (or u′ and v′ 0..0.65), with a small margin. */
    fun toPx(x: Float, y: Float, uv: Boolean, size: Float): Pair<Float, Float> {
        val (sx, sy) = if (uv) 0.65f to 0.65f else 0.8f to 0.9f
        val m = size * 0.04f
        val span = size - 2 * m
        val s = span / max(sx, sy)
        val ox = m + (span - sx * s) / 2
        return (ox + x * s) to (size - m - y * s)
    }

    fun xyToUv(x: Float, y: Float): Pair<Float, Float> {
        val d = -2 * x + 12 * y + 3
        return 4 * x / d to 9 * y / d
    }

    /** The spectral locus, 380–700 nm, as CIE 1931 xy. */
    val locus = floatArrayOf(
        0.1741f, 0.0050f, 0.1740f, 0.0050f, 0.1738f, 0.0049f, 0.1736f, 0.0049f, 0.1733f, 0.0048f, 0.1730f, 0.0048f,
        0.1726f, 0.0048f, 0.1721f, 0.0048f, 0.1714f, 0.0051f, 0.1703f, 0.0058f, 0.1689f, 0.0069f, 0.1669f, 0.0086f,
        0.1644f, 0.0109f, 0.1611f, 0.0138f, 0.1566f, 0.0177f, 0.1510f, 0.0227f, 0.1440f, 0.0297f, 0.1355f, 0.0399f,
        0.1241f, 0.0578f, 0.1096f, 0.0868f, 0.0913f, 0.1327f, 0.0687f, 0.2007f, 0.0454f, 0.2950f, 0.0235f, 0.4127f,
        0.0082f, 0.5384f, 0.0039f, 0.6548f, 0.0139f, 0.7502f, 0.0389f, 0.8120f, 0.0743f, 0.8338f, 0.1142f, 0.8262f,
        0.1547f, 0.8059f, 0.1929f, 0.7816f, 0.2296f, 0.7543f, 0.2658f, 0.7243f, 0.3016f, 0.6923f, 0.3373f, 0.6589f,
        0.3731f, 0.6245f, 0.4087f, 0.5896f, 0.4441f, 0.5547f, 0.4788f, 0.5202f, 0.5125f, 0.4866f, 0.5448f, 0.4544f,
        0.5752f, 0.4242f, 0.6029f, 0.3965f, 0.6270f, 0.3725f, 0.6482f, 0.3514f, 0.6658f, 0.3340f, 0.6801f, 0.3197f,
        0.6915f, 0.3083f, 0.7006f, 0.2993f, 0.7079f, 0.2920f, 0.7140f, 0.2859f, 0.7190f, 0.2809f, 0.7230f, 0.2770f,
        0.7260f, 0.2740f, 0.7283f, 0.2717f, 0.7300f, 0.2700f, 0.7311f, 0.2689f, 0.7320f, 0.2680f, 0.7327f, 0.2673f,
        0.7334f, 0.2666f, 0.7340f, 0.2660f, 0.7344f, 0.2656f, 0.7346f, 0.2654f, 0.7347f, 0.2653f,
    )

    class Gamut(val name: String, val xy: FloatArray)

    val rec709 = Gamut("Rec.709", floatArrayOf(0.64f, 0.33f, 0.30f, 0.60f, 0.15f, 0.06f))
    val p3 = Gamut("P3-D65", floatArrayOf(0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f))
    val rec2020 = Gamut("Rec.2020", floatArrayOf(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f))
    const val D65_X = 0.3127f
    const val D65_Y = 0.3290f
}

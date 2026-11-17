package com.graden.editor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A clip's sound effects. Each has its own on switch and keeps its settings while off. All of them run in [FxChain],
 * the same code in the preview and the export, so what is heard while editing is what is exported.
 */
data class SoundFxSet(
    // Noise reduction: spectral subtraction against a noise floor tracked from the quietest moments (minimum statistics).
    val nrOn: Boolean = false, val nrAmount: Float = 12f, val nrSens: Float = 2f,
    // Voice isolation: the speech band kept, everything outside it and between words turned down hard.
    val voiceOn: Boolean = false, val voiceAmount: Float = 0.7f,
    // De-esser: a split-band compressor keyed on the sibilant band.
    val deessOn: Boolean = false, val deessFreq: Float = 6500f, val deessAmount: Float = 6f,
    // Compressor: feed-forward, soft knee, decoupled peak detector (Giannoulis, Massberg & Reiss, JAES 2012).
    val compOn: Boolean = false, val compThreshold: Float = -18f, val compRatio: Float = 3f,
    val compAttack: Float = 10f, val compRelease: Float = 120f, val compMakeup: Float = 0f,
    // Echo: a feedback delay, darkened each repeat, optionally bouncing left and right.
    val echoOn: Boolean = false, val echoMs: Float = 300f, val echoFeedback: Float = 0.35f, val echoMix: Float = 0.3f, val echoPing: Boolean = false,
    // Reverb: Freeverb (Jezar at Dreampoint, public domain): eight comb and four all-pass filters per side.
    val revOn: Boolean = false, val revSize: Float = 0.5f, val revDamp: Float = 0.5f, val revMix: Float = 0.25f,
    // Limiter: 5 ms look-ahead brick wall at the ceiling.
    val limOn: Boolean = false, val limCeiling: Float = -1f, val limRelease: Float = 60f,
    // Auto-duck: this clip turned down while other clips play sound (speech under music).
    val duckOn: Boolean = false, val duckDb: Float = -12f, val duckThreshold: Float = -36f, val duckFadeMs: Float = 250f,
) {
    /** Any effect that changes the sound itself (not auto-duck, which is a level). */
    val processing: Boolean get() = nrOn || voiceOn || deessOn || compOn || echoOn || revOn || limOn
    val isDefault: Boolean get() = this == SoundFxSet()
}

/** A two-pole filter for two channels (RBJ Audio EQ Cookbook), in double precision. */
class Biquad2 {
    var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
    private val z = DoubleArray(4)

    fun run(x: Double, c: Int): Double {
        val i = c * 2
        val y = b0 * x + z[i]
        z[i] = b1 * x - a1 * y + z[i + 1]
        z[i + 1] = b2 * x - a2 * y
        return y
    }

    fun reset() = z.fill(0.0)

    private fun set(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0
    }

    fun peak(f: Double, db: Double, q: Double, fs: Double) {
        val a = 10.0.pow(db / 40.0); val w = 2 * PI * f / fs; val al = sin(w) / (2 * q)
        set(1 + al * a, -2 * cos(w), 1 - al * a, 1 + al / a, -2 * cos(w), 1 - al / a)
    }

    /** Shelf with slope S = 1. */
    fun shelf(f: Double, db: Double, fs: Double, low: Boolean) {
        val a = 10.0.pow(db / 40.0); val w = 2 * PI * f / fs; val c = cos(w)
        val al = sin(w) / 2 * sqrt(2.0); val s = 2 * sqrt(a) * al
        if (low) set(
            a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
            (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s,
        ) else set(
            a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
            (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s,
        )
    }

    fun highpass(f: Double, q: Double, fs: Double) {
        val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
    }

    fun lowpass(f: Double, q: Double, fs: Double) {
        val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
    }

    /** The filter's gain at [f] in dB: its true response, for drawing. */
    fun magnitudeDb(f: Double, fs: Double): Double {
        val w = 2 * PI * f / fs
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di).coerceAtLeast(1e-30))
    }

    companion object {
        /** The three-band equaliser's whole response at [f] (low shelf 200 Hz, bell 1 kHz Q 1, high shelf 4 kHz). */
        fun eqResponseDb(f: Double, bass: Float, mid: Float, treble: Float, fs: Double = 48_000.0): Double {
            val b = Biquad2(); var sum = 0.0
            if (bass != 0f) { b.shelf(200.0, bass.toDouble(), fs, true); sum += b.magnitudeDb(f, fs) }
            if (mid != 0f) { b.peak(1000.0, mid.toDouble(), 1.0, fs); sum += b.magnitudeDb(f, fs) }
            if (treble != 0f) { b.shelf(4000.0, treble.toDouble(), fs, false); sum += b.magnitudeDb(f, fs) }
            return sum
        }
    }
}

/**
 * A clip's whole sound chain on interleaved stereo float, in place, at [rate]: voice band, noise reduction, equaliser,
 * de-esser, compressor, echo, reverb, pan, limiter. Nothing that is off costs anything. [reset] at every seek.
 */
class FxChain(private val rate: Int) {
    private val fs = rate.toDouble()
    private val eq = Array(3) { Biquad2() }
    private var eqKey = Triple(Float.NaN, Float.NaN, Float.NaN)
    private val voiceHp = Biquad2().apply { highpass(100.0, 0.707, fs) }
    private val voiceLp = Biquad2().apply { lowpass(7500.0, 0.707, fs) }
    private val den = Denoiser(rate)
    private val deess = DeEsser(rate)
    private val comp = Compressor(rate)
    private val echo = Echo(rate)
    private val rev = Reverb(rate)
    private val lim = Limiter(rate)

    /** How many frames late the processed sound comes out with these settings (the export moves it back). */
    fun latencyFor(fx: SoundFxSet): Int = (if (fx.nrOn || fx.voiceOn) den.latency else 0) + (if (fx.limOn) lim.latency else 0)

    fun reset() {
        eq.forEach { it.reset() }; voiceHp.reset(); voiceLp.reset()
        den.reset(); deess.reset(); comp.reset(); echo.reset(); rev.reset(); lim.reset()
    }

    /**
     * Processes [frames] frames of [x]. [monoSource]: both channels carry the same mono sound, so pan places it with a
     * constant-power law (centre unchanged, a hard side +3 dB); a stereo source's pan is a balance.
     */
    fun process(x: FloatArray, frames: Int, bass: Float, mid: Float, treble: Float, pan: Float, fx: SoundFxSet, monoSource: Boolean) {
        if (fx.voiceOn) for (i in 0 until frames) for (c in 0..1) {
            val k = i * 2 + c
            x[k] = voiceLp.run(voiceHp.run(x[k].toDouble(), c), c).toFloat()
        }
        if (fx.nrOn || fx.voiceOn) {
            val red = if (fx.nrOn) fx.nrAmount else 0f
            val v = if (fx.voiceOn) fx.voiceAmount else 0f
            den.process(x, frames, max(red, if (fx.voiceOn) 14f + 14f * v else 0f), if (fx.nrOn) fx.nrSens else 2f, v)
        }
        if (bass != 0f || mid != 0f || treble != 0f) {
            if (eqKey != Triple(bass, mid, treble)) {
                eqKey = Triple(bass, mid, treble)
                eq[0].shelf(200.0, bass.toDouble(), fs, true); eq[1].peak(1000.0, mid.toDouble(), 1.0, fs); eq[2].shelf(4000.0, treble.toDouble(), fs, false)
            }
            for (i in 0 until frames) for (c in 0..1) {
                val k = i * 2 + c
                var v = x[k].toDouble()
                if (bass != 0f) v = eq[0].run(v, c)
                if (mid != 0f) v = eq[1].run(v, c)
                if (treble != 0f) v = eq[2].run(v, c)
                x[k] = v.toFloat()
            }
        }
        if (fx.deessOn) deess.process(x, frames, fx.deessFreq, fx.deessAmount)
        if (fx.compOn) comp.process(x, frames, fx.compThreshold, fx.compRatio, fx.compAttack, fx.compRelease, fx.compMakeup)
        if (fx.echoOn) echo.process(x, frames, fx.echoMs, fx.echoFeedback, fx.echoMix, fx.echoPing)
        if (fx.revOn) rev.process(x, frames, fx.revSize, fx.revDamp, fx.revMix)
        if (pan != 0f) {
            val l: Float; val r: Float
            if (monoSource) {
                val th = (pan.coerceIn(-1f, 1f) + 1f) * (PI / 4)
                l = (sqrt(2.0) * cos(th)).toFloat(); r = (sqrt(2.0) * sin(th)).toFloat()
            } else {
                l = if (pan > 0f) 1f - pan else 1f; r = if (pan < 0f) 1f + pan else 1f
            }
            for (i in 0 until frames) { x[i * 2] *= l; x[i * 2 + 1] *= r }
        }
        if (fx.limOn) lim.process(x, frames, fx.limCeiling, fx.limRelease)
    }

    companion object {
        /** The last stage of every mix: below −0.9 dBFS untouched, above it bent smoothly towards full scale. */
        fun softClip(v: Float): Float {
            val a = abs(v)
            return if (a <= 0.9f) v else Math.signum(v) * (0.9f + 0.1f * tanh((a - 0.9f) / 0.1f))
        }
    }
}

// ---- noise reduction and voice isolation ----------------------------------------------------------------------------

/**
 * Short-time spectral noise reduction, per channel: 75%-overlapped square-root Hann frames,
 * each bin's noise floor tracked from its quietest moments (rising at most 2.5 dB a second), a Wiener-style gain with an
 * over-subtraction factor and a floor, smoothed over time (fast to open, slow to close) and across neighbouring bins
 * against "musical noise". With [voice] above 0, frequencies outside speech and the gaps between words go down too.
 */
private class Denoiser(rate: Int) {
    private val n = if (rate > 64_000) 1024 else 512
    private val hop = n / 4
    val latency = n
    private val fs = rate.toDouble()
    private val win = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
    private val bins = n / 2 + 1
    private val hist = Array(2) { DoubleArray(n) }
    private val ola = Array(2) { DoubleArray(n) }
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val pSm = Array(2) { DoubleArray(bins) }
    // Minimum statistics (Martin 2001): a heavily smoothed power per bin, its minimum over about 1.2 s kept as the
    // minima of [subs] sub-windows of [subLen] frames each.
    private val pNs = Array(2) { DoubleArray(bins) }
    private val subs = 6
    private val subLen = max(4, (1.2 * rate / hop / 6).toInt())
    private val subMin = Array(2) { Array(subs) { DoubleArray(bins) { Double.MAX_VALUE } } }
    private val curMin = Array(2) { DoubleArray(bins) { Double.MAX_VALUE } }
    private val noise = Array(2) { DoubleArray(bins) }
    private val gSm = Array(2) { DoubleArray(bins) { 1.0 } }
    private val g = DoubleArray(bins)
    private var k = 0
    private var frames = 0
    private val warm = (0.25 * fs / hop).toInt()
    private var vadGain = DoubleArray(2) { 1.0 }
    private val loBin = (300.0 / fs * n).toInt(); private val hiBin = (3400.0 / fs * n).toInt()
    private val outLo = (100.0 / fs * n).toInt(); private val outHi = (7000.0 / fs * n).toInt()

    fun reset() {
        hist.forEach { it.fill(0.0) }; ola.forEach { it.fill(0.0) }; pSm.forEach { it.fill(0.0) }
        noise.forEach { it.fill(0.0) }; gSm.forEach { it.fill(1.0) }
        pNs.forEach { it.fill(0.0) }; curMin.forEach { it.fill(Double.MAX_VALUE) }; subMin.forEach { w -> w.forEach { it.fill(Double.MAX_VALUE) } }
        vadGain.fill(1.0); k = 0; frames = 0
    }

    fun process(x: FloatArray, count: Int, reductionDb: Float, sens: Float, voice: Float) {
        val floor = 10.0.pow(-reductionDb / 20.0)
        for (i in 0 until count) {
            for (c in 0..1) {
                hist[c][n - hop + k] = x[i * 2 + c].toDouble()
                x[i * 2 + c] = ola[c][k].toFloat()
            }
            if (++k == hop) {
                k = 0
                for (c in 0..1) frame(c, floor, sens.toDouble(), voice.toDouble())
                frames++
            }
        }
    }

    private fun frame(c: Int, floor: Double, sens: Double, voice: Double) {
        val h = hist[c]
        for (i in 0 until n) { re[i] = h[i] * win[i]; im[i] = 0.0 }
        fft(re, im, false)
        val ps = pSm[c]; val nz = noise[c]; val gs = gSm[c]
        var speech = 0.0; var speechNoise = 0.0
        for (b in 0 until bins) {
            val p = re[b] * re[b] + im[b] * im[b]
            ps[b] = if (frames == 0) p else 0.7 * ps[b] + 0.3 * p
            val pn = pNs[c]
            pn[b] = if (frames == 0) p else 0.92 * pn[b] + 0.08 * p
            // Only once the smoothing has settled: a single frame's power can sit near zero and would pass for silence.
            if (frames >= 16 && pn[b] < curMin[c][b]) curMin[c][b] = pn[b]
            var m = curMin[c][b]
            for (w in subMin[c]) if (w[b] < m) m = w[b]
            nz[b] = if (m == Double.MAX_VALUE) pn[b] / NOISE_BIAS else m
            if (b in loBin..hiBin) { speech += ps[b]; speechNoise += nz[b] }
            // The minimum sits below the noise's average (by about 3 to 4 dB for this smoothing): corrected
            // (minimum statistics), then compared with the smoothed power, which is steadier than one frame's.
            val over = sens * (1 + voice)
            g[b] = max(floor, 1 - over * NOISE_BIAS * nz[b] / (ps[b] + 1e-20))
        }
        // Across neighbouring bins, then over time: open quickly, close slowly.
        for (b in 0 until bins) {
            // Weak bins are averaged with their neighbours (against "musical noise"); a strong bin is kept as it is, so a
            // narrow sound such as a voice's harmonic is never pulled down by the quiet bins beside it.
            val avg = (g[max(0, b - 1)] + 2 * g[b] + g[min(bins - 1, b + 1)]) / 4
            val m = if (g[b] > 0.5) g[b] else avg
            gs[b] = if (m > gs[b]) 0.55 * m + 0.45 * gs[b] else 0.82 * gs[b] + 0.18 * m
        }
        // Voice: outside the speech band down, and whole frames between words down.
        if (voice > 0) {
            val cut = 1 - 0.95 * voice
            for (b in 0 until bins) if (b < outLo || b > outHi) gs[b] *= cut
            val snr = speech / (speechNoise * 2.5 + 1e-20)
            val target = if (snr > 1.6) 1.0 else 10.0.pow(-voice * 18 / 20)
            vadGain[c] = if (target > vadGain[c]) 0.5 * target + 0.5 * vadGain[c] else 0.9 * vadGain[c] + 0.1 * target
        } else vadGain[c] = 1.0
        // A sub-window ends: its minimum joins the window, the oldest leaves.
        if (c == 1 && (frames + 1) % subLen == 0) for (ch in 0..1) {
            val w = subMin[ch]
            val oldest = w[0]
            for (i in 0 until subs - 1) w[i] = w[i + 1]
            System.arraycopy(curMin[ch], 0, oldest, 0, bins)
            w[subs - 1] = oldest
            curMin[ch].fill(Double.MAX_VALUE)
        }
        val warmUp = frames < warm
        val vg = vadGain[c]
        for (b in 0 until bins) {
            val gg = if (warmUp) 1.0 else gs[b] * vg
            re[b] *= gg; im[b] *= gg
            if (b in 1 until n / 2) { re[n - b] = re[b]; im[n - b] = -im[b] }
        }
        fft(re, im, true)
        val o = ola[c]
        System.arraycopy(o, hop, o, 0, n - hop)
        java.util.Arrays.fill(o, n - hop, n, 0.0)
        // Square-root Hann on both sides at 75% overlap sums to 2: halved.
        for (i in 0 until n) o[i] += re[i] * win[i] * 0.5
        System.arraycopy(h, hop, h, 0, n - hop)
    }

    private companion object { const val NOISE_BIAS = 2.5 }

    /** In-place radix-2 FFT; [inverse] includes the 1/n. */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val size = re.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= size) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < size) {
                var cr = 1.0; var ci = 0.0
                for (m in 0 until len / 2) {
                    val ur = re[i + m]; val ui = im[i + m]
                    val vr = re[i + m + len / 2] * cr - im[i + m + len / 2] * ci
                    val vi = re[i + m + len / 2] * ci + im[i + m + len / 2] * cr
                    re[i + m] = ur + vr; im[i + m] = ui + vi
                    re[i + m + len / 2] = ur - vr; im[i + m + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until size) { re[i] /= size; im[i] /= size }
    }
}

// ---- dynamics ----------------------------------------------------------------------------------------------------

/** Linear gain to dB, floored at −120. */
private fun db(v: Double) = 20 * log10(max(v, 1e-6))
private fun coef(ms: Float, fs: Double) = exp(-1.0 / (max(ms, 0.01f) / 1000.0 * fs))

/**
 * De-esser as a dynamic EQ: a detector listens above [freq] (a high-pass, fast envelope); while that band is loud, a
 * high shelf at [freq] cuts it by up to [maxDb] (three quarters of the excess above −32 dBFS). With no sibilance the
 * shelf sits at 0 dB and the sound is untouched. The shelf follows the cut every 16 samples.
 */
private class DeEsser(rate: Int) {
    private val fs = rate.toDouble()
    private val detect = Biquad2()
    private val shelf = Biquad2()
    private var key = Float.NaN
    private var env = 0.0
    private var applied = 0.0
    fun reset() { detect.reset(); shelf.reset(); env = 0.0; applied = 0.0; shelf.shelf(key.toDouble().takeIf { !it.isNaN() } ?: 6500.0, 0.0, fs, false) }
    fun process(x: FloatArray, frames: Int, freq: Float, maxDb: Float) {
        if (freq != key) { key = freq; detect.highpass(freq.toDouble(), 0.707, fs); shelf.shelf(freq.toDouble(), -applied, fs, false) }
        val att = coef(1f, fs); val rel = coef(60f, fs)
        val threshold = -32.0
        for (i in 0 until frames) {
            val l = x[i * 2].toDouble(); val r = x[i * 2 + 1].toDouble()
            val lev = max(abs(detect.run(l, 0)), abs(detect.run(r, 1)))
            env = if (lev > env) att * env + (1 - att) * lev else rel * env + (1 - rel) * lev
            if (i % 16 == 0) {
                val over = db(env) - threshold
                val red = if (over > 0) min(maxDb.toDouble(), over * 0.75) else 0.0
                if (abs(red - applied) > 0.05) { applied = red; shelf.shelf(freq.toDouble(), -red, fs, false) }
            }
            x[i * 2] = shelf.run(l, 0).toFloat()
            x[i * 2 + 1] = shelf.run(r, 1).toFloat()
        }
    }
}

/** A feed-forward compressor in the log domain with a 6 dB soft knee, stereo linked. */
private class Compressor(rate: Int) {
    private val fs = rate.toDouble()
    private var yl = 0.0
    fun reset() { yl = 0.0 }
    fun process(x: FloatArray, frames: Int, t: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeup: Float) {
        val aA = coef(attackMs, fs); val aR = coef(releaseMs, fs)
        val w = 6.0; val r = max(1f, ratio).toDouble(); val th = t.toDouble()
        val mk = makeup.toDouble()
        for (i in 0 until frames) {
            val xd = db(max(abs(x[i * 2]), abs(x[i * 2 + 1])).toDouble())
            val d = xd - th
            val yg = when {
                2 * d < -w -> xd
                2 * abs(d) <= w -> xd + (1 / r - 1) * (d + w / 2) * (d + w / 2) / (2 * w)
                else -> th + d / r
            }
            val red = xd - yg
            yl = if (red > yl) aA * yl + (1 - aA) * red else aR * yl + (1 - aR) * red
            val g = 10.0.pow((mk - yl) / 20).toFloat()
            x[i * 2] *= g; x[i * 2 + 1] *= g
        }
    }
}

/**
 * A look-ahead peak limiter: the signal is delayed 5 ms while the loudest sample of that window sets the gain, which
 * glides down within the window and recovers over [releaseMs]. The output never goes past the ceiling.
 */
private class Limiter(rate: Int) {
    private val fs = rate.toDouble()
    val latency = max(1, (0.005 * fs).toInt())
    private val buf = FloatArray(latency * 2)
    private val peaks = FloatArray(latency)
    private var pos = 0
    private var g = 1.0
    private var pk = 0f
    fun reset() { buf.fill(0f); peaks.fill(0f); pos = 0; g = 1.0; pk = 0f }
    fun process(x: FloatArray, frames: Int, ceilingDb: Float, releaseMs: Float) {
        val ceil = 10.0.pow(ceilingDb / 20.0)
        val att = 1 - exp(-5.0 / latency)
        val rel = 1 - coef(releaseMs, fs)
        for (i in 0 until frames) {
            val l = x[i * 2]; val r = x[i * 2 + 1]
            // The window's loudest sample, kept as it slides: rescanned only when the loudest one leaves.
            val v = max(abs(l), abs(r))
            val leaving = peaks[pos]
            peaks[pos] = v
            if (v >= pk) pk = v
            else if (leaving >= pk) { pk = 0f; for (p in peaks) if (p > pk) pk = p }
            val target = if (pk > ceil) ceil / pk else 1.0
            g += (target - g) * (if (target < g) att else rel)
            val dl = buf[pos * 2]; val dr = buf[pos * 2 + 1]
            buf[pos * 2] = l; buf[pos * 2 + 1] = r
            pos = (pos + 1) % latency
            val c = ceil.toFloat()
            x[i * 2] = (dl * g).toFloat().coerceIn(-c, c)
            x[i * 2 + 1] = (dr * g).toFloat().coerceIn(-c, c)
        }
    }
}

// ---- time effects ------------------------------------------------------------------------------------------------

/** A feedback echo up to 1.2 s, each repeat a little darker; ping-pong bounces the repeats between the sides. */
private class Echo(rate: Int) {
    private val fs = rate.toDouble()
    private val size = (1.25 * fs).toInt()
    private val bl = FloatArray(size); private val br = FloatArray(size)
    private var w = 0
    private var lpL = 0.0; private var lpR = 0.0
    private val damp = exp(-2 * PI * 5000.0 / fs)
    fun reset() { bl.fill(0f); br.fill(0f); w = 0; lpL = 0.0; lpR = 0.0 }
    fun process(x: FloatArray, frames: Int, ms: Float, feedback: Float, mix: Float, ping: Boolean) {
        val d = (ms / 1000.0 * fs).toInt().coerceIn(1, size - 1)
        val fb = feedback.coerceIn(0f, 0.9f)
        for (i in 0 until frames) {
            val l = x[i * 2]; val r = x[i * 2 + 1]
            val rd = (w - d + size) % size
            val el = bl[rd]; val er = br[rd]
            lpL = (1 - damp) * el + damp * lpL; lpR = (1 - damp) * er + damp * lpR
            if (ping) {
                bl[w] = ((l + r) * 0.5f + fb * lpR).toFloat()
                br[w] = (fb * lpL).toFloat()
            } else {
                bl[w] = (l + fb * lpL).toFloat(); br[w] = (r + fb * lpR).toFloat()
            }
            w = (w + 1) % size
            val dry = 1f - 0.3f * mix
            x[i * 2] = l * dry + el * mix
            x[i * 2 + 1] = r * dry + er * mix
        }
    }
}

/** Freeverb: eight parallel lowpass-feedback combs into four series all-passes, per side (the right offset by 23). */
private class Reverb(rate: Int) {
    private val k = rate / 44_100.0
    private fun len(n: Int) = max(1, (n * k).toInt())
    private val combL = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb(len(it)) }
    private val combR = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb(len(it + 23)) }
    private val apL = intArrayOf(556, 441, 341, 225).map { AllPass(len(it)) }
    private val apR = intArrayOf(556, 441, 341, 225).map { AllPass(len(it + 23)) }
    fun reset() { (combL + combR).forEach { it.reset() }; (apL + apR).forEach { it.reset() } }
    fun process(x: FloatArray, frames: Int, size: Float, damp: Float, mix: Float) {
        val fb = size.coerceIn(0f, 1f) * 0.28f + 0.7f
        val dm = damp.coerceIn(0f, 1f) * 0.4f
        val wet = mix * 3f
        val dry = 1f - 0.4f * mix
        for (i in 0 until frames) {
            val l = x[i * 2]; val r = x[i * 2 + 1]
            val input = (l + r) * 0.015f
            var ol = 0f; var or = 0f
            for (c in combL) ol += c.run(input, fb, dm)
            for (c in combR) or += c.run(input, fb, dm)
            for (a in apL) ol = a.run(ol)
            for (a in apR) or = a.run(or)
            x[i * 2] = l * dry + ol * wet
            x[i * 2 + 1] = r * dry + or * wet
        }
    }

    private class Comb(n: Int) {
        private val b = FloatArray(n); private var i = 0; private var store = 0f
        fun reset() { b.fill(0f); i = 0; store = 0f }
        fun run(input: Float, fb: Float, damp: Float): Float {
            val out = b[i]
            store = out * (1 - damp) + store * damp
            b[i] = input + store * fb
            if (++i == b.size) i = 0
            return out
        }
    }

    private class AllPass(n: Int) {
        private val b = FloatArray(n); private var i = 0
        fun reset() { b.fill(0f); i = 0 }
        fun run(input: Float): Float {
            val bo = b[i]
            val out = -input + bo
            b[i] = input + bo * 0.5f
            if (++i == b.size) i = 0
            return out
        }
    }
}

/** Saved with the project: only what differs from the defaults. */
fun SoundFxSet.toJson(): org.json.JSONObject? {
    if (isDefault) return null
    val d = SoundFxSet()
    val o = org.json.JSONObject()
    fun b(k: String, v: Boolean, dv: Boolean) { if (v != dv) o.put(k, v) }
    fun f(k: String, v: Float, dv: Float) { if (v != dv) o.put(k, v.toDouble()) }
    b("nr", nrOn, d.nrOn); f("nra", nrAmount, d.nrAmount); f("nrs", nrSens, d.nrSens)
    b("vo", voiceOn, d.voiceOn); f("voa", voiceAmount, d.voiceAmount)
    b("de", deessOn, d.deessOn); f("def", deessFreq, d.deessFreq); f("dea", deessAmount, d.deessAmount)
    b("co", compOn, d.compOn); f("cot", compThreshold, d.compThreshold); f("cor", compRatio, d.compRatio)
    f("coa", compAttack, d.compAttack); f("cor2", compRelease, d.compRelease); f("com", compMakeup, d.compMakeup)
    b("ec", echoOn, d.echoOn); f("ecm", echoMs, d.echoMs); f("ecf", echoFeedback, d.echoFeedback); f("ecx", echoMix, d.echoMix); b("ecp", echoPing, d.echoPing)
    b("rv", revOn, d.revOn); f("rvs", revSize, d.revSize); f("rvd", revDamp, d.revDamp); f("rvm", revMix, d.revMix)
    b("li", limOn, d.limOn); f("lic", limCeiling, d.limCeiling); f("lir", limRelease, d.limRelease)
    b("du", duckOn, d.duckOn); f("dud", duckDb, d.duckDb); f("dut", duckThreshold, d.duckThreshold); f("duf", duckFadeMs, d.duckFadeMs)
    return o
}

fun soundFxFromJson(o: org.json.JSONObject?): SoundFxSet {
    if (o == null) return SoundFxSet()
    val d = SoundFxSet()
    fun f(k: String, dv: Float) = o.optDouble(k, dv.toDouble()).toFloat()
    fun b(k: String, dv: Boolean) = o.optBoolean(k, dv)
    return SoundFxSet(
        b("nr", d.nrOn), f("nra", d.nrAmount), f("nrs", d.nrSens),
        b("vo", d.voiceOn), f("voa", d.voiceAmount),
        b("de", d.deessOn), f("def", d.deessFreq), f("dea", d.deessAmount),
        b("co", d.compOn), f("cot", d.compThreshold), f("cor", d.compRatio), f("coa", d.compAttack), f("cor2", d.compRelease), f("com", d.compMakeup),
        b("ec", d.echoOn), f("ecm", d.echoMs), f("ecf", d.echoFeedback), f("ecx", d.echoMix), b("ecp", d.echoPing),
        b("rv", d.revOn), f("rvs", d.revSize), f("rvd", d.revDamp), f("rvm", d.revMix),
        b("li", d.limOn), f("lic", d.limCeiling), f("lir", d.limRelease),
        b("du", d.duckOn), f("dud", d.duckDb), f("dut", d.duckThreshold), f("duf", d.duckFadeMs),
    )
}

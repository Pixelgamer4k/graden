package com.graden.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Checks the sound effects on synthetic sound: each does what it says, and nothing breaks. */
class AudioFxTest {
    private val rate = 48_000

    private fun tone(sec: Double, hz: Double, amp: Double) = FloatArray((sec * rate).toInt() * 2) { i ->
        (amp * sin(2 * PI * hz * (i / 2) / rate)).toFloat()
    }

    private fun noise(sec: Double, amp: Double, seed: Long = 1) = java.util.Random(seed).let { r ->
        FloatArray((sec * rate).toInt() * 2) { (r.nextGaussian() * amp).toFloat() }
    }

    private fun run(x: FloatArray, fx: SoundFxSet, pan: Float = 0f, bass: Float = 0f, mono: Boolean = false): FloatArray {
        val c = FxChain(rate)
        val out = x.copyOf()
        var i = 0
        while (i < out.size / 2) {
            val n = minOf(1024, out.size / 2 - i)
            val buf = out.copyOfRange(i * 2, (i + n) * 2)
            c.process(buf, n, bass, 0f, 0f, pan, fx, mono)
            System.arraycopy(buf, 0, out, i * 2, n * 2)
            i += n
        }
        return out
    }

    private fun rmsDb(x: FloatArray, from: Int = 0) = 10 * log10(x.drop(from * 2).map { it.toDouble() * it }.average())
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    @Test fun offIsIdentity() {
        val x = noise(0.5, 0.2)
        val y = run(x, SoundFxSet())
        assertTrue(x.contentEquals(y))
    }

    @Test fun everythingOnStaysFinite() {
        val all = SoundFxSet(nrOn = true, voiceOn = true, deessOn = true, compOn = true, echoOn = true, revOn = true, limOn = true, echoPing = true)
        val y = run(tone(2.0, 440.0, 0.9), all, pan = 0.5f, bass = 6f)
        assertTrue(y.all { it.isFinite() })
        assertTrue(peak(y) <= 10f.let { Math.pow(10.0, -1.0 / 20).toFloat() } + 1e-4f)
    }

    @Test fun limiterHoldsCeiling() {
        val y = run(tone(1.0, 200.0, 1.8), SoundFxSet(limOn = true, limCeiling = -3f))
        val ceil = Math.pow(10.0, -3.0 / 20).toFloat()
        assertTrue("peak ${peak(y)} > $ceil", peak(y) <= ceil + 1e-5f)
    }

    @Test fun compressorReducesLoudPart() {
        val loud = tone(1.0, 300.0, 0.9)
        val y = run(loud, SoundFxSet(compOn = true, compThreshold = -20f, compRatio = 4f, compMakeup = 0f))
        val before = rmsDb(loud, rate / 2); val after = rmsDb(y, rate / 2)
        // −4 dB in, 16 dB over, 4:1 → about 12 dB less.
        assertTrue("before $before after $after", before - after in 9.0..14.0)
    }

    @Test fun noiseReductionLowersNoiseKeepsTone() {
        val n = noise(3.0, 0.02)
        val y = run(n, SoundFxSet(nrOn = true, nrAmount = 20f, nrSens = 2f))
        val drop = rmsDb(n, rate) - rmsDb(y, rate)
        assertTrue("noise down only $drop dB", drop > 10)
        // A sound that comes and goes like syllables (a 1 kHz tone in 150 ms bursts) is kept within 2 dB; a
        // steady tone is what it is meant to remove (hum, whine), so that is not what is checked here.
        val t = tone(3.0, 1000.0, 0.3)
        // Syllables: 150 ms of sound, then 200 ms of nothing, as in speech.
        for (i in 0 until t.size / 2) {
            val ph = (i % (0.35 * rate).toInt()) / rate.toDouble()
            val env = if (ph < 0.15) 0.5 - 0.5 * kotlin.math.cos(2 * PI * ph / 0.15) else 0.0
            t[i * 2] *= env.toFloat(); t[i * 2 + 1] *= env.toFloat()
        }
        val mix = FloatArray(t.size) { t[it] + n[it] }
        val z = run(mix, SoundFxSet(nrOn = true, nrAmount = 20f, nrSens = 2f))
        val keep = rmsDb(mix, rate) - rmsDb(z, rate)
        assertTrue("tone lost $keep dB", abs(keep) < 2)
    }

    @Test fun monoPanIsConstantPower() {
        val x = tone(0.2, 440.0, 0.5)
        val y = run(x, SoundFxSet(), pan = 1f, mono = true)
        // Hard right: left silent, right +3 dB.
        assertTrue(peak(FloatArray(y.size / 2) { y[it * 2] }) < 1e-4f)
        val r = peak(FloatArray(y.size / 2) { y[it * 2 + 1] })
        assertEquals(0.5 * sqrt(2.0), r.toDouble(), 0.01)
    }

    @Test fun eqResponseMatchesFilter() {
        assertEquals(6.0, Biquad2.eqResponseDb(20.0, 6f, 0f, 0f), 0.3)
        assertEquals(0.0, Biquad2.eqResponseDb(10_000.0, 6f, 0f, 0f), 0.3)
        assertEquals(6.0, Biquad2.eqResponseDb(1000.0, 0f, 6f, 0f), 0.05)
    }

    @Test fun reverbAndEchoLeaveATail() {
        val x = FloatArray(rate * 2); for (i in 0 until 200) x[i] = 0.8f
        val e = run(x, SoundFxSet(echoOn = true, echoMs = 200f, echoMix = 0.5f))
        assertTrue(peak(e.copyOfRange((0.19 * rate * 2).toInt(), (0.25 * rate * 2).toInt())) > 0.1f)
        val r = run(x, SoundFxSet(revOn = true, revMix = 0.5f))
        assertTrue(rmsDb(r.copyOfRange(rate / 5, rate)) > -60)
    }

    @Test fun deEsserCutsHissNotVoice() {
        val hiss = tone(1.0, 8000.0, 0.5); val low = tone(1.0, 500.0, 0.5)
        val fx = SoundFxSet(deessOn = true, deessFreq = 6000f, deessAmount = 10f)
        val hissDrop = rmsDb(hiss, rate / 2) - rmsDb(run(hiss, fx), rate / 2)
        val lowDrop = rmsDb(low, rate / 2) - rmsDb(run(low, fx), rate / 2)
        assertTrue("hiss down $hissDrop", hissDrop > 6)
        assertTrue("voice band down $lowDrop", abs(lowDrop) < 1)
    }

    @Test fun voiceIsolationCutsRumble() {
        val rumble = tone(2.0, 50.0, 0.5)
        val drop = rmsDb(rumble, rate) - rmsDb(run(rumble, SoundFxSet(voiceOn = true, voiceAmount = 1f)), rate)
        assertTrue("rumble down only $drop", drop > 15)
    }
}

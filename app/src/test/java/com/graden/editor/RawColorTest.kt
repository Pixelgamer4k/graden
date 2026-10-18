package com.graden.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DNG colour maths, on a real calibration (a phone sensor recorded by MotionCam). */
class RawColorTest {
    private val cal = RawCal(JSONObject("""{"cm1":[0.9848,-0.3684,-0.1011,-0.2682,1.1295,0.1152,0.0588,0.0403,0.5348],
        "cm2":[1.9084,-1.3277,0.1113,-0.2221,1.1645,0.2236,0.141,-0.0884,0.8508],
        "fm1":[0.4375,0.3828125,0.140625,0.21875,0.71875,0.0625,0.015625,0.09375,0.7109375],
        "fm2":[0.4375,0.3828125,0.140625,0.21875,0.71875,0.0625,0.015625,0.09375,0.7109375],
        "cc1":[1.0234375,0,0,0,1,0,0,0,0.9921875],"cc2":[1,0,0,0,1,0,0,0,0.9921875],
        "i1":6504,"i2":2856,"asShot":[0.5419921875,1.0,0.5224609375],"iso":450}"""))

    @Test
    fun asShotIsAPlausibleWhite() {
        val (t, ti) = RawColor.asShotTempTint(cal)
        println("RAWCOLOR as shot: ${t.toInt()} K, tint ${"%.1f".format(ti)}")
        assertTrue("temperature $t", t in 2500.0..9000.0)
        assertTrue("tint $ti", ti in -60.0..60.0)
    }

    @Test
    fun temperatureRoundTrips() {
        for (t in listOf(3200.0, 4300.0, 5500.0, 6500.0)) for (ti in listOf(-20.0, 0.0, 20.0)) {
            val n = RawColor.neutral(cal, t, ti)
            val (t2, ti2) = RawColor.tempTint(cal, n)
            println("RAWCOLOR $t/$ti -> ${"%.0f".format(t2)}/${"%.1f".format(ti2)}")
            assertEquals(t, t2, t * 0.03)
            assertEquals(ti, ti2, 4.0)
        }
    }

    @Test
    fun neutralStaysNeutral() {
        // A grey the camera saw under the as-shot light, through gains and matrix, lands grey with green 1.
        val b = RawColor.block(cal, RawLayer(decode = RawLayer.CLIP))
        val n = cal.asShot.map { it / cal.asShot[1] }
        val wb = DoubleArray(3) { n[it] * b[12 + it] }
        val out = DoubleArray(3) { r -> (0 until 3).sumOf { c -> b[r * 4 + c] * wb[c] } }
        println("RAWCOLOR grey -> ${out.joinToString { "%.4f".format(it) }}")
        for (v in out) assertEquals(1.0, v, 0.02)
    }
}

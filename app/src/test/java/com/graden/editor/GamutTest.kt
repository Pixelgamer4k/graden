package com.graden.editor

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The gamut matrices against the ones the makers and standards publish. */
class GamutTest {
    /** Row-major from [ColorSpaces.matrix] (which is column-major). */
    private fun m(a: String, b: String): DoubleArray {
        val c = ColorSpaces.matrix(ColorSpaces.gamut(a), ColorSpaces.gamut(b))
        return DoubleArray(9) { c[(it % 3) * 3 + it / 3].toDouble() }
    }

    private fun check(name: String, got: DoubleArray, want: DoubleArray, tol: Double) {
        val err = got.indices.maxOf { abs(got[it] - want[it]) }
        println("GAMUT %-28s max error %.5f".format(name, err))
        assertTrue("$name: ${got.joinToString { "%.5f".format(it) }}", err < tol)
    }

    @Test fun rec709To2020() = check("Rec.709 -> Rec.2020 (BT.2087)", m("Rec.709", "Rec.2020"),
        doubleArrayOf(0.6274, 0.3293, 0.0433, 0.0691, 0.9195, 0.0114, 0.0164, 0.0880, 0.8956), 6e-4)
    @Test fun awg3To709() = check("ARRI AWG3 -> Rec.709 (ARRI)", m("ARRI Wide Gamut 3", "Rec.709"),
        doubleArrayOf(1.617523, -0.537287, -0.080237, -0.070573, 1.334613, -0.264040, -0.021102, -0.226954, 1.248056), 1e-3)
    @Test fun sg3cTo709() = check("Sony S-Gamut3.Cine -> 709", m("Sony S-Gamut3.Cine", "Rec.709"),
        doubleArrayOf(1.6269474, -0.5401385, -0.0868089, -0.1785155, 1.4179409, -0.2394254, -0.0444361, -0.1959199, 1.2403560), 1e-3)
    @Test fun vgamutTo709() = check("Panasonic V-Gamut -> 709", m("Panasonic V-Gamut", "Rec.709"),
        doubleArrayOf(1.806576, -0.695697, -0.110879, -0.170090, 1.305955, -0.135865, -0.025206, -0.154468, 1.179674), 1e-3)
    @Test fun ap1To709() = check("ACES AP1 -> 709 (Bradford)", m("ACES (AP1)", "Rec.709"),
        doubleArrayOf(1.70505, -0.62179, -0.08326, -0.13026, 1.14080, -0.01055, -0.02400, -0.12897, 1.15297), 2e-3)
    @Test fun p3To709() = check("P3-D65 -> Rec.709", m("P3-D65", "Rec.709"),
        doubleArrayOf(1.2249, -0.2247, 0.0, -0.0420, 1.0419, 0.0, -0.0197, -0.0786, 1.0979), 6e-4)
    @Test fun dwgToXyz() = check("DaVinci WG -> XYZ (BMD)", m("DaVinci Wide Gamut", "CIE XYZ"),
        doubleArrayOf(0.70062239, 0.14877482, 0.10105872, 0.27411851, 0.87363190, -0.14775041, -0.09896291, -0.13789533, 1.32591599), 1e-3)
}

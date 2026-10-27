package com.graden.editor

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * One colour wheel, as Resolve keeps it. [u] and [v] are where its puck sits, in a unit disc (blue–yellow across,
 * red–cyan up); [y] moves the three channels together, so the puck and [y] make the R, G and B values. [m] is the
 * wheel's Y (Resolve's master, the jog under the wheel): it acts on luminance, as far as Lum Mix lets it. Offset has no
 * Y in Resolve: its jog moves [y].
 */
data class Wheel(val u: Float = 0f, val v: Float = 0f, val y: Float = 0f, val m: Float = 0f) {
    val isZero: Boolean get() = u == 0f && v == 0f && y == 0f && m == 0f

    /**
     * The red, green and blue shifts the puck makes, with the luma left where it is: the puck adds colour
     * without brightening or darkening. [strength] is the shift at the edge of the disc.
     */
    fun rgb(strength: Float): FloatArray {
        val cb = u * strength
        val cr = v * strength
        return floatArrayOf(1.5748f * cr, -0.1873f * cb - 0.4681f * cr, 1.8556f * cb)
    }

    val reach: Float get() = hypot(u, v)
}

/**
 * A clip's primary grade, laid out like the Color page in Resolve: four wheels and the primary controls
 * beside them. Values are kept as Resolve shows them (Saturation 50, Hue 50, Lum Mix 100, Pivot 0.435…), so
 * the numbers read the same way.
 */
data class Grade(
    val lift: Wheel = Wheel(),
    val gamma: Wheel = Wheel(),
    val gain: Wheel = Wheel(),
    val offset: Wheel = Wheel(),
    /** Colour temperature shift, warmer above zero. */
    val temp: Float = 0f,
    /** Green below zero, magenta above. */
    val tint: Float = 0f,
    val contrast: Float = 1f,
    val pivot: Float = 0.435f,
    /** Mid/Detail: local contrast in the mid-tones. */
    val midDetail: Float = 0f,
    /** Saturation added mostly where colour is weak. */
    val colorBoost: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val saturation: Float = 50f,
    val hue: Float = 50f,
    /** How much the wheels' master rings move luma only (100) rather than each channel (0). */
    val lumMix: Float = 100f,
    /** Custom and hue/sat/lum curves. */
    val curves: Curves = Curves(),
    /** Layer 1: the colour space transform the clip is read in with. */
    val cst: Cst = Cst(),
    /** Layer 3: LUTs, applied top to bottom after the correction. */
    val luts: List<LutLayer> = emptyList(),
    val noise: NoiseLayer = NoiseLayer(),
    val lens: LensLayer = LensLayer(),
    val deflicker: DeflickerLayer = DeflickerLayer(),
    val zones: ZonesLayer = ZonesLayer(),
    val logWheels: LogLayer = LogLayer(),
    val mixer: MixerLayer = MixerLayer(),
    val warper: WarperLayer = WarperLayer(),
    val slice: SliceLayer = SliceLayer(),
    val detail: DetailLayer = DetailLayer(),
    val secondaries: List<Secondary> = emptyList(),
    val film: FilmLayer = FilmLayer(),
    val limiter: LimiterLayer = LimiterLayer(),
    /** The correction layer as a whole: on, and how strongly. */
    val correctionOn: Boolean = true,
    val correctionMix: Float = 1f,
    val lutsOn: Boolean = true,
    /** Before everything, for a RAW clip: how its sensor data is developed (white balance, exposure, highlights). */
    val raw: RawLayer = RawLayer(),
) {
    val isIdentity: Boolean get() = this == Grade()

    /**
     * The values the shader reads, in the order its uniforms expect: each wheel's R, G and B values (as the readouts
     * show them, less neutral) and its Y; then the primaries; then the temperature and tint matrix.
     */
    fun uniforms(): FloatArray {
        val l = lift.rgb(LIFT_REACH)
        val g = gamma.rgb(GAMMA_REACH)
        val n = gain.rgb(GAIN_REACH)
        val o = offset.rgb(OFFSET_REACH)
        val t = TempTint.matrix(temp, tint)
        return floatArrayOf(
            lift.y + l[0], lift.y + l[1], lift.y + l[2], lift.m,
            gamma.y + g[0], gamma.y + g[1], gamma.y + g[2], gamma.m,
            gain.y + n[0], gain.y + n[1], gain.y + n[2], gain.m,
            offset.y + o[0], offset.y + o[1], offset.y + o[2], 0f,
            temp / 2000f, tint / 100f, contrast, pivot,
            (midDetail + if (zones.on) zones.md else 0f) / 100f, colorBoost / 100f, shadows / 100f, highlights / 100f,
            saturation / 50f, (hue - 50f) / 50f * Math.PI.toFloat(), lumMix / 100f, t[8],
            t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7],
        )
    }

    fun toJson(): JSONObject {
        fun w(x: Wheel) = JSONArray().put(x.u.toDouble()).put(x.v.toDouble()).put(x.y.toDouble()).put(x.m.toDouble())
        return JSONObject().put("l", w(lift)).put("g", w(gamma)).put("n", w(gain)).put("o", w(offset))
            .put("temp", temp.toDouble()).put("tint", tint.toDouble()).put("con", contrast.toDouble()).put("piv", pivot.toDouble())
            .put("md", midDetail.toDouble()).put("cb", colorBoost.toDouble()).put("sh", shadows.toDouble()).put("hl", highlights.toDouble())
            .put("sat", saturation.toDouble()).put("hue", hue.toDouble()).put("lm", lumMix.toDouble())
            .putOpt("cv", if (curves.isIdentity) null else curves.toJson())
            .putOpt("cst", if (cst == Cst()) null else cst.toJson())
            .putOpt("luts", if (luts.isEmpty()) null else JSONArray().apply { luts.forEach { put(it.toJson()) } })
            .putOpt("nr", noise.takeIf { it != NoiseLayer() }?.toJson()).putOpt("ln", lens.takeIf { it != LensLayer() }?.toJson())
            .putOpt("df", deflicker.takeIf { it != DeflickerLayer() }?.toJson()).putOpt("zn", zones.takeIf { it != ZonesLayer() }?.toJson())
            .putOpt("lw", logWheels.takeIf { it != LogLayer() }?.toJson()).putOpt("mx", mixer.takeIf { it != MixerLayer() }?.toJson())
            .putOpt("wp", warper.takeIf { it != WarperLayer() }?.toJson()).putOpt("sl", slice.takeIf { it != SliceLayer() }?.toJson())
            .putOpt("dt", detail.takeIf { it != DetailLayer() }?.toJson())
            .putOpt("sec", if (secondaries.isEmpty()) null else JSONArray().apply { secondaries.forEach { put(it.toJson()) } })
            .putOpt("fl", film.takeIf { it != FilmLayer() }?.toJson()).putOpt("lim", limiter.takeIf { it != LimiterLayer() }?.toJson())
            .put("cOn", correctionOn).put("cmx", correctionMix.toDouble()).put("lon", lutsOn)
            .putOpt("raw", raw.takeIf { !it.isDefault }?.toJson())
    }

    companion object {
        /** Shift at the edge of each wheel's disc, as a fraction of full range. */
        const val LIFT_REACH = 0.18f
        const val GAMMA_REACH = 0.18f
        const val GAIN_REACH = 0.35f
        const val OFFSET_REACH = 0.18f

        fun fromJson(o: JSONObject?): Grade {
            if (o == null) return Grade()
            // An earlier version saved the correction switch over Contrast ("con"): a true or false there is not a contrast.
            if (o.opt("con") is Boolean) o.put("con", 1.0)
            // Before the wheels had their own Y, the jog's value (the third) was the master: it moves to Y, which keeps
            // the picture (at the default Lum Mix). Offset has no Y and keeps it.
            fun w(k: String): Wheel = o.optJSONArray(k)?.let {
                val y = it.optDouble(2).toFloat()
                when {
                    it.length() >= 4 -> Wheel(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), y, it.optDouble(3).toFloat())
                    k == "o" -> Wheel(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), y)
                    else -> Wheel(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), 0f, y)
                }
            } ?: Wheel()
            fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
            return Grade(
                w("l"), w("g"), w("n"), w("o"),
                f("temp", 0f), f("tint", 0f), f("con", 1f), f("piv", 0.435f), f("md", 0f), f("cb", 0f),
                f("sh", 0f), f("hl", 0f), f("sat", 50f), f("hue", 50f), f("lm", 100f),
                Curves.fromJson(o.optJSONObject("cv")), Cst.fromJson(o.optJSONObject("cst")),
                o.optJSONArray("luts")?.let { a -> (0 until a.length()).mapNotNull { LutLayer.fromJson(a.optJSONObject(it)) } }
                    ?: LutLayer.legacy(o.optJSONObject("lut")),
                NoiseLayer.fromJson(o.optJSONObject("nr")), LensLayer.fromJson(o.optJSONObject("ln")), DeflickerLayer.fromJson(o.optJSONObject("df")),
                ZonesLayer.fromJson(o.optJSONObject("zn")), LogLayer.fromJson(o.optJSONObject("lw")), MixerLayer.fromJson(o.optJSONObject("mx")),
                WarperLayer.fromJson(o.optJSONObject("wp")), SliceLayer.fromJson(o.optJSONObject("sl")), DetailLayer.fromJson(o.optJSONObject("dt")),
                o.optJSONArray("sec")?.let { a -> (0 until a.length()).mapNotNull { Secondary.fromJson(a.optJSONObject(it)) } } ?: emptyList(),
                FilmLayer.fromJson(o.optJSONObject("fl")), LimiterLayer.fromJson(o.optJSONObject("lim")),
                o.optBoolean("cOn", true), f("cmx", 1f), o.optBoolean("lon", true),
                RawLayer.fromJson(o.optJSONObject("raw")),
            )
        }
    }
}

/**
 * Resolve's Temp and Tint (measured from Resolve 21): a Bradford chromatic adaptation on Rec.709, scaled so grey keeps
 * its luminance. The cone gains it uses for each Temp (−2000 to 2000 by 500) and Tint (−100 to 100 by 20) were measured
 * and are interpolated here (in log), matching Resolve to about 0.002.
 */
object TempTint {
    /** Rec.709 to Bradford cone space, and back. */
    private val T = floatArrayOf(0.422658f, 0.491357f, 0.027364f, 0.055691f, 0.961556f, 0.023189f, 0.021379f, 0.087644f, 0.980743f)
    private val TI = floatArrayOf(2.538448f, -1.293483f, -0.040243f, -0.146000f, 1.116622f, -0.022329f, -0.042288f, -0.071590f, 1.022507f)
    /** ln of the cone gains, [temp][tint][cone]. */
    private val D = floatArrayOf(
        -0.084301f, 0.046685f, 0.132466f, -0.075692f, 0.039151f, 0.146454f, -0.067036f, 0.031574f, 0.160301f, -0.058432f, 0.023841f, 0.174090f, -0.049843f, 0.016005f, 0.187783f, -0.041272f, 0.008064f, 0.201335f, -0.032749f, -0.000038f, 0.214830f, -0.024186f, -0.008196f, 0.228199f, -0.015669f, -0.016519f, 0.241511f, -0.007202f, -0.024965f, 0.254690f, 0.001307f, -0.033515f, 0.267826f,
        -0.075109f, 0.045471f, 0.086818f, -0.066756f, 0.038018f, 0.101902f, -0.058385f, 0.030475f, 0.116858f, -0.050057f, 0.022825f, 0.131685f, -0.041714f, 0.015075f, 0.146442f, -0.033381f, 0.007224f, 0.161026f, -0.025100f, -0.000795f, 0.175489f, -0.016826f, -0.008917f, 0.189882f, -0.008540f, -0.017147f, 0.204114f, -0.000267f, -0.025492f, 0.218279f, 0.007990f, -0.033955f, 0.232333f,
        -0.063754f, 0.043100f, 0.034916f, -0.055690f, 0.035775f, 0.051424f, -0.047641f, 0.028302f, 0.067710f, -0.039597f, 0.020732f, 0.083883f, -0.031564f, 0.013058f, 0.099902f, -0.023572f, 0.005223f, 0.115755f, -0.015586f, -0.002711f, 0.131508f, -0.007549f, -0.010697f, 0.147114f, 0.000413f, -0.018904f, 0.162563f, 0.008427f, -0.027161f, 0.177922f, 0.016398f, -0.035593f, 0.193130f,
        -0.051133f, 0.040442f, -0.026527f, -0.043423f, 0.033186f, -0.008322f, -0.035751f, 0.025778f, 0.009659f, -0.028022f, 0.018329f, 0.027435f, -0.020326f, 0.010729f, 0.045053f, -0.012665f, 0.003021f, 0.062465f, -0.004950f, -0.004780f, 0.079680f, 0.002703f, -0.012750f, 0.096799f, 0.010377f, -0.020822f, 0.113679f, 0.018075f, -0.029002f, 0.130471f, 0.025740f, -0.037299f, 0.147029f,
        -0.036586f, 0.036913f, -0.099150f, -0.029264f, 0.029764f, -0.078880f, -0.021969f, 0.022470f, -0.058782f, -0.014640f, 0.015135f, -0.038961f, -0.007340f, 0.007648f, -0.019420f, -0.000044f, -0.000044f, -0.000044f, 0.007290f, -0.007678f, 0.019081f, 0.014591f, -0.015525f, 0.037996f, 0.021917f, -0.023476f, 0.056666f, 0.029245f, -0.031533f, 0.075146f, 0.036571f, -0.039752f, 0.093434f,
        -0.019264f, 0.031841f, -0.185417f, -0.012414f, 0.024789f, -0.162309f, -0.005555f, 0.017644f, -0.139597f, 0.001315f, 0.010408f, -0.117147f, 0.008219f, 0.003080f, -0.095064f, 0.015097f, -0.004404f, -0.073294f, 0.021985f, -0.011988f, -0.051754f, 0.028874f, -0.019727f, -0.030559f, 0.035769f, -0.027570f, -0.009641f, 0.042700f, -0.035512f, 0.011065f, 0.049603f, -0.043625f, 0.031453f,
        0.000982f, 0.025152f, -0.290969f, 0.007337f, 0.018285f, -0.264176f, 0.013677f, 0.011275f, -0.237880f, 0.020059f, 0.004177f, -0.212058f, 0.026456f, -0.003012f, -0.186618f, 0.032834f, -0.010356f, -0.161690f, 0.039252f, -0.017792f, -0.137112f, 0.045684f, -0.025329f, -0.112884f, 0.052096f, -0.033025f, -0.089113f, 0.058548f, -0.040819f, -0.065660f, 0.065007f, -0.048776f, -0.042581f,
        0.025021f, 0.016139f, -0.423449f, 0.030801f, 0.009442f, -0.391457f, 0.036599f, 0.002653f, -0.360304f, 0.042390f, -0.004279f, -0.329796f, 0.048201f, -0.011300f, -0.299891f, 0.054028f, -0.018465f, -0.270649f, 0.059905f, -0.025671f, -0.241954f, 0.065743f, -0.033085f, -0.213800f, 0.071622f, -0.040598f, -0.186219f, 0.077547f, -0.048207f, -0.159120f, 0.083488f, -0.055917f, -0.132487f,
        0.054463f, 0.002985f, -0.588742f, 0.059561f, -0.003532f, -0.549498f, 0.064716f, -0.010128f, -0.511273f, 0.069869f, -0.016813f, -0.474200f, 0.075048f, -0.023638f, -0.438120f, 0.080249f, -0.030557f, -0.403045f, 0.085474f, -0.037618f, -0.368775f, 0.090699f, -0.044779f, -0.335338f, 0.095999f, -0.052027f, -0.302679f, 0.101269f, -0.059433f, -0.270779f, 0.106562f, -0.066996f, -0.239520f,
    )

    /** The 3×3 matrix (row-major) that applies [temp] and [tint] to linear-or-display Rec.709 values. */
    fun matrix(temp: Float, tint: Float): FloatArray {
        if (temp == 0f && tint == 0f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val ti = ((temp.coerceIn(-2000f, 2000f) + 2000f) / 500f).coerceIn(0f, 8f)
        val ni = ((tint.coerceIn(-100f, 100f) + 100f) / 20f).coerceIn(0f, 10f)
        val i0 = minOf(ti.toInt(), 7); val j0 = minOf(ni.toInt(), 9)
        val a = ti - i0; val b = ni - j0
        val g = FloatArray(3) { k ->
            fun at(i: Int, j: Int) = D[(i * 11 + j) * 3 + k]
            kotlin.math.exp((at(i0, j0) * (1 - a) + at(i0 + 1, j0) * a) * (1 - b) + (at(i0, j0 + 1) * (1 - a) + at(i0 + 1, j0 + 1) * a) * b)
        }
        // TI · diag(g) · T
        val m = FloatArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += TI[r * 3 + k] * g[k] * T[k * 3 + c]
            m[r * 3 + c] = s
        }
        // Grey keeps its luminance.
        val yr = 0.2126f * (m[0] + m[1] + m[2]) + 0.7152f * (m[3] + m[4] + m[5]) + 0.0722f * (m[6] + m[7] + m[8])
        for (k in 0 until 9) m[k] /= yr
        return m
    }
}

/** The four wheels by name, for the page and for the numbers under each wheel. */
enum class WheelKind(val label: String, val reach: Float) {
    LIFT("Lift", Grade.LIFT_REACH), GAMMA("Gamma", Grade.GAMMA_REACH), GAIN("Gain", Grade.GAIN_REACH), OFFSET("Offset", Grade.OFFSET_REACH);

    fun of(g: Grade): Wheel = when (this) {
        LIFT -> g.lift
        GAMMA -> g.gamma
        GAIN -> g.gain
        OFFSET -> g.offset
    }

    fun set(g: Grade, w: Wheel): Grade = when (this) {
        LIFT -> g.copy(lift = w)
        GAMMA -> g.copy(gamma = w)
        GAIN -> g.copy(gain = w)
        OFFSET -> g.copy(offset = w)
    }

    /** The readout of a channel left untouched: 1 for Gain, 25 for Offset, 0 for the others. */
    val neutral: Float get() = when (this) { GAIN -> 1f; OFFSET -> 25f; else -> 0f }

    /** How far a readout moves for a full sweep of a channel strip, and the step a haptic tick marks. */
    val sweep: Float get() = when (this) { GAIN -> 0.8f; OFFSET -> 30f; else -> 0.4f }
    val step: Float get() = when (this) { GAIN -> 0.02f; OFFSET -> 1f; else -> 0.01f }

    private fun raw(readout: Float) = when (this) { GAIN -> readout - 1f; OFFSET -> (readout - 25f) / 100f; else -> readout }

    /**
     * [w] with one channel's readout (0 Y, 1 R, 2 G, 3 B) set to [value] and the others kept: Y is the wheel's own (for
     * Offset, which has none, it moves all three); a colour channel is solved back into the puck's position and the
     * channels' common shift, so the wheel shows exactly what was set.
     */
    fun withChannel(w: Wheel, ch: Int, value: Float): Wheel {
        if (ch == 0 && this != OFFSET) return w.copy(m = raw(value).coerceIn(-1f, 1f))
        if (ch == 0) {
            val dy = raw(value) - raw(readout(w)[0])
            return w.copy(y = (w.y + dy).coerceIn(-1f, 1f))
        }
        val r = readout(w).copyOf()
        r[ch] = value
        val R = raw(r[1]); val G = raw(r[2]); val B = raw(r[3])
        val y = 0.2126f * R + 0.7152f * G + 0.0722f * B
        var u = (B - y) / 1.8556f / reach
        var v = (R - y) / 1.5748f / reach
        val m = hypot(u, v)
        if (m > 1f) { u /= m; v /= m }
        return Wheel(u, v, y.coerceIn(-1f, 1f), w.m)
    }

    /** What the jog under the wheel moves: the wheel's Y, or for Offset (which has none) all three channels. */
    fun master(w: Wheel): Float = if (this == OFFSET) w.y else w.m
    fun withMaster(w: Wheel, x: Float): Wheel = if (this == OFFSET) w.copy(y = x) else w.copy(m = x)

    /** Y, R, G and B as Resolve shows them under the wheel: Gain sits at 1, Offset at 25, the others at 0. */
    fun readout(w: Wheel): FloatArray {
        val c = w.rgb(reach)
        return when (this) {
            GAIN -> floatArrayOf(1 + w.m, 1 + w.y + c[0], 1 + w.y + c[1], 1 + w.y + c[2])
            OFFSET -> floatArrayOf(25 + w.y * 100, 25 + (w.y + c[0]) * 100, 25 + (w.y + c[1]) * 100, 25 + (w.y + c[2]) * 100)
            else -> floatArrayOf(w.m, w.y + c[0], w.y + c[1], w.y + c[2])
        }
    }
}

/** The shader that applies a [Grade], shared by the preview and the scope read-back. */
object GradeShader {
    const val VERTEX = """
attribute vec2 aPos;
uniform mat4 uTex;
uniform vec4 uRect;
varying vec2 vUv;
void main() {
    vec2 t = aPos * 0.5 + 0.5;
    vUv = t;
    gl_Position = vec4(mix(uRect.xy, uRect.zw, t), 0.0, 1.0);
}
"""

    /** The same grade reading a plain image texture (native frames at rest). */
    val IMAGE_FRAGMENT: String by lazy {
        FRAGMENT.replace("#extension GL_OES_EGL_image_external : require\n", "").replace("samplerExternalOES", "sampler2D")
    }

    val FRAGMENT: String get() = FRAGMENT_SRC.replace("CM_FUNCS", "// GRADE-BODY\n" + ColorSpaces.GLSL + ColorPipe.GLSL + GradeTextures.GLSL)

    private const val FRAGMENT_SRC = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vUv;
uniform samplerExternalOES uSrc;
uniform mat4 uTex;
uniform vec4 uG[9];
uniform float uOn;
uniform vec2 uTexel;

CM_FUNCS
uniform vec4 uL[72];
uniform float uTime;
// Earlier frames (temporal noise reduction and motion blur), in picture coordinates, newest first; and how many.
uniform sampler2D uH1;
uniform sampler2D uH2;
uniform sampler2D uH3;
uniform sampler2D uH4;
uniform sampler2D uH5;
uniform float uHistN;
// The quarter-size, mipmapped copy of the corrected picture (for halation and bloom), and the target's size.
uniform sampler2D uGlow;
uniform vec2 uFb;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// Stabilisation: where each output point reads the frame (uStab, picture coordinates with y down, then in Warp the
// mesh displacement uStabG on top), and how the edges it opens are filled (uStabP.y: 0 auto zoom, 1 fixed zoom,
// 2 mirror, 3 stretch, 4 black; x: on; z: mesh on).
uniform mat3 uStab;
uniform vec4 uStabP;
uniform sampler2D uStabG;
float stabKeep = 1.0;
vec2 stabUv(vec2 p) {
    if (uStabP.x < 0.5) return p;
    bool up = uL[71].y > 0.5;
    vec3 h = uStab * vec3(p.x, up ? 1.0 - p.y : p.y, 1.0);
    vec2 q = h.xy / h.z;
    if (uStabP.z > 0.5) q += texture2D(uStabG, (clamp(q, 0.0, 1.0) * vec2(16.0, 9.0) + 0.5) / vec2(17.0, 10.0)).rg;
    if (uStabP.y > 1.5 && uStabP.y < 2.5) q = 1.0 - abs(1.0 - mod(q, 2.0));
    else if (uStabP.y > 3.5 || (uStabP.y > 0.5 && uStabP.y < 1.5)) {
        vec2 e = step(vec2(0.0), q) * step(q, vec2(1.0));
        stabKeep = e.x * e.y;
    }
    q = clamp(q, 0.0, 1.0);
    return vec2(q.x, up ? 1.0 - q.y : q.y);
}
vec3 raw(vec2 p) { vec2 s = stabUv(p); return texture2D(uSrc, (uTex * vec4(s, 0.0, 1.0)).xy).rgb; }
// The colour space transform is part of reading each sample in.
vec3 cst(vec3 r) { return xform(rawDev(r), uXf[0], uXf[1], uXfY[0], uCstM); }
float luma(vec3 c) { return dot(c, LUMA); }
/**
 * A RAW clip's sharpness and midtone detail, from its raw settings (see uRaw). Done as a ratio of the brightness to its
 * surroundings, in stops: it never pushes anything below black or shifts colours, differences smaller than the
 * sensor's noise are left alone, and the boost is limited so edges do not ring. The surroundings' brightness is read
 * straight from the developed sensor data (one weighted sum a sample), which keeps the shader small.
 */
float rawY(vec2 p) {
    vec3 cam = (exp2(raw(p) * 14.0) - 1.0) / 4096.0;
    vec3 lr = 0.2627 * uRaw[0].xyz + 0.6780 * uRaw[1].xyz + 0.0593 * uRaw[2].xyz;
    return max(dot(lr, cam * uRaw[3].xyz), 1e-6);
}
vec3 rawDetail(vec3 c) {
    if (uRaw[4].x < 0.5 || (uRaw[7].x <= 0.0 && uRaw[7].y == 0.0)) return c;
    float y = rawY(vUv);
    float st = 0.0;
    if (uRaw[7].x > 0.0) {
        // Fine detail: against the 8 neighbours, a pixel and a half away.
        vec2 d = uTexel * 1.5;
        float b = rawY(vUv + vec2(d.x, 0.0)) + rawY(vUv - vec2(d.x, 0.0)) + rawY(vUv + vec2(0.0, d.y)) + rawY(vUv - vec2(0.0, d.y));
        b += 0.5 * (rawY(vUv + d) + rawY(vUv - d) + rawY(vUv + vec2(d.x, -d.y)) + rawY(vUv + vec2(-d.x, d.y)));
        float e = log2(y) - log2(b / 6.0);
        e = sign(e) * max(abs(e) - 0.04, 0.0);
        st += clamp(e * uRaw[7].x, -0.6, 0.6);
    }
    if (uRaw[7].y != 0.0) {
        // Midtone detail: against a wider surround, mostly in the mid-tones.
        vec2 e2 = uTexel * 6.0;
        float m = rawY(vUv + vec2(e2.x, 0.0)) + rawY(vUv - vec2(e2.x, 0.0)) + rawY(vUv + vec2(0.0, e2.y)) + rawY(vUv - vec2(0.0, e2.y));
        float e = log2(y) - log2(m * 0.25);
        float L = log2(y * uRaw[4].y / 0.18);
        float w = smoothstep(-6.0, -3.0, L) * (1.0 - smoothstep(1.0, 3.0, L));
        st += clamp(e * uRaw[7].y * w, -0.8, 0.8);
    }
    if (uRaw[6].w >= 0.0) return encodeG(decodeG(c, uRaw[6].w) * exp2(st), uRaw[6].w);
    return c * exp2(st);
}
/** Position in the picture with y running down (the preview's textures run up, export's down). */
vec2 pic(vec2 p) { return vec2(p.x, uL[71].y > 0.5 ? 1.0 - p.y : p.y); }
float aspect() { return uTexel.y / max(uTexel.x, 1e-9); }

// ---- input: gate weave, lens, temporal and spatial noise reduction, motion blur ----------------------
float vnoise(float t) { float i = floor(t); float f = fract(t); f = f * f * (3.0 - 2.0 * f); return mix(fract(sin(i * 12.9898) * 43758.5453), fract(sin((i + 1.0) * 12.9898) * 43758.5453), f); }
/** Gate weave: the frame drifts slightly in the gate, as projected film does. */
vec2 weave(vec2 p) {
    if (uL[67].x <= 0.0) return p;
    float t = uTime * uL[67].y * 2.0;
    vec2 o = vec2(vnoise(t) - 0.5, (vnoise(t + 31.7) - 0.5) * 0.6) * uL[67].x * 0.004;
    return p + o;
}
vec2 lensUv(vec2 p) {
    if (uL[4].x < 0.5 || uL[4].z == 0.0) return p;
    vec2 d = p - 0.5;
    return 0.5 + d * (1.0 + uL[4].z * dot(d, d) * 2.0);
}

/** One spatial tap: weighted by how close its brightness is (edges are kept), and counted for the colour blur. */
void tap(vec2 q, float yc, float s2, inout vec3 aL, inout float wL, inout vec3 aC, inout float nC) {
    vec3 v = raw(q);
    float dy = luma(v) - yc;
    float w = exp(-dy * dy / s2);
    aL += v * w; wL += w; aC += v; nC += 1.0;
}
void ring(vec2 q, vec2 t, float yc, float s2, inout vec3 aL, inout float wL, inout vec3 aC, inout float nC) {
    tap(q + vec2(t.x, 0.0), yc, s2, aL, wL, aC, nC); tap(q - vec2(t.x, 0.0), yc, s2, aL, wL, aC, nC);
    tap(q + vec2(0.0, t.y), yc, s2, aL, wL, aC, nC); tap(q - vec2(0.0, t.y), yc, s2, aL, wL, aC, nC);
    tap(q + t * 0.7071, yc, s2, aL, wL, aC, nC); tap(q - t * 0.7071, yc, s2, aL, wL, aC, nC);
    tap(q + vec2(t.x, -t.y) * 0.7071, yc, s2, aL, wL, aC, nC); tap(q + vec2(-t.x, t.y) * 0.7071, yc, s2, aL, wL, aC, nC);
}
/** Spatial NR (Resolve's modes): Faster one ring, Better two, Enhanced two plus a wider ring for colour. */
vec3 spatialNR(vec2 q, vec3 c) {
    if (uL[0].x < 0.5) return c;
    vec2 t = uTexel * uL[0].w;
    float yc = luma(c);
    float s2 = pow(0.002 + 0.12 * uL[0].y, 2.0);
    vec3 aL = c; float wL = 1.0; vec3 aC = c; float nC = 1.0;
    ring(q, t, yc, s2, aL, wL, aC, nC);
    if (uL[1].x > 0.5) ring(q, t * 2.0, yc, s2, aL, wL, aC, nC);
    if (uL[1].x > 1.5) {
        vec3 cw = raw(q + vec2(t.x, 0.0) * 3.5) + raw(q - vec2(t.x, 0.0) * 3.5) + raw(q + vec2(0.0, t.y) * 3.5) + raw(q - vec2(0.0, t.y) * 3.5);
        aC += cw; nC += 4.0;
    }
    vec3 bil = aL / wL;
    vec3 box = aC / nC;
    float y = mix(yc, luma(bil), clamp(uL[0].y * 4.0, 0.0, 1.0));
    vec3 chroma = mix(c - yc, box - luma(box), clamp(uL[0].z * 1.5, 0.0, 1.0));
    return mix(y + chroma, c, uL[1].y);
}

/** The earlier frame [h] at q, moved to where it best lines up with this one (motion estimation, by search). */
vec3 matchFrame(sampler2D h, vec2 q, vec3 c) {
    vec3 best = texture2D(h, q).rgb;
    if (uL[3].y < 0.5) return best;
    float bd = abs(luma(best) - luma(c));
    vec2 r = uTexel * uL[3].z;
    vec3 v; float d;
    v = texture2D(h, q + vec2(r.x, 0.0)).rgb; d = abs(luma(v) - luma(c)); if (d < bd) { bd = d; best = v; }
    v = texture2D(h, q - vec2(r.x, 0.0)).rgb; d = abs(luma(v) - luma(c)); if (d < bd) { bd = d; best = v; }
    v = texture2D(h, q + vec2(0.0, r.y)).rgb; d = abs(luma(v) - luma(c)); if (d < bd) { bd = d; best = v; }
    v = texture2D(h, q - vec2(0.0, r.y)).rgb; d = abs(luma(v) - luma(c)); if (d < bd) { bd = d; best = v; }
    if (uL[3].y > 1.5) {
        v = texture2D(h, q + r).rgb; d = abs(luma(v) - luma(c)); if (d < bd) { bd = d; best = v; }
        v = texture2D(h, q - r).rgb; d = abs(luma(v) - luma(c)); if (d < bd) { bd = d; best = v; }
        v = texture2D(h, q + vec2(r.x, -r.y)).rgb; d = abs(luma(v) - luma(c)); if (d < bd) { bd = d; best = v; }
        v = texture2D(h, q + vec2(-r.x, r.y)).rgb; d = abs(luma(v) - luma(c)); if (d < bd) { bd = d; best = v; }
    }
    return best;
}

/** One earlier frame's share in temporal NR: luma and chroma weighed by their thresholds, none where things moved. */
void tnr(vec3 h, vec3 c, inout float aY, inout float wY, inout vec3 aC, inout float wC) {
    float dy = luma(h) - luma(c);
    float motion = 1.0 - smoothstep(uL[2].w * 0.6, uL[2].w * 0.6 + 0.05, abs(dy));
    float sl = max(uL[2].y * 0.2, 1e-4);
    float sc = max(uL[2].z * 0.2, 1e-4);
    float w1 = exp(-dy * dy / (sl * sl)) * motion;
    vec3 dc = (h - luma(h)) - (c - luma(c));
    float w2 = exp(-dot(dc, dc) / (sc * sc)) * motion;
    aY += luma(h) * w1; wY += w1;
    aC += (h - luma(h)) * w2; wC += w2;
}
vec3 temporalNR(vec2 q, vec3 c) {
    if (uL[2].x < 0.5 || uHistN < 0.5) return c;
    float n = min(uL[2].x, uHistN);
    float aY = luma(c); float wY = 1.0; vec3 aC = c - luma(c); float wC = 1.0;
    tnr(matchFrame(uH1, q, c), c, aY, wY, aC, wC);
    if (n > 1.5) tnr(matchFrame(uH2, q, c), c, aY, wY, aC, wC);
    if (n > 2.5) tnr(matchFrame(uH3, q, c), c, aY, wY, aC, wC);
    if (n > 3.5) tnr(matchFrame(uH4, q, c), c, aY, wY, aC, wC);
    if (n > 4.5) tnr(matchFrame(uH5, q, c), c, aY, wY, aC, wC);
    vec3 o = aY / wY + aC / wC;
    return mix(o, c, uL[3].x);
}

/** Motion blur: where the picture moved since the last frames, they are blended in along the movement. */
vec3 motionBlur(vec2 q, vec3 c) {
    if (uL[5].z <= 0.0 || uHistN < 0.5) return c;
    vec3 h1 = texture2D(uH1, q).rgb;
    vec3 acc = c + h1;
    float n = 2.0;
    if (uHistN > 1.5) { acc += texture2D(uH2, q).rgb; n += 1.0; }
    float moved = smoothstep(0.02, 0.12, abs(luma(h1) - luma(c)));
    return mix(c, acc / n, uL[5].z * moved);
}

vec3 inputAt(vec2 p0) {
    vec2 p = weave(p0);
    vec2 q = lensUv(p);
    vec3 c;
    if (uL[4].x > 0.5 && uL[4].y != 0.0) {
        // Lateral chromatic aberration: red and blue scaled the opposite ways about the centre.
        vec2 d = q - 0.5;
        c = vec3(raw(0.5 + d * (1.0 + uL[4].y)).r, raw(q).g, raw(0.5 + d * (1.0 - uL[4].y)).b);
    } else c = raw(q);
    c = temporalNR(q, c);
    c = spatialNR(q, c);
    c = motionBlur(q, c);
    if (uL[4].x > 0.5 && uL[4].w != 0.0) { vec2 d = (pic(p0) - 0.5) * vec2(aspect(), 1.0); c *= 1.0 + uL[4].w * dot(d, d) * 1.5; }
    return c;
}

// ---- correction --------------------------------------------------------------------------------
// The Color Wheels palette, as Resolve 21 does it (measured through its own LUT export, to 3e-5 for the wheels).
const float OFFSET_STEP = 7.5 / 1023.0 * 100.0;   // Offset moves 7.5 ten-bit codes per step (kept here / 100)

/** Lift then gain, as Resolve: black moves with lift, white with gain. */
vec3 liftGain(vec3 c, vec3 l, vec3 n) { return n * (c + l * (1.0 - c)) / (1.0 + l * (n - 1.0)); }

/** Resolve's gamma: brighter by 1 / (1 + g) above zero, darker by (1 − g) below. */
vec3 wheelGamma(vec3 c, vec3 g) {
    vec3 e = mix(1.0 - g, 1.0 / (1.0 + g), step(0.0, g));
    return sign(c) * pow(abs(c), e);
}

/** One side of Resolve's contrast S-curve (measured table, see SatCompTable): [u] 0 at the end, 1 at the pivot. */
float conSide(float u, float k) {
    float row = clamp((k - 1.0) / 0.05, 0.0, 20.0);
    return texture2D(uSatComp, vec2((clamp(u, 0.0, 1.0) * 191.0 + 0.5) / 192.0, (1729.0 + row + 0.5) / 1772.0)).r;
}
float conCurve(float x, float k, float p, float f0, float s0) {
    if (x <= 0.0) return p * f0 + x * s0;
    if (x >= 1.0) return 1.0 - (1.0 - p) * f0 + (x - 1.0) * s0;
    return x < p ? p * conSide(x / p, k) : 1.0 - (1.0 - p) * conSide((1.0 - x) / (1.0 - p), k);
}

/**
 * Contrast about [pivot], as Resolve's (its default S-curve): below 1 a straight line through the pivot; above, a
 * measured S-curve with Resolve's own shape, carrying on beyond 0 and 1 at its end slope.
 */
vec3 sContrast(vec3 c, float k, float pivot) {
    if (abs(k - 1.0) < 1e-4) return c;
    float p = clamp(pivot, 0.01, 0.99);
    if (k < 1.0) return p + (c - p) * k;
    if (uSatCompOn < 0.5) {
        vec3 x = clamp(c, 0.0, 1.0);
        vec3 lo = p * pow(x / p, vec3(k));
        vec3 hi = 1.0 - (1.0 - p) * pow((1.0 - x) / (1.0 - p), vec3(k));
        return mix(lo, hi, step(p, x)) + (c - x);
    }
    float f0 = conSide(0.0, k);
    float s0 = (conSide(1.0 / 191.0, k) - f0) * 191.0;
    return vec3(conCurve(c.r, k, p, f0, s0), conCurve(c.g, k, p, f0, s0), conCurve(c.b, k, p, f0, s0));
}

/**
 * Resolve's Color Boost (measured): luminance kept, the colour moved straight from grey so its chroma (max − min
 * channel) follows a measured curve for the amount [b] (−1 to 1). Up, chroma rises by a near-constant amount, eased in
 * from grey; down, it falls by one and stops at grey, so muted colours move most. Smooth, so gradients stay clean.
 */
float boostAt(float ch, float b) {
    float row = (clamp(b, -1.0, 1.0) + 1.0) * 10.0;
    float v = (1750.0 + row + 0.5) / 1772.0;
    float x = clamp(ch, 0.0, 1.0);
    float f = texture2D(uSatComp, vec2((x * 191.0 + 0.5) / 192.0, v)).r;
    if (ch > 1.0) f += (ch - 1.0) * (f - texture2D(uSatComp, vec2((190.0 + 0.5) / 192.0, v)).r) * 191.0;
    return f;
}
vec3 colorBoost(vec3 c, float b) {
    float ch = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    if (ch < 1e-6) return c;
    if (uSatCompOn < 0.5) {
        float y = luma(c);
        return y + (c - y) * (1.0 + b * (1.0 - smoothstep(0.0, 0.6, ch)));
    }
    float y = luma(c);
    return y + (c - y) * (boostAt(ch, b) / ch);
}

// Resolve's Hue turns colours about grey in its own plane (measured: an exact rotation by the dial's angle).
const mat3 HUE_A = mat3(0.5773503, 0.5773503, 0.5773503, 0.6964470, -0.2084033, -0.0040914, 0.0000000, 0.0875793, -0.6810601);
const mat3 HUE_AI = mat3(0.3637189, 1.1343388, 0.3015185, 1.2124232, -1.0050914, 1.0338369, 0.1559087, -0.1292474, -1.3353554);

/**
 * The primaries and wheels (Resolve's Color Wheels palette), in Resolve's order: Temp and Tint; Color Boost; Lift, Gain, Offset and
 * Gamma on each channel, with the wheels' Y on luminance as far as Lum Mix allows; Contrast; Saturation; Hue.
 */
vec3 primaries(vec3 src) {
    vec4 L = uG[0]; vec4 G = uG[1]; vec4 N = uG[2]; vec4 O = uG[3];
    vec4 P = uG[4]; vec4 Q = uG[5]; vec4 R = uG[6];
    vec3 c = src;
    c = mat3(uG[7].x, uG[7].w, uG[8].z, uG[7].y, uG[8].x, uG[8].w, uG[7].z, uG[8].y, R.w) * c;
    if (abs(Q.y) > 0.0001) c = colorBoost(c, Q.y);
    // Each channel; the luminance as the Y values would have it; then the luminance put where Lum Mix says.
    vec3 off = O.rgb * OFFSET_STEP;
    vec3 q = wheelGamma(liftGain(c, 2.0 * L.rgb, 1.0 + N.rgb) + off, 4.0 * G.rgb);
    float ty = wheelGamma(liftGain(vec3(luma(c)), vec3(2.0 * L.a), vec3(1.0 + N.a)) + luma(off), vec3(4.0 * G.a)).x;
    // Contrast on both, and the luminance settled once, after it (as Resolve).
    q = sContrast(q, P.z, P.w);
    ty = sContrast(vec3(ty), P.z, P.w).x;
    c = q + R.z * (ty - luma(q));
    float y = luma(c);
    float ws = 1.0 - smoothstep(0.0, 0.55, y);
    float wh = smoothstep(0.45, 1.0, y);
    c += Q.z * 0.35 * ws * ws + Q.w * 0.35 * wh * wh;
    if (abs(Q.x) > 0.0001) {
        vec2 d = uTexel * 6.0;
        vec3 rb = raw(vUv + vec2(d.x, 0.0)) + raw(vUv - vec2(d.x, 0.0));
        rb += raw(vUv + vec2(0.0, d.y)) + raw(vUv - vec2(0.0, d.y));
        rb += raw(vUv + d * 0.7) + raw(vUv - d * 0.7);
        rb += raw(vUv + vec2(d.x, -d.y) * 0.7) + raw(vUv + vec2(-d.x, d.y) * 0.7);
        float b = luma(cst(rb * 0.125));
        float ys = luma(src);
        float mid = 1.0 - pow(abs(2.0 * ys - 1.0), 2.0);
        c += (ys - b) * Q.x * 2.5 * mid;
    }
    y = luma(c);
    c = y + (c - y) * R.x;
    if (abs(R.y) > 0.0001) {
        float cs = cos(R.y); float sn = sin(R.y);
        c = HUE_A * (mat3(1.0, 0.0, 0.0, 0.0, cs, -sn, 0.0, sn, cs) * (HUE_AI * c));
    }
    return c;
}

/** How much a shadow-side zone (Black, Dark, Shadow) reaches a value [st] stops from grey: all below its range, easing off. */
float below(float st, vec4 z) { float f = 0.25 + z.w * 4.0; return 1.0 - smoothstep(z.z - f, z.z + f, st); }
/** A highlight-side zone (Light, Highlight, Specular): all above its range. */
float above(float st, vec4 z) { float f = 0.25 + z.w * 4.0; return smoothstep(z.z - f, z.z + f, st); }

/**
 * The HDR palette, as Resolve's: each zone adds its exposure (stops), saturation and colour to everything on its side of
 * its range, easing over its falloff; then the global wheel, temperature, tint, hue, contrast about the pivot (in stops),
 * mid-detail and black offset. In linear light. Deflicker's gain is applied here too.
 */
vec3 zones(vec3 c) {
    bool z = uL[20].y > 0.5;
    bool fl = uL[5].y > 0.5 && uL[5].x != 1.0;
    if (!z && !fl) return c;
    float g = uL[71].x;
    vec3 l = decodeG(c, g);
    if (fl) l *= uL[5].x;
    if (z) {
        vec3 l0 = l;
        float y = max(dot(l, vec3(0.2627, 0.6780, 0.0593)), 1e-6);
        float st = log2(y / 0.18);
        float w0 = below(st, uL[6]), w1 = below(st, uL[7]), w2 = below(st, uL[8]);
        float w3 = above(st, uL[9]), w4 = above(st, uL[10]), w5 = above(st, uL[11]);
        float e = w0 * uL[6].x + w1 * uL[7].x + w2 * uL[8].x + w3 * uL[9].x + w4 * uL[10].x + w5 * uL[11].x + uL[18].x;
        float sat = mix(1.0, uL[6].y, w0) * mix(1.0, uL[7].y, w1) * mix(1.0, uL[8].y, w2) * mix(1.0, uL[9].y, w3) * mix(1.0, uL[10].y, w4) * mix(1.0, uL[11].y, w5) * uL[18].y;
        vec3 col = w0 * uL[12].rgb + w1 * uL[13].rgb + w2 * uL[14].rgb + w3 * uL[15].rgb + w4 * uL[16].rgb + w5 * uL[17].rgb + uL[21].rgb;
        // Contrast about the pivot, in stops.
        float st2 = (st - uL[19].z) * uL[19].y + uL[19].z + e;
        vec3 o = l * exp2(st2 - st) * max(1.0 + col, 0.0);
        // Temperature and tint as white balance.
        o *= vec3(1.0 + 0.22 * uL[18].z + 0.07 * uL[18].w, 1.0 - 0.16 * uL[18].w, 1.0 - 0.22 * uL[18].z + 0.07 * uL[18].w);
        float y2 = dot(o, vec3(0.2627, 0.6780, 0.0593));
        o = y2 + (o - y2) * sat;
        if (uL[19].x != 0.0) {
            vec3 k = vec3(0.57735);
            float a = uL[19].x * 6.2831853; float cs = cos(a);
            o = o * cs + cross(k, o) * sin(a) + k * dot(k, o) * (1.0 - cs);
        }
        o += uL[20].x * 0.02;
        l = mix(l0, o, uL[20].z);
    }
    return encodeG(l, g);
}

/** Zone view: the picture painted by which HDR zone leads at each point. */
vec3 zoneView(vec3 c) {
    vec3 l = decodeG(c, uL[71].x);
    float st = log2(max(dot(l, vec3(0.2627, 0.6780, 0.0593)), 1e-6) / 0.18);
    float w0 = below(st, uL[6]), w1 = below(st, uL[7]) - w0, w2 = below(st, uL[8]) - below(st, uL[7]);
    float w5 = above(st, uL[11]), w4 = above(st, uL[10]) - w5, w3 = above(st, uL[9]) - above(st, uL[10]);
    return clamp(w0 * vec3(0.15, 0.15, 0.6) + w1 * vec3(0.2, 0.4, 0.9) + w2 * vec3(0.3, 0.7, 0.8) +
        w3 * vec3(0.95, 0.85, 0.3) + w4 * vec3(1.0, 0.55, 0.15) + w5 * vec3(1.0, 0.2, 0.2), 0.0, 1.0);
}

vec3 logWheels(vec3 c) {
    if (uL[22].x < 0.5) return c;
    float y = luma(c);
    float ws = 1.0 - smoothstep(uL[22].y - 0.08, uL[22].y + 0.08, y);
    float wh = smoothstep(uL[22].z - 0.08, uL[22].z + 0.08, y);
    float wm = clamp(1.0 - ws - wh, 0.0, 1.0);
    return mix(c, c + uL[23].rgb * ws + uL[24].rgb * wm + uL[25].rgb * wh, uL[22].w);
}

vec3 mixer(vec3 c) {
    if (uL[29].x < 0.5) return c;
    vec3 o = vec3(dot(uL[26].rgb, c), dot(uL[27].rgb, c), dot(uL[28].rgb, c));
    if (uL[29].y > 0.5) o = vec3(o.r);
    else if (uL[29].z > 0.5) o *= luma(c) / max(luma(o), 1e-5);
    return mix(c, o, uL[29].w);
}

/** Colour warper and colour slice: hue turned and saturation scaled by where a colour sits; slice density darkens. */
vec3 warpSlice(vec3 c) {
    if (uL[30].x <= 0.0 && uL[30].y <= 0.0) return c;
    float y = luma(c);
    float cb = (c.b - y) / 1.8556;
    float cr = (c.r - y) / 1.5748;
    float mx = max(c.r, max(c.g, c.b));
    float sat = mx > 1e-5 ? clamp((mx - min(c.r, min(c.g, c.b))) / mx, 0.0, 1.0) : 0.0;
    float hue = fract(atan(cr, cb + 1e-9) / 6.2831853 - 0.2857 + 1.0);
    float rot = 0.0; float gain = 1.0; float dens = 0.0;
    float near = smoothstep(0.0, 0.12, sat);
    if (uL[30].x > 0.0) {
        vec4 w = texture2D(uCurves, vec2((hue * 255.0 + 0.5) / 256.0, (4.0 + clamp(sat * 4.0 - 1.0, 0.0, 3.0) + 0.5) / 8.0));
        rot += (w.r - 0.5) * 0.5 * uL[30].x * near;
        gain *= mix(1.0, w.g * 2.0, uL[30].x * near);
    }
    if (uL[30].y > 0.0) {
        vec4 s = crv(hue, 3.0);
        rot += (s.r - 0.5) * 0.5 * uL[30].y * near;
        gain *= mix(1.0, s.g * 2.0, uL[30].y * near);
        dens = (s.b + uL[30].z) * uL[30].y;
    }
    float a = rot * 6.2831853;
    float cs = cos(a); float sn = sin(a);
    float nb = (cb * cs - cr * sn) * gain;
    float nr = (cb * sn + cr * cs) * gain;
    y *= 1.0 - clamp(dens, 0.0, 1.0) * sat * 0.6;
    return vec3(y + 1.5748 * nr, y - 0.1873 * nb - 0.4681 * nr, y + 1.8556 * nb);
}

/** Sharpen and soften, against a blur of the picture as read in. */
vec3 detail(vec3 c, vec3 src) {
    if (uL[31].w < 0.5) return c;
    vec2 d = uTexel * uL[31].z;
    vec3 rb = raw(vUv + vec2(d.x, 0.0)) + raw(vUv - vec2(d.x, 0.0)) + raw(vUv + vec2(0.0, d.y)) + raw(vUv - vec2(0.0, d.y));
    vec3 b = cst(rb * 0.25);
    c += (luma(src) - luma(b)) * uL[31].x * 2.0;
    return c + (b - src) * uL[31].y;
}

/** Exposure that suits the curve the correction works on: an offset on log curves, a gain on display ones. */
vec3 expose(vec3 c, float stops) {
    float g = uL[71].x;
    if (g > 7.5 && g < 29.5) return c + stops * 0.09;
    return c * exp2(stops / 2.4);
}

/** What the secondary being shown (Highlight) selects here, 0..1. */
float gSecMask = 1.0;

/** Secondaries: each a qualifier (hue, saturation, luminance) and/or a window, with its own adjustments inside. */
vec3 secondaries(vec3 c) {
    for (int i = 0; i < 4; i++) {
        vec4 a1 = uL[38 + i * 7];
        if (a1.z < 0.5) continue;
        vec4 q0 = uL[32 + i * 7]; vec4 q1 = uL[33 + i * 7]; vec4 q2 = uL[34 + i * 7];
        vec4 w0 = uL[35 + i * 7]; vec4 w1 = uL[36 + i * 7]; vec4 a0 = uL[37 + i * 7];
        float m = 1.0;
        if (q0.w > 0.5) {
            float y = luma(c);
            float cb = (c.b - y) / 1.8556;
            float cr = (c.r - y) / 1.5748;
            float mx = max(c.r, max(c.g, c.b));
            float sat = mx > 1e-5 ? clamp((mx - min(c.r, min(c.g, c.b))) / mx, 0.0, 1.0) : 0.0;
            float hue = fract(atan(cr, cb + 1e-9) / 6.2831853 - 0.2857 + 1.0);
            float sf = max(q0.z, 1e-3);
            if (q2.x > 0.5) { float dh = abs(hue - q0.x); dh = min(dh, 1.0 - dh); m *= 1.0 - smoothstep(q0.y, q0.y + sf, dh); m *= smoothstep(0.02, 0.08, sat); }
            if (q2.y > 0.5) m *= smoothstep(q1.x - sf, q1.x, sat) * (1.0 - smoothstep(q1.y, q1.y + sf, sat));
            if (q2.z > 0.5) { float l = clamp(y, 0.0, 1.0); m *= smoothstep(q1.z - sf, q1.z, l) * (1.0 - smoothstep(q1.w, q1.w + sf, l)); }
            if (q2.w > 0.5) m = 1.0 - m;
        }
        if (w0.x > 0.5) {
            vec2 p = (pic(vUv) - w0.yz) * vec2(aspect(), 1.0);
            float ca = cos(-w1.z); float sa = sin(-w1.z);
            p = vec2(p.x * ca - p.y * sa, p.x * sa + p.y * ca);
            float soft = max(w1.w, 1e-3);
            float mw;
            if (w0.x < 1.5) mw = 1.0 - smoothstep(1.0 - soft, 1.0 + soft, length(p / max(w1.xy, vec2(1e-3))));
            else if (w0.x < 2.5) mw = smoothstep(-soft, soft, -p.y);
            else mw = 1.0 - smoothstep(1.0 - soft, 1.0 + soft, max(abs(p.x) / max(w1.x, 1e-3), abs(p.y) / max(w1.y, 1e-3)));
            if (w0.w > 0.5) mw = 1.0 - mw;
            m *= mw;
        }
        if (float(i) + 1.0 == uL[71].w) gSecMask = m;
        if (m <= 0.0) continue;
        vec3 o = expose(c, a0.x);
        o = (o - 0.435) * a0.y + 0.435;
        o *= vec3(1.0 + 0.22 * a1.x + 0.07 * a1.y, 1.0 - 0.16 * a1.y, 1.0 - 0.22 * a1.x + 0.07 * a1.y);
        float y = luma(o);
        o = y + (o - y) * a0.z;
        if (a0.w != 0.0) {
            float cb = (o.b - y) / 1.8556; float cr = (o.r - y) / 1.5748;
            float an = a0.w * 6.2831853; float cs = cos(an); float sn = sin(an);
            float nb = cb * cs - cr * sn; float nr = cb * sn + cr * cs;
            o = vec3(y + 1.5748 * nr, y - 0.1873 * nb - 0.4681 * nr, y + 1.8556 * nb);
        }
        c = mix(c, o, m * a1.w);
    }
    return c;
}

// ---- look (modelled on Resolve's Film Look Creator) -----------------------------------------------
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vn2(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
/** The blurred corrected picture at [radius] (in pixels of the full picture), from the glow copy's mipmaps. */
vec3 glowAt(float radius) {
    vec2 uv = gl_FragCoord.xy / uFb;
    float lod = log2(max(radius / 4.0, 1.0));
    vec2 o = radius / uFb * 0.5;
    return (texture2D(uGlow, uv + vec2(o.x, o.y), lod).rgb + texture2D(uGlow, uv + vec2(-o.x, o.y), lod).rgb +
        texture2D(uGlow, uv + vec2(o.x, -o.y), lod).rgb + texture2D(uGlow, uv - o, lod).rgb) * 0.25;
}
vec3 rotHue(vec3 c, float turns) {
    vec3 k = vec3(0.57735);
    float a = turns * 6.2831853; float cs = cos(a);
    return c * cs + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - cs);
}

/** The film stage's own space: display-like (gamma 2.4 over linear light, 1 = SDR white, HDR above), in any project. */
vec3 toFilm(vec3 c) { float g = uL[71].x; return (g > 1.5 && g < 2.5) ? c : pw(max(decodeG(c, g), 0.0), 1.0 / 2.4); }
vec3 fromFilm(vec3 d) { float g = uL[71].x; return (g > 1.5 && g < 2.5) ? d : encodeG(pw(max(d, 0.0), 2.4), g); }

vec3 film(vec3 cw) {
    if (uL[68].x < 0.5) return cw;
    vec3 c = toFilm(cw);
    // Look: exposure and flicker, subtractive saturation, richness, bleach bypass, split tone, fade, highlight roll-off.
    float flick = 0.0;
    if (uL[67].z > 0.0) flick = (vnoise(uTime * uL[67].w * 8.0) - 0.5) * uL[67].z * 0.5;
    if (uL[60].x != 0.0 || flick != 0.0) c *= exp2((uL[60].x + flick) / 2.4);
    float y = luma(c);
    float mx = max(c.r, max(c.g, c.b)); float mn = min(c.r, min(c.g, c.b));
    float sat = mx > 1e-5 ? clamp((mx - mn) / mx, 0.0, 1.0) : 0.0;
    if (uL[60].y != 0.0) {
        // Subtractive: more colour is more dye, so saturated colour also gets denser (darker), as on film.
        c = y + (c - y) * (1.0 + uL[60].y);
        c *= 1.0 - uL[60].y * sat * 0.25;
    }
    if (uL[60].z != 0.0) { vec3 k = clamp(c, 0.0, 1.0); c = mix(c, k * k * (3.0 - 2.0 * k) + (c - k), uL[60].z * sat); }
    if (uL[60].w > 0.0) {
        y = luma(c);
        // The overlay works on 0..1; light above SDR white (HDR) is set aside and added back, so it stays bright.
        vec3 k = clamp(c, 0.0, 1.0);
        float yk = clamp(y, 0.0, 1.0);
        vec3 ov = mix(2.0 * k * yk, 1.0 - 2.0 * (1.0 - k) * (1.0 - yk), step(0.5, yk));
        c = mix(c, mix(ov, vec3(yk), 0.45) + (c - k), uL[60].w);
    }
    if (uL[61].x > 0.0) {
        y = clamp(luma(c), 0.0, 1.0);
        // Warm highlights and the opposite (cool) shadows, about the pivot; colour only, brightness kept.
        vec3 warm = rotHue(vec3(0.06, 0.0, -0.06), uL[61].y);
        warm -= luma(warm);
        float w = smoothstep(uL[61].z - 0.3, uL[61].z + 0.3, y);
        c += (warm * w - warm * (1.0 - w) * 0.8) * uL[61].x;
    }
    // Fade: blacks lifted to a milky grey, as a print that has aged.
    if (uL[61].w > 0.0) c = c * (1.0 - uL[61].w * 0.1) + uL[61].w * 0.07;
    if (uL[68].z > 0.0) {
        float k = 1.0 - uL[68].z * 0.35;
        c = vec3(shoulder(c.r, k, 1.0, 1.0 + uL[68].z), shoulder(c.g, k, 1.0, 1.0 + uL[68].z), shoulder(c.b, k, 1.0, 1.0 + uL[68].z));
    }
    // Halation: light scattered back through the film base, a red-orange glow round the brightest parts.
    if (uL[62].x > 0.0 && uL[68].w > 0.5) {
        // The scattered light: highlights spread wide, less what is already bright here, so it rings their edges.
        vec3 gl = toFilm(glowAt(4.0 + uL[62].y * 90.0));
        float k = max(1.0 - uL[62].z, 0.05);
        float spread = max(luma(gl) - uL[62].z, 0.0) / k;
        float core = max(luma(c) - uL[62].z, 0.0) / k;
        float halo = max(spread - core * 0.85, 0.0) + core * 0.08;
        vec3 tint = rotHue(vec3(1.0, 0.22, 0.05), uL[62].w);
        tint = mix(vec3(luma(tint)), tint, uL[63].x);
        c += tint * halo * uL[62].x * 0.8;
    }
    // Bloom: a soft wide glow from highlights.
    if (uL[63].y > 0.0 && uL[68].w > 0.5) {
        vec3 gl = toFilm(glowAt(10.0 + uL[63].z * 160.0));
        vec3 h = max(gl - uL[63].w, 0.0) / max(1.0 - uL[63].w, 0.05);
        h *= vec3(1.0 + uL[71].z * 0.2, 1.0, 1.0 - uL[71].z * 0.2);
        h = clamp(h * uL[63].y * 0.6, 0.0, 1.0);
        c = c + h * (1.0 - clamp(c, 0.0, 1.0));
    }
    // Vignette: darkening (or lightening) towards the edges, round or the frame's shape.
    if (uL[66].x != 0.0) {
        vec2 d = (pic(vUv) - 0.5) * 2.0;
        d.x *= mix(1.0, aspect(), uL[66].z);
        float r = mix(max(abs(d.x), abs(d.y)), length(d) / mix(1.0, aspect(), uL[66].z) * 0.85, uL[66].z);
        float v = smoothstep(uL[66].y, uL[66].y + 0.05 + uL[66].w * 1.2, r);
        c *= 1.0 - uL[66].x * v;
    }
    // Grain: two octaves of noise at the stock's size, softened, in colour or mono, by tonal range; still or moving.
    if (uL[64].x > 0.0) {
        vec2 px = vUv / uTexel / max(uL[64].y, 0.3);
        float t = uL[65].w > 0.5 ? floor(uTime * 24.0) * 7.31 : 0.0;
        vec3 n = vec3(vn2(px + t), vn2(px + t + 19.1), vn2(px + t + 47.3)) - 0.5;
        vec3 n2 = vec3(hash(px + t), hash(px + t + 3.3), hash(px + t + 9.9)) - 0.5;
        n = mix(n2, n, uL[64].z);
        float m = (n.r + n.g + n.b) / 3.0;
        n = mix(vec3(m), n, uL[64].w);
        y = clamp(luma(c), 0.0, 1.0);
        float ws = 1.0 - smoothstep(0.0, 0.35, y);
        float wh = smoothstep(0.6, 1.0, y);
        float wm = clamp(1.0 - ws - wh, 0.0, 1.0);
        float amt = ws * uL[65].x + wm * uL[65].y + wh * uL[65].z;
        c += n * uL[64].x * 0.09 * amt;
    }
    return fromFilm(c);
}

// ---- output ---------------------------------------------------------------------------------------
float softTop(float v) { return v > 0.9 ? 0.9 + 0.1 * (1.0 - exp(-(v - 0.9) / 0.1)) : v; }
vec3 limit(vec3 o) {
    if (uL[69].x < 0.5) return o;
    if (uL[69].z < 1.0) {
        float y = luma(o);
        vec3 ch = o - y;
        float m = length(ch);
        float lim = uL[69].z * 0.6;
        if (m > lim) o = y + ch * lim / m;
    }
    if (uL[69].y > 0.5) o = vec3(softTop(o.r), softTop(o.g), softTop(o.b));
    return o;
}
// Into the space the picture is shown or written in.
vec3 fin(vec3 c) { return clamp(limit(xform(c, uXf[2], uXf[3], uXfY[1], uFinM)), 0.0, 1.0); }

void main() {
    vec3 src = rawDetail(cst(inputAt(vUv)));
    src *= stabKeep;
    vec3 c = src;
    bool before = uL[70].z >= 0.0 && pic(vUv).x > uL[70].z;
    if (!before) {
        // Correction, mixed by its strength.
        vec3 k = c;
        if (uOn > 0.5) k = primaries(k);
        k = zones(k);
        k = logWheels(k);
        k = mixer(k);
        k = warpSlice(k);
        if (uCurveOn > 0.5) k = applyCurves(k);
        k = detail(k, src);
        c = mix(c, k, uL[70].x);
        c = secondaries(c);
        // The glow pass only needs the corrected picture.
        if (uL[70].w > 0.5) { gl_FragColor = vec4(c, 1.0); return; }
        if (uL[20].w > 0.5) { gl_FragColor = vec4(zoneView(c), 1.0); return; }
        // The look: LUTs and film, blended as one by the look mix.
        vec3 pre = c;
        if (uL[70].y > 0.5) {
            for (int i = 0; i < 8; i++) {
                if (float(i) >= uLutN) break;
                c = mix(c, lutAt(c, float(i)), uLutS[i]);
            }
        }
        c = film(c);
        c = mix(pre, c, uL[68].y);
    }
    vec3 o = fin(c);
    // Highlight: what the secondary selects in its colours, the rest a flat grey, as in Resolve.
    if (uL[71].w > 0.5) o = mix(vec3(0.16 + luma(o) * 0.2), o, gSecMask);
    gl_FragColor = vec4(o, 1.0);
}
"""
}

package com.graden.editor

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * One colour wheel. [u] and [v] are where its puck sits, in a unit disc (blue–yellow across, red–cyan up);
 * [y] is its master ring, which moves all channels together.
 */
data class Wheel(val u: Float = 0f, val v: Float = 0f, val y: Float = 0f) {
    val isZero: Boolean get() = u == 0f && v == 0f && y == 0f

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
) {
    val isIdentity: Boolean get() = this == Grade()

    /** The values the shader reads, in the order its uniforms expect. */
    fun uniforms(): FloatArray {
        val l = lift.rgb(LIFT_REACH)
        val g = gamma.rgb(GAMMA_REACH)
        val n = gain.rgb(GAIN_REACH)
        val o = offset.rgb(OFFSET_REACH)
        return floatArrayOf(
            l[0], l[1], l[2], lift.y,
            g[0], g[1], g[2], gamma.y,
            n[0], n[1], n[2], gain.y,
            o[0], o[1], o[2], offset.y,
            temp / 2000f, tint / 100f, contrast, pivot,
            midDetail / 100f, colorBoost / 100f, shadows / 100f, highlights / 100f,
            saturation / 50f, (hue - 50f) / 50f * Math.PI.toFloat(), lumMix / 100f, 0f,
        )
    }

    fun toJson(): JSONObject {
        fun w(x: Wheel) = JSONArray().put(x.u.toDouble()).put(x.v.toDouble()).put(x.y.toDouble())
        return JSONObject().put("l", w(lift)).put("g", w(gamma)).put("n", w(gain)).put("o", w(offset))
            .put("temp", temp.toDouble()).put("tint", tint.toDouble()).put("con", contrast.toDouble()).put("piv", pivot.toDouble())
            .put("md", midDetail.toDouble()).put("cb", colorBoost.toDouble()).put("sh", shadows.toDouble()).put("hl", highlights.toDouble())
            .put("sat", saturation.toDouble()).put("hue", hue.toDouble()).put("lm", lumMix.toDouble())
    }

    companion object {
        /** Shift at the edge of each wheel's disc, as a fraction of full range. */
        const val LIFT_REACH = 0.18f
        const val GAMMA_REACH = 0.18f
        const val GAIN_REACH = 0.35f
        const val OFFSET_REACH = 0.18f

        fun fromJson(o: JSONObject?): Grade {
            if (o == null) return Grade()
            fun w(k: String): Wheel = o.optJSONArray(k)?.let { Wheel(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), it.optDouble(2).toFloat()) } ?: Wheel()
            fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
            return Grade(
                w("l"), w("g"), w("n"), w("o"),
                f("temp", 0f), f("tint", 0f), f("con", 1f), f("piv", 0.435f), f("md", 0f), f("cb", 0f),
                f("sh", 0f), f("hl", 0f), f("sat", 50f), f("hue", 50f), f("lm", 100f),
            )
        }
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
     * [w] with one channel's readout (0 Y, 1 R, 2 G, 3 B) set to [value] and the others kept: Y moves all three together;
     * a colour channel is solved back into the puck's position and the master, so the wheel shows exactly what was set.
     */
    fun withChannel(w: Wheel, ch: Int, value: Float): Wheel {
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
        return Wheel(u, v, y.coerceIn(-1f, 1f))
    }

    /** Y, R, G and B as Resolve shows them under the wheel: Gain sits at 1, Offset at 25, the others at 0. */
    fun readout(w: Wheel): FloatArray {
        val c = w.rgb(reach)
        return when (this) {
            GAIN -> floatArrayOf(1 + w.y, 1 + w.y + c[0], 1 + w.y + c[1], 1 + w.y + c[2])
            OFFSET -> floatArrayOf(25 + w.y * 100, 25 + (w.y + c[0]) * 100, 25 + (w.y + c[1]) * 100, 25 + (w.y + c[2]) * 100)
            else -> floatArrayOf(w.y, w.y + c[0], w.y + c[1], w.y + c[2])
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

    const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vUv;
uniform samplerExternalOES uSrc;
uniform mat4 uTex;
uniform vec4 uG[7];
uniform float uOn;
uniform vec2 uTexel;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 at(vec2 p) { return texture2D(uSrc, (uTex * vec4(p, 0.0, 1.0)).xy).rgb; }
float luma(vec3 c) { return dot(c, LUMA); }

// Lift, gamma and gain for one set of values: lift raises the blacks and leaves white, gain scales from black,
// gamma bends the middle.
vec3 lgg(vec3 c, vec3 lift, vec3 gam, vec3 gain) {
    c = c + lift * (1.0 - c);
    c = c * gain;
    vec3 e = exp2(-gam * 2.2);
    return sign(c) * pow(abs(c), e);
}

void main() {
    vec3 src = at(vUv);
    if (uOn < 0.5) { gl_FragColor = vec4(src, 1.0); return; }
    vec4 L = uG[0]; vec4 G = uG[1]; vec4 N = uG[2]; vec4 O = uG[3];
    vec4 P = uG[4]; vec4 Q = uG[5]; vec4 R = uG[6];
    vec3 c = src;

    // Offset moves everything, then white balance.
    c += O.rgb + O.a;
    c *= vec3(1.0 + 0.22 * P.x + 0.07 * P.y, 1.0 - 0.16 * P.y, 1.0 - 0.22 * P.x + 0.07 * P.y);

    // Wheels. The colour of each wheel always works per channel; its master ring works per channel too, or on
    // luma only (which keeps saturation), as Lum Mix says.
    vec3 perChannel = lgg(c, L.rgb + L.a, G.rgb + G.a, 1.0 + N.rgb + N.a);
    vec3 colourOnly = lgg(c, L.rgb, G.rgb, 1.0 + N.rgb);
    float y0 = luma(colourOnly);
    float y1 = luma(lgg(vec3(y0), vec3(L.a + 0.0), vec3(G.a), vec3(1.0 + N.a)));
    vec3 lumaOnly = colourOnly + (y1 - y0);
    c = mix(perChannel, lumaOnly, R.z);

    // Contrast about the pivot.
    c = (c - P.w) * P.z + P.w;

    // Shadows and highlights, weighted towards their end of the range.
    float y = luma(c);
    float ws = 1.0 - smoothstep(0.0, 0.55, y);
    float wh = smoothstep(0.45, 1.0, y);
    c += Q.z * 0.35 * ws * ws + Q.w * 0.35 * wh * wh;

    // Mid/Detail: the difference from a soft blur, added back in the mid-tones.
    if (abs(Q.x) > 0.0001) {
        vec2 d = uTexel * 6.0;
        float b = 0.0;
        b += luma(at(vUv + vec2(d.x, 0.0))) + luma(at(vUv - vec2(d.x, 0.0)));
        b += luma(at(vUv + vec2(0.0, d.y))) + luma(at(vUv - vec2(0.0, d.y)));
        b += luma(at(vUv + d * 0.7)) + luma(at(vUv - d * 0.7));
        b += luma(at(vUv + vec2(d.x, -d.y) * 0.7)) + luma(at(vUv + vec2(-d.x, d.y) * 0.7));
        b *= 0.125;
        float ys = luma(src);
        float mid = 1.0 - pow(abs(2.0 * ys - 1.0), 2.0);
        c += (ys - b) * Q.x * 2.5 * mid;
    }

    // Colour Boost (more where colour is weak), Saturation, then Hue turned around the grey axis.
    y = luma(c);
    float s = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    c = y + (c - y) * (1.0 + Q.y * (1.0 - clamp(s * 2.0, 0.0, 1.0)));
    c = y + (c - y) * R.x;
    if (abs(R.y) > 0.0001) {
        float cb = (c.b - y) / 1.8556;
        float cr = (c.r - y) / 1.5748;
        float cs = cos(R.y); float sn = sin(R.y);
        float nb = cb * cs - cr * sn;
        float nr = cb * sn + cr * cs;
        c = vec3(y + 1.5748 * nr, y - 0.1873 * nb - 0.4681 * nr, y + 1.8556 * nb);
    }
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
}

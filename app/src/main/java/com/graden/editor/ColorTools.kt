package com.graden.editor

import android.opengl.GLES20
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point on a curve: [x] in, [y] out, both 0..1. */
data class CurvePt(val x: Float, val y: Float)

/**
 * The curves of a grade, as on Resolve's Curves palette. The custom curves ([y], [r], [g], [b]) map input to output
 * and start as the straight line. The others are flat at 0.5 (no change) and start with no points: Hue vs Hue turns
 * the hue, Hue vs Sat and Lum vs Sat and Sat vs Sat scale saturation (0.5 = ×1, 1 = ×2), Hue vs Lum lifts or lowers
 * brightness.
 */
data class Curves(
    val y: List<CurvePt> = LINE,
    val r: List<CurvePt> = LINE,
    val g: List<CurvePt> = LINE,
    val b: List<CurvePt> = LINE,
    val hueHue: List<CurvePt> = emptyList(),
    val hueSat: List<CurvePt> = emptyList(),
    val hueLum: List<CurvePt> = emptyList(),
    val lumSat: List<CurvePt> = emptyList(),
    val satSat: List<CurvePt> = emptyList(),
) {
    val isIdentity: Boolean get() = this == Curves()

    fun toJson(): JSONObject {
        fun a(l: List<CurvePt>) = JSONArray().apply { l.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } }
        return JSONObject().put("y", a(y)).put("r", a(r)).put("g", a(g)).put("b", a(b))
            .put("hh", a(hueHue)).put("hs", a(hueSat)).put("hl", a(hueLum)).put("ls", a(lumSat)).put("ss", a(satSat))
    }

    companion object {
        val LINE = listOf(CurvePt(0f, 0f), CurvePt(1f, 1f))

        /** Where red sits on the hue curves' axis is 0; the shader uses the same angle. */
        const val HUE0 = 0.2857f

        fun fromJson(o: JSONObject?): Curves {
            if (o == null) return Curves()
            fun a(k: String, d: List<CurvePt>): List<CurvePt> {
                val j = o.optJSONArray(k) ?: return d
                return (0 until j.length() / 2).map { CurvePt(j.optDouble(it * 2).toFloat(), j.optDouble(it * 2 + 1).toFloat()) }
            }
            return Curves(a("y", LINE), a("r", LINE), a("g", LINE), a("b", LINE), a("hh", emptyList()), a("hs", emptyList()), a("hl", emptyList()), a("ls", emptyList()), a("ss", emptyList()))
        }

        /**
         * The curve through [pts] at [x]: a monotone cubic (Fritsch–Carlson), so it never overshoots between points.
         * [periodic] curves (hue) wrap around; an empty list is flat at [flat].
         */
        fun eval(pts: List<CurvePt>, x: Float, periodic: Boolean = false, flat: Float = 0.5f): Float {
            if (pts.isEmpty()) return flat
            if (pts.size == 1) return pts[0].y
            val p = if (!periodic) pts else {
                // Copies a turn either side, so the curve joins up across 0 and 1.
                pts.map { CurvePt(it.x - 1f, it.y) } + pts + pts.map { CurvePt(it.x + 1f, it.y) }
            }
            if (x <= p.first().x) return p.first().y
            if (x >= p.last().x) return p.last().y
            val n = p.size
            val d = FloatArray(n - 1) { (p[it + 1].y - p[it].y) / max(p[it + 1].x - p[it].x, 1e-5f) }
            val m = FloatArray(n) { i ->
                when (i) {
                    0 -> d[0]
                    n - 1 -> d[n - 2]
                    else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
                }
            }
            for (i in 0 until n - 1) {
                if (abs(d[i]) < 1e-6f) { m[i] = 0f; m[i + 1] = 0f; continue }
                val a = m[i] / d[i]; val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) { val t = 3f / kotlin.math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
            }
            var k = 0
            while (k < n - 2 && x > p[k + 1].x) k++
            val h = max(p[k + 1].x - p[k].x, 1e-5f)
            val t = (x - p[k].x) / h
            val t2 = t * t; val t3 = t2 * t
            return (2 * t3 - 3 * t2 + 1) * p[k].y + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * p[k + 1].y + (t3 - t2) * h * m[k + 1]
        }

        /**
         * The curves as a 256×4 RGBA texture: row 0 the custom curves (Y, R, G, B) as offsets from the straight line,
         * row 1 Hue vs Hue, Hue vs Sat, Hue vs Lum, row 2 Lum vs Sat, Sat vs Sat. Offsets are stored as 0.5 + d / 2.
         */
        fun texture(c: Curves, slice: SliceLayer = SliceLayer(), warper: WarperLayer = WarperLayer()): ByteBuffer {
            val buf = ByteBuffer.allocateDirect(256 * 8 * 4).order(ByteOrder.nativeOrder())
            val sv = FloatArray(3)
            fun b(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            fun off(pts: List<CurvePt>, x: Float) = b(0.5f + (eval(pts, x, flat = x) - x) / 2f)
            for (row in 0 until 8) for (i in 0 until 256) {
                val x = i / 255f
                when (row) {
                    0 -> { buf.put(off(c.y, x)); buf.put(off(c.r, x)); buf.put(off(c.g, x)); buf.put(off(c.b, x)) }
                    1 -> { buf.put(b(eval(c.hueHue, x, true))); buf.put(b(eval(c.hueSat, x, true))); buf.put(b(eval(c.hueLum, x, true))); buf.put(127) }
                    2 -> { buf.put(b(eval(c.lumSat, x))); buf.put(b(eval(c.satSat, x))); buf.put(127); buf.put(127) }
                    3 -> {
                        // Colour slice: hue shift (0.5 = none, ±0.5 turn), saturation (×2 at 1), density (0..1).
                        LayerPack.sliceRow(slice, x, sv)
                        buf.put(b(0.5f + sv[0] * 2f)); buf.put(b(sv[1] / 2f)); buf.put(b(sv[2])); buf.put(127)
                    }
                    else -> {
                        // Colour warper, one row per saturation ring: hue shift (±0.25 turn) and saturation factor.
                        val (dh, ds) = warper.at(x, (row - 4).toFloat())
                        buf.put(b(0.5f + dh * 2f)); buf.put(b(ds / 2f)); buf.put(127); buf.put(127)
                    }
                }
            }
            buf.position(0)
            return buf
        }
    }
}

/**
 * Layer 1 of a clip: its colour space transform. Unset fields follow the project: the input is what the clip is shot
 * in (its "Shot in" setting, else read from the file), the output is the working space.
 */
data class Cst(
    val on: Boolean = true,
    val inGamut: String? = null,
    val inGamma: String? = null,
    val outGamut: String? = null,
    val outGamma: String? = null,
    val tone: String = "DaVinci",
    val gamutMap: String = "Saturation Compression",
    /** Brightest input and output in nits for tone mapping; null takes them from the curves. */
    val inNits: Int? = null,
    val outNits: Int? = null,
    /** Data levels of the clip: 0 as the file says, 1 video levels (64–940), 2 full range. */
    val levels: Int = 0,
) {
    val custom: Boolean get() = inGamut != null || inGamma != null || outGamut != null || outGamma != null

    fun toJson(): JSONObject = JSONObject().put("on", on).putOpt("ig", inGamut).putOpt("iy", inGamma).putOpt("og", outGamut).putOpt("oy", outGamma)
        .put("tm", tone).put("gm", gamutMap).putOpt("in", inNits).putOpt("on2", outNits).put("lv", levels)

    companion object {
        fun fromJson(o: JSONObject?): Cst = if (o == null) Cst() else Cst(
            o.optBoolean("on", true), o.optString("ig").ifEmpty { null }, o.optString("iy").ifEmpty { null },
            o.optString("og").ifEmpty { null }, o.optString("oy").ifEmpty { null }, o.optString("tm", "DaVinci"), o.optString("gm", "Saturation Compression"),
            if (o.has("in")) o.optInt("in") else null, if (o.has("on2")) o.optInt("on2") else null, o.optInt("lv"),
        )
    }
}

/**
 * One LUT in layer 3. [to] is the colour space the LUT turns its input into (a "S-Log3 to Rec.709" LUT: Rec.709), so
 * what follows knows what it is getting; null keeps the space it was given (a creative look).
 */
data class LutLayer(val file: String, val name: String, val strength: Float = 1f, val on: Boolean = true, val to: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("f", file).put("n", name).put("s", strength.toDouble()).put("on", on).putOpt("to", to)

    companion object {
        fun fromJson(o: JSONObject?): LutLayer? = o?.let { LutLayer(it.optString("f"), it.optString("n"), it.optDouble("s", 1.0).toFloat(), it.optBoolean("on", true), it.optString("to").ifEmpty { null }) }
            ?.takeIf { it.file.isNotEmpty() }

        /** The single LUT of earlier versions. */
        fun legacy(o: JSONObject?): List<LutLayer> = listOfNotNull(o?.let {
            LutLayer(it.optString("f"), it.optString("n"), it.optDouble("s", 1.0).toFloat(), true, if (it.optBoolean("c")) ColorSpaces.REC709.name else null)
        }?.takeIf { it.file.isNotEmpty() })

        /** What a LUT converts to, guessed from its name: "… to Rec.709", "…709…" after a log name, and the like. */
        fun guessTo(name: String): String? {
            val n = name.lowercase()
            val conv = listOf("log", "to ", "_to_", "-to-", "2709", "to709", "lc709", "rec709").any { it in n } &&
                listOf("log", "gamut", "raw", "film", "cine", "protune", "ipp2", "arri", "sony", "canon", "dji", "vlog", "slog", "clog").any { it in n }
            return when {
                !conv -> null
                "2020" in n && ("pq" in n || "st2084" in n) -> ColorSpaces.PQ.name
                "hlg" in n -> ColorSpaces.HLG.name
                "2020" in n -> ColorSpaces.REC2020.name
                else -> ColorSpaces.REC709.name
            }
        }
    }
}

/**
 * The whole colour path of one picture, as the grade shader takes it: layer 1 (its CST, from what it was shot in) and,
 * after the correction and LUTs, the step into the space it is shown or written in. Worked out per clip here, so the
 * preview and export draw the same thing.
 */
object ColorPipe {
    /** Two packed transforms, then a RAW clip's raw stage (see [RawColor.block]; all zero for other clips). */
    const val SIZE = ColorSpaces.XF_SIZE * 2 + RawColor.BLOCK

    /**
     * The block for clip [c] (of asset [a]) drawn into [dest] with [destNits] as the brightest it may go.
     * [input] is what the picture really is (an SDR proxy of an HDR clip is Rec.709).
     */
    fun block(c: Clip, input: ColorSpaces.Space, meta: ProjectMeta, dest: ColorSpaces.Space, destNits: Float, bypass: Boolean = false, raw: FloatArray? = null,
              fullRange: Boolean = false): FloatArray {
        val out = FloatArray(SIZE)
        raw?.copyInto(out, ColorSpaces.XF_SIZE * 2)
        val g = c.grade
        val working = ColorSpaces.working(meta.workingSpace)
        val cst = g.cst
        var gamut = input.gamut
        var gamma = input.gamma
        if (cst.on) {
            val fg = cst.inGamut?.let(ColorSpaces::gamut) ?: input.gamut
            val fy = cst.inGamma?.let(ColorSpaces::gamma) ?: input.gamma
            val tg = cst.outGamut?.let(ColorSpaces::gamut) ?: working.gamut
            val ty = cst.outGamma?.let(ColorSpaces::gamma) ?: working.gamma
            // Data levels, as Resolve's clip setting: Auto takes the picture as decoded (video levels already stretched
            // to 0..1, and the log curve read from that, as Resolve does); Full on a video-levels file, or Video on a
            // full-range one, undoes the decoder's assumption. A RAW clip's own decode is always full range.
            val lv = if (raw != null) 0 else when (cst.levels) { 2 -> if (fullRange) 0 else 1; 1 -> if (fullRange) -1 else 0; else -> 0 }
            // A RAW clip keeps the sensor's highlights above 1.0: they are rolled off as Resolve does with Max Input
            // 10,000 nits (with Auto, Resolve takes linear light as ending at 1.0 and clips there).
            val inN = cst.inNits?.toFloat() ?: if (raw != null && fy.code == 0) 10000f else null
            ColorSpaces.Xf(fg, fy, tg, ty, cst.tone, cst.gamutMap, inN,
                cst.outNits?.toFloat() ?: if (ty.hdr) meta.masterNits.toFloat() else ColorSpaces.peakNits(ty), levels = lv).pack(out, 0)
        } else identity(out, 0)
        // As in Resolve: after the colour space transform (or with it off, untouched) the picture is taken to be in the
        // timeline's working space, so the output is converted from that. A CST into LogC on a Rec.709 timeline then
        // looks like LogC, as it should, instead of being converted back again.
        gamut = working.gamut
        gamma = working.gamma
        // A LUT that converts hands on its own space.
        if (!bypass) g.luts.lastOrNull { it.on && it.to != null }?.to?.let(ColorSpaces::byName)?.let { gamut = it.gamut; gamma = it.gamma }
        ColorSpaces.Xf(gamut, gamma, dest.gamut, dest.gamma, meta.toneMap, meta.gamutMap, null, destNits).pack(out, ColorSpaces.XF_SIZE)
        return out
    }

    /** The curve the correction works on for clip [c] shot in [input]: the CST's output (the working space by default). */
    fun workGamma(c: Clip, input: ColorSpaces.Space, meta: ProjectMeta): Int {
        val cst = c.grade.cst
        if (!cst.on) return input.gamma.code
        return (cst.outGamma?.let(ColorSpaces::gamma) ?: ColorSpaces.working(meta.workingSpace).gamma).code
    }

    /** A block that only takes Rec.709 into [dest] (titles, solid colours, pictures). */
    fun plain(meta: ProjectMeta, dest: ColorSpaces.Space, destNits: Float): FloatArray {
        val out = FloatArray(SIZE)
        identity(out, 0)
        ColorSpaces.Xf(ColorSpaces.G709, ColorSpaces.GAMMA24, dest.gamut, dest.gamma, meta.toneMap, meta.gamutMap, null, destNits).pack(out, ColorSpaces.XF_SIZE)
        return out
    }

    private fun identity(out: FloatArray, at: Int) {
        out.fill(0f, at, at + ColorSpaces.XF_SIZE)
        out[at + 8] = 1f; out[at + 12] = 1f; out[at + 16] = 1f
    }

    /** Nothing to convert anywhere. */
    fun isOff(b: FloatArray) = b[0] < 0.5f && b[ColorSpaces.XF_SIZE] < 0.5f

    /** Sets the block's uniforms in [prog]. */
    fun set(prog: Int, b: FloatArray) {
        fun u(n: String) = GLES20.glGetUniformLocation(prog, n)
        val x = ColorSpaces.XF_SIZE
        val v = floatArrayOf(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[x], b[x + 1], b[x + 2], b[x + 3], b[x + 4], b[x + 5], b[x + 6], b[x + 7])
        GLES20.glUniform4fv(u("uXf"), 4, v, 0)
        GLES20.glUniformMatrix3fv(u("uCstM"), 1, false, b, 8)
        GLES20.glUniformMatrix3fv(u("uFinM"), 1, false, b, x + 8)
        GLES20.glUniform3fv(u("uXfY"), 2, floatArrayOf(b[17], b[18], b[19], b[x + 17], b[x + 18], b[x + 19]), 0)
        if (b.size >= SIZE) GLES20.glUniform4fv(u("uRaw"), 8, b, x * 2)
        SatCompTable.bind(prog)
    }

    const val GLSL = """
uniform vec4 uXf[4];
uniform mat3 uCstM;
uniform mat3 uFinM;
// Luminance weights of each transform's source gamut.
uniform vec3 uXfY[2];
// The raw stage of a RAW clip (see RawColor.block): its developed camera colour (log, not white balanced) to scene
// linear Rec.2020. Rows of the matrix; the white-balance gains and highlight recovery; (on, exposure, saturation,
// black offset); (contrast, midpoint, highlights, shadows); (highlight roll-off, low roll-off, colour boost, the gamma
// it is decoded into or -1 for linear); (sharpness, midtone detail, white level).
uniform vec4 uRaw[8];
vec3 rawDev(vec3 v) {
    if (uRaw[4].x < 0.5) return v;
    vec3 cam = (exp2(v * 14.0) - 1.0) / 4096.0;
    vec3 gain = uRaw[3].xyz;
    vec3 wb = cam * gain;
    // Highlights. The white level is where the sensor clips (1.0: the recording's own). Past it a channel's value is
    // unknown, and white balance would turn the clipped part magenta or cyan. Recovery 0 clips to the white no
    // channel can exceed (the clip of the channel with the smallest gain). Recovery 1 rebuilds: each clipped channel
    // rises towards the brightest channel, gradually, by how far that one has gone past the safe white, so colour and
    // detail stay where only some channels clipped, a fully clipped area becomes white as bright as the sensor
    // allows (about a stop more), a light that is really red or blue stays so, and brightness only ever rises with
    // the light (no bands). In between, a blend.
    float wl = clamp(uRaw[7].z > 0.0 ? uRaw[7].z : 1.0, 0.25, 2.0);
    float cmax = max(cam.r, max(cam.g, cam.b)) / wl;
    if (cmax > 0.88) {
        float Ws = wl * min(gain.r, min(gain.g, gain.b));
        float Rm = wl * max(gain.r, max(gain.g, gain.b));
        float m = max(wb.r, max(wb.g, wb.b));
        vec3 t = smoothstep(vec3(0.88), vec3(1.0), cam / wl);
        float d = smoothstep(Ws, max(Rm, Ws * 1.01), m);
        vec3 rec = wb + t * d * max(vec3(m) - wb, vec3(0.0));
        vec3 clip = mix(wb, vec3(Ws), smoothstep(0.92, 1.0, cmax));
        wb = mix(clip, rec, clamp(uRaw[3].w, 0.0, 1.0));
    }
    // Black level: taken off after white balance, the same on every channel, so the shadows stay neutral (taken off
    // the camera channels before the gains, it tinted them green or pink); not clipped at zero (that lifts the noise).
    float bo = uRaw[4].w;
    wb = (wb - bo) / max(1.0 - bo, 0.05);
    vec3 lin = vec3(dot(uRaw[0].xyz, wb), dot(uRaw[1].xyz, wb), dot(uRaw[2].xyz, wb)) * uRaw[4].y;
    vec3 W = vec3(0.2627, 0.6780, 0.0593);
    float y = max(dot(lin, W), 1e-6);
    // Tone, in stops about the midpoint: contrast, then highlights and shadows each on their side.
    float mid = max(uRaw[5].y, 1e-3);
    float L = log2(y / mid);
    float L2 = L * uRaw[5].x;
    L2 += uRaw[5].z * smoothstep(0.0, 4.0, L2) * 1.5;
    L2 += uRaw[5].w * (1.0 - smoothstep(-5.0, 0.0, L2)) * 1.5;
    float y2 = mid * exp2(L2);
    // Roll-offs: a soft shoulder above three stops over the midpoint, a soft toe below four under.
    if (uRaw[6].x > 0.0) { float k = mid * 8.0; if (y2 > k) { float e = y2 - k; y2 = k + e / (1.0 + e * uRaw[6].x / k); } }
    if (uRaw[6].y > 0.0) { float k = mid / 16.0; if (y2 < k) y2 = mix(y2, k * pow(y2 / k, 1.0 + uRaw[6].y), 1.0); }
    lin *= y2 / y;
    y = y2;
    // Saturation, and colour boost (more where colour is weak).
    float c = length(lin - y) / max(y, 1e-6);
    float s = uRaw[4].z * (1.0 + uRaw[6].z * (1.0 - smoothstep(0.0, 0.6, c)));
    // Not clipped at zero: below-black noise stays centred on black, so it does not tint the shadows.
    vec3 o = y + (lin - y) * s;
    if (uRaw[6].w >= 0.0) o = encodeG(o, uRaw[6].w);
    return o;
}
"""
}

/** Reads Adobe/Resolve .cube 3D LUTs. */
object CubeLut {
    class Lut(val size: Int, val data: FloatArray)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Lut>()

    /** The LUT in [path], parsed once; null when the file is not a 3D .cube. */
    fun load(path: String): Lut? = cache[path] ?: runCatching { parse(File(path).readText()) }.getOrNull()?.also { cache[path] = it }

    fun parse(text: String): Lut {
        var size = 0
        var min = floatArrayOf(0f, 0f, 0f)
        var max = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "LUT_3D_SIZE" -> size = parts[1].toInt()
                "LUT_1D_SIZE" -> error("1D LUTs are not supported")
                "DOMAIN_MIN" -> min = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                "DOMAIN_MAX" -> max = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                "TITLE", "LUT_3D_INPUT_RANGE" -> {}
                else -> if (parts.size >= 3 && parts[0].first().let { it.isDigit() || it == '-' || it == '.' }) {
                    values.add(parts[0].toFloat()); values.add(parts[1].toFloat()); values.add(parts[2].toFloat())
                }
            }
        }
        require(size in 2..129) { "Not a 3D LUT" }
        require(values.size == size * size * size * 3) { "LUT has ${values.size / 3} entries, expected ${size * size * size}" }
        // Rescales a non-default input domain into 0..1 by resampling is rare; the output is kept as written.
        if (min.any { it != 0f } || max.any { it != 1f }) android.util.Log.w("CubeLut", "domain ${min.toList()}..${max.toList()} treated as 0..1")
        return Lut(size, values.toFloatArray())
    }

    /** The LUT at [r], [g], [b] (0..1), trilinear. */
    fun sample(l: Lut, r: Float, g: Float, b: Float, out: FloatArray) {
        val n = l.size - 1
        val fr = r.coerceIn(0f, 1f) * n; val fg = g.coerceIn(0f, 1f) * n; val fb = b.coerceIn(0f, 1f) * n
        val r0 = min(fr.toInt(), n - 1); val g0 = min(fg.toInt(), n - 1); val b0 = min(fb.toInt(), n - 1)
        val tr = fr - r0; val tg = fg - g0; val tb = fb - b0
        for (c in 0 until 3) {
            fun v(i: Int, j: Int, k: Int) = l.data[((k * l.size + j) * l.size + i) * 3 + c]
            val c00 = v(r0, g0, b0) * (1 - tr) + v(r0 + 1, g0, b0) * tr
            val c10 = v(r0, g0 + 1, b0) * (1 - tr) + v(r0 + 1, g0 + 1, b0) * tr
            val c01 = v(r0, g0, b0 + 1) * (1 - tr) + v(r0 + 1, g0, b0 + 1) * tr
            val c11 = v(r0, g0 + 1, b0 + 1) * (1 - tr) + v(r0 + 1, g0 + 1, b0 + 1) * tr
            out[c] = (c00 * (1 - tg) + c10 * tg) * (1 - tb) + (c01 * (1 - tg) + c11 * tg) * tb
        }
    }

    /** Every LUT is drawn at this many points a side (the common size; 65-point LUTs are resampled). */
    const val ATLAS = 33

    /** The LUT as one tile of the shader's LUT texture: [ATLAS] slices of ATLAS² side by side, blue choosing the slice. */
    fun texture(l: Lut): ByteArray {
        val n = ATLAS
        val out = ByteArray(n * n * n * 4)
        val px = FloatArray(3)
        var k = 0
        for (g in 0 until n) for (b in 0 until n) for (r in 0 until n) {
            if (n == l.size) {
                val i = ((b * n + g) * n + r) * 3
                px[0] = l.data[i]; px[1] = l.data[i + 1]; px[2] = l.data[i + 2]
            } else sample(l, r / (n - 1f), g / (n - 1f), b / (n - 1f), px)
            for (c in 0 until 3) out[k++] = (px[c].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            out[k++] = -1
        }
        return out
    }
}

/**
 * The curve and LUT textures of one GL context, made and kept up to date for whatever grade is drawn. Used by the
 * preview's GL threads and by export, so both draw the same picture.
 */
class GradeTextures {
    private var curvesTex = 0
    private var curvesKey: Any? = null
    private var lutTex = 0
    private var lutKey: String? = null
    private var blank = 0

    private fun newTex(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    /** Binds [g]'s curve and LUT textures (units 1 and 2) and sets their uniforms in [prog]. Leaves unit 0 active. */
    fun bind(prog: Int, g: Grade?) {
        fun u(n: String) = GLES20.glGetUniformLocation(prog, n)
        if (blank == 0) {
            blank = newTex()
            val b = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).put(byteArrayOf(127, 127, 127, 127)).also { it.position(0) }
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
        }
        val curves = g?.curves
        val curvesOn = curves != null && !curves.isIdentity
        val texOn = g != null && (curvesOn || g.slice.active || g.warper.active)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        if (texOn) {
            if (curvesTex == 0) curvesTex = newTex()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curvesTex)
            val key = Triple(g!!.curves, g.slice, g.warper)
            if (key != curvesKey) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 8, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, Curves.texture(g.curves, g.slice, g.warper))
                curvesKey = key
            }
        } else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blank)
        GLES20.glUniform1i(u("uCurves"), 1)
        GLES20.glUniform1f(u("uCurveOn"), if (curvesOn) 1f else 0f)

        // Layer 3: every LUT that is on, resampled to 33 points and stacked into one texture.
        val layers = g?.luts?.filter { it.on }.orEmpty().take(MAX_LUTS)
        val luts = layers.mapNotNull { l -> CubeLut.load(l.file)?.let { l to it } }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        if (luts.isNotEmpty()) {
            if (lutTex == 0) lutTex = newTex()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
            val key = luts.joinToString("|") { it.first.file }
            if (key != lutKey) {
                val n = CubeLut.ATLAS
                val buf = ByteBuffer.allocateDirect(n * n * n * 4 * luts.size).order(ByteOrder.nativeOrder())
                for ((_, lut) in luts) buf.put(CubeLut.texture(lut))
                buf.position(0)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n * n, n * luts.size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                lutKey = key
            }
            val st = FloatArray(MAX_LUTS) { i -> luts.getOrNull(i)?.first?.strength ?: 0f }
            GLES20.glUniform1f(u("uLutN"), luts.size.toFloat())
            GLES20.glUniform1fv(u("uLutS"), MAX_LUTS, st, 0)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blank)
            GLES20.glUniform1f(u("uLutN"), 0f)
        }
        GLES20.glUniform1i(u("uLut"), 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Frees the textures; call on the GL thread with the context current. */
    fun release() {
        val ids = intArrayOf(curvesTex, lutTex, blank).filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
        curvesTex = 0; lutTex = 0; blank = 0; curvesKey = null; lutKey = null
    }

    companion object {
        /** LUTs a clip can stack. */
        const val MAX_LUTS = 8

        /** The GLSL the grade shader uses for curves and LUTs. */
        const val GLSL = """
uniform sampler2D uCurves;
uniform float uCurveOn;
uniform sampler2D uLut;
uniform float uLutN;
uniform float uLutS[8];
vec4 crv(float x, float row) { return texture2D(uCurves, vec2((clamp(x, 0.0, 1.0) * 255.0 + 0.5) / 256.0, (row + 0.5) / 8.0)); }
float cdl(float v) { return (v - 0.5) * 2.0; }
// LUT [i] of the stack: 33-point slices side by side, one LUT under another.
vec3 lutAt(vec3 c, float i) {
    float n = 33.0;
    c = clamp(c, 0.0, 1.0) * (n - 1.0);
    float s0 = min(floor(c.b), n - 2.0);
    float f = c.b - s0;
    float y = (i * n + c.g + 0.5) / (n * uLutN);
    vec3 a = texture2D(uLut, vec2((s0 * n + c.r + 0.5) / (n * n), y)).rgb;
    vec3 b = texture2D(uLut, vec2(((s0 + 1.0) * n + c.r + 0.5) / (n * n), y)).rgb;
    return mix(a, b, f);
}
vec3 applyCurves(vec3 c) {
    vec3 k = clamp(c, 0.0, 1.0);
    vec3 over = c - k;
    k = clamp(k + vec3(cdl(crv(k.r, 0.0).r), cdl(crv(k.g, 0.0).r), cdl(crv(k.b, 0.0).r)), 0.0, 1.0);
    k = clamp(k + vec3(cdl(crv(k.r, 0.0).g), cdl(crv(k.g, 0.0).b), cdl(crv(k.b, 0.0).a)), 0.0, 1.0);
    c = k + over;
    float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float cb = (c.b - y) / 1.8556;
    float cr = (c.r - y) / 1.5748;
    float mx = max(c.r, max(c.g, c.b));
    float sat = mx > 1e-5 ? clamp((mx - min(c.r, min(c.g, c.b))) / mx, 0.0, 1.0) : 0.0;
    float hue = fract(atan(cr, cb + 1e-9) / 6.2831853 - 0.2857 + 1.0);
    vec4 h = crv(hue, 1.0);
    vec4 l = crv(y, 2.0);
    float s2 = crv(sat, 2.0).g;
    float rot = (h.r - 0.5) * 6.2831853;
    float gain = (2.0 * h.g) * (2.0 * l.r) * (2.0 * s2);
    float cs = cos(rot); float sn = sin(rot);
    float nb = (cb * cs - cr * sn) * gain;
    float nr = (cb * sn + cr * cs) * gain;
    y += (h.b - 0.5) * 0.5 * smoothstep(0.0, 0.25, sat);
    return vec3(y + 1.5748 * nr, y - 0.1873 * nb - 0.4681 * nr, y + 1.8556 * nb);
}
"""
    }
}

/**
 * What a grade needs beyond the frame itself, per GL context: the quarter-size, mipmapped copy of the corrected picture
 * (halation and bloom blur from it; deflicker measures the frame's brightness from its smallest level), and the last
 * frames (temporal noise reduction and motion blur). Used by the preview and by export alike.
 */
class FrameExtras {
    private var blank = 0
    private var glowTex = 0
    private var glowFbo = 0
    private var meanFbo = 0
    var glowW = 0; private set
    var glowH = 0; private set
    private var levels = 1
    private val ringTex = IntArray(5)
    private val ringFbo = IntArray(5)
    private var ringW = 0
    private var ringH = 0
    private var ringHead = 0
    /** How many earlier frames are kept right now. */
    var historyCount = 0; private set
    private var ema = -1f
    private val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    private fun tex2d(w: Int, h: Int, mip: Boolean): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (mip) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (mip) {
            val n = 1 + (Math.log(maxOf(w, h).toDouble()) / Math.log(2.0)).toInt()
            levels = n
            android.opengl.GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, n, android.opengl.GLES30.GL_RGBA8, w, h)
        } else android.opengl.GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, android.opengl.GLES30.GL_RGBA8, w, h)
        return t[0]
    }

    private fun fbo(tex: Int, level: Int = 0): Int {
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, level)
        return f[0]
    }

    private fun blankTex(): Int {
        if (blank == 0) {
            blank = tex2d(1, 1, false)
            val b = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).put(byteArrayOf(0, 0, 0, -1)).also { it.position(0) }
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
        }
        return blank
    }

    /**
     * Binds the glow framebuffer (a quarter of [fbW]×[fbH]) for the glow pass and clears it. The caller draws the picture
     * with its viewport scaled by [GLOW_SCALE], then calls [glowEnd].
     */
    fun glowBegin(fbW: Int, fbH: Int) {
        val w = maxOf(fbW / 4, 1); val h = maxOf(fbH / 4, 1)
        if (glowTex == 0 || w != glowW || h != glowH) {
            releaseGlow()
            glowTex = tex2d(w, h, true)
            glowFbo = fbo(glowTex)
            meanFbo = fbo(glowTex, levels - 1)
            glowW = w; glowH = h
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, glowFbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    fun glowEnd() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glowTex)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
    }

    /** The frame's average brightness (0..1), from the glow copy's 1×1 level. */
    fun mean(): Float {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, meanFbo)
        px.position(0)
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
        val r = (px.get(0).toInt() and 255) / 255f; val g = (px.get(1).toInt() and 255) / 255f; val b = (px.get(2).toInt() and 255) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /**
     * Deflicker's gain for a frame of brightness [mean]: the brightness it should have is a running average that follows
     * real changes at [speed] (1..100); the gain is how far the frame is from it, by [strength] (0..100).
     */
    fun deflickerGain(mean: Float, strength: Float, speed: Float): Float {
        if (mean <= 0.002f) return 1f
        if (ema < 0f) ema = mean
        ema += (mean - ema) * (0.02f + speed / 100f * 0.5f)
        val gain = Math.pow((ema / mean).toDouble(), (strength / 100f * 2.2f).toDouble()).toFloat()
        return gain.coerceIn(0.5f, 2f)
    }

    fun resetDeflicker() { ema = -1f }

    /** Binds the glow copy (or nothing) to unit 3, and the target's size, for [prog]. */
    fun bindGlow(prog: Int, ready: Boolean, fbW: Int, fbH: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (ready && glowTex != 0) glowTex else blankTex())
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uGlow"), 3)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uFb"), fbW.toFloat(), fbH.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /**
     * Takes the oldest history slot (made [w]×[h]) as the newest frame and binds its framebuffer: the caller draws the
     * current frame, as read in, into it. [keep] is how many frames are wanted.
     */
    fun pushBegin(w: Int, h: Int, keep: Int): Int {
        if (ringW != w || ringH != h) { releaseRing(); ringW = w; ringH = h }
        ringHead = (ringHead + 4) % 5
        if (ringTex[ringHead] == 0) {
            ringTex[ringHead] = tex2d(w, h, false)
            ringFbo[ringHead] = fbo(ringTex[ringHead])
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ringFbo[ringHead])
        GLES20.glViewport(0, 0, w, h)
        historyCount = minOf(historyCount + 1, keep.coerceIn(0, 5))
        return ringFbo[ringHead]
    }

    /** Forgets the earlier frames (a cut, a jump): they no longer belong to this picture. */
    fun resetHistory() { historyCount = 0 }

    /** Binds the earlier frames, newest first, to units 4..8 for [prog]; [skipNewest] when the newest is this frame itself. */
    fun bindHistory(prog: Int, use: Int, skipNewest: Boolean = false) {
        val have = (if (skipNewest) historyCount - 1 else historyCount).coerceAtLeast(0)
        val n = minOf(use, have)
        for (k in 0 until 5) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4 + k)
            val slot = (ringHead + k + if (skipNewest) 1 else 0) % 5
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (k < n && ringTex[slot] != 0) ringTex[slot] else blankTex())
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uH${k + 1}"), 4 + k)
        }
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHistN"), n.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun releaseGlow() {
        if (glowTex != 0) GLES20.glDeleteTextures(1, intArrayOf(glowTex), 0)
        if (glowFbo != 0) GLES20.glDeleteFramebuffers(2, intArrayOf(glowFbo, meanFbo), 0)
        glowTex = 0; glowFbo = 0; meanFbo = 0
    }

    private fun releaseRing() {
        for (k in 0 until 5) {
            if (ringTex[k] != 0) GLES20.glDeleteTextures(1, intArrayOf(ringTex[k]), 0)
            if (ringFbo[k] != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(ringFbo[k]), 0)
            ringTex[k] = 0; ringFbo[k] = 0
        }
        historyCount = 0
    }

    /** Frees everything; call with the context current. */
    fun release() {
        releaseGlow()
        releaseRing()
        if (blank != 0) GLES20.glDeleteTextures(1, intArrayOf(blank), 0)
        blank = 0
    }

    companion object {
        const val GLOW_SCALE = 0.25f

        /** Copies a frame as read in (no colour work) into a history slot. */
        const val COPY_OES = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES uSrc;
uniform mat4 uTex;
void main() { gl_FragColor = vec4(texture2D(uSrc, (uTex * vec4(vUv, 0.0, 1.0)).xy).rgb, 1.0); }
"""
        const val COPY_2D = """precision mediump float;
varying vec2 vUv;
uniform sampler2D uSrc;
uniform mat4 uTex;
void main() { gl_FragColor = vec4(texture2D(uSrc, (uTex * vec4(vUv, 0.0, 1.0)).xy).rgb, 1.0); }
"""
    }
}

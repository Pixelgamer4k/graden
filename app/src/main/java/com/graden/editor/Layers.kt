package com.graden.editor

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/*
 * The layers of a clip's grade beyond the primaries, in the order they act:
 *
 *   input:      noise reduction, lens, deflicker, colour space transform
 *   correction: primaries and wheels, HDR zones, log wheels, RGB mixer, colour warper, colour slice, curves, detail
 *   secondaries (qualifier and window, each with its own adjustments)
 *   LUTs, film look (with the look's overall mix), output limiter
 *
 * Every layer has an on switch and most a mix; all of them are packed into one uniform block ([LayerPack]) that the
 * grade shader reads, so the preview and export draw the same picture in one pass.
 */

private fun JSONObject.f(k: String, d: Float) = optDouble(k, d.toDouble()).toFloat()
private fun floats(a: FloatArray) = JSONArray().apply { a.forEach { put(it.toDouble()) } }
private fun JSONObject.floats(k: String, d: FloatArray): FloatArray = optJSONArray(k)?.let { j -> FloatArray(d.size) { i -> j.optDouble(i, d[i].toDouble()).toFloat() } } ?: d

/**
 * Noise reduction and motion blur, as Resolve's Motion Effects palette. Temporal NR compares each frame with up to five
 * before it ([frames]); motion estimation (None, Faster, Better) and motion range say how far it looks for where things
 * moved. Thresholds and blends run 0..100 as in Resolve; a blend of 0 is full strength.
 */
data class NoiseLayer(
    val on: Boolean = true,
    val tFrames: Int = 0,
    val tMoEst: Int = 1,
    val tRange: Int = 1,
    val tLuma: Float = 0f,
    val tChroma: Float = 0f,
    val tLink: Boolean = true,
    val tMotion: Float = 50f,
    val tBlend: Float = 0f,
    val sMode: Int = 0,
    val sRadius: Int = 0,
    val sLuma: Float = 0f,
    val sChroma: Float = 0f,
    val sLink: Boolean = true,
    val sBlend: Float = 0f,
    val mbMoEst: Int = 1,
    val mbRange: Int = 1,
    val mbAmount: Float = 0f,
) {
    val temporal get() = on && tFrames > 0 && (tLuma > 0f || tChroma > 0f)
    val spatial get() = on && (sLuma > 0f || sChroma > 0f)
    val blur get() = on && mbAmount > 0f
    /** Earlier frames this needs kept. */
    val history get() = maxOf(if (temporal) tFrames else 0, if (blur) 2 else 0)
    val active get() = temporal || spatial || blur
    fun toJson() = JSONObject().put("on", on).put("tf", tFrames).put("tm", tMoEst).put("tr", tRange).put("tl", tLuma.toDouble()).put("tc", tChroma.toDouble())
        .put("tk", tLink).put("tmo", tMotion.toDouble()).put("tb", tBlend.toDouble()).put("sm", sMode).put("sr", sRadius).put("sl", sLuma.toDouble())
        .put("sc", sChroma.toDouble()).put("sk", sLink).put("sb", sBlend.toDouble()).put("mm", mbMoEst).put("mr", mbRange).put("ma", mbAmount.toDouble())
    companion object {
        val MO_EST = listOf("None", "Faster", "Better")
        val RANGES = listOf("Small", "Medium", "Large")
        val MODES = listOf("Faster", "Better", "Enhanced")
        fun fromJson(o: JSONObject?) = if (o == null) NoiseLayer() else NoiseLayer(
            o.optBoolean("on", true), o.optInt("tf"), o.optInt("tm", 1), o.optInt("tr", 1), o.f("tl", 0f), o.f("tc", 0f), o.optBoolean("tk", true),
            o.f("tmo", 50f), o.f("tb", 0f), o.optInt("sm"), o.optInt("sr"), o.f("sl", o.f("l", 0f)), o.f("sc", o.f("c", 0f)), o.optBoolean("sk", true),
            o.f("sb", 0f), o.optInt("mm", 1), o.optInt("mr", 1), o.f("ma", 0f),
        )
    }
}

/** Lens fixes: lateral chromatic aberration, barrel/pincushion distortion, and vignetting (lifting the corners). */
data class LensLayer(val on: Boolean = true, val ca: Float = 0f, val distortion: Float = 0f, val vignette: Float = 0f) {
    val active get() = on && (ca != 0f || distortion != 0f || vignette != 0f)
    fun toJson() = JSONObject().put("on", on).put("ca", ca.toDouble()).put("d", distortion.toDouble()).put("v", vignette.toDouble())
    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) LensLayer() else LensLayer(o.optBoolean("on", true), o.f("ca", 0f), o.f("d", 0f), o.f("v", 0f))
    }
}

/** Deflicker: evens out frame-to-frame brightness changes (lights, timelapses). [strength] 0..100, [speed] how fast it follows real changes. */
data class DeflickerLayer(val on: Boolean = false, val strength: Float = 80f, val speed: Float = 20f) {
    fun toJson() = JSONObject().put("on", on).put("s", strength.toDouble()).put("v", speed.toDouble())
    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) DeflickerLayer() else DeflickerLayer(o.optBoolean("on", false), o.f("s", 80f), o.f("v", 20f))
    }
}

/** One HDR zone: exposure (stops), saturation, colour (X, Y), and where it reaches (range, stops from grey) with its falloff. */
data class HdrZone(val exp: Float = 0f, val sat: Float = 1f, val x: Float = 0f, val y: Float = 0f, val range: Float = 0f, val falloff: Float = 0.2f) {
    fun toJson() = JSONArray().put(exp.toDouble()).put(sat.toDouble()).put(x.toDouble()).put(y.toDouble()).put(range.toDouble()).put(falloff.toDouble())
    fun neutral(def: HdrZone) = exp == 0f && sat == 1f && x == 0f && y == 0f && range == def.range && falloff == def.falloff
    companion object {
        fun fromJson(a: JSONArray?, d: HdrZone) = if (a == null) d else HdrZone(
            a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 1.0).toFloat(), a.optDouble(2, 0.0).toFloat(), a.optDouble(3, 0.0).toFloat(),
            a.optDouble(4, d.range.toDouble()).toFloat(), a.optDouble(5, d.falloff.toDouble()).toFloat(),
        )
    }
}

/**
 * The HDR palette, as Resolve's High Dynamic Range color wheels: Black, Dark and Shadow act on everything below their
 * range, Light, Highlight and Specular on everything above it, each easing over its falloff; then the Global wheel and
 * Temp, Tint, Hue, Contrast, Pivot (stops), MD (mid-detail) and B/Ofs (black offset). In linear light.
 */
data class ZonesLayer(
    val on: Boolean = true,
    val zones: List<HdrZone> = DEFAULTS,
    val global: HdrZone = HdrZone(),
    val temp: Float = 0f,
    val tint: Float = 0f,
    val hue: Float = 0f,
    val contrast: Float = 1f,
    val pivot: Float = 0f,
    val md: Float = 0f,
    val bOfs: Float = 0f,
    val mix: Float = 1f,
) {
    val active get() = on && mix > 0f && (zones.withIndex().any { (i, z) -> !z.neutral(DEFAULTS[i]) } || !global.neutral(HdrZone()) ||
        temp != 0f || tint != 0f || hue != 0f || contrast != 1f || md != 0f || bOfs != 0f)
    fun toJson() = JSONObject().put("on", on).put("z", JSONArray().apply { zones.forEach { put(it.toJson()) } }).put("g", global.toJson())
        .put("te", temp.toDouble()).put("ti", tint.toDouble()).put("hu", hue.toDouble()).put("co", contrast.toDouble()).put("pv", pivot.toDouble())
        .put("md", md.toDouble()).put("bo", bOfs.toDouble()).put("m", mix.toDouble())
    companion object {
        val NAMES = listOf("Black", "Dark", "Shadow", "Light", "Highlight", "Specular")
        /** Resolve's starting ranges and falloffs. */
        val DEFAULTS = listOf(
            HdrZone(range = -4f, falloff = 0.18f), HdrZone(range = -1.5f, falloff = 0.2f), HdrZone(range = 1f, falloff = 0.22f),
            HdrZone(range = -1f, falloff = 0.22f), HdrZone(range = 1.5f, falloff = 0.22f), HdrZone(range = 4f, falloff = 0.18f),
        )
        fun fromJson(o: JSONObject?): ZonesLayer {
            if (o == null) return ZonesLayer()
            val za = o.optJSONArray("z")
            return ZonesLayer(
                o.optBoolean("on", true), DEFAULTS.mapIndexed { i, d -> HdrZone.fromJson(za?.optJSONArray(i), d) }, HdrZone.fromJson(o.optJSONArray("g"), HdrZone()),
                o.f("te", 0f), o.f("ti", 0f), o.f("hu", 0f), o.f("co", 1f), o.f("pv", 0f), o.f("md", 0f), o.f("bo", 0f), o.f("m", 1f),
            )
        }
    }
}

/** Log wheels: Shadow, Midtone and Highlight colour offsets, split at [low] and [high] (in the working space's values). */
data class LogLayer(
    val on: Boolean = true,
    val shadow: Wheel = Wheel(),
    val midtone: Wheel = Wheel(),
    val highlight: Wheel = Wheel(),
    val low: Float = 0.333f,
    val high: Float = 0.55f,
    val mix: Float = 1f,
) {
    val active get() = on && mix > 0f && !(shadow.isZero && midtone.isZero && highlight.isZero)
    fun toJson(): JSONObject {
        fun w(x: Wheel) = JSONArray().put(x.u.toDouble()).put(x.v.toDouble()).put(x.y.toDouble())
        return JSONObject().put("on", on).put("s", w(shadow)).put("mi", w(midtone)).put("h", w(highlight)).put("lo", low.toDouble()).put("hi", high.toDouble()).put("m", mix.toDouble())
    }
    companion object {
        fun fromJson(o: JSONObject?): LogLayer {
            if (o == null) return LogLayer()
            fun w(k: String) = o.optJSONArray(k)?.let { Wheel(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), it.optDouble(2).toFloat()) } ?: Wheel()
            return LogLayer(o.optBoolean("on", true), w("s"), w("mi"), w("h"), o.f("lo", 0.333f), o.f("hi", 0.55f), o.f("m", 1f))
        }
    }
}

/** RGB mixer: each output channel as a mix of the inputs; monochrome; keep brightness when the mix changes it. */
data class MixerLayer(
    val on: Boolean = true,
    val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
    val mono: Boolean = false,
    val preserveLum: Boolean = true,
    val mix: Float = 1f,
) {
    val active get() = on && mix > 0f && (mono || !m.contentEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
    override fun equals(other: Any?) = other is MixerLayer && on == other.on && m.contentEquals(other.m) && mono == other.mono && preserveLum == other.preserveLum && mix == other.mix
    override fun hashCode() = m.contentHashCode()
    fun toJson() = JSONObject().put("on", on).put("x", floats(m)).put("mo", mono).put("pl", preserveLum).put("m", mix.toDouble())
    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) MixerLayer() else MixerLayer(
            o.optBoolean("on", true), o.floats("x", floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)), o.optBoolean("mo"), o.optBoolean("pl", true), o.f("m", 1f),
        )
    }
}

/**
 * Colour warper: a mesh over hue (12 spokes) and saturation (4 rings). Each point can be moved round (hue, in turns)
 * and out or in (saturation, as a factor); colours between points follow smoothly.
 */
data class WarperLayer(
    val on: Boolean = true,
    val dHue: FloatArray = FloatArray(HUES * RINGS),
    val dSat: FloatArray = FloatArray(HUES * RINGS) { 1f },
    val mix: Float = 1f,
) {
    val active get() = on && mix > 0f && (dHue.any { it != 0f } || dSat.any { it != 1f })
    override fun equals(other: Any?) = other is WarperLayer && on == other.on && dHue.contentEquals(other.dHue) && dSat.contentEquals(other.dSat) && mix == other.mix
    override fun hashCode() = dHue.contentHashCode() * 31 + dSat.contentHashCode()
    fun toJson() = JSONObject().put("on", on).put("h", floats(dHue)).put("s", floats(dSat)).put("m", mix.toDouble())

    /** The warp at hue [h] (0..1, the hue curves' axis) and ring position [r] (0 = the innermost ring, RINGS-1 the outer). */
    fun at(h: Float, r: Float): Pair<Float, Float> {
        val x = ((h % 1f) + 1f) % 1f * HUES
        val i0 = floor(x).toInt() % HUES; val i1 = (i0 + 1) % HUES; val tx = x - floor(x)
        val rr = r.coerceIn(0f, RINGS - 1f)
        val j0 = floor(rr).toInt().coerceAtMost(RINGS - 1); val j1 = (j0 + 1).coerceAtMost(RINGS - 1); val ty = rr - j0
        fun v(a: FloatArray, i: Int, j: Int) = a[j * HUES + i]
        fun lerp(a: FloatArray): Float {
            val top = v(a, i0, j0) * (1 - tx) + v(a, i1, j0) * tx
            val bot = v(a, i0, j1) * (1 - tx) + v(a, i1, j1) * tx
            return top * (1 - ty) + bot * ty
        }
        return lerp(dHue) to lerp(dSat)
    }

    companion object {
        const val HUES = 12
        const val RINGS = 4
        fun fromJson(o: JSONObject?) = if (o == null) WarperLayer() else WarperLayer(
            o.optBoolean("on", true), o.floats("h", FloatArray(HUES * RINGS)), o.floats("s", FloatArray(HUES * RINGS) { 1f }), o.f("m", 1f),
        )
    }
}

/**
 * Colour slice (as Resolve 19): seven hue slices (Red, Skin, Yellow, Green, Cyan, Blue, Magenta), each with a hue shift
 * (turns), saturation (factor) and density (darkens saturated colour, 0..1); plus a global density.
 */
data class SliceLayer(
    val on: Boolean = true,
    val hue: FloatArray = FloatArray(7),
    val sat: FloatArray = FloatArray(7) { 1f },
    val density: FloatArray = FloatArray(7),
    val globalDensity: Float = 0f,
    val mix: Float = 1f,
) {
    val active get() = on && mix > 0f && (globalDensity != 0f || hue.any { it != 0f } || sat.any { it != 1f } || density.any { it != 0f })
    override fun equals(other: Any?) = other is SliceLayer && on == other.on && hue.contentEquals(other.hue) && sat.contentEquals(other.sat) &&
        density.contentEquals(other.density) && globalDensity == other.globalDensity && mix == other.mix
    override fun hashCode() = hue.contentHashCode() * 31 + sat.contentHashCode()
    fun toJson() = JSONObject().put("on", on).put("h", floats(hue)).put("s", floats(sat)).put("d", floats(density)).put("g", globalDensity.toDouble()).put("m", mix.toDouble())
    companion object {
        val NAMES = listOf("Red", "Skin", "Yellow", "Green", "Cyan", "Blue", "Magenta")
        /** Slice centres on the hue axis (red at 0), and their widths. */
        val CENTRES = floatArrayOf(0f, 0.07f, 0.17f, 0.33f, 0.5f, 0.67f, 0.83f)
        fun fromJson(o: JSONObject?) = if (o == null) SliceLayer() else SliceLayer(
            o.optBoolean("on", true), o.floats("h", FloatArray(7)), o.floats("s", FloatArray(7) { 1f }), o.floats("d", FloatArray(7)), o.f("g", 0f), o.f("m", 1f),
        )
    }
}

/** Sharpen and soften (0..100), at a radius in pixels of the preview picture. */
data class DetailLayer(val on: Boolean = true, val sharpen: Float = 0f, val soften: Float = 0f, val radius: Float = 1.5f) {
    val active get() = on && (sharpen > 0f || soften > 0f)
    fun toJson() = JSONObject().put("on", on).put("sh", sharpen.toDouble()).put("so", soften.toDouble()).put("r", radius.toDouble())
    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) DetailLayer() else DetailLayer(o.optBoolean("on", true), o.f("sh", 0f), o.f("so", 0f), o.f("r", 1.5f))
    }
}

/** What a secondary selects by colour (a qualifier): hue, saturation and luminance ranges with softness. */
data class Qualifier(
    val useHue: Boolean = true, val hueCentre: Float = 0.07f, val hueWidth: Float = 0.08f,
    val useSat: Boolean = false, val satLo: Float = 0.1f, val satHi: Float = 1f,
    val useLum: Boolean = false, val lumLo: Float = 0.05f, val lumHi: Float = 0.95f,
    val softness: Float = 0.06f, val invert: Boolean = false,
) {
    fun toJson() = JSONObject().put("uh", useHue).put("hc", hueCentre.toDouble()).put("hw", hueWidth.toDouble()).put("us", useSat).put("sl", satLo.toDouble())
        .put("sh", satHi.toDouble()).put("ul", useLum).put("ll", lumLo.toDouble()).put("lh", lumHi.toDouble()).put("sf", softness.toDouble()).put("iv", invert)
    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) Qualifier() else Qualifier(
            o.optBoolean("uh", true), o.f("hc", 0.07f), o.f("hw", 0.08f), o.optBoolean("us"), o.f("sl", 0.1f), o.f("sh", 1f),
            o.optBoolean("ul"), o.f("ll", 0.05f), o.f("lh", 0.95f), o.f("sf", 0.06f), o.optBoolean("iv"),
        )
    }
}

/** A power window: none, circle (ellipse), linear (gradient) or rectangle; in picture coordinates 0..1. */
data class Window(
    val type: Int = NONE, val cx: Float = 0.5f, val cy: Float = 0.5f, val w: Float = 0.35f, val h: Float = 0.35f,
    val angle: Float = 0f, val softness: Float = 0.15f, val invert: Boolean = false,
) {
    fun toJson() = JSONObject().put("t", type).put("x", cx.toDouble()).put("y", cy.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("a", angle.toDouble()).put("s", softness.toDouble()).put("i", invert)
    companion object {
        const val NONE = 0; const val CIRCLE = 1; const val LINEAR = 2; const val RECT = 3
        val NAMES = listOf("None", "Circle", "Linear", "Rectangle")
        fun fromJson(o: JSONObject?) = if (o == null) Window() else Window(
            o.optInt("t"), o.f("x", 0.5f), o.f("y", 0.5f), o.f("w", 0.35f), o.f("h", 0.35f), o.f("a", 0f), o.f("s", 0.15f), o.optBoolean("i"),
        )
    }
}

/** A secondary: a qualifier and/or a window, and the adjustments made inside it. */
data class Secondary(
    val name: String = "Secondary",
    val on: Boolean = true,
    val qualify: Boolean = true,
    val qualifier: Qualifier = Qualifier(),
    val window: Window = Window(),
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val hueShift: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val mix: Float = 1f,
) {
    fun toJson() = JSONObject().put("n", name).put("on", on).put("q", qualify).put("qu", qualifier.toJson()).put("w", window.toJson())
        .put("e", exposure.toDouble()).put("c", contrast.toDouble()).put("s", saturation.toDouble()).put("h", hueShift.toDouble())
        .put("t", temp.toDouble()).put("ti", tint.toDouble()).put("m", mix.toDouble())
    companion object {
        const val MAX = 4
        fun fromJson(o: JSONObject?) = if (o == null) null else Secondary(
            o.optString("n", "Secondary"), o.optBoolean("on", true), o.optBoolean("q", true), Qualifier.fromJson(o.optJSONObject("qu")), Window.fromJson(o.optJSONObject("w")),
            o.f("e", 0f), o.f("c", 1f), o.f("s", 1f), o.f("h", 0f), o.f("t", 0f), o.f("ti", 0f), o.f("m", 1f),
        )
    }
}

/**
 * The film look, modelled on Resolve's Film Look Creator: the look (exposure, subtractive saturation, richness, bleach
 * bypass, split tone, fade, highlight roll-off), halation, bloom, grain (by stock, with its tonal response), vignette,
 * gate weave and flicker; [lookMix] blends the whole look, LUTs included.
 */
data class FilmLayer(
    val on: Boolean = true,
    val preset: String = "Custom",
    val exposure: Float = 0f,
    val subSat: Float = 0f,
    val richness: Float = 0f,
    val bleach: Float = 0f,
    val splitAmount: Float = 0f,
    val splitHue: Float = 0f,
    val splitPivot: Float = 0.45f,
    val fade: Float = 0f,
    val rolloff: Float = 0f,
    val halation: Float = 0f,
    val halSpread: Float = 0.35f,
    val halThreshold: Float = 0.75f,
    val halHue: Float = 0f,
    val halSat: Float = 1f,
    val bloom: Float = 0f,
    val bloomSpread: Float = 0.5f,
    val bloomThreshold: Float = 0.7f,
    val bloomWarmth: Float = 0f,
    val grain: Float = 0f,
    val stock: String = "35mm",
    val grainSize: Float = 1f,
    val grainSoft: Float = 0.6f,
    val grainColor: Float = 0.35f,
    val grainShadows: Float = 1f,
    val grainMids: Float = 1f,
    val grainHighs: Float = 0.55f,
    val grainAnimated: Boolean = true,
    val vignette: Float = 0f,
    val vigSize: Float = 0.55f,
    val vigRound: Float = 0.7f,
    val vigSoft: Float = 0.5f,
    val weave: Float = 0f,
    val weaveSpeed: Float = 1f,
    val flicker: Float = 0f,
    val flickerSpeed: Float = 1f,
    val lookMix: Float = 1f,
) {
    val active get() = on && (exposure != 0f || subSat != 0f || richness != 0f || bleach > 0f || splitAmount > 0f || fade > 0f || rolloff > 0f ||
        halation > 0f || bloom > 0f || grain > 0f || vignette != 0f || weave > 0f || flicker > 0f)
    /** Halation or bloom: they need the blurred copy of the picture. */
    val glows get() = on && (halation > 0f || bloom > 0f)
    fun toJson(): JSONObject {
        val o = JSONObject().put("on", on).put("pr", preset)
        val v = floatArrayOf(exposure, subSat, richness, bleach, splitAmount, splitHue, splitPivot, fade, rolloff, halation, halSpread, halThreshold, halHue, halSat,
            bloom, bloomSpread, bloomThreshold, bloomWarmth, grain, grainSize, grainSoft, grainColor, grainShadows, grainMids, grainHighs, vignette, vigSize, vigRound,
            vigSoft, weave, weaveSpeed, flicker, flickerSpeed, lookMix)
        return o.put("v", floats(v)).put("st", stock).put("ga", grainAnimated)
    }
    companion object {
        val STOCKS = listOf("8mm", "16mm", "35mm", "65mm")
        /** How big each stock's grain looks against the frame, relative to 35 mm. */
        fun stockScale(s: String) = when (s) { "8mm" -> 2.6f; "16mm" -> 1.7f; "65mm" -> 0.6f; else -> 1f }
        fun fromJson(o: JSONObject?): FilmLayer {
            if (o == null) return FilmLayer()
            val d = FilmLayer()
            val v = o.optJSONArray("v") ?: return d.copy(on = o.optBoolean("on", true), halation = o.f("ha", 0f), bloom = o.f("bl", 0f), grain = o.f("gr", 0f),
                vignette = o.f("vi", 0f) / 100f, lookMix = o.f("lm", 1f))
            fun x(i: Int, def: Float) = v.optDouble(i, def.toDouble()).toFloat()
            return FilmLayer(
                o.optBoolean("on", true), o.optString("pr", "Custom"), x(0, 0f), x(1, 0f), x(2, 0f), x(3, 0f), x(4, 0f), x(5, 0f), x(6, 0.45f), x(7, 0f), x(8, 0f),
                x(9, 0f), x(10, 0.35f), x(11, 0.75f), x(12, 0f), x(13, 1f), x(14, 0f), x(15, 0.5f), x(16, 0.7f), x(17, 0f), x(18, 0f), o.optString("st", "35mm"),
                x(19, 1f), x(20, 0.6f), x(21, 0.35f), x(22, 1f), x(23, 1f), x(24, 0.55f), o.optBoolean("ga", true), x(25, 0f), x(26, 0.55f), x(27, 0.7f),
                x(28, 0.5f), x(29, 0f), x(30, 1f), x(31, 0f), x(32, 1f), x(33, 1f),
            )
        }

        /** Starting points, as the Film Look Creator's presets. */
        val PRESETS: List<Pair<String, (FilmLayer) -> FilmLayer>> = listOf(
            "Clean 65mm" to { f -> f.copy(preset = "Clean 65mm", stock = "65mm", subSat = 0.15f, richness = 0.2f, rolloff = 0.35f, halation = 0.12f, halSpread = 0.25f, grain = 0.15f, grainSoft = 0.8f, vignette = 0.12f) },
            "35mm Print" to { f -> f.copy(preset = "35mm Print", stock = "35mm", subSat = 0.3f, richness = 0.35f, splitAmount = 0.25f, rolloff = 0.5f, halation = 0.35f, halSpread = 0.35f, bloom = 0.15f, grain = 0.35f, vignette = 0.2f, weave = 0.2f, flicker = 0.1f) },
            "16mm Documentary" to { f -> f.copy(preset = "16mm Documentary", stock = "16mm", subSat = 0.1f, fade = 0.2f, rolloff = 0.4f, halation = 0.45f, halSpread = 0.5f, grain = 0.6f, grainSoft = 0.45f, grainColor = 0.5f, vignette = 0.3f, weave = 0.45f, flicker = 0.25f) },
            "8mm Home Movie" to { f -> f.copy(preset = "8mm Home Movie", stock = "8mm", subSat = -0.1f, fade = 0.35f, splitAmount = 0.35f, rolloff = 0.6f, halation = 0.6f, halSpread = 0.6f, bloom = 0.3f, grain = 0.85f, grainSoft = 0.35f, grainColor = 0.6f, vignette = 0.5f, vigRound = 0.4f, weave = 0.8f, flicker = 0.5f) },
            "Bleach Bypass" to { f -> f.copy(preset = "Bleach Bypass", bleach = 0.7f, subSat = -0.2f, rolloff = 0.3f, grain = 0.4f, vignette = 0.25f) },
            "Faded Print" to { f -> f.copy(preset = "Faded Print", fade = 0.5f, subSat = -0.25f, splitAmount = 0.3f, splitHue = 0.05f, halation = 0.2f, grain = 0.3f) },
        )
    }
}

/** Output limiter: soft highlight and black clip instead of hard clipping, and a saturation limit (0..1, 1 = off). */
data class LimiterLayer(val on: Boolean = false, val softClip: Boolean = true, val satLimit: Float = 1f) {
    fun toJson() = JSONObject().put("on", on).put("sc", softClip).put("sl", satLimit.toDouble())
    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) LimiterLayer() else LimiterLayer(o.optBoolean("on", false), o.optBoolean("sc", true), o.f("sl", 1f))
    }
}

/**
 * Packs a grade's layers into the shader's uniform block `uL[SIZE]` (vec4s). Order and meaning match [GLSL] exactly.
 * [workGamma] is the code of the curve the correction works on (to reach linear light for the zones), [time] seconds
 * (for grain), [deflickerGain] the brightness factor deflicker worked out for this frame.
 */
object LayerPack {
    const val SIZE = 72

    /** The block for no grade at all, in the preview (y up) and in export. */
    val PREVIEW_NONE = pack(Grade(), 2, yUp = true)
    val EXPORT_NONE = pack(Grade(), 2, yUp = false)

    /** Radius in pixels for Resolve's Small, Medium and Large. */
    private fun radius(i: Int) = when (i) { 0 -> 1f; 1 -> 2f; else -> 3.2f }
    private fun range(i: Int) = when (i) { 0 -> 2f; 1 -> 4f; else -> 8f }

    /**
     * [glowPass]: the quarter-size pass that only draws the corrected picture (for halation and bloom). [zoneView] paints
     * the HDR zones instead of the picture. [glowReady] says the blurred copy exists for this draw.
     */
    fun pack(
        g: Grade, workGamma: Int, yUp: Boolean, deflickerGain: Float = 1f, wipe: Float = -1f,
        glowPass: Boolean = false, glowReady: Boolean = false, zoneView: Boolean = false, highlight: Int = 0,
    ): FloatArray {
        val u = FloatArray(SIZE * 4)
        fun v(i: Int, a: Float, b: Float, c: Float, d: Float) { u[i * 4] = a; u[i * 4 + 1] = b; u[i * 4 + 2] = c; u[i * 4 + 3] = d }
        val n = g.noise
        if (n.spatial) {
            val ch = if (n.sLink) n.sLuma else n.sChroma
            v(0, 1f, n.sLuma / 100f, ch / 100f, radius(n.sRadius))
            v(1, n.sMode.toFloat(), n.sBlend / 100f, 0f, 0f)
        }
        if (n.temporal) {
            val ch = if (n.tLink) n.tLuma else n.tChroma
            v(2, n.tFrames.toFloat(), n.tLuma / 100f, ch / 100f, n.tMotion / 100f)
            v(3, n.tBlend / 100f, n.tMoEst.toFloat(), range(n.tRange), 0f)
        }
        val l = g.lens
        if (l.active) v(4, 1f, l.ca / 1000f, l.distortion / 100f, l.vignette / 100f)
        v(5, deflickerGain, if (g.deflicker.on) 1f else 0f, if (n.blur) n.mbAmount / 100f else 0f, 0f)
        val z = g.zones
        if (z.active) {
            for (i in 0 until 6) {
                val zz = z.zones[i]
                v(6 + i, zz.exp, zz.sat, zz.range, zz.falloff)
                val c = Wheel(zz.x, zz.y).rgb(0.5f)
                v(12 + i, c[0], c[1], c[2], 0f)
            }
            v(18, z.global.exp, z.global.sat, z.temp / 2000f, z.tint / 100f)
            v(19, z.hue / 360f, z.contrast, z.pivot, 0f)
            val gc = Wheel(z.global.x, z.global.y).rgb(0.5f)
            v(21, gc[0], gc[1], gc[2], 0f)
        } else for (i in 0 until 6) v(6 + i, 0f, 1f, ZonesLayer.DEFAULTS[i].range, ZonesLayer.DEFAULTS[i].falloff)
        v(20, if (z.active) z.bOfs else 0f, if (z.active) 1f else 0f, z.mix, if (zoneView) 1f else 0f)
        if (!z.active) { v(18, 0f, 1f, 0f, 0f); v(19, 0f, 1f, 0f, 0f) }
        val lg = g.logWheels
        if (lg.active) {
            v(22, 1f, lg.low, lg.high, lg.mix)
            for ((i, w) in listOf(lg.shadow, lg.midtone, lg.highlight).withIndex()) {
                val c = w.rgb(0.25f)
                v(23 + i, c[0] + w.y * 0.25f, c[1] + w.y * 0.25f, c[2] + w.y * 0.25f, 0f)
            }
        }
        val mx = g.mixer
        if (mx.active) {
            v(26, mx.m[0], mx.m[1], mx.m[2], 0f)
            v(27, mx.m[3], mx.m[4], mx.m[5], 0f)
            v(28, mx.m[6], mx.m[7], mx.m[8], 0f)
            v(29, 1f, if (mx.mono) 1f else 0f, if (mx.preserveLum) 1f else 0f, mx.mix)
        }
        v(30, if (g.warper.active) g.warper.mix else 0f, if (g.slice.active) g.slice.mix else 0f, if (g.slice.active) g.slice.globalDensity else 0f, 0f)
        val d = g.detail
        if (d.active) v(31, d.sharpen / 100f, d.soften / 100f, d.radius, 1f)
        for ((i, s) in g.secondaries.take(Secondary.MAX).withIndex()) {
            if (!s.on || s.mix <= 0f) continue
            val q = s.qualifier
            val w = s.window
            val at = 32 + i * 7
            v(at, q.hueCentre, q.hueWidth, q.softness, if (s.qualify) 1f else 0f)
            v(at + 1, q.satLo, q.satHi, q.lumLo, q.lumHi)
            v(at + 2, if (q.useHue) 1f else 0f, if (q.useSat) 1f else 0f, if (q.useLum) 1f else 0f, if (q.invert) 1f else 0f)
            v(at + 3, w.type.toFloat(), w.cx, w.cy, if (w.invert) 1f else 0f)
            v(at + 4, w.w, w.h, w.angle * (PI.toFloat() / 180f), w.softness)
            v(at + 5, s.exposure, s.contrast, s.saturation, s.hueShift)
            v(at + 6, s.temp / 2000f, s.tint / 100f, 1f, s.mix)
        }
        val f = g.film
        if (f.active) {
            v(60, f.exposure, f.subSat, f.richness, f.bleach)
            v(61, f.splitAmount, f.splitHue, f.splitPivot, f.fade)
            v(62, f.halation, f.halSpread, f.halThreshold, f.halHue)
            v(63, f.halSat, f.bloom, f.bloomSpread, f.bloomThreshold)
            v(64, f.grain, f.grainSize * FilmLayer.stockScale(f.stock), f.grainSoft, f.grainColor)
            v(65, f.grainShadows, f.grainMids, f.grainHighs, if (f.grainAnimated) 1f else 0f)
            v(66, f.vignette, f.vigSize, f.vigRound, f.vigSoft)
            v(67, f.weave, f.weaveSpeed, f.flicker, f.flickerSpeed)
            v(68, 1f, f.lookMix, f.rolloff, if (glowReady) 1f else 0f)
        } else v(68, 0f, f.lookMix, 0f, 0f)
        val lim = g.limiter
        v(69, if (lim.on) 1f else 0f, if (lim.softClip) 1f else 0f, lim.satLimit, 0f)
        v(70, if (g.correctionOn) g.correctionMix else 0f, if (g.lutsOn) 1f else 0f, if (glowPass) -1f else wipe, if (glowPass) 1f else 0f)
        v(71, workGamma.toFloat(), if (yUp) 1f else 0f, f.bloomWarmth, highlight.toFloat())
        return u
    }

    /** Colour slice and warper rows of the curve texture (rows 3 to 7), as offsets the shader reads. */
    fun sliceRow(s: SliceLayer, x: Float, out: FloatArray) {
        // Each slice's share at hue x: a smooth bump round its centre, normalised so neighbours blend.
        var wsum = 0f
        var h = 0f; var sat = 0f; var den = 0f
        for (i in 0 until 7) {
            var d = kotlin.math.abs(x - SliceLayer.CENTRES[i])
            d = minOf(d, 1f - d)
            val width = if (i == 1) 0.035f else 0.06f
            val w = exp(-(d * d) / (2 * width * width))
            wsum += w
            h += w * s.hue[i]; sat += w * (s.sat[i] - 1f); den += w * s.density[i]
        }
        val k = if (wsum > 1f) 1f / wsum else 1f
        out[0] = h * k; out[1] = 1f + sat * k; out[2] = den * k
    }

    /** A point on the warper's wheel, for drawing: hue turn [h] and ring [r] to (x, y) in a unit disc. */
    fun warpPoint(h: Float, r: Float): Pair<Float, Float> {
        val a = (h + Curves.HUE0) * 2 * PI
        val rad = (r + 1f) / WarperLayer.RINGS
        return (cos(a) * rad).toFloat() to (sin(a) * rad).toFloat()
    }
}

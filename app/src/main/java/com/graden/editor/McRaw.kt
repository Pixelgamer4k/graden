package com.graden.editor

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A MotionCam RAW (.mcraw) recording, open for reading (the native decoder in cpp/mcraw_jni.cpp). Use from one thread
 * at a time; close when done.
 */
class McRaw private constructor(private var h: Long) : Closeable {
    /** Each frame's time, in nanoseconds, in order. */
    val frames: LongArray = nativeFrames(h)
    /** The recording's own metadata (camera, colour matrices, levels). */
    val metadata: JSONObject = JSONObject(nativeMetadata(h))
    val info: McRawInfo by lazy { McRawInfo(metadata, JSONObject(nativeFrameMetadata(h, frames.first()) ?: "{}"), frames) }

    fun frameMetadata(ts: Long): JSONObject = JSONObject(nativeFrameMetadata(h, ts) ?: "{}")

    /** Decodes frame [ts] into [out] (direct, at least width × height × 2 bytes); returns its metadata. */
    fun load(ts: Long, out: ByteBuffer): JSONObject = JSONObject(nativeLoadFrame(h, ts, out) ?: throw java.io.IOException("Couldn't read the frame"))

    /** The sound: interleaved 16-bit samples, the time (ns) of the first, its rate and channels; null when silent. */
    fun audio(): McRawAudio? {
        val rate = nativeAudioRate(h); val ch = nativeAudioChannels(h)
        if (rate <= 0 || ch <= 0) return null
        val start = LongArray(1)
        val s = nativeAudio(h, start) ?: return null
        if (s.isEmpty()) return null
        return McRawAudio(s, start[0], rate, ch)
    }

    /** The gyroscope as (time ns, x, y, z rad/s) per sample, flattened; empty when not recorded. */
    fun gyro(): DoubleArray = nativeGyro(h) ?: DoubleArray(0)

    override fun close() {
        if (h != 0L) nativeClose(h)
        h = 0L
    }

    companion object {
        val available: Boolean = runCatching { System.loadLibrary("mcraw"); true }.getOrDefault(false)

        /** Whether [uri] is a MotionCam RAW file, by its name or its first bytes. */
        fun isMcRaw(context: Context, uri: Uri, name: String?): Boolean {
            if (name?.endsWith(".mcraw", true) == true) return true
            return runCatching {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    val b = ByteArray(7)
                    s.read(b) == 7 && String(b, Charsets.US_ASCII) == "MOTION "
                } ?: false
            }.getOrDefault(false)
        }

        fun open(context: Context, uri: Uri): McRaw {
            check(available) { "RAW decoding isn't available on this phone" }
            val pfd = if (uri.scheme == "file") android.os.ParcelFileDescriptor.open(File(uri.path!!), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            else context.contentResolver.openFileDescriptor(uri, "r") ?: throw java.io.IOException("Couldn't open the file")
            // The decoder keeps its own copy of the descriptor.
            return pfd.use { McRaw(nativeOpen(it.fd)) }
        }

        @JvmStatic private external fun nativeOpen(fd: Int): Long
        @JvmStatic private external fun nativeClose(h: Long)
        @JvmStatic private external fun nativeFrames(h: Long): LongArray
        @JvmStatic private external fun nativeMetadata(h: Long): String
        @JvmStatic private external fun nativeFrameMetadata(h: Long, ts: Long): String?
        @JvmStatic private external fun nativeLoadFrame(h: Long, ts: Long, out: ByteBuffer): String?
        @JvmStatic private external fun nativeAudioRate(h: Long): Int
        @JvmStatic private external fun nativeAudioChannels(h: Long): Int
        @JvmStatic private external fun nativeAudio(h: Long, start: LongArray): ShortArray?
        @JvmStatic private external fun nativeGyro(h: Long): DoubleArray?
    }
}

class McRawAudio(val samples: ShortArray, val startNs: Long, val rate: Int, val channels: Int)

/**
 * What a recording is, from its metadata: size, timing, the sensor's layout and levels, and the colour calibration
 * (DNG style: two illuminants, each with a colour matrix, a forward matrix and a calibration).
 */
class McRawInfo(container: JSONObject, first: JSONObject, frames: LongArray) {
    val width = first.optInt("width")
    val height = first.optInt("height")
    /** Which colour each of the 2 × 2 sensor sites is: 0 red, 1 green, 2 blue, in the order (0,0) (1,0) (0,1) (1,1). */
    val cfa: IntArray = when (container.optString("sensorArrangment", container.optString("sensorArrangement", "rggb")).lowercase()) {
        "bggr" -> intArrayOf(2, 1, 1, 0)
        "grbg" -> intArrayOf(1, 0, 2, 1)
        "gbrg" -> intArrayOf(1, 2, 0, 1)
        else -> intArrayOf(0, 1, 1, 2)
    }
    val black: FloatArray = floats(container.optJSONArray("blackLevel"), 4, 0f)
    val white: Float = container.optDouble("whiteLevel", 1023.0).toFloat()
    val orientation: Int = first.optInt("orientation", 0)
    val firstNs: Long = frames.firstOrNull() ?: 0L
    /** The frame rate, from the median gap between frames. */
    val fps: Float = run {
        if (frames.size < 2) 30f
        else {
            val gaps = (1 until frames.size).map { frames[it] - frames[it - 1] }.sorted()
            (1e9 / gaps[gaps.size / 2].coerceAtLeast(1)).toFloat()
        }
    }
    val durationUs: Long = if (frames.size < 2) 33_333L else (frames.last() - frames.first()) / 1000 + (1e6 / fps).toLong()
    val cm1 = floats(container.optJSONArray("colorMatrix1"), 9, 0f)
    val cm2 = floats(container.optJSONArray("colorMatrix2"), 9, 0f)
    val fm1 = floats(container.optJSONArray("forwardMatrix1"), 9, 0f)
    val fm2 = floats(container.optJSONArray("forwardMatrix2"), 9, 0f)
    val cc1 = floats(container.optJSONArray("calibrationMatrix1"), 9, 0f).let { if (it.all { v -> v == 0f }) identity() else it }
    val cc2 = floats(container.optJSONArray("calibrationMatrix2"), 9, 0f).let { if (it.all { v -> v == 0f }) identity() else it }
    val illum1: Double = cct(container.optString("colorIlluminant1", "d65"))
    val illum2: Double = cct(container.optString("colorIlluminant2", "standarda"))
    val baselineExposure: Float = container.optDouble("baselineExposure", 0.0).toFloat()
    val iso: Int = first.optInt("iso")
    val exposureNs: Double = first.optDouble("exposureTime")
    val focal: Double = container.optJSONArray("focalLengths")?.optDouble(0) ?: 0.0
    val aperture: Double = container.optJSONArray("apertures")?.optDouble(0) ?: 0.0
    val model: String = container.optJSONObject("extraData")?.optJSONObject("postProcessSettings")?.optJSONObject("metadata")?.optString("build.model") ?: ""
    val hasAudio: Boolean = (container.optJSONObject("extraData")?.optInt("audioChannels") ?: 0) > 0
    /** The white balance the camera chose, as a camera neutral (green 1); per frame it can wander, so this is the first frame's. */
    val asShot: FloatArray = floats(first.optJSONArray("asShotNeutral"), 3, 1f)

    /** Everything the develop stage and the colour maths need, kept with the project. */
    fun toJson(): JSONObject = JSONObject().put("w", width).put("h", height).put("cfa", JSONArray(cfa.toList())).put("black", JSONArray(black.map { it.toDouble() }))
        .put("white", white.toDouble()).put("cm1", arr(cm1)).put("cm2", arr(cm2)).put("fm1", arr(fm1)).put("fm2", arr(fm2)).put("cc1", arr(cc1)).put("cc2", arr(cc2))
        .put("i1", illum1).put("i2", illum2).put("asShot", arr(asShot)).put("be", baselineExposure.toDouble()).put("iso", iso).put("exp", exposureNs)
        .put("model", model).put("focal", focal).put("ap", aperture)

    companion object {
        fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private fun arr(f: FloatArray) = JSONArray(f.map { it.toDouble() })
        fun floats(a: JSONArray?, n: Int, d: Float) = FloatArray(n) { a?.optDouble(it, d.toDouble())?.toFloat() ?: d }

        /** The colour temperature of a named DNG illuminant. */
        fun cct(name: String): Double = when (name.lowercase().replace(" ", "").replace("_", "")) {
            "standarda", "tungsten", "incandescent" -> 2856.0
            "d50" -> 5003.0
            "d55", "daylight", "fineweather", "flash" -> 5503.0
            "d65", "cloudyweather" -> 6504.0
            "d75", "shade" -> 7504.0
            "standardb" -> 4874.0
            "standardc" -> 6774.0
            "coolwhitefluorescent", "fluorescent" -> 4230.0
            "whitefluorescent" -> 3450.0
            "warmwhitefluorescent" -> 2940.0
            "daylightfluorescent" -> 6430.0
            "isostudiotungsten" -> 3200.0
            else -> 5003.0
        }
    }
}

/** The colour calibration of a recording, as stored with the project (see [McRawInfo.toJson]). */
class RawCal(j: JSONObject) {
    val cm1 = d(j, "cm1"); val cm2 = d(j, "cm2"); val fm1 = d(j, "fm1"); val fm2 = d(j, "fm2"); val cc1 = d(j, "cc1"); val cc2 = d(j, "cc2")
    val i1 = j.optDouble("i1", 6504.0); val i2 = j.optDouble("i2", 2856.0)
    val asShot = d(j, "asShot", 3)
    val baselineExposure = j.optDouble("be", 0.0)
    val iso = j.optInt("iso", 0)
    val exposureNs = j.optDouble("exp", 0.0)
    val model = j.optString("model")
    val focal = j.optDouble("focal", 0.0)
    val aperture = j.optDouble("ap", 0.0)
    val width = j.optInt("w"); val height = j.optInt("h")
    val hasFm = fm1.any { it != 0.0 }

    private fun d(j: JSONObject, k: String, n: Int = 9) = DoubleArray(n) { j.optJSONArray(k)?.optDouble(it) ?: 0.0 }
}

/**
 * How a RAW clip is developed, on the Camera RAW palette (as Resolve's Camera Raw): from the camera's own metadata, or
 * set for the clip: white balance (as shot, a preset, or temperature and tint), ISO, exposure, the tone (contrast about
 * a midpoint, highlights, shadows, roll-offs), colour (saturation, colour boost), highlight recovery, and the black and
 * white levels. Applied live in the shader to the developed camera colour, never baked in.
 */
data class RawLayer(
    val on: Boolean = true,
    /** 0 develops as the camera recorded it (every other setting is ignored); 1 with this clip's settings. */
    val decode: Int = CAMERA,
    /** 0 as shot, 1 a temperature and tint. */
    val wb: Int = AS_SHOT,
    val temp: Float = 5500f,
    val tint: Float = 0f,
    /** 0: as recorded; otherwise the ISO to develop at (brightness relative to the recorded ISO). */
    val iso: Float = 0f,
    val exposure: Float = 0f,
    val saturation: Float = 1f,
    val colorBoost: Float = 0f,
    val contrast: Float = 1f,
    /** The tone the contrast pivots about, as scene linear grey. */
    val midpoint: Float = 0.18f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val highRolloff: Float = 0f,
    val lowRolloff: Float = 0f,
    val recovery: Float = 0.5f,
    val blackOffset: Float = 0f,
    val whiteScale: Float = 1f,
    val sharpness: Float = 0f,
    val midDetail: Float = 0f,
    /** The colour space and gamma the clip is decoded into (null: scene-linear Rec.2020, for the colour space layer to take on). */
    val outGamut: String? = null,
    val outGamma: String? = null,
) {
    val isDefault get() = this == RawLayer()
    /** The settings actually used: the camera's own, or these. */
    val effective: RawLayer get() = if (decode == CAMERA) RawLayer(on = on, outGamut = outGamut, outGamma = outGamma) else this

    fun toJson(): JSONObject = JSONObject().put("on", on).put("dc", decode).put("wb", wb).put("t", temp.toDouble()).put("ti", tint.toDouble())
        .put("iso", iso.toDouble()).put("e", exposure.toDouble()).put("s", saturation.toDouble()).put("cb", colorBoost.toDouble())
        .put("c", contrast.toDouble()).put("mp", midpoint.toDouble()).put("h", highlights.toDouble()).put("sh", shadows.toDouble())
        .put("hr", highRolloff.toDouble()).put("lr", lowRolloff.toDouble()).put("rec", recovery.toDouble())
        .put("bo", blackOffset.toDouble()).put("ws", whiteScale.toDouble()).put("sp", sharpness.toDouble()).put("md", midDetail.toDouble())
        .putOpt("og", outGamut).putOpt("oy", outGamma)

    companion object {
        const val CAMERA = 0; const val CLIP = 1
        const val AS_SHOT = 0; const val CUSTOM = 1
        /** White balance presets, as colour temperatures. */
        val PRESETS = listOf("Tungsten" to 3200f, "Fluorescent" to 4000f, "Daylight" to 5500f, "Cloudy" to 6500f, "Shade" to 7500f)
        /** The ISOs offered, in thirds of a stop. */
        val ISOS = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
        fun fromJson(o: JSONObject?): RawLayer = o?.let {
            fun f(k: String, d: Double) = it.optDouble(k, d).toFloat()
            RawLayer(it.optBoolean("on", true), it.optInt("dc", CAMERA), it.optInt("wb"), f("t", 5500.0), f("ti", 0.0), f("iso", 0.0), f("e", 0.0),
                f("s", 1.0), f("cb", 0.0), f("c", 1.0), f("mp", 0.18), f("h", 0.0), f("sh", 0.0), f("hr", 0.0), f("lr", 0.0), f("rec", 0.5),
                f("bo", 0.0), f("ws", 1.0), f("sp", 0.0), f("md", 0.0), it.optString("og").ifEmpty { null }, it.optString("oy").ifEmpty { null })
        } ?: RawLayer()
    }
}

/**
 * DNG colour science: camera RGB to scene-linear Rec.2020 for a white balance, and temperature and tint to and from a
 * camera neutral. Follows the DNG specification (illuminant interpolation by inverse temperature, forward matrices).
 */
object RawColor {
    // ---- small 3 × 3 helpers (row-major doubles) ----
    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i -> val r = i / 3; val c = i % 3; a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] }
    fun mulv(a: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> a[r * 3] * v[0] + a[r * 3 + 1] * v[1] + a[r * 3 + 2] * v[2] }
    fun inv(m: DoubleArray): DoubleArray {
        val (a, b, c) = Triple(m[0], m[1], m[2]); val (d, e, f) = Triple(m[3], m[4], m[5]); val (g, h, i) = Triple(m[6], m[7], m[8])
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-15) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        return doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det, (f * g - d * i) / det, (a * i - c * g) / det,
            (c * d - a * f) / det, (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det)
    }
    private fun lerp(a: DoubleArray, b: DoubleArray, t: Double) = DoubleArray(a.size) { a[it] * t + b[it] * (1 - t) }
    private fun diag(v: DoubleArray) = doubleArrayOf(v[0], 0.0, 0.0, 0.0, v[1], 0.0, 0.0, 0.0, v[2])

    /** CIE xy of a colour temperature (daylight above 4000 K, the Planckian locus below), plus [tint] across it. */
    fun xy(cct: Double, tint: Double = 0.0): DoubleArray {
        val t = cct.coerceIn(1667.0, 25000.0)
        val x: Double; val y: Double
        if (t >= 4000) {
            x = if (t <= 7000) -4.6070e9 / t.pow(3) + 2.9678e6 / t.pow(2) + 0.09911e3 / t + 0.244063
            else -2.0064e9 / t.pow(3) + 1.9018e6 / t.pow(2) + 0.24748e3 / t + 0.237040
            y = -3.0 * x * x + 2.870 * x - 0.275
        } else {
            x = -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) + 0.8776956e3 / t + 0.179910
            y = if (t <= 2222) -1.1063814 * x.pow(3) - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            else -0.9549476 * x.pow(3) - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
        }
        if (tint == 0.0) return doubleArrayOf(x, y)
        // Tint moves along the isotherm in the uv plane (perpendicular to the locus), Adobe's scale (−150 … 150).
        fun uv(px: Double, py: Double) = (4 * px / (-2 * px + 12 * py + 3)) to (6 * py / (-2 * px + 12 * py + 3))
        val (u, v) = uv(x, y)
        val n = xy(t * 1.01).let { uv(it[0], it[1]) }
        val du = n.first - u; val dv = n.second - v
        val len = hypot(du, dv).coerceAtLeast(1e-12)
        // Perpendicular, pointing towards green for negative tint (as in Lightroom: + is magenta).
        val pu = -dv / len; val pv = du / len
        val k = -tint / 3000.0
        val u2 = u + pu * k; val v2 = v + pv * k
        return doubleArrayOf(3 * u2 / (2 * u2 - 8 * v2 + 4), 2 * v2 / (2 * u2 - 8 * v2 + 4))
    }

    /** Colour temperature (McCamy) of xy. */
    fun cctOf(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return 449 * n.pow(3) + 3525 * n * n + 6823.3 * n + 5520.33
    }

    private fun weight(cal: RawCal, cct: Double): Double {
        val (lo, hi) = if (cal.i1 <= cal.i2) cal.i1 to cal.i2 else cal.i2 to cal.i1
        val t = cct.coerceIn(lo, hi)
        val g = if (hi == lo) 1.0 else (1 / t - 1 / hi) / (1 / lo - 1 / hi)
        // g is the weight of the lower-temperature illuminant.
        return if (cal.i1 <= cal.i2) g else 1 - g
    }

    private fun xyzToCamera(cal: RawCal, cct: Double): DoubleArray {
        val w = weight(cal, cct)
        return mul(lerp(cal.cc1, cal.cc2, w), lerp(cal.cm1, cal.cm2, w))
    }

    private fun xyz(xy: DoubleArray) = doubleArrayOf(xy[0] / xy[1], 1.0, (1 - xy[0] - xy[1]) / xy[1])

    /** The camera neutral (green 1) for a white of [cct] K and [tint]. */
    fun neutral(cal: RawCal, cct: Double, tint: Double): DoubleArray {
        val n = mulv(xyzToCamera(cal, cct), xyz(xy(cct, tint)))
        return doubleArrayOf(n[0] / n[1], 1.0, n[2] / n[1])
    }

    /** The temperature and tint that a camera neutral [n] is (found as the DNG spec says: by iterating). */
    fun tempTint(cal: RawCal, n: DoubleArray): Pair<Double, Double> {
        var t = 5000.0
        var xyv = doubleArrayOf(0.3457, 0.3585)
        repeat(30) {
            val c = mulv(inv(xyzToCamera(cal, t)), n)
            val s = c[0] + c[1] + c[2]
            xyv = doubleArrayOf(c[0] / s, c[1] / s)
            val nt = cctOf(xyv[0], xyv[1]).coerceIn(2000.0, 15000.0)
            if (abs(nt - t) < 0.5) return@repeat
            t = nt
        }
        // Tint: the offset from the locus point at that temperature, found by search.
        var best = 0.0; var bestD = Double.MAX_VALUE
        var lo = -150.0; var hi = 150.0
        repeat(40) {
            val a = lo + (hi - lo) / 3; val b = hi - (hi - lo) / 3
            fun dist(ti: Double): Double { val p = xy(t, ti); return hypot(p[0] - xyv[0], p[1] - xyv[1]) }
            if (dist(a) < dist(b)) hi = b else lo = a
            val m = (lo + hi) / 2; val dm = dist(m)
            if (dm < bestD) { bestD = dm; best = m }
        }
        return t to best
    }

    /** XYZ (D50) to linear Rec.2020 (D65), with Bradford adaptation. */
    private val XYZD50_TO_2020: DoubleArray by lazy {
        val brad = doubleArrayOf(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)
        val d50 = doubleArrayOf(0.96422, 1.0, 0.82521); val d65 = doubleArrayOf(0.95047, 1.0, 1.08883)
        val s = mulv(brad, d50); val dd = mulv(brad, d65)
        val adapt = mul(inv(brad), mul(diag(doubleArrayOf(dd[0] / s[0], dd[1] / s[1], dd[2] / s[2])), brad))
        val xyzTo2020 = doubleArrayOf(1.7166512, -0.3556708, -0.2533663, -0.6666844, 1.6164812, 0.0157685, 0.0176399, -0.0427706, 0.9421031)
        mul(xyzTo2020, adapt)
    }

    /**
     * The develop matrix for camera neutral [n]: camera RGB (white balanced by [gains], see [gains]) to scene-linear
     * Rec.2020, where a neutral camera value of 1 lands on 1.
     */
    fun cameraTo2020(cal: RawCal, n: DoubleArray): DoubleArray {
        val (t, _) = tempTint(cal, n)
        val w = weight(cal, t)
        val cc = lerp(cal.cc1, cal.cc2, w)
        val toPcs = if (cal.hasFm) {
            // DNG: ReferenceNeutral = inv(CC)·n; CameraToPCS = FM · diag(1/ReferenceNeutral) · inv(CC).
            val fm = lerp(cal.fm1, cal.fm2, w)
            val ref = mulv(inv(cc), n)
            mul(fm, mul(diag(doubleArrayOf(1 / ref[0], 1 / ref[1], 1 / ref[2])), inv(cc)))
        } else {
            // Without forward matrices: the inverse colour matrix, scaled so the neutral maps to D50.
            val m = inv(xyzToCamera(cal, t))
            val pcs = mulv(m, n)
            val d50 = doubleArrayOf(0.96422, 1.0, 0.82521)
            mul(diag(doubleArrayOf(d50[0] / pcs[0], d50[1] / pcs[1], d50[2] / pcs[2])), m)
        }
        val m = mul(XYZD50_TO_2020, toPcs)
        // Normalised so the white-balanced neutral (camera n) gives (1, 1, 1) after the gains; the gains are taken out
        // again so this matrix applies to gain-multiplied camera values.
        val g = gains(n)
        return mul(m, diag(doubleArrayOf(1 / g[0], 1 / g[1], 1 / g[2]))).let { mm ->
            val one = mulv(mm, doubleArrayOf(g[0] * n[0], g[1] * n[1], g[2] * n[2]))
            val s = one[1]
            DoubleArray(9) { mm[it] / s }
        }
    }

    /** White-balance gains for neutral [n], green 1: a channel saturates at its gain in the balanced picture. */
    fun gains(n: DoubleArray) = doubleArrayOf(1 / n[0], 1.0, 1 / n[2])

    /**
     * The raw block the shader takes for [layer] on a recording calibrated [cal]: rows of the matrix (×3, xyz),
     * the gains with highlight recovery (r, g, b, amount), and (on, exposure gain, saturation, black offset).
     */
    fun block(cal: RawCal, layer0: RawLayer): FloatArray {
        val out = FloatArray(BLOCK)
        if (!layer0.on) return out
        val layer = layer0.effective
        val n = if (layer.wb == RawLayer.AS_SHOT) cal.asShot.map { it.coerceAtLeast(1e-4) }.toDoubleArray().let { doubleArrayOf(it[0] / it[1], 1.0, it[2] / it[1]) }
        else neutral(cal, layer.temp.toDouble(), layer.tint.toDouble())
        var m = cameraTo2020(cal, n)
        // Decoded into another gamut: its matrix folded in (ColorSpaces.matrix is column-major).
        layer.outGamut?.takeIf { it != "Rec.2020" }?.let { name ->
            val cm = ColorSpaces.matrix(ColorSpaces.gamut("Rec.2020"), ColorSpaces.gamut(name))
            m = mul(DoubleArray(9) { cm[(it % 3) * 3 + it / 3].toDouble() }, m)
        }
        val g = gains(n)
        for (r in 0 until 3) for (c in 0 until 3) out[r * 4 + c] = m[r * 3 + c].toFloat()
        out[12] = g[0].toFloat(); out[13] = g[1].toFloat(); out[14] = g[2].toFloat(); out[15] = layer.recovery
        // ISO: brightness relative to the ISO it was recorded at.
        val isoGain = if (layer.iso > 0f && cal.iso > 0) layer.iso / cal.iso.toDouble() else 1.0
        out[16] = 1f
        out[17] = (2.0.pow(layer.exposure + cal.baselineExposure + DEFAULT_EV) * isoGain / layer.whiteScale.coerceAtLeast(0.25f)).toFloat()
        out[18] = layer.saturation
        out[19] = layer.blackOffset
        out[20] = layer.contrast; out[21] = layer.midpoint; out[22] = layer.highlights; out[23] = layer.shadows
        out[24] = layer.highRolloff; out[25] = layer.lowRolloff; out[26] = layer.colorBoost
        out[27] = layer.outGamma?.let { ColorSpaces.gamma(it).code.toFloat() } ?: -1f
        out[28] = layer.sharpness; out[29] = layer.midDetail
        // Where the sensor clips (highlight recovery works from it), the recording's white being 1.
        out[30] = layer.whiteScale.coerceIn(0.25f, 1f)
        return out
    }

    /** Floats in a raw block (eight vec4s). */
    const val BLOCK = 32

    /**
     * Where sensor white lands, in stops above the pipeline's reference white: raw footage keeps its highlights, and
     * the output's tone mapping rolls them off.
     */
    const val DEFAULT_EV = 1.5

    /** The log curve the developed camera colour is stored in (see RawDevelop): 0…4 linear into 0…1. */
    fun encode(x: Double) = ln(1 + 4096 * max(x, 0.0)) / ln(2.0) / 14.0
    fun decode(v: Double) = (exp(v * 14.0 * ln(2.0)) - 1) / 4096.0

    /** For the Raw palette: the as-shot white balance as a temperature and tint. */
    fun asShotTempTint(cal: RawCal): Pair<Double, Double> {
        val a = cal.asShot.map { it.coerceAtLeast(1e-4) }
        return tempTint(cal, doubleArrayOf(a[0] / a[1], 1.0, a[2] / a[1]))
    }

    @Suppress("unused") private fun len(v: DoubleArray) = sqrt(v.sumOf { it * it })
}

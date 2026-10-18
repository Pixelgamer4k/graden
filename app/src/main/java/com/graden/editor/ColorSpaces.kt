package com.graden.editor

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Colour science: every colour space is a gamut (primaries and white point) and a gamma (transfer curve), chosen
 * separately as in Resolve's Color Space Transform. Gamuts become 3×3 matrices worked out here from their published
 * primaries; curves are GLSL functions from the makers' white papers. All transforms pass through linear light, where
 * 1.0 is SDR reference white (203 nits, ITU-R BT.2408) and scene-referred curves keep 0.18 as mid grey.
 */
object ColorSpaces {
    // ---- gamuts ------------------------------------------------------------------------------------------

    /** A gamut: primaries and white point (CIE xy). */
    class Gamut(val id: Int, val name: String, val r: DoubleArray, val g: DoubleArray, val b: DoubleArray, val w: DoubleArray = D65)

    private val D65 = doubleArrayOf(0.3127, 0.3290)
    private val D60 = doubleArrayOf(0.32168, 0.33767)
    private val DCI = doubleArrayOf(0.314, 0.351)
    private fun xy(x: Double, y: Double) = doubleArrayOf(x, y)

    val G709 = Gamut(1, "Rec.709", xy(0.640, 0.330), xy(0.300, 0.600), xy(0.150, 0.060))
    val G2020 = Gamut(2, "Rec.2020", xy(0.708, 0.292), xy(0.170, 0.797), xy(0.131, 0.046))
    val GP3D65 = Gamut(3, "P3-D65", xy(0.680, 0.320), xy(0.265, 0.690), xy(0.150, 0.060))
    val GP3DCI = Gamut(4, "DCI-P3", xy(0.680, 0.320), xy(0.265, 0.690), xy(0.150, 0.060), DCI)
    val GP3D60 = Gamut(5, "P3-D60", xy(0.680, 0.320), xy(0.265, 0.690), xy(0.150, 0.060), D60)
    val GAP0 = Gamut(6, "ACES (AP0)", xy(0.7347, 0.2653), xy(0.0, 1.0), xy(0.0001, -0.0770), D60)
    val GAP1 = Gamut(7, "ACES (AP1)", xy(0.713, 0.293), xy(0.165, 0.830), xy(0.128, 0.044), D60)
    val GAWG3 = Gamut(8, "ARRI Wide Gamut 3", xy(0.6840, 0.3130), xy(0.2210, 0.8480), xy(0.0861, -0.1020))
    val GAWG4 = Gamut(9, "ARRI Wide Gamut 4", xy(0.7347, 0.2653), xy(0.1424, 0.8576), xy(0.0991, -0.0308))
    val GBMD = Gamut(10, "Blackmagic Wide Gamut Gen 4/5", xy(0.717721, 0.317118), xy(0.228041, 0.861569), xy(0.100584, -0.082045))
    val GCINEMA = Gamut(11, "Canon Cinema Gamut", xy(0.740, 0.270), xy(0.170, 1.140), xy(0.080, -0.100))
    val GCANONP3 = Gamut(12, "Canon DCI-P3+", xy(0.740, 0.270), xy(0.220, 0.780), xy(0.090, -0.090), DCI)
    val GDWG = Gamut(13, "DaVinci Wide Gamut", xy(0.8000, 0.3130), xy(0.1682, 0.9877), xy(0.0790, -0.1155))
    val GDJI = Gamut(14, "DJI D-Gamut", xy(0.71, 0.31), xy(0.21, 0.88), xy(0.09, -0.08))
    val GFC = Gamut(15, "Fujifilm F-Gamut C", xy(0.7347, 0.2653), xy(0.0263, 0.9737), xy(0.1173, -0.0224))
    val GPROTUNE = Gamut(16, "GoPro Protune Native", xy(0.698480461, 0.193026445), xy(0.329555, 1.024597), xy(0.108443, -0.034679))
    val GLEICA = Gamut(17, "Leica L-Gamut", xy(0.8000, 0.3100), xy(0.1400, 0.8500), xy(0.1000, -0.0500))
    val GVG = Gamut(18, "Panasonic V-Gamut", xy(0.730, 0.280), xy(0.165, 0.840), xy(0.100, -0.030))
    val GRWG = Gamut(19, "RED Wide Gamut RGB", xy(0.780308, 0.304253), xy(0.121595, 1.493994), xy(0.095612, -0.084589))
    val GSG3 = Gamut(20, "Sony S-Gamut3", xy(0.730, 0.280), xy(0.140, 0.855), xy(0.100, -0.050))
    val GSG3C = Gamut(21, "Sony S-Gamut3.Cine", xy(0.766, 0.275), xy(0.225, 0.800), xy(0.089, -0.087))
    val GVSG3 = Gamut(22, "Sony Venice S-Gamut3", xy(0.740464, 0.279364), xy(0.089241, 0.893810), xy(0.110488237, -0.052579333))
    val GVSG3C = Gamut(23, "Sony Venice S-Gamut3.Cine", xy(0.775902, 0.274502), xy(0.188683, 0.828685), xy(0.101337, -0.089188))
    val GXYZ = Gamut(24, "CIE XYZ", xy(1.0, 0.0), xy(0.0, 1.0), xy(0.0, 0.0), xy(1.0 / 3, 1.0 / 3))
    val GAPPLEWG = Gamut(25, "Apple Wide Gamut", xy(0.725, 0.301), xy(0.221, 0.814), xy(0.068, -0.076))

    /** Every gamut, in the order the CST lists them. Same primaries under a maker's own name are listed as aliases. */
    val gamuts: List<Pair<String, Gamut>> = listOf(
        "Rec.709" to G709, "sRGB" to G709, "Rec.2020" to G2020, "Rec.2100" to G2020, "P3-D65" to GP3D65, "Display P3" to GP3D65,
        "DCI-P3" to GP3DCI, "P3-D60" to GP3D60, "ACES (AP0)" to GAP0, "ACES (AP1)" to GAP1, "ARRI Wide Gamut 3" to GAWG3,
        "ARRI Wide Gamut 4" to GAWG4, "Blackmagic Wide Gamut Gen 4/5" to GBMD, "Canon Cinema Gamut" to GCINEMA, "Canon DCI-P3+" to GCANONP3,
        "DaVinci Wide Gamut" to GDWG, "DJI D-Gamut" to GDJI, "Fujifilm F-Gamut" to G2020, "Fujifilm F-Gamut C" to GFC,
        "GoPro Protune Native" to GPROTUNE, "Leica L-Gamut" to GLEICA, "Nikon N-Gamut" to G2020, "Panasonic V-Gamut" to GVG,
        "RED Wide Gamut RGB" to GRWG, "Sony S-Gamut" to GSG3, "Sony S-Gamut3" to GSG3, "Sony S-Gamut3.Cine" to GSG3C,
        "Sony Venice S-Gamut3" to GVSG3, "Sony Venice S-Gamut3.Cine" to GVSG3C, "Apple Log (Rec.2020)" to G2020, "Apple Wide Gamut" to GAPPLEWG, "CIE XYZ" to GXYZ,
    )

    fun gamut(name: String): Gamut = gamuts.firstOrNull { it.first == name }?.second ?: G709

    // ---- gammas -----------------------------------------------------------------------------------------

    /** A transfer curve: its code in the shader. [display]: an SDR display curve (tone mapping aims below it). */
    class Gamma(val code: Int, val name: String, val display: Boolean = false, val hdr: Boolean = false)

    val LINEAR = Gamma(0, "Linear")
    val GAMMA22 = Gamma(1, "Gamma 2.2", display = true)
    val GAMMA24 = Gamma(2, "Gamma 2.4", display = true)
    val GAMMA26 = Gamma(3, "Gamma 2.6", display = true)
    val REC709G = Gamma(4, "Rec.709", display = true)
    val SRGBG = Gamma(5, "sRGB", display = true)
    val HLGG = Gamma(6, "Rec.2100 HLG", hdr = true)
    val PQG = Gamma(7, "Rec.2100 ST2084", hdr = true)
    val ACESCC = Gamma(8, "ACEScc")
    val ACESCCT = Gamma(9, "ACEScct")
    val LOGC3 = Gamma(10, "ARRI LogC3")
    val LOGC4 = Gamma(11, "ARRI LogC4")
    val BMDFILM = Gamma(12, "Blackmagic Design Film Gen 5")
    val CLOG = Gamma(13, "Canon Log")
    val CLOG2 = Gamma(14, "Canon Log 2")
    val CLOG3 = Gamma(15, "Canon Log 3")
    val DI = Gamma(16, "DaVinci Intermediate")
    val DLOG = Gamma(17, "DJI D-Log")
    val FLOG = Gamma(18, "Fujifilm F-Log")
    val FLOG2 = Gamma(19, "Fujifilm F-Log2")
    val NLOG = Gamma(20, "Nikon N-Log")
    val VLOGG = Gamma(21, "Panasonic V-Log")
    val LOG3G10 = Gamma(22, "RED Log3G10")
    val SLOG = Gamma(23, "Sony S-Log")
    val SLOG2 = Gamma(24, "Sony S-Log2")
    val SLOG3G = Gamma(25, "Sony S-Log3")
    val APPLELOG = Gamma(26, "Apple Log")
    val LLOG = Gamma(27, "Leica L-Log")
    val PROTUNE = Gamma(28, "GoPro Protune Flat")
    val CINEON = Gamma(29, "Cineon Film Log")
    val GAMMA196 = Gamma(30, "Gamma 1.96 (Rec.709-A)", display = true)

    val gammas = listOf(
        LINEAR, GAMMA22, GAMMA24, GAMMA26, REC709G, SRGBG, GAMMA196, HLGG, PQG, ACESCC, ACESCCT, LOGC3, LOGC4, BMDFILM, CLOG, CLOG2, CLOG3,
        DI, DLOG, FLOG, FLOG2, NLOG, VLOGG, LOG3G10, SLOG, SLOG2, SLOG3G, APPLELOG, LLOG, PROTUNE, CINEON,
    )

    fun gamma(name: String): Gamma = gammas.firstOrNull { it.name == name } ?: GAMMA24

    // ---- named spaces (a gamut with a gamma) ------------------------------------------------------------

    /** A colour space by name: what a clip is shot in, the working space, or an output. */
    class Space(
        val code: Int, val name: String, val note: String, val gamutName: String, val gammaName: String,
        val hdr: Boolean = false, val log: Boolean = false,
    ) {
        val gamut: Gamut get() = gamut(gamutName)
        val gamma: Gamma get() = gamma(gammaName)
    }

    val REC709 = Space(1, "Rec.709 Gamma 2.4", "Standard HD video, for TVs and most screens", "Rec.709", "Gamma 2.4")
    val REC709A = Space(2, "Rec.709-A", "Rec.709 that looks the same on Apple devices as elsewhere", "Rec.709", "Gamma 1.96 (Rec.709-A)")
    val SRGB = Space(3, "sRGB", "Computer and web screens", "sRGB", "sRGB")
    val REC709_22 = Space(4, "Rec.709 Gamma 2.2", "Rec.709 for brighter rooms and phone screens", "Rec.709", "Gamma 2.2")
    val REC2020 = Space(5, "Rec.2020 Gamma 2.4", "Wide-gamut SDR", "Rec.2020", "Gamma 2.4")
    val HLG = Space(6, "Rec.2100 HLG", "HDR that also plays acceptably on SDR screens; broadcast and phones", "Rec.2100", "Rec.2100 HLG", hdr = true)
    val PQ = Space(7, "Rec.2100 ST2084 (PQ)", "HDR10: YouTube, Netflix and most HDR streaming", "Rec.2100", "Rec.2100 ST2084", hdr = true)
    val P3D65 = Space(8, "P3-D65", "Cinema-grade P3 with gamma 2.6", "P3-D65", "Gamma 2.6")
    val DISPLAY_P3 = Space(9, "Display P3", "Phones, tablets and Apple displays (P3 with the sRGB curve)", "Display P3", "sRGB")
    val REC709_SCENE = Space(10, "Rec.709 (Scene)", "Camera Rec.709 (BT.709 camera curve)", "Rec.709", "Rec.709")
    val LINEAR709 = Space(11, "Linear Rec.709", "Scene linear with Rec.709 primaries", "Rec.709", "Linear", log = true)
    val LINEAR2020 = Space(12, "Linear Rec.2020", "Scene linear with Rec.2020 primaries", "Rec.2020", "Linear", log = true)
    /** A RAW recording, as its raw stage hands it on: scene linear Rec.2020 with the sensor's highlights kept. */
    val RAW = Space(13, "Camera RAW", "Developed from the sensor's own data", "Rec.2020", "Linear", log = true)

    val SLOG3 = Space(20, "Sony S-Log3 / S-Gamut3.Cine", "Sony cameras", "Sony S-Gamut3.Cine", "Sony S-Log3", log = true)
    val VLOG = Space(21, "Panasonic V-Log / V-Gamut", "Panasonic and some DJI cameras", "Panasonic V-Gamut", "Panasonic V-Log", log = true)
    val LOGC3S = Space(22, "ARRI LogC3 / Wide Gamut 3", "ARRI ALEXA cameras (EI 800)", "ARRI Wide Gamut 3", "ARRI LogC3", log = true)
    val APPLE_LOG = Space(23, "Apple Log", "iPhone 15 Pro and later", "Apple Log (Rec.2020)", "Apple Log", log = true)
    private val cameraSpaces = listOf(
        SLOG3,
        Space(40, "Sony S-Log3 / S-Gamut3", "Sony cameras", "Sony S-Gamut3", "Sony S-Log3", log = true),
        Space(41, "Sony S-Log3 / Venice S-Gamut3.Cine", "Sony VENICE", "Sony Venice S-Gamut3.Cine", "Sony S-Log3", log = true),
        Space(42, "Sony S-Log2 / S-Gamut", "Older Sony cameras", "Sony S-Gamut", "Sony S-Log2", log = true),
        Space(43, "Sony S-Log / S-Gamut", "First Sony log cameras", "Sony S-Gamut", "Sony S-Log", log = true),
        VLOG, LOGC3S,
        Space(44, "ARRI LogC4 / Wide Gamut 4", "ARRI ALEXA 35", "ARRI Wide Gamut 4", "ARRI LogC4", log = true),
        Space(45, "Canon Log 3 / Cinema Gamut", "Canon cinema and mirrorless cameras", "Canon Cinema Gamut", "Canon Log 3", log = true),
        Space(46, "Canon Log 2 / Cinema Gamut", "Canon cinema cameras", "Canon Cinema Gamut", "Canon Log 2", log = true),
        Space(47, "Canon Log / Cinema Gamut", "Original Canon Log", "Canon Cinema Gamut", "Canon Log", log = true),
        Space(48, "Blackmagic Film Gen 5", "Blackmagic cameras (BRAW, Film)", "Blackmagic Wide Gamut Gen 4/5", "Blackmagic Design Film Gen 5", log = true),
        Space(49, "RED Log3G10 / RWG", "RED cameras (IPP2)", "RED Wide Gamut RGB", "RED Log3G10", log = true),
        Space(50, "Fujifilm F-Log / F-Gamut", "Fujifilm cameras", "Fujifilm F-Gamut", "Fujifilm F-Log", log = true),
        Space(51, "Fujifilm F-Log2 / F-Gamut", "Fujifilm cameras, wider range", "Fujifilm F-Gamut", "Fujifilm F-Log2", log = true),
        Space(52, "Fujifilm F-Log2 C / F-Gamut C", "Fujifilm F-Log2 C", "Fujifilm F-Gamut C", "Fujifilm F-Log2", log = true),
        Space(53, "Nikon N-Log / N-Gamut", "Nikon cameras", "Nikon N-Gamut", "Nikon N-Log", log = true),
        Space(54, "DJI D-Log / D-Gamut", "DJI drones and cameras", "DJI D-Gamut", "DJI D-Log", log = true),
        Space(55, "Leica L-Log / L-Gamut", "Leica cameras", "Leica L-Gamut", "Leica L-Log", log = true),
        Space(56, "GoPro Protune Flat", "GoPro (Protune, Flat colour)", "GoPro Protune Native", "GoPro Protune Flat", log = true),
        APPLE_LOG,
        Space(57, "Cineon Film Log", "Film scans", "Rec.709", "Cineon Film Log", log = true),
    )

    val DWG = Space(30, "DaVinci Wide Gamut", "Very wide working space with a log curve: keeps HDR highlights", "DaVinci Wide Gamut", "DaVinci Intermediate", log = true)
    val ACESCCT_S = Space(31, "ACEScct", "Academy grading space with a log curve: keeps HDR highlights", "ACES (AP1)", "ACEScct", log = true)
    val ACESCC_S = Space(32, "ACEScc", "Academy grading space, pure log", "ACES (AP1)", "ACEScc", log = true)

    private val rawSpaces = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Space>()

    /** What a clip can be marked as shot in. */
    val inputs = listOf(REC709, REC709_SCENE, SRGB, REC709_22, REC2020, DISPLAY_P3, P3D65, HLG, PQ, LINEAR709, LINEAR2020) + cameraSpaces + listOf(DWG, ACESCCT_S, ACESCC_S, RAW)
    /** What can be graded in. The log ones keep HDR highlights; the display ones roll them off into SDR. */
    val working = listOf(REC709, REC2020, P3D65, DWG, ACESCCT_S, LOGC3S, SLOG3)
    /** What a video file can be delivered as, and correctly tagged. */
    val outputs = listOf(REC709, REC709A, SRGB, REC709_22, REC2020, HLG, PQ, P3D65, DISPLAY_P3)

    private val all = (inputs + working + outputs).distinctBy { it.name }
    fun byName(n: String): Space? = all.firstOrNull { it.name == n }

    fun output(n: String): Space = byName(n)?.takeIf { it in outputs } ?: when {
        "HLG" in n -> HLG
        "ST2084" in n || "PQ" in n -> PQ
        "2020" in n -> REC2020
        else -> REC709
    }

    fun working(n: String): Space = byName(n)?.takeIf { it in working } ?: when {
        "ACES" in n -> ACESCCT_S
        "Wide Gamut" in n -> DWG
        "P3" in n -> P3D65
        "2020" in n -> REC2020
        else -> REC709
    }

    // ---- what a clip is shot in ---------------------------------------------------------------------------

    /** Transfer of each HDR file (by URI), read from the file itself: 6 HLG, 7 PQ. Filled in the background. */
    private val probed = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Reads which HDR curve [a] uses, once. Call off the main thread. */
    fun probe(context: Context, a: MediaAsset, path: String) {
        if (!a.hdr || probed.containsKey(a.uri)) return
        val code = runCatching {
            val ex = MediaExtractor()
            try {
                if (path.startsWith("content:")) ex.setDataSource(context, Uri.parse(path), null)
                else ex.setDataSource(if (path.startsWith("file:")) Uri.parse(path).path!! else path)
                val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER) && f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_HLG) HLG.code else PQ.code
            } finally {
                ex.release()
            }
        }.getOrDefault(PQ.code)
        probed[a.uri] = code
    }

    /** The space [c]'s file is in: what it was marked as, else what the file says (HDR by its curve, others Rec.709). */
    fun inputOf(c: Clip?, a: MediaAsset?): Space {
        if (a?.isRaw == true) {
            // Decoded into the space chosen on the Camera RAW palette (scene-linear Rec.2020 unless set).
            val r = c?.grade?.raw
            if (r?.outGamut == null && r?.outGamma == null) return RAW
            val key = (r.outGamut ?: "Rec.2020") to (r.outGamma ?: "Linear")
            return rawSpaces.getOrPut(key) { Space(13, "Camera RAW", "Developed from the sensor's own data", key.first, key.second, log = true) }
        }
        c?.cs?.let { n -> byName(n)?.let { return it } }
        if (a == null || !a.hdr) return REC709
        // The curve read at import; for assets imported before that was kept, the one read in the background.
        return if (a.hlg || probed[a.uri] == HLG.code) HLG else PQ
    }

    /** The brightest a space's pictures go, in nits, for tone mapping: HDR masters 1000, camera logs 10000, SDR 100. */
    fun peakNits(s: Space): Float = peakNits(s.gamma)

    fun peakNits(g: Gamma): Float = when {
        g.hdr -> 1000f
        !g.display -> 10000f
        else -> 100f
    }

    // ---- matrices --------------------------------------------------------------------------------------------

    private fun xyz(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)

    private fun mul(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    private fun mm(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun inv(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    /** RGB to XYZ for [g] (columns are the primaries, scaled to reach its white). */
    private fun toXyz(g: Gamut): DoubleArray {
        if (g === GXYZ) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val r = xyz(g.r[0], g.r[1]); val gg = xyz(g.g[0], g.g[1]); val b = xyz(g.b[0], g.b[1]); val w = xyz(g.w[0], g.w[1])
        val m = doubleArrayOf(r[0], gg[0], b[0], r[1], gg[1], b[1], r[2], gg[2], b[2])
        val s = mul(inv(m), w)
        return doubleArrayOf(m[0] * s[0], m[1] * s[1], m[2] * s[2], m[3] * s[0], m[4] * s[1], m[5] * s[2], m[6] * s[0], m[7] * s[1], m[8] * s[2])
    }

    /** CAT02 adaptation from white [from] to white [to]: what Resolve's colour space transform uses (measured). */
    private fun adapt(from: DoubleArray, to: DoubleArray): DoubleArray {
        if (from.contentEquals(to)) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val br = doubleArrayOf(0.7328, 0.4296, -0.1624, -0.7036, 1.6975, 0.0061, 0.0030, 0.0136, 0.9834)
        val s = mul(br, xyz(from[0], from[1]))
        val d = mul(br, xyz(to[0], to[1]))
        val scale = doubleArrayOf(d[0] / s[0], 0.0, 0.0, 0.0, d[1] / s[1], 0.0, 0.0, 0.0, d[2] / s[2])
        return mm(inv(br), mm(scale, br))
    }

    /** Linear RGB in gamut [a] to linear RGB in gamut [b], with white adapted by CAT02 (XYZ is taken as D65), column-major. */
    fun matrix(a: Gamut, b: Gamut): FloatArray {
        val wa = if (a === GXYZ) D65 else a.w
        val wb = if (b === GXYZ) D65 else b.w
        val m = mm(inv(toXyz(b)), mm(adapt(wa, wb), toXyz(a)))
        return floatArrayOf(m[0].toFloat(), m[3].toFloat(), m[6].toFloat(), m[1].toFloat(), m[4].toFloat(), m[7].toFloat(), m[2].toFloat(), m[5].toFloat(), m[8].toFloat())
    }

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    // ---- transforms -------------------------------------------------------------------------------------------

    /** Tone mapping methods, as Resolve names them. */
    val toneMaps = listOf("None", "Simple", "Luminance Mapping", "Saturation Preserving", "DaVinci")
    /** Gamut mapping methods. */
    val gamutMaps = listOf("None", "Saturation Compression")

    /** Floats one packed transform takes (see [Xf.pack]). */
    const val XF_SIZE = 20

    /** How much each channel of [g] adds to luminance (the Y row of its RGB to XYZ). */
    fun lumaWeights(g: Gamut): FloatArray {
        val m = toXyz(g)
        return floatArrayOf(m[3].toFloat(), m[4].toFloat(), m[5].toFloat())
    }

    /**
     * One colour space transform, as the shader takes it: from one gamut and curve to another, with tone mapping when
     * the target holds less light and gamut mapping into the target gamut. Matched to Resolve's Color Space Transform
     * by measurement (see the GLSL): the same curves, CAT02 matrices, the DaVinci tone curve, and its levels handling.
     */
    class Xf(
        val fromGamut: Gamut, val fromGamma: Gamma, val toGamut: Gamut, val toGamma: Gamma,
        val tone: String = "DaVinci", val gamutMap: String = "Saturation Compression",
        /** The brightest input in nits; null takes it from the curve (its value at code 1.0), as Resolve's Auto does. */
        val inNits: Float? = null,
        val outNits: Float = 100f,
        /**
         * Data levels against what the decoder assumed, as Resolve's clip setting: 0 as decoded; 1 the clip is set to
         * Full but the file was decoded as video levels (values taken back to code values); -1 the opposite.
         */
        val levels: Int = 0,
    ) {
        /** Nothing to do: same curve and gamut. */
        val identity: Boolean get() = fromGamut === toGamut && fromGamma === toGamma && levels == 0

        /** (on, gamma in, gamma out, tone method), (peak in, peak out, gamut method, levels), the matrix, luminance weights. */
        fun pack(out: FloatArray, at: Int) {
            if (identity) {
                out.fill(0f, at, at + XF_SIZE)
                out[at + 8] = 1f; out[at + 12] = 1f; out[at + 16] = 1f
                return
            }
            // Linear light: 1.0 = SDR white (100 nits on SDR scenes, 203 nits for HDR, ITU-R BT.2408, as Resolve).
            val outPeak = if (toGamma.display) 1f else outNits / 203f
            out[at] = 1f
            out[at + 1] = fromGamma.code.toFloat()
            out[at + 2] = toGamma.code.toFloat()
            out[at + 3] = if (toGamma.display || toGamma.hdr) toneMaps.indexOf(tone).coerceAtLeast(0).toFloat() else 0f
            // Peak in: -1 is "the curve's own" (the shader decodes code 1.0).
            out[at + 4] = inNits?.let { if (fromGamma.hdr) it / 203f else it / 100f } ?: -1f
            out[at + 5] = outPeak
            // Gamut mapping: 0 none, else which measured Saturation Compression table (by the output gamut).
            out[at + 6] = if ((toGamma.display || toGamma.hdr) && gamutMaps.indexOf(gamutMap) > 0) SatCompTable.index(toGamut).toFloat() else 0f
            out[at + 7] = levels.toFloat()
            val m = if (fromGamut === toGamut) IDENTITY else matrix(fromGamut, toGamut)
            System.arraycopy(m, 0, out, at + 8, 9)
            lumaWeights(fromGamut).copyInto(out, at + 17)
        }
    }

    fun xf(from: Space, to: Space, tone: String = "DaVinci", gamutMap: String = "Saturation Compression", outNits: Float? = null, inNits: Float? = null) =
        Xf(from.gamut, from.gamma, to.gamut, to.gamma, tone, gamutMap, inNits, outNits ?: peakNits(to))

    // ---- GLSL -------------------------------------------------------------------------------------------

    private fun f(v: Double) = String.format(Locale.US, "%.10f", v)

    /** ARRI LogC4 constants, from ARRI's specification. */
    private val c4a = (2.0.pow(18) - 16) / 117.45
    private val c4b = (1023.0 - 95) / 1023
    private val c4c = 95.0 / 1023
    private val c4s = (7 * ln(2.0) * 2.0.pow(7 - 14 * c4c / c4b)) / (c4a * c4b)
    private val c4t = (2.0.pow(14 * (-c4c / c4b) + 6) - 64) / c4a

    /**
     * The shader side. `decodeG(c, code)` takes encoded values to linear, `encodeG(l, code)` back; `xform` does one
     * whole transform (curve, matrix, gamut and tone mapping, curve) from a packed [Xf].
     */
    val GLSL: String by lazy {
        "\nconst float SDR_WHITE = 203.0;\n" +
            "const float LC4A = ${f(c4a)}; const float LC4B = ${f(c4b)}; const float LC4C = ${f(c4c)}; const float LC4S = ${f(c4s)}; const float LC4T = ${f(c4t)};\n" +
            CURVES
    }

    private const val CURVES = """
vec3 pw(vec3 c, float g) { return sign(c) * pow(abs(c), vec3(g)); }
// The camera and sRGB curves continue their straight toe below zero (as Resolve does, measured), so colours outside
// the gamut keep their real (negative) values instead of a mirrored curve.
vec3 srgbDec(vec3 c) { return mix(c / 12.92, pow((max(c, 0.0) + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 srgbEnc(vec3 c) { return mix(c * 12.92, 1.055 * pow(max(c, 0.0), vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
vec3 r709Dec(vec3 c) { return mix(c / 4.5, pow((max(c, 0.0) + 0.099) / 1.099, vec3(1.0 / 0.45)), step(0.081, c)); }
vec3 r709Enc(vec3 c) { return mix(c * 4.5, 1.099 * pow(max(c, 0.0), vec3(0.45)) - 0.099, step(0.018, c)); }
vec3 pqDec(vec3 e) {
    vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / 78.84375));
    return pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(1.0 / 0.1593017578125)) * 10000.0;
}
vec3 pqEnc(vec3 nits) {
    vec3 y = pow(clamp(nits / 10000.0, 0.0, 1.0), vec3(0.1593017578125));
    return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), vec3(78.84375));
}
// HLG with the 1000-nit reference display (system gamma 1.2).
vec3 hlgDec(vec3 e) {
    e = max(e, 0.0);
    vec3 s = mix(e * e / 3.0, (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0, step(0.5, e));
    float y = dot(s, vec3(0.2627, 0.6780, 0.0593));
    return s * pow(max(y, 1e-6), 0.2) * 1000.0;
}
vec3 hlgEnc(vec3 nits) {
    vec3 d = max(nits / 1000.0, 0.0);
    float yd = dot(d, vec3(0.2627, 0.6780, 0.0593));
    vec3 s = d * pow(max(yd, 1e-6), -0.2 / 1.2);
    return mix(sqrt(3.0 * s), 0.17883277 * log(max(12.0 * s - 0.28466892, 1e-6)) + 0.55991073, step(1.0 / 12.0, s));
}

// The log curves, three channels at once (both sides of each cut are computed and the right one kept, so every
// expression is kept finite). From the makers' specifications.
vec3 lgv(vec3 v) { return log2(max(v, 1e-30)) * 0.30102999566; }
vec3 p10v(vec3 v) { return exp2(min(v, 30.0) * 3.32192809489); }
vec3 sel(vec3 a, vec3 b, vec3 t) { return mix(a, b, t); }
vec3 dlog0(vec3 y, float g) {
    if (g < 8.5) return sel((exp2(y * 17.52 - 9.72) - 0.0000152587890625) * 2.0, exp2(y * 17.52 - 9.72), step(-0.3013698630, y));
    if (g < 9.5) return sel((y - 0.0729055341958355) / 10.5402377416545, exp2(y * 17.52 - 9.72), step(0.155251141552511, y));
    if (g < 10.5) return sel((y - 0.092809) / 5.367655, (p10v((y - 0.385537) / 0.247190) - 0.052272) / 5.555556, step(0.149658, y));
    if (g < 11.5) return sel(y * LC4S + LC4T, (exp2(min(14.0 * (y - LC4C) / LC4B + 6.0, 60.0)) - 64.0) / LC4A, step(0.0, y));
    if (g < 12.5) return sel((y - 0.09246575342465753) / 8.283605932402494, exp(min((y - 0.5300133392291939) / 0.08692876065491224, 60.0)) - 0.005494072432257808, step(0.13388378, y));
    if (g < 13.5) return sel(-(p10v((0.0730597 - y) / 0.529136) - 1.0) / 10.1596, (p10v((y - 0.0730597) / 0.529136) - 1.0) / 10.1596, step(0.0730597, y));
    if (g < 14.5) return sel(-(p10v((0.092864125 - y) / 0.24136077) - 1.0) / 87.09937546, (p10v((y - 0.092864125) / 0.24136077) - 1.0) / 87.09937546, step(0.092864125, y));
    if (g < 15.5) {
        vec3 a = -(p10v((0.07623209 - y) / 0.42889912) - 1.0) / 14.98325;
        vec3 b = (y - 0.073059361) / 2.3069815;
        vec3 c = (p10v((y - 0.069886632) / 0.42889912) - 1.0) / 14.98325;
        return sel(sel(a, b, step(0.04076162, y)), c, step(0.105357102, y));
    }
    if (g < 16.5) return sel(y / 10.44426855, exp2(min(y / 0.07329248 - 7.0, 60.0)) - 0.0075, step(0.02740668, y));
    if (g < 17.5) return sel((y - 0.0929) / 6.025, (p10v(3.89616 * y - 2.27752) - 0.0108) / 0.9892, step(0.14, y));
    if (g < 18.5) return sel((y - 0.092864) / 8.735631, p10v((y - 0.790453) / 0.344676) / 0.555556 - 0.009468 / 0.555556, step(0.100537775, y));
    if (g < 19.5) return sel((y - 0.092864) / 8.799461, p10v((y - 0.384316) / 0.245281) / 5.555556 - 0.064829 / 5.555556, step(0.100686685, y));
    if (g < 20.5) { vec3 q = max(y * 1023.0 / 650.0, 0.0); return sel(q * q * q - 0.0075, exp(min((y * 1023.0 - 619.0) / 150.0, 60.0)), step(452.0 / 1023.0, y)); }
    if (g < 21.5) return sel((y - 0.125) / 5.6, p10v((y - 0.598206) / 0.241514) - 0.00873, step(0.181, y));
    if (g < 22.5) return sel(y / 15.1927 - 0.01, (p10v(y / 0.224282) - 1.0) / 155.975327 - 0.01, step(0.0, y));
    if (g < 23.5) return 0.9 * sel((y - 0.030001222851889303) / 5.0, p10v((y - 0.646596) / 0.432699) - 0.037584, step(0.030001222851889303, y));
    if (g < 24.5) return 0.9 * sel((y - 0.030001222851889303) / 3.53881278538813, 219.0 * (p10v((y - 0.646596) / 0.432699) - 0.037584) / 155.0, step(0.030001222851889303, y));
    if (g < 25.5) return sel((y * 1023.0 - 95.0) * 0.01125 / (171.2102946929 - 95.0), p10v((y * 1023.0 - 420.0) / 261.5) * 0.19 - 0.01, step(171.2102946929 / 1023.0, y));
    if (g < 26.5) return sel(sqrt(max(y, 0.0) / 47.28711236) - 0.05641088, exp2(min((y - 0.69336945) / 0.08550479, 60.0)) - 0.00964052, step(0.208554, y));
    if (g < 27.5) return sel((y - 0.09) / 8.0, (p10v((y - 0.6) / 0.27) - 0.0115) / 1.3, step(0.138, y));
    if (g < 28.5) return (exp2(min(y * 6.820178962, 60.0)) - 1.0) / 112.0;
    return (p10v((y * 1023.0 - 685.0) * 0.002 / 0.6) - 0.0108) / (1.0 - 0.0108);
}
vec3 elog0(vec3 x, float g) {
    if (g < 8.5) {
        vec3 c = (log2(max(x, 1e-30)) + 9.72) / 17.52;
        vec3 b = (log2(max(0.0000152587890625 + x * 0.5, 1e-30)) + 9.72) / 17.52;
        return sel(sel(vec3(-0.3584474886), b, step(1e-30, x)), c, step(0.000030517578125, x));
    }
    if (g < 9.5) return sel(10.5402377416545 * x + 0.0729055341958355, (log2(max(x, 1e-30)) + 9.72) / 17.52, step(0.0078125, x));
    if (g < 10.5) return sel(5.367655 * x + 0.092809, 0.247190 * lgv(5.555556 * x + 0.052272) + 0.385537, step(0.010591, x));
    if (g < 11.5) return sel((x - LC4T) / LC4S, (log2(max(LC4A * x + 64.0, 1e-30)) - 6.0) / 14.0 * LC4B + LC4C, step(LC4T, x));
    if (g < 12.5) return sel(8.283605932402494 * x + 0.09246575342465753, 0.08692876065491224 * log(max(x + 0.005494072432257808, 1e-30)) + 0.5300133392291939, step(0.005, x));
    if (g < 13.5) { vec3 r = x; return sel(-(0.529136 * lgv(-r * 10.1596 + 1.0)) + 0.0730597, 0.529136 * lgv(10.1596 * r + 1.0) + 0.0730597, step(0.0, r)); }
    if (g < 14.5) { vec3 r = x; return sel(-(0.24136077 * lgv(-r * 87.09937546 + 1.0)) + 0.092864125, 0.24136077 * lgv(87.09937546 * r + 1.0) + 0.092864125, step(0.0, r)); }
    if (g < 15.5) {
        vec3 r = x;
        vec3 a = 0.07623209 - 0.42889912 * lgv(1.0 - 14.98325 * r);
        vec3 b = 2.3069815 * r + 0.073059361;
        vec3 c = 0.42889912 * lgv(14.98325 * r + 1.0) + 0.069886632;
        return sel(sel(a, b, step(-0.014, r)), c, step(0.014, r));
    }
    if (g < 16.5) return sel(x * 10.44426855, (log2(max(x + 0.0075, 1e-30)) + 7.0) * 0.07329248, step(0.00262409, x));
    if (g < 17.5) return sel(6.025 * x + 0.0929, lgv(x * 0.9892 + 0.0108) * 0.256663 + 0.584555, step(0.0078, x));
    if (g < 18.5) return sel(8.735631 * x + 0.092864, 0.344676 * lgv(0.555556 * x + 0.009468) + 0.790453, step(0.00089, x));
    if (g < 19.5) return sel(8.799461 * x + 0.092864, 0.245281 * lgv(5.555556 * x + 0.064829) + 0.384316, step(0.000889, x));
    if (g < 20.5) return sel(650.0 * pow(max(x + 0.0075, 0.0), vec3(1.0 / 3.0)) / 1023.0, (150.0 * log(max(x, 1e-30)) + 619.0) / 1023.0, step(0.328, x));
    if (g < 21.5) return sel(5.6 * x + 0.125, 0.241514 * lgv(x + 0.00873) + 0.598206, step(0.01, x));
    if (g < 22.5) { vec3 v = x + 0.01; return sel(v * 15.1927, 0.224282 * lgv(v * 155.975327 + 1.0), step(0.0, v)); }
    if (g < 23.5) { vec3 r = x / 0.9; return sel(r * 5.0 + 0.030001222851889303, 0.432699 * lgv(r + 0.037584) + 0.646596, step(0.0, r)); }
    if (g < 24.5) { vec3 r = x / 0.9; return sel(r * 3.53881278538813 + 0.030001222851889303, 0.432699 * lgv(155.0 * r / 219.0 + 0.037584) + 0.646596, step(0.0, r)); }
    if (g < 25.5) return sel((x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0, (420.0 + lgv((x + 0.01) / 0.19) * 261.5) / 1023.0, step(0.01125, x));
    if (g < 26.5) { vec3 q = x + 0.05641088; return sel(47.28711236 * q * q * step(0.0, q), 0.08550479 * log2(max(x + 0.00964052, 1e-30)) + 0.69336945, step(0.01, x)); }
    if (g < 27.5) return sel(8.0 * x + 0.09, 0.27 * lgv(1.3 * x + 0.0115) + 0.6, step(0.006, x));
    if (g < 28.5) return log(max(x * 112.0 + 1.0, 1e-30)) / log(113.0);
    return (685.0 + 300.0 * lgv(x * (1.0 - 0.0108) + 0.0108)) / 1023.0;
}

// Canon Log, Canon Log 3, S-Log and S-Log2 are specified in IRE (Canon Log 2 v1.2 already in code values) (video levels); the rest in full-range
// code values. Every curve here takes and gives code values, as Resolve's do.
bool ireCurve(float g) { return (g > 12.5 && g < 13.5) || (g > 14.5 && g < 15.5) || (g > 22.5 && g < 24.5); }
vec3 dlog(vec3 y, float g) { return dlog0(ireCurve(g) ? (y * 1023.0 - 64.0) / 876.0 : y, g); }
vec3 elog(vec3 x, float g) { vec3 v = elog0(x, g); return ireCurve(g) ? (v * 876.0 + 64.0) / 1023.0 : v; }

/** Encoded values in curve [g] to linear (1.0 = SDR white). */
vec3 decodeG(vec3 c, float g) {
    if (g < 0.5) return c;
    if (g < 1.5) return pw(c, 2.2);
    if (g < 2.5) return pw(c, 2.4);
    if (g < 3.5) return pw(c, 2.6);
    if (g < 4.5) return r709Dec(c);
    if (g < 5.5) return srgbDec(c);
    if (g < 6.5) return hlgDec(c) / SDR_WHITE;
    if (g < 7.5) return pqDec(c) / SDR_WHITE;
    if (g > 29.5) return pw(c, 1.961);
    return dlog(c, g);
}

vec3 encodeG(vec3 l, float g) {
    if (g < 0.5) return l;
    if (g < 1.5) return pw(l, 1.0 / 2.2);
    if (g < 2.5) return pw(l, 1.0 / 2.4);
    if (g < 3.5) return pw(l, 1.0 / 2.6);
    if (g < 4.5) return r709Enc(l);
    if (g < 5.5) return srgbEnc(l);
    if (g < 6.5) return hlgEnc(max(l, 0.0) * SDR_WHITE);
    if (g < 7.5) return pqEnc(max(l, 0.0) * SDR_WHITE);
    if (g > 29.5) return pw(l, 1.0 / 1.961);
    return elog(l, g);
}

/** A smooth shoulder: [x] up to [k] unchanged, then easing to reach [hi] at [top]. */
float shoulder(float x, float k, float hi, float top) {
    if (x <= k) return x;
    float t = clamp((x - k) / max(top - k, 1e-4), 0.0, 1.0);
    return k + (hi - k) * (1.0 - (1.0 - t) * (1.0 - t));
}

/**
 * Resolve's DaVinci tone curve, measured from Resolve 21 itself (Fusion's Color Space Transform, 274,625 colours):
 * a·x / (x + k) on each channel of scene light, odd about zero, reaching the target peak [F] (scene units) at the input
 * peak [X], with k = k0·X/(X − 1). k0 is 0.91 at Adaptation 9 for log and linear sources, 1.5306 for HDR video sources.
 */
vec3 dvCurve(vec3 x, float X, float F, float k0) {
    float k = k0 * X / max(X - 1.0, 1e-4);
    float a = F * (X + k) / X;
    return sign(x) * a * abs(x) / (abs(x) + k);
}

/** Tone mapping in the source gamut (as Resolve does it); [yw] the source's luminance weights. */
vec3 toneMap(vec3 l, float m, float X, float F, float k0, vec3 yw) {
    // Simple and DaVinci: each channel on its own.
    if (m < 1.5 || m > 3.5) return dvCurve(l, X, F, k0);
    // Luminance Mapping and Saturation Preserving: the luminance through the curve, the colour ratios kept.
    float y = dot(l, yw);
    if (y <= 1e-7) return l;
    return l * (dvCurve(vec3(y), X, F, k0).x / y);
}

/** k0 of the DaVinci curve for an HDR master of [nits] (measured at 203, 400, 600, 1000, 2000 and 4000 nits). */
float hdrK0(float nits) {
    if (nits <= 203.0) return 0.91;
    if (nits <= 400.0) return mix(0.91, 1.7401, (nits - 203.0) / 197.0);
    if (nits <= 600.0) return mix(1.7401, 2.5636, (nits - 400.0) / 200.0);
    if (nits <= 1000.0) return mix(2.5636, 4.2078, (nits - 600.0) / 400.0);
    if (nits <= 2000.0) return mix(4.2078, 8.4970, (nits - 1000.0) / 1000.0);
    return mix(8.4970, 18.554, (min(nits, 4000.0) - 2000.0) / 2000.0);
}

// Curves that describe a display's light (SDR gammas, sRGB; HDR: HLG, PQ) and those that describe the scene (linear,
// the Rec.709 camera curve, logs). Resolve moves between the two with the BT.709 / BT.1886 OOTF (measured exactly):
// display light = OETF709(scene)^2.4; HDR video is taken to scene light from its nits the same way at 100 nits, scaled.
bool sdrDisplay(float g) { return (g > 0.5 && g < 3.5) || (g > 4.5 && g < 5.5) || g > 29.5; }
bool hdrDisplay(float g) { return g > 5.5 && g < 7.5; }
vec3 spow(vec3 v, float p) { return sign(v) * pow(abs(v), vec3(p)); }
const float HDR_SCENE = 0.827660;   // 1 / (0.595208 · 2.03): HDR display light to scene light, at 203 nits = SDR white

uniform sampler2D uSatComp;
uniform float uSatCompOn;
/**
 * Resolve's Saturation Compression, from its measured table (see SatCompTable): the colour scaled so its largest
 * channel is 1, looked up by the other two on that face of the cube, scaled back. [m]: 0 off, 1 Rec.709, 2 Rec.2020, 3 P3.
 */
vec3 gamutMap(vec3 c, float m) {
    if (m < 0.5 || uSatCompOn < 0.5) return c;
    float mx = max(c.r, max(c.g, c.b));
    if (mx <= 1e-9) return c;
    vec3 n = c / mx;
    float face; vec2 ab;
    if (n.r >= n.g && n.r >= n.b) { face = 0.0; ab = n.gb; }
    else if (n.g >= n.b) { face = 1.0; ab = n.rb; }
    else { face = 2.0; ab = n.rg; }
    vec2 q = (clamp(ab, -3.0, 1.0) + 3.0) * (191.0 / 4.0);
    float row = ((m - 1.0) * 3.0 + face) * 192.0 + q.y;
    return texture2D(uSatComp, vec2((q.x + 0.5) / 192.0, (row + 0.5) / 1728.0)).rgb * mx;
}

/**
 * One packed transform (see ColorSpaces.Xf): A = (on, gamma in, gamma out, tone), B = (peak in, peak out, gamut,
 * levels), [yw] the source's luminance weights. Resolve's order (measured): decode; display light to scene light
 * where the target is a scene curve or tone mapping runs; tone map in the source gamut; matrix; gamut map; clip to the
 * target; scene light back to display light where the target is a display; encode.
 */
vec3 xform(vec3 c, vec4 A, vec4 B, vec3 yw, mat3 M) {
    if (A.x < 0.5) return c;
    if (B.w > 0.5) c = c * (876.0 / 1023.0) + 64.0 / 1023.0;
    else if (B.w < -0.5) c = (c - 64.0 / 1023.0) * (1023.0 / 876.0);
    float gi = A.y, go = A.z;
    bool inSdr = sdrDisplay(gi), inHdr = hdrDisplay(gi), outSdr = sdrDisplay(go), outHdr = hdrDisplay(go);
    vec3 l = decodeG(c, gi);
    // The input's peak: given (B.x, in this curve's linear units), else the curve's own (HDR video: 1,000 nits).
    float Xl = B.x > 0.0 ? B.x : (inHdr ? 1000.0 / SDR_WHITE : decodeG(vec3(1.0), gi).g);
    // The target's peak: SDR 1.0; HDR the master's nits (B.y is nits / 203).
    float outNits = outHdr ? B.y * SDR_WHITE : 100.0;
    bool tm = A.w > 0.5 && (outSdr || outHdr || go == 4.0) && (inHdr ? Xl * SDR_WHITE > outNits * 1.001 : Xl > (outHdr ? outNits / 100.0 : 1.0) * 1.001);
    bool scene = !(inSdr || inHdr);
    float X = Xl;
    if (!scene && (tm || !(outSdr || outHdr))) {
        if (inHdr) {
            // HDR video: clipped at its peak when tone mapped (as Resolve), then to scene light.
            if (tm) l = min(l, vec3(Xl));
            l = r709Dec(spow(l * (SDR_WHITE / 100.0), 1.0 / 2.4)) * HDR_SCENE;
            X = r709Dec(vec3(pow(Xl * SDR_WHITE / 100.0, 1.0 / 2.4))).x * HDR_SCENE;
        } else {
            l = r709Dec(spow(l, 1.0 / 2.4));
            X = r709Dec(vec3(pow(max(Xl, 1e-6), 1.0 / 2.4))).x;
        }
        scene = true;
    }
    // The target's peak in scene light (the light that, shown through the OOTF, reaches it).
    float F = outHdr ? r709Dec(vec3(pow(outNits / 100.0, 1.0 / 2.4))).x : 1.0;
    if (tm) {
        l = toneMap(l, A.w, X, F, inHdr ? 1.5306 : (outHdr ? hdrK0(outNits) : 0.91), yw);
        // For an HDR master Resolve drops negative channels here, before the matrix (SDR keeps them; both measured).
        if (outHdr) l = max(l, 0.0);
    }
    l = M * l;
    l = gamutMap(l, B.z);
    if (tm) l = clamp(l, 0.0, F);
    if (scene && (outSdr || outHdr)) {
        l = spow(r709Enc(l), 2.4);
        if (outHdr) l *= 100.0 / SDR_WHITE;
    }
    return encodeG(l, go);
}
vec3 xform(vec3 c, vec4 A, vec4 B, mat3 M) { return xform(c, A, B, vec3(0.2627, 0.6780, 0.0593), M); }
"""
}

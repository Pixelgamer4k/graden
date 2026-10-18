package com.graden.editor.export

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** A video codec export can write. More (ProRes, AV1, DNxHR…) slot in here later. */
enum class VideoCodec(val label: String, val mime: String) {
    H264("H.264", MediaFormat.MIMETYPE_VIDEO_AVC),
    H265("H.265", MediaFormat.MIMETYPE_VIDEO_HEVC),
}

/** The file wrapper. Both are ISO media files; MOV is marked as QuickTime for editing applications. */
enum class Container(val label: String, val ext: String, val mime: String) {
    MP4("MP4", "mp4", "video/mp4"),
    MOV("QuickTime (MOV)", "mov", "video/quicktime"),
}

/** How the bitrate is chosen. */
enum class QualityMode(val label: String) {
    AUTO_BEST("Automatic · Best"),
    RESTRICT("Restrict to"),
    CONSTANT("Constant quality"),
}

enum class RateMode(val label: String, val value: Int) {
    VBR("Variable (VBR)", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
    CBR("Constant (CBR)", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
}

/** One profile an encoder offers, with the name editors use for it. */
data class ProfileChoice(val label: String, val value: Int, val tenBit: Boolean)

/**
 * An encoder on this device, as Android describes it. Nothing here is assumed about the phone: sizes, rates,
 * bitrates and profiles are whatever the device reports.
 */
data class EncoderInfo(
    val name: String,
    val codec: VideoCodec,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val widthAlign: Int,
    val heightAlign: Int,
    val maxBitrate: Int,
    val maxFps: Int,
    val profiles: List<ProfileChoice>,
    val supportsCbr: Boolean,
    val supportsVbr: Boolean,
    val supportsCq: Boolean,
    val qualityRange: IntRange?,
    private val caps: MediaCodecInfo.VideoCapabilities?,
    /** x264 / x265 inside the app (no size limit, CRF, 10-bit), rather than one of the phone's codecs. */
    val soft: Boolean = false,
    /** For a hardware encoder: it takes 10-bit P010 frames, so it can make Main 10. */
    val p010: Boolean = false,
    /** Can write HDR10 (HEVC Main 10 HDR10, with mastering metadata). HLG needs only 10-bit. */
    val hdr10: Boolean = false,
    /**
     * Built for HDR editing (Android's "hdr-editing" feature, or a ".hdr" encoder): writes the HDR10 metadata into the
     * stream itself. Some phones' plain encoder (Snapdragon 8 Gen 2's) leaves it out, so HDR exports prefer these.
     */
    val hdrEditing: Boolean = false,
    /** Main 10 that the encoder does not list but makes when asked (MediaTek's Dimensity 9300); the file is checked. */
    val unlistedMain10: Boolean = false,
) {
    /** Offers 10-bit output. */
    val tenBit: Boolean get() = profiles.any { it.tenBit }

    /** "Hardware · Qualcomm" or "Software · Android", for the encoder list. */
    val label: String
        get() {
            if (soft) return "Software · " + name
            val maker = when {
                name.startsWith("c2.qti") || name.startsWith("OMX.qcom") -> "Qualcomm"
                name.startsWith("c2.exynos") || name.startsWith("OMX.Exynos") -> "Samsung Exynos"
                name.startsWith("c2.mtk") || name.startsWith("OMX.MTK") -> "MediaTek"
                name.startsWith("c2.unisoc") -> "Unisoc"
                name.contains("google") || name.startsWith("c2.android") -> "Android"
                else -> name.substringBefore('.').ifEmpty { name }
            }
            val variant = name.substringAfterLast("encoder", "").trim('.').takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
            return (if (hardware) "Hardware · " else "Software · ") + maker + variant
        }

    fun supports(w: Int, h: Int, fps: Float): Boolean {
        val c = caps ?: return w in 16..maxWidth && h in 16..maxHeight && w % 2 == 0 && h % 2 == 0
        return runCatching { c.areSizeAndRateSupported(w, h, fps.toDouble()) }.getOrDefault(false) ||
            runCatching { c.isSizeSupported(w, h) }.getOrDefault(false) && fps <= maxFps + 0.5f
    }

    /** Short note of the limits, shown under the encoder's name. */
    val limits: String get() = if (soft) "Any size up to ${maxWidth}×${maxHeight} · CRF · 8 / 10-bit" else
        "Up to ${maxWidth}×${maxHeight} · ${maxBitrate / 1_000_000} Mbps" + if (p010) " · 10-bit" else ""

    companion object {
        /** Offer software encoders (Android's own, and x264 / x265) in the export screen. Off for now. */
        const val SOFTWARE_ENCODERS = false

        /** Every encoder for H.264 and H.265 on this device, hardware first. */
        fun discover(): List<EncoderInfo> {
            val out = ArrayList<EncoderInfo>()
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder) continue
                // The same encoder listed again under an older name.
                if (android.os.Build.VERSION.SDK_INT >= 29 && info.isAlias) continue
                for (codec in VideoCodec.entries) {
                    if (info.supportedTypes.none { it.equals(codec.mime, ignoreCase = true) }) continue
                    val c = runCatching { info.getCapabilitiesForType(codec.mime) }.getOrNull() ?: continue
                    val v = c.videoCapabilities ?: continue
                    // Encoders that cannot take a picture from the GPU (surface input) are left out.
                    if (c.colorFormats.none { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }) continue
                    // Special-purpose encoders that cannot even make 720p (thumbnails, image tiles) are not export choices.
                    if (!runCatching { v.isSizeSupported(1280, 720) || v.isSizeSupported(720, 1280) }.getOrDefault(false)) continue
                    val e = c.encoderCapabilities
                    // 10-bit frames reach a hardware encoder as P010 buffers, where it accepts them.
                    val p010 = android.os.Build.VERSION.SDK_INT >= 33 && c.colorFormats.any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 }
                    var profiles = c.profileLevels.map { it.profile }.distinct().mapNotNull { profileChoice(codec, it, p010) }.sortedBy { it.label }
                    // An HEVC encoder that takes P010 but lists only Main: some (MediaTek) make true Main 10 all the same.
                    // Offered, and the output's profile is checked when the export starts.
                    val unlisted = p010 && codec == VideoCodec.H265 && profiles.none { it.tenBit } && info.name.let { it.startsWith("c2.mtk") || it.startsWith("OMX.MTK") }
                    if (unlisted) profiles = (profiles + ProfileChoice("Main 10", MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, true)).sortedBy { it.label }
                    val hdrEdit = (android.os.Build.VERSION.SDK_INT >= 33 && runCatching { c.isFeatureSupported("hdr-editing") }.getOrDefault(false)) || info.name.endsWith(".hdr")
                    out += EncoderInfo(
                        name = info.name,
                        codec = codec,
                        hardware = if (android.os.Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("c2.android") && !info.name.startsWith("OMX.google"),
                        maxWidth = v.supportedWidths.upper,
                        maxHeight = v.supportedHeights.upper,
                        widthAlign = v.widthAlignment,
                        heightAlign = v.heightAlignment,
                        maxBitrate = v.bitrateRange.upper,
                        maxFps = v.supportedFrameRates.upper,
                        profiles = profiles,
                        supportsCbr = e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
                        supportsVbr = e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
                        supportsCq = e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ),
                        qualityRange = runCatching { e.qualityRange.let { it.lower..it.upper } }.getOrNull(),
                        caps = v,
                        p010 = p010 && profiles.any { it.tenBit },
                        hdr10 = p010 && codec == VideoCodec.H265 && c.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 },
                        hdrEditing = hdrEdit,
                        unlistedMain10 = unlisted,
                    )
                }
            }
            // Only the phone's hardware encoders are offered for now. x264 and x265 stay built in, ready to switch back on.
            out.removeAll { !it.hardware }
            if (SOFTWARE_ENCODERS && SoftEncoder.available) {
                out += EncoderInfo("x264", VideoCodec.H264, false, 8192, 8192, 2, 2, 800_000_000, 240,
                    listOf(ProfileChoice("High", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, false), ProfileChoice("High 10", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10, true)),
                    supportsCbr = true, supportsVbr = true, supportsCq = true, qualityRange = 0..51, caps = null, soft = true)
                out += EncoderInfo("x265", VideoCodec.H265, false, 8192, 8192, 2, 2, 800_000_000, 240,
                    listOf(ProfileChoice("Main", MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, false), ProfileChoice("Main 10", MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, true)),
                    supportsCbr = true, supportsVbr = true, supportsCq = true, qualityRange = 0..51, caps = null, soft = true)
            }
            return out.sortedWith(compareBy<EncoderInfo>({ it.soft }, { !it.hardware }, { it.name }))
        }

        private fun profileChoice(codec: VideoCodec, p: Int, p010: Boolean): ProfileChoice? = when (codec) {
            VideoCodec.H264 -> when (p) {
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> ProfileChoice("Baseline", p, false)
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> ProfileChoice("Main", p, false)
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> ProfileChoice("High", p, false)
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> if (p010) ProfileChoice("High 10", p, true) else null
                else -> null
            }
            VideoCodec.H265 -> when (p) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> ProfileChoice("Main", p, false)
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> if (p010) ProfileChoice("Main 10", p, true) else null
                else -> null
            }
        }
    }
}

/** Everything one export is made with. */
data class ExportSettings(
    val fileName: String,
    val container: Container = Container.MP4,
    val codec: VideoCodec = VideoCodec.H265,
    val encoder: String = "",
    val width: Int,
    val height: Int,
    val quality: QualityMode = QualityMode.AUTO_BEST,
    /** For [QualityMode.RESTRICT]: bits per second. */
    val bitrate: Int = 40_000_000,
    /** For [QualityMode.CONSTANT]: the encoder's quality number. */
    val cq: Int = 80,
    val rateMode: RateMode = RateMode.VBR,
    /** 0: the encoder's best (its highest profile). */
    val profile: Int = 0,
    /** Frames between keyframes; 0 is automatic (one a second). */
    val keyframeEvery: Int = 0,
    val audio: Boolean = true,
    val audioBitrate: Int = 320_000,
    val audioRate: Int = 48_000,
    /** Bring the mix to the project's loudness target. */
    val normalizeLoudness: Boolean = false,
    val loudnessTarget: Float = -14f,
    /** 8 or 10 bits per channel. */
    val bitDepth: Int = 8,
    /** For x264 / x265: speed against compression, "ultrafast" to "veryslow". */
    val speedPreset: String = "medium",
    /** The colour space written out (a name from [com.graden.editor.ColorSpaces.outputs]). */
    val output: String = "Rec.709 Gamma 2.4",
    /** What the footage itself was recorded at, scaled to this export ([SourceRate.floor]); not saved with presets. */
    val sourceFloor: Int = 0,
) {
    /** The bitrate the encoder is given, in bits per second. */
    fun videoBitrate(fps: Float, enc: EncoderInfo?): Int {
        val want = when (quality) {
            // Never less than the original was recorded at: nothing the camera kept is thrown away.
            QualityMode.AUTO_BEST -> maxOf(autoBest(codec, width, height, fps), sourceFloor)
            QualityMode.RESTRICT -> bitrate
            QualityMode.CONSTANT -> maxOf(autoBest(codec, width, height, fps), sourceFloor)
        }
        return enc?.let { minOf(want, it.maxBitrate) } ?: want
    }

    fun toJson(): JSONObject = JSONObject()
        .put("container", container.name).put("codec", codec.name).put("encoder", encoder)
        .put("w", width).put("h", height).put("quality", quality.name).put("bitrate", bitrate).put("cq", cq)
        .put("rate", rateMode.name).put("profile", profile).put("kf", keyframeEvery)
        .put("audio", audio).put("abr", audioBitrate).put("norm", normalizeLoudness).put("depth", bitDepth).put("speed", speedPreset).put("out", output)

    companion object {
        /**
         * "Automatic · Best": a bitrate at which nothing is visibly lost, from the number of pixels per second.
         * About 75 Mbps for H.264 at 4K 30 and 45 Mbps for H.265, 19 / 11 Mbps at 1080p 30.
         */
        fun autoBest(codec: VideoCodec, w: Int, h: Int, fps: Float): Int {
            val bpp = if (codec == VideoCodec.H264) 0.30 else 0.18
            return (w.toLong() * h * fps * bpp).roundToLong().coerceIn(4_000_000L, 400_000_000L).toInt()
        }

        fun fromJson(o: JSONObject, name: String): ExportSettings = ExportSettings(
            fileName = name,
            container = runCatching { Container.valueOf(o.getString("container")) }.getOrDefault(Container.MP4),
            codec = runCatching { VideoCodec.valueOf(o.getString("codec")) }.getOrDefault(VideoCodec.H265),
            encoder = o.optString("encoder"),
            width = o.optInt("w", 1920), height = o.optInt("h", 1080),
            quality = runCatching { QualityMode.valueOf(o.getString("quality")) }.getOrDefault(QualityMode.AUTO_BEST),
            bitrate = o.optInt("bitrate", 40_000_000), cq = o.optInt("cq", 80),
            rateMode = runCatching { RateMode.valueOf(o.getString("rate")) }.getOrDefault(RateMode.VBR),
            profile = o.optInt("profile", 0), keyframeEvery = o.optInt("kf", 0),
            audio = o.optBoolean("audio", true), audioBitrate = o.optInt("abr", 320_000),
            normalizeLoudness = o.optBoolean("norm", false),
            bitDepth = o.optInt("depth", 8), speedPreset = o.optString("speed", "medium"), output = o.optString("out", "Rec.709 Gamma 2.4"),
        )
    }
}

/** A named starting point. [apply] fills in the settings for a project of [w]×[h]. */
data class ExportPreset(val name: String, val note: String, val user: Boolean = false, val apply: (ExportSettings, Int, Int) -> ExportSettings)

object ExportPresets {
    /** Scales the project's shape to [shortSide] on its short side. */
    private fun scaled(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val k = shortSide.toFloat() / minOf(w, h)
        fun even(x: Float) = (x.toInt() / 2 * 2).coerceAtLeast(2)
        return even(w * k) to even(h * k)
    }

    val builtIn: List<ExportPreset> = listOf(
        ExportPreset("Master", "H.265 · timeline size · Best") { s, w, h ->
            s.copy(codec = VideoCodec.H265, container = Container.MOV, width = w, height = h, quality = QualityMode.AUTO_BEST, audioBitrate = 320_000)
        },
        ExportPreset("YouTube 4K", "H.264 · 2160p · Best") { s, w, h ->
            val (a, b) = scaled(w, h, 2160)
            s.copy(codec = VideoCodec.H264, container = Container.MP4, width = a, height = b, quality = QualityMode.AUTO_BEST, audioBitrate = 320_000)
        },
        ExportPreset("YouTube 1080p", "H.264 · 1080p · Best") { s, w, h ->
            val (a, b) = scaled(w, h, 1080)
            s.copy(codec = VideoCodec.H264, container = Container.MP4, width = a, height = b, quality = QualityMode.AUTO_BEST, audioBitrate = 320_000)
        },
        ExportPreset("Reel / Short", "H.264 · 1080 short side · 25 Mbps") { s, w, h ->
            val (a, b) = scaled(w, h, 1080)
            s.copy(codec = VideoCodec.H264, container = Container.MP4, width = a, height = b, quality = QualityMode.RESTRICT, bitrate = 25_000_000, audioBitrate = 256_000)
        },
    )

    private const val PREFS = "export_presets"

    fun loadUser(context: Context): List<Pair<String, JSONObject>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]") ?: "[]"
        val a = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("name") to it.getJSONObject("s") } }
    }

    fun saveUser(context: Context, name: String, s: ExportSettings) {
        val list = loadUser(context).filter { it.first != name } + (name to s.toJson())
        val a = JSONArray()
        for ((n, j) in list) a.put(JSONObject().put("name", n).put("s", j))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }

    fun deleteUser(context: Context, name: String) {
        val a = JSONArray()
        for ((n, j) in loadUser(context).filter { it.first != name }) a.put(JSONObject().put("name", n).put("s", j))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }
}

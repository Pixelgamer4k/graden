package com.graden.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLES30
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Develops a MotionCam RAW recording into its master: every frame, at full size, with the sensor's black and white
 * levels and lens shading corrected and the colour demosaiced (Malvar–He–Cutler), still as the camera saw it (not
 * white balanced, no colour matrix), stored in a log curve ([RawColor.encode]) as 10-bit HEVC where the phone can,
 * with the recording's sound. The white balance, colour and exposure are applied live in the grade (see the raw stage
 * in Grade.kt), so they can be changed at any time without developing again.
 */
/**
 * How a MotionCam RAW recording's master is developed (the MCRAW panel's Develop section), after MotionCam's own
 * tools and the open MCRAW projects (MotionCam Fuse, mcraw-tui, mcraw4vulkan, MCRAW Studio): draft quality, vignette
 * correction from the per-frame gain maps (or only its colour part), completion of clipped photosites, hot pixels,
 * exposure and white balance normalised (or smoothed) across frames, a constant frame rate, quad-Bayer binning and the
 * data levels. Changing any of them develops the master again.
 */
data class DevelopOptions(
    /** Draft: half resolution, each 2 × 2 group averaged. */
    val half: Boolean = false,
    /** [VIGNETTE_OFF], [VIGNETTE_FULL] or [VIGNETTE_COLOUR] (only the colour part of the gain map: the natural fall-off stays). */
    val vignette: Int = VIGNETTE_FULL,
    /** Clipped photosites completed from their unclipped neighbours (mcraw-tui's censored-photosite rule). */
    val highlights: Boolean = false,
    val hotPixels: Boolean = false,
    /** [AS_RECORDED], [NORMALISE] (ISO and shutter changes taken out) or [SMOOTH] (changes eased over about a second). */
    val exposure: Int = AS_RECORDED,
    /** [AS_RECORDED] (the first frame's white balance throughout), [FOLLOW] (the camera's, frame by frame) or [SMOOTH]. */
    val whiteBalance: Int = AS_RECORDED,
    /** [AS_RECORDED] (the recorded timestamps), [CFR_STANDARD] (nearest standard rate, frames repeated or dropped in real time) or [CFR_MEDIAN]. */
    val frameRate: Int = AS_RECORDED,
    /** Photosites per side of each colour block: 1 Bayer, 2 quad, 3 nona, 4 hexadeca (binned to Bayer). */
    val cfa: Int = 1,
    /** The file's fixed black and white levels instead of each frame's own. */
    val staticLevels: Boolean = false,
    /**
     * Black levels set by hand, per colour as R, Gr, Gb, B (null: the recording's own). Older MotionCam versions
     * recorded some clips (12/14-bit DCG among them) with black 0, which leaves the shadows lifted and magenta.
     */
    val black: List<Float>? = null,
    /** White level set by hand (0: the recording's own). */
    val white: Float = 0f,
) {
    /** Black per site of the 2 × 2 pattern [cfa] (0 R, 1 G, 2 B) from the per-colour values: Gr shares a row with red. */
    fun blackPerSite(cfa: IntArray): FloatArray? = black?.let { b ->
        val redRow = (cfa.indexOf(0).coerceAtLeast(0)) / 2
        FloatArray(4) { s -> when (cfa[s]) { 0 -> b[0]; 2 -> b[3]; else -> if (s / 2 == redRow) b[1] else b[2] } }
    }
    val shading get() = vignette != VIGNETTE_OFF
    /** What the master file's name carries beyond quality and shading. */
    val code: String get() = buildString {
        if (vignette == VIGNETTE_COLOUR) append("vc"); if (highlights) append("hl"); if (hotPixels) append("hp")
        if (exposure != 0) append("e$exposure"); if (whiteBalance != 0) append("w$whiteBalance"); if (frameRate != 0) append("r$frameRate")
        if (cfa > 1) append("q$cfa"); if (staticLevels) append("ls")
        black?.let { b -> append("lb" + b.joinToString("-") { it.toInt().toString() }) }; if (white > 0f) append("lw${white.toInt()}")
    }
    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("half", half).put("vig", vignette).put("hl", highlights).put("hp", hotPixels)
        .put("exp", exposure).put("wb", whiteBalance).put("fr", frameRate).put("cfa", cfa).put("sl", staticLevels)
        .apply { black?.let { put("bk", org.json.JSONArray(it.map { v -> v.toDouble() })) }; if (white > 0f) put("wt", white.toDouble()) }

    companion object {
        const val VIGNETTE_OFF = 0; const val VIGNETTE_FULL = 1; const val VIGNETTE_COLOUR = 2
        const val AS_RECORDED = 0; const val NORMALISE = 1; const val FOLLOW = 1; const val SMOOTH = 2
        const val CFR_STANDARD = 1; const val CFR_MEDIAN = 2
        val STANDARD_RATES = doubleArrayOf(23.976, 24.0, 25.0, 29.97, 30.0, 48.0, 50.0, 59.94, 60.0, 90.0, 100.0, 119.88, 120.0, 240.0)
        /** The options an asset was developed with ([json] wins; older projects only have half and shading). */
        fun of(json: String?, half: Boolean, shading: Boolean): DevelopOptions = json?.let { runCatching {
            val o = org.json.JSONObject(it)
            DevelopOptions(o.optBoolean("half", half), o.optInt("vig", if (shading) 1 else 0), o.optBoolean("hl"), o.optBoolean("hp"),
                o.optInt("exp"), o.optInt("wb"), o.optInt("fr"), o.optInt("cfa", 1).coerceIn(1, 4), o.optBoolean("sl"),
                o.optJSONArray("bk")?.takeIf { a -> a.length() == 4 }?.let { a -> List(4) { i -> a.optDouble(i).toFloat() } }, o.optDouble("wt", 0.0).toFloat())
        }.getOrNull() } ?: DevelopOptions(half = half, vignette = if (shading) VIGNETTE_FULL else VIGNETTE_OFF)
        /** The standard rate nearest [fps]. */
        fun nearestStandard(fps: Double) = STANDARD_RATES.minBy { kotlin.math.abs(it - fps) }
    }
}

object RawDevelop {
    const val VERSION = 1

    class Result(val file: File, val info: McRawInfo, val width: Int, val height: Int, val tenBit: Boolean)

    /** The master for [uri] lives here (one per recording, develop version and options). */
    fun masterFile(context: Context, uri: Uri, sizeBytes: Long, half: Boolean = false, shading: Boolean = true): File =
        masterFile(context, uri, sizeBytes, DevelopOptions(half = half, vignette = if (shading) DevelopOptions.VIGNETTE_FULL else DevelopOptions.VIGNETTE_OFF))

    fun masterFile(context: Context, uri: Uri, sizeBytes: Long, o: DevelopOptions): File =
        File(File(context.filesDir, "raw").apply { mkdirs() },
            "${uri.toString().hashCode().toUInt()}_${sizeBytes}_d$VERSION${if (o.half) "h" else "f"}${if (o.shading) "s" else "n"}${o.code.let { if (it.isEmpty()) "" else "_$it" }}.mp4")

    private const val VERTEX = """#version 300 es
in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    private const val FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
uniform usampler2D uRaw;
uniform sampler2D uShade;
uniform vec4 uBlack;
uniform float uWhite;
uniform ivec4 uCfa;
uniform ivec2 uSize;
uniform ivec2 uOut;
uniform int uRot;
uniform int uShadeOn;
uniform int uShadeMode;
uniform int uHalf;
uniform vec3 uGain;
uniform sampler2D uPre;
uniform int uPreOn;
out vec4 o;

int site(ivec2 p) { return (p.y & 1) * 2 + (p.x & 1); }
ivec2 reflectP(ivec2 p) {
    // Mirrored about the edges, two at a time, so each sample keeps its colour.
    if (p.x < 0) p.x = -p.x; if (p.y < 0) p.y = -p.y;
    if (p.x >= uSize.x) p.x = 2 * uSize.x - 2 - p.x; if (p.y >= uSize.y) p.y = 2 * uSize.y - 2 - p.y;
    return p;
}
float v(ivec2 p) {
    p = reflectP(p);
    // Completed / binned already (the pre pass): levels, shading and gains are in.
    if (uPreOn == 1) return texelFetch(uPre, p, 0).r;
    int s = site(p);
    float b = s == 0 ? uBlack.x : s == 1 ? uBlack.y : s == 2 ? uBlack.z : uBlack.w;
    float x = (float(texelFetch(uRaw, p, 0).r) - b) / (uWhite - b);
    if (uShadeOn == 1) {
        vec2 uv = vec2(p) / vec2(uSize - 1);
        vec2 ms = vec2(textureSize(uShade, 0));
        vec4 g = texture(uShade, (uv * (ms - 1.0) + 0.5) / ms);
        int c = s == 0 ? uCfa.x : s == 1 ? uCfa.y : s == 2 ? uCfa.z : uCfa.w;
        // Colour only: each gain over the smallest at that place, so the natural fall-off stays.
        if (uShadeMode == 2) g /= max(min(min(g.r, g.g), min(g.b, g.a)), 1e-4);
        // Android's map: red, green on even rows, green on odd rows, blue.
        x *= c == 0 ? g.r : c == 2 ? g.a : ((p.y & 1) == 0 ? g.g : g.b);
    }
    int cc = s == 0 ? uCfa.x : s == 1 ? uCfa.y : s == 2 ? uCfa.z : uCfa.w;
    return x * (cc == 0 ? uGain.r : cc == 2 ? uGain.b : uGain.g);
}
void main() {
    // Output pixel (top row first) to sensor pixel, turned as the recording says.
    ivec2 q = ivec2(int(gl_FragCoord.x), uOut.y - 1 - int(gl_FragCoord.y));
    ivec2 S = uHalf == 1 ? uSize / 2 : uSize;
    ivec2 p = uRot == 90 ? ivec2(q.y, S.y - 1 - q.x) : uRot == 180 ? ivec2(S.x - 1 - q.x, S.y - 1 - q.y)
        : uRot == 270 ? ivec2(S.x - 1 - q.y, q.x) : q;
    if (uHalf == 1) {
        // Half resolution: each 2 × 2 group of sites is one pixel, its own red, blue and the two greens averaged.
        ivec2 b = p * 2;
        float s0 = v(b), s1 = v(b + ivec2(1, 0)), s2 = v(b + ivec2(0, 1)), s3 = v(b + ivec2(1, 1));
        vec3 acc = vec3(0.0); vec3 n = vec3(0.0);
        if (uCfa.x == 0) { acc.r += s0; n.r += 1.0; } else if (uCfa.x == 2) { acc.b += s0; n.b += 1.0; } else { acc.g += s0; n.g += 1.0; }
        if (uCfa.y == 0) { acc.r += s1; n.r += 1.0; } else if (uCfa.y == 2) { acc.b += s1; n.b += 1.0; } else { acc.g += s1; n.g += 1.0; }
        if (uCfa.z == 0) { acc.r += s2; n.r += 1.0; } else if (uCfa.z == 2) { acc.b += s2; n.b += 1.0; } else { acc.g += s2; n.g += 1.0; }
        if (uCfa.w == 0) { acc.r += s3; n.r += 1.0; } else if (uCfa.w == 2) { acc.b += s3; n.b += 1.0; } else { acc.g += s3; n.g += 1.0; }
        o = vec4(log2(1.0 + 4096.0 * clamp(acc / max(n, vec3(1.0)), 0.0, 4.0)) / 14.0, 1.0);
        return;
    }
    float C = v(p);
    float N1 = v(p + ivec2(0, -1)), S1 = v(p + ivec2(0, 1)), W1 = v(p + ivec2(-1, 0)), E1 = v(p + ivec2(1, 0));
    float N2 = v(p + ivec2(0, -2)), S2 = v(p + ivec2(0, 2)), W2 = v(p + ivec2(-2, 0)), E2 = v(p + ivec2(2, 0));
    float D = v(p + ivec2(-1, -1)) + v(p + ivec2(1, -1)) + v(p + ivec2(-1, 1)) + v(p + ivec2(1, 1));
    float far = N2 + S2 + W2 + E2;
    // Malvar–He–Cutler: each missing colour from its neighbours and this site's own detail.
    float gAt = (4.0 * C + 2.0 * (N1 + S1 + W1 + E1) - far) / 8.0;
    float oppAt = (6.0 * C + 2.0 * D - 1.5 * far) / 8.0;
    float rowAt = (5.0 * C + 4.0 * (W1 + E1) - (W2 + E2) - D + 0.5 * (N2 + S2)) / 8.0;
    float colAt = (5.0 * C + 4.0 * (N1 + S1) - (N2 + S2) - D + 0.5 * (W2 + E2)) / 8.0;
    int s = site(p);
    int c = s == 0 ? uCfa.x : s == 1 ? uCfa.y : s == 2 ? uCfa.z : uCfa.w;
    vec3 rgb;
    if (c == 0) rgb = vec3(C, gAt, oppAt);
    else if (c == 2) rgb = vec3(oppAt, gAt, C);
    else {
        int h = s ^ 1;
        int hc = h == 0 ? uCfa.x : h == 1 ? uCfa.y : h == 2 ? uCfa.z : uCfa.w;
        rgb = hc == 0 ? vec3(rowAt, C, colAt) : vec3(colAt, C, rowAt);
    }
    o = vec4(log2(1.0 + 4096.0 * clamp(rgb, 0.0, 4.0)) / 14.0, 1.0);
}
"""

    /**
     * The pre pass, into a float picture the size of the (binned) Bayer mosaic: levels, gain maps and gains, then
     * each colour block averaged (quad, nona, hexadeca sensors), hot pixels replaced by their neighbours, and clipped
     * photosites completed as mcraw-tui does: from the unclipped neighbours in the 3 × 3 around it, each giving the
     * brightness it implies along the frame's neutral; their median, along this site's colour, never below the clip.
     */
    private const val PRE = """#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
uniform usampler2D uRaw;
uniform sampler2D uShade;
uniform vec4 uBlack;
uniform float uWhite;
uniform ivec4 uCfa;
uniform ivec2 uSize;
uniform int uShadeOn;
uniform int uShadeMode;
uniform vec3 uGain;
uniform vec3 uNeutral;
uniform int uN;
uniform int uHl;
uniform int uHot;
out vec4 o;

ivec2 B;
int site(ivec2 q) { return (q.y & 1) * 2 + (q.x & 1); }
int colour(ivec2 q) { int s = site(q); return s == 0 ? uCfa.x : s == 1 ? uCfa.y : s == 2 ? uCfa.z : uCfa.w; }
ivec2 refl(ivec2 q) {
    if (q.x < 0) q.x = -q.x; if (q.y < 0) q.y = -q.y;
    if (q.x >= B.x) q.x = 2 * B.x - 2 - q.x; if (q.y >= B.y) q.y = 2 * B.y - 2 - q.y;
    return q;
}
float gainOf(int c) { return c == 0 ? uGain.r : c == 2 ? uGain.b : uGain.g; }
float neutralOf(int c) { return c == 0 ? uNeutral.r : c == 2 ? uNeutral.b : uNeutral.g; }
/** Block q of the mosaic: its value, whether any of it was clipped, and the value the clip itself stands for. */
float block(ivec2 q, out bool clipped, out float rail) {
    q = refl(q);
    int s = site(q); int c = colour(q);
    float b = s == 0 ? uBlack.x : s == 1 ? uBlack.y : s == 2 ? uBlack.z : uBlack.w;
    float sh = 1.0;
    if (uShadeOn == 1) {
        vec2 uv = (vec2(q) + 0.5) / vec2(B);
        vec2 ms = vec2(textureSize(uShade, 0));
        vec4 g = texture(uShade, (uv * (ms - 1.0) + 0.5) / ms);
        if (uShadeMode == 2) g /= max(min(min(g.r, g.g), min(g.b, g.a)), 1e-4);
        sh = c == 0 ? g.r : c == 2 ? g.a : ((q.y & 1) == 0 ? g.g : g.b);
    }
    float k = sh * gainOf(c) / (uWhite - b);
    float acc = 0.0; clipped = false;
    for (int y = 0; y < uN; y++) for (int x = 0; x < uN; x++) {
        float r = float(texelFetch(uRaw, q * uN + ivec2(x, y), 0).r);
        if (r >= uWhite - 1.0) clipped = true;
        acc += r - b;
    }
    rail = (uWhite - b) * k;
    return acc / float(uN * uN) * k;
}
void main() {
    B = uSize / uN;
    ivec2 q = ivec2(gl_FragCoord.xy);
    bool cl; float rail;
    float val = block(q, cl, rail);
    int c = colour(q);
    if (cl && uHl == 1) {
        // Completion: the brightness each unclipped neighbour implies along the neutral, their median.
        float sc[8]; int n = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            bool c2; float r2;
            float w = block(q + ivec2(dx, dy), c2, r2);
            if (!c2 && w > 0.02) { sc[n] = w / max(neutralOf(colour(q + ivec2(dx, dy))), 1e-4); n++; }
        }
        float s;
        // Nothing unclipped around: the darkest neutral every channel's clip allows.
        if (n == 0) s = rail / max(min(uNeutral.r, min(uNeutral.g, uNeutral.b)), 1e-4);
        else {
            for (int i = 1; i < 8; i++) { if (i >= n) break; float t = sc[i]; int j = i - 1; while (j >= 0 && sc[j] > t) { sc[j + 1] = sc[j]; j--; } sc[j + 1] = t; }
            s = (n % 2 == 1) ? sc[n / 2] : 0.5 * (sc[n / 2 - 1] + sc[n / 2]);
        }
        val = max(val, neutralOf(c) * s);
    } else if (!cl && uHot == 1) {
        // A hot pixel: far above all four same-coloured neighbours two away; replaced by their middle two.
        bool d; float r;
        float a = block(q + ivec2(2, 0), d, r), e = block(q - ivec2(2, 0), d, r), f = block(q + ivec2(0, 2), d, r), g = block(q - ivec2(0, 2), d, r);
        float mx = max(max(a, e), max(f, g));
        if (val > 2.0 * mx + 0.03) {
            float lo = min(min(a, e), min(f, g));
            val = (a + e + f + g - mx - lo) * 0.5;
        }
    }
    o = vec4(val, 0.0, 0.0, 1.0);
}
"""

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("develop shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun hevc10(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
        }
    }.getOrDefault(false)

    /** The largest size (same shape) an encoder of [mime] takes, up to [w] × [h]. */
    private fun fit(mime: String, w: Int, h: Int): Pair<Int, Int> {
        val caps = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
                .map { it.getCapabilitiesForType(mime).videoCapabilities }
        }.getOrDefault(emptyList())
        var s = 1f
        while (s > 0.2f) {
            val ww = ((w * s).toInt() / 16 * 16).coerceAtLeast(16); val hh = ((h * s).toInt() / 2 * 2).coerceAtLeast(16)
            if (caps.any { it.isSizeSupported(ww, hh) }) return ww to hh
            s -= 0.125f
        }
        return (w / 2 / 16 * 16) to (h / 2 / 2 * 2)
    }

    /** Develops [uri] into [out], reporting progress 0..1; [cancelled] stops it (and nothing is left behind). */
    fun develop(context: Context, uri: Uri, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean, half: Boolean = false, shading: Boolean = true): Result? =
        develop(context, uri, out, progress, cancelled, DevelopOptions(half = half, vignette = if (shading) DevelopOptions.VIGNETTE_FULL else DevelopOptions.VIGNETTE_OFF))

    /**
     * The black and white levels the sensor data itself shows, from a few frames: per colour (R, Gr, Gb, B) the
     * level the darkest photosites sit on, snapped to the usual values (64, 256, 1024 …) when close, and the highest
     * value reached (the white). Null black when the frames have nothing near black (a bright scene).
     */
    class Levels(val black: List<Float>?, val white: Float, val recordedBlack: List<Float>, val recordedWhite: Float)

    fun detectLevels(context: Context, uri: Uri): Levels? = runCatching {
        McRaw.open(context, uri).use { raw ->
            val info = raw.info
            val frames = raw.frames
            if (frames.isEmpty()) return null
            val buf = ByteBuffer.allocateDirect(info.width * info.height * 2).order(ByteOrder.nativeOrder())
            val redRow = info.cfa.indexOf(0).coerceAtLeast(0) / 2
            fun colourOf(s: Int) = when (info.cfa[s]) { 0 -> 0; 2 -> 3; else -> if (s / 2 == redRow) 1 else 2 }
            val samples = Array(4) { ArrayList<Int>() }
            var top = 0
            for (ts in listOf(frames.first(), frames[frames.size / 2], frames.last()).distinct()) {
                val meta = raw.load(ts, buf)
                val w = meta.optInt("width", info.width); val h = meta.optInt("height", info.height)
                val sb = buf.asShortBuffer()
                // Every 7th row and column (odd, so all four sites are met).
                var y = 0
                while (y < h) {
                    var x = 0
                    while (x < w) {
                        val v = sb.get(y * w + x).toInt() and 0xFFFF
                        samples[colourOf((y and 1) * 2 + (x and 1))] += v
                        if (v > top) top = v
                        x += 7
                    }
                    y += 7
                }
            }
            val common = floatArrayOf(16f, 32f, 64f, 128f, 256f, 512f, 1024f, 2048f, 4096f)
            val rw = listOf(1023f, 4095f, 16383f, 65535f).firstOrNull { top <= it } ?: top.toFloat()
            val black = samples.map { s ->
                if (s.isEmpty()) return@map null
                s.sort()
                // The black is where the darkest photosites gather: the peak of the low half of the values (smoothed),
                // which noise spreads but does not move.
                val lo = s[(s.size * 0.001).toInt()]
                val hi = min(s[s.size / 2], lo + (rw * 0.1f).toInt())
                if (lo > rw * 0.2f) return@map null
                val hist = IntArray(hi - lo + 1)
                for (v in s) { if (v < lo) continue; if (v > hi) break; hist[v - lo]++ }
                val half = max(1, (hi - lo) / 80)
                var best = 0; var bestN = -1L
                var acc = 0L
                for (i in 0 until min(hist.size, 2 * half + 1)) acc += hist[i]
                for (i in hist.indices) {
                    if (acc > bestN) { bestN = acc; best = i }
                    val out = i - half; val inn = i + half + 1
                    if (out >= 0) acc -= hist[out]; if (inn < hist.size) acc += hist[inn]
                }
                val e = (lo + best).toFloat()
                if (e < 8f) return@map 0f
                val near = common.minBy { kotlin.math.abs(it - e) }
                if (kotlin.math.abs(near - e) <= near * 0.15f) near else e
            }
            val recorded = List(4) { s -> info.black[s] }.let { b -> List(4) { c -> b[(0 until 4).first { colourOf(it) == c }] } }
            Levels(if (black.any { it == null }) null else black.map { it!! }, rw, recorded, info.white)
        }
    }.getOrNull()

    /** Gaussian smoothing of [v] over [sigma] samples. */
    private fun gauss(v: DoubleArray, sigma: Double): DoubleArray {
        if (sigma < 0.5 || v.size < 3) return v.copyOf()
        val r = (3 * sigma).toInt() + 1
        return DoubleArray(v.size) { i ->
            var a = 0.0; var w = 0.0
            for (j in max(0, i - r)..min(v.size - 1, i + r)) { val k = kotlin.math.exp(-0.5 * ((j - i) / sigma).let { it * it }); a += v[j] * k; w += k }
            a / w
        }
    }

    fun develop(context: Context, uri: Uri, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean, opts: DevelopOptions): Result? {
        val tmp = File(out.path + ".part")
        tmp.delete()
        McRaw.open(context, uri).use { raw ->
            val info = raw.info
            val frames = raw.frames
            if (frames.isEmpty() || info.width <= 0) return null
            val n = opts.cfa.coerceIn(1, 4)
            val half = opts.half && n == 1
            val upright = info.orientation == 90 || info.orientation == 270
            val tenBit = hevc10()
            val mime = if (tenBit) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val bw = info.width / n / 2 * 2; val bh = info.height / n / 2 * 2
            val sw = if (half) bw / 2 else bw; val sh = if (half) bh / 2 else bh
            val (ow, oh) = fit(mime, if (upright) sh else sw, if (upright) sw else sh)
            // Per frame: exposure (ISO × shutter) and the camera's neutral, for normalising or smoothing them.
            val needMeta = opts.exposure != DevelopOptions.AS_RECORDED || opts.whiteBalance != DevelopOptions.AS_RECORDED
            val metas = if (needMeta) frames.map { raw.frameMetadata(it) } else emptyList()
            val gainExp = DoubleArray(frames.size) { 1.0 }
            val fpsRec = info.fps.toDouble().coerceAtLeast(1.0)
            if (opts.exposure != DevelopOptions.AS_RECORDED) {
                val lnE = DoubleArray(frames.size) { k ->
                    val m = metas[k]; val e = m.optDouble("iso", info.iso.toDouble()).coerceAtLeast(1.0) * m.optDouble("exposureTime", info.exposureNs).coerceAtLeast(1.0)
                    kotlin.math.ln(e)
                }
                val ref = if (opts.exposure == DevelopOptions.SMOOTH) gauss(lnE, fpsRec * 0.5) else DoubleArray(frames.size) { lnE[0] }
                for (k in frames.indices) gainExp[k] = kotlin.math.exp(ref[k] - lnE[k])
            }
            // White balance: factors that make the master's frames as if shot with the target neutral (the first
            // frame's stays the as-shot one the grade uses).
            val wbFac = Array(frames.size) { doubleArrayOf(1.0, 1.0, 1.0) }
            if (opts.whiteBalance != DevelopOptions.AS_RECORDED) {
                val ns = Array(frames.size) { k ->
                    val a = metas[k].optJSONArray("asShotNeutral")
                    val v = DoubleArray(3) { a?.optDouble(it, info.asShot[it].toDouble()) ?: info.asShot[it].toDouble() }.map { it.coerceAtLeast(1e-4) }
                    doubleArrayOf(v[0] / v[1], 1.0, v[2] / v[1])
                }
                val t = if (opts.whiteBalance == DevelopOptions.SMOOTH) {
                    val r = gauss(DoubleArray(ns.size) { kotlin.math.ln(ns[it][0]) }, fpsRec * 0.75); val b = gauss(DoubleArray(ns.size) { kotlin.math.ln(ns[it][2]) }, fpsRec * 0.75)
                    Array(ns.size) { doubleArrayOf(kotlin.math.exp(r[it]), 1.0, kotlin.math.exp(b[it])) }
                } else ns
                for (k in frames.indices) wbFac[k] = doubleArrayOf(ns[0][0] / t[k][0], 1.0, ns[0][2] / t[k][2])
            }
            // Which recorded frame each output frame is, and when it is shown.
            val gaps = (1 until frames.size).map { frames[it] - frames[it - 1] }.sorted()
            val medianGap = if (gaps.isEmpty()) (1e9 / fpsRec).toLong() else gaps[gaps.size / 2].coerceAtLeast(1L)
            val (schedule, fps) = when (opts.frameRate) {
                DevelopOptions.CFR_MEDIAN -> frames.indices.map { it to it * medianGap } to (1e9 / medianGap).toFloat()
                DevelopOptions.CFR_STANDARD -> {
                    val target = DevelopOptions.nearestStandard(1e9 / medianGap)
                    val step = 1e9 / target
                    val span = frames.last() - frames.first()
                    val count = (span / step).toInt() + 1
                    var j = 0
                    (0 until count).map { i ->
                        val tt = frames.first() + i * step
                        while (j + 1 < frames.size && kotlin.math.abs(frames[j + 1] - tt) <= kotlin.math.abs(frames[j] - tt)) j++
                        j to (i * step).toLong()
                    } to target.toFloat()
                }
                else -> frames.indices.map { it to (frames[it] - info.firstNs) } to info.fps
            }
            val fmt = MediaFormat.createVideoFormat(mime, ow, oh).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // A master, so generous: about 0.2 bits a pixel at 10 bits, more for 8.
                setInteger(MediaFormat.KEY_BIT_RATE, (ow.toLong() * oh * fps * (if (tenBit) 0.2f else 0.3f)).toLong().coerceIn(20_000_000, 200_000_000).toInt())
                setFloat(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (tenBit) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            val enc = MediaCodec.createEncoderByType(mime)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = enc.createInputSurface()
            enc.start()
            val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val gl = DevelopGl(input, tenBit, half, opts)
            var ok = false
            try {
                // The sound first, as AAC in memory; it joins the file once the picture's track is known.
                val audio = encodeAudio(raw.audio(), info.firstNs)
                var videoTrack = -1; var audioTrack = -1; var started = false
                val pending = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
                val bi = MediaCodec.BufferInfo()
                fun drain(end: Boolean) {
                    while (true) {
                        val i = enc.dequeueOutputBuffer(bi, if (end) 10_000 else 0)
                        if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return else continue }
                        if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            videoTrack = muxer.addTrack(enc.outputFormat)
                            if (audio != null) audioTrack = muxer.addTrack(audio.first)
                            muxer.start(); started = true
                            if (audio != null) for ((b, inf) in audio.second) muxer.writeSampleData(audioTrack, b, inf)
                            for ((b, inf) in pending) muxer.writeSampleData(videoTrack, b, inf)
                            pending.clear()
                            continue
                        }
                        if (i < 0) continue
                        val b = enc.getOutputBuffer(i)!!
                        if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                        if (bi.size > 0) {
                            if (started) muxer.writeSampleData(videoTrack, b, bi)
                            else { val c = ByteBuffer.allocate(bi.size); b.position(bi.offset); b.limit(bi.offset + bi.size); c.put(b); c.flip(); pending += c to MediaCodec.BufferInfo().apply { set(0, bi.size, bi.presentationTimeUs, bi.flags) } }
                        }
                        val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        enc.releaseOutputBuffer(i, false)
                        if (eos) return
                    }
                }
                val buf = ByteBuffer.allocateDirect(info.width * info.height * 2).order(ByteOrder.nativeOrder())
                val t0 = android.os.SystemClock.uptimeMillis()
                var loaded = -1
                var meta = org.json.JSONObject()
                for ((i, sc) in schedule.withIndex()) {
                    if (cancelled()) return null
                    val (k, pts) = sc
                    // A repeated frame (constant frame rate) is drawn again without reading it again.
                    val fresh = k != loaded
                    if (fresh) { meta = raw.load(frames[k], buf); loaded = k }
                    val g = gainExp[k]; val f = wbFac[k]
                    gl.draw(buf, info, meta, ow, oh, pts, floatArrayOf((g * f[0]).toFloat(), g.toFloat(), (g * f[2]).toFloat()), fresh)
                    drain(false)
                    progress((i + 1f) / schedule.size)
                }
                enc.signalEndOfInputStream()
                drain(true)
                android.util.Log.i("RawDevelop", "developed ${schedule.size} frames (${opts.code.ifEmpty { "default" }}) ${info.width}x${info.height} -> ${ow}x$oh ${if (tenBit) "10-bit HEVC" else "8-bit AVC"} in ${android.os.SystemClock.uptimeMillis() - t0} ms")
                ok = started
            } finally {
                gl.release()
                runCatching { enc.stop() }; enc.release(); input.release()
                runCatching { muxer.stop() }; runCatching { muxer.release() }
                if (!ok) tmp.delete()
            }
            if (!ok || !tmp.renameTo(out)) return null
            return Result(out, info, ow, oh, tenBit)
        }
    }

    /** The recording's sound as AAC samples (for the muxer), timed from the first frame; null when silent. */
    private fun encodeAudio(a: McRawAudio?, firstNs: Long): Pair<MediaFormat, List<Pair<ByteBuffer, MediaCodec.BufferInfo>>>? {
        if (a == null) return null
        return runCatching {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, a.rate, a.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000 * a.channels / 2)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            // Sound that started before the first frame is cut; sound that started after waits for its moment.
            var skip = 0
            var startUs = 0L
            if (a.startNs >= 0) {
                val d = a.startNs - firstNs
                if (d < 0) skip = ((-d) * a.rate / 1_000_000_000L).toInt() * a.channels else startUs = d / 1000
            }
            val out = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
            var outFmt: MediaFormat? = null
            var pos = skip.coerceAtMost(a.samples.size)
            var inDone = false
            val bi = MediaCodec.BufferInfo()
            val frameBytes = 2 * a.channels
            while (true) {
                if (!inDone) {
                    val i = c.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i)!!
                        val n = min((a.samples.size - pos), b.capacity() / 2 / a.channels * a.channels)
                        val us = startUs + (pos - skip).toLong() / a.channels * 1_000_000L / a.rate
                        if (n <= 0) { c.queueInputBuffer(i, 0, 0, us, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else {
                            b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(a.samples, pos, n)
                            c.queueInputBuffer(i, 0, n * 2, us, 0); pos += n
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(bi, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFmt = c.outputFormat; continue }
                if (o < 0) continue
                val b = c.getOutputBuffer(o)!!
                if (bi.size > 0 && bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val cp = ByteBuffer.allocate(bi.size); b.position(bi.offset); b.limit(bi.offset + bi.size); cp.put(b); cp.flip()
                    out += cp to MediaCodec.BufferInfo().apply { set(0, bi.size, bi.presentationTimeUs, bi.flags) }
                }
                val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                c.releaseOutputBuffer(o, false)
                if (eos) break
            }
            c.stop(); c.release()
            @Suppress("UNUSED_VARIABLE") val unused = frameBytes
            outFmt?.let { it to out }
        }.getOrNull()
    }

    /** The GL side: an ES 3 context drawing into the encoder's surface (10 bits a channel when the master is). */
    private class DevelopGl(surface: Surface, tenBit: Boolean, private val half: Boolean, private val opts: DevelopOptions) {
        private val shading = opts.shading
        /** The pre pass (completion, hot pixels, binning) needs a float target; without one those are skipped. */
        private val prePass = opts.highlights || opts.hotPixels || opts.cfa > 1
        private var preProg = 0
        private var preTex = 0
        private var preFbo = 0
        private var preW = 0; private var preH = 0
        private var preOk = true
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val eglSurface: android.opengl.EGLSurface
        private val prog: Int
        private var rawTex = 0
        private var shadeTex = 0
        private var rawW = 0; private var rawH = 0
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        init {
            val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
            fun choose(bits: Int, alpha: Int): EGLConfig? {
                val attrs = intArrayOf(EGL14.EGL_RED_SIZE, bits, EGL14.EGL_GREEN_SIZE, bits, EGL14.EGL_BLUE_SIZE, bits, EGL14.EGL_ALPHA_SIZE, alpha,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE)
                val cfg = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
                return if (EGL14.eglChooseConfig(display, attrs, 0, cfg, 0, 1, n, 0) && n[0] > 0) cfg[0] else null
            }
            val config = (if (tenBit) choose(10, 2) else null) ?: choose(8, 8)!!
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(prog)
            val ok = IntArray(1); GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("develop program: " + GLES20.glGetProgramInfoLog(prog))
            if (prePass) {
                val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
                preOk = ext.contains("GL_EXT_color_buffer_float") || ext.contains("GL_EXT_color_buffer_half_float")
                if (preOk) {
                    preProg = GLES20.glCreateProgram()
                    GLES20.glAttachShader(preProg, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
                    GLES20.glAttachShader(preProg, shader(GLES20.GL_FRAGMENT_SHADER, PRE))
                    GLES20.glLinkProgram(preProg)
                    GLES20.glGetProgramiv(preProg, GLES20.GL_LINK_STATUS, ok, 0)
                    if (ok[0] == 0) throw IllegalStateException("develop pre program: " + GLES20.glGetProgramInfoLog(preProg))
                } else android.util.Log.w("RawDevelop", "no float render target: highlight completion, hot pixels and binning are skipped")
            }
        }

        private fun ensurePre(w: Int, h: Int): Boolean {
            if (!preOk) return false
            if (preTex != 0 && w == preW && h == preH) return true
            if (preTex == 0) {
                preTex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                preFbo = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, preTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            for (fmt in listOf(GLES30.GL_R32F to GLES20.GL_FLOAT, GLES30.GL_R16F to GLES30.GL_HALF_FLOAT)) {
                GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, fmt.first, w, h, 0, GLES30.GL_RED, fmt.second, null)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, preFbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, preTex, 0)
                val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                if (st == GLES20.GL_FRAMEBUFFER_COMPLETE) { preW = w; preH = h; return true }
            }
            android.util.Log.w("RawDevelop", "float target incomplete: pre pass skipped")
            preOk = false
            return false
        }

        fun draw(buf: ByteBuffer, info: McRawInfo, meta: org.json.JSONObject, ow: Int, oh: Int, ptsNs: Long, gain: FloatArray, upload: Boolean = true) {
            val w = meta.optInt("width", info.width); val h = meta.optInt("height", info.height)
            GLES20.glUseProgram(prog)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            val fresh = rawTex == 0 || w != rawW || h != rawH
            if (fresh) {
                if (rawTex == 0) rawTex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, w, h)
                rawW = w; rawH = h
            } else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTex)
            if (upload || fresh) {
                buf.position(0)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED_INTEGER, GLES20.GL_UNSIGNED_SHORT, buf)
            }
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uRaw"), 0)
            // Lens shading, per frame (it follows the camera's own correction).
            val lsc = meta.optJSONArray("lensShadingMap")
            val mw = meta.optInt("lensShadingMapWidth"); val mh = meta.optInt("lensShadingMapHeight")
            val shadeOn = shading && lsc != null && lsc.length() >= 4 && mw > 0 && mh > 0 && (0 until 4).all { lsc.optJSONArray(it)?.length() ?: 0 >= mw * mh }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            if (shadeTex == 0) {
                shadeTex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadeTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            } else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadeTex)
            if (shadeOn) {
                val fb = ByteBuffer.allocateDirect(mw * mh * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val planes = (0 until 4).map { lsc!!.getJSONArray(it) }
                for (i in 0 until mw * mh) for (c in 0 until 4) fb.put(planes[c].optDouble(i, 1.0).toFloat())
                fb.position(0)
                GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, mw, mh, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT, fb)
            } else {
                val fb = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(1f, 1f, 1f, 1f)); position(0) }
                GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT, fb)
            }
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uShade"), 1)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uShadeOn"), if (shadeOn) 1 else 0)
            // Levels: this frame's own black and white where it has them (unless the file's fixed ones are asked for).
            val bl = if (opts.staticLevels) null else meta.optJSONArray("dynamicBlackLevel")?.takeIf { it.length() >= 4 }
            val black = opts.blackPerSite(info.cfa) ?: FloatArray(4) { bl?.optDouble(it)?.toFloat() ?: info.black[it] }
            val white = if (opts.white > 0f) opts.white
                else if (opts.staticLevels) info.white else meta.optDouble("dynamicWhiteLevel", info.white.toDouble()).toFloat().takeIf { it > 0f } ?: info.white
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uBlack"), black[0], black[1], black[2], black[3])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uWhite"), white)
            GLES30.glUniform4i(GLES20.glGetUniformLocation(prog, "uCfa"), info.cfa[0], info.cfa[1], info.cfa[2], info.cfa[3])
            GLES30.glUniform2i(GLES20.glGetUniformLocation(prog, "uSize"), w, h)
            GLES30.glUniform2i(GLES20.glGetUniformLocation(prog, "uOut"), ow, oh)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uRot"), info.orientation)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uHalf"), if (half) 1 else 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uShadeMode"), opts.vignette)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uGain"), gain[0], gain[1], gain[2])
            // The pre pass: the (binned) mosaic completed, into a float picture the demosaic then reads.
            val n = opts.cfa.coerceIn(1, 4)
            val bw = w / n / 2 * 2; val bh = h / n / 2 * 2
            val pre = prePass && ensurePre(bw, bh)
            if (pre) {
                GLES20.glUseProgram(preProg)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, preFbo)
                GLES20.glViewport(0, 0, bw, bh)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(preProg, "uRaw"), 0)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(preProg, "uShade"), 1)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(preProg, "uShadeOn"), if (shadeOn) 1 else 0)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(preProg, "uShadeMode"), opts.vignette)
                GLES20.glUniform4f(GLES20.glGetUniformLocation(preProg, "uBlack"), black[0], black[1], black[2], black[3])
                GLES20.glUniform1f(GLES20.glGetUniformLocation(preProg, "uWhite"), white)
                GLES30.glUniform4i(GLES20.glGetUniformLocation(preProg, "uCfa"), info.cfa[0], info.cfa[1], info.cfa[2], info.cfa[3])
                GLES30.glUniform2i(GLES20.glGetUniformLocation(preProg, "uSize"), w, h)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(preProg, "uGain"), gain[0], gain[1], gain[2])
                // The frame's neutral as it stands after the gains.
                val an = meta.optJSONArray("asShotNeutral")
                val nv = FloatArray(3) { (an?.optDouble(it, info.asShot[it].toDouble())?.toFloat() ?: info.asShot[it]).coerceAtLeast(1e-4f) }
                GLES20.glUniform3f(GLES20.glGetUniformLocation(preProg, "uNeutral"), nv[0] / nv[1] * gain[0] / gain[1], 1f, nv[2] / nv[1] * gain[2] / gain[1])
                GLES20.glUniform1i(GLES20.glGetUniformLocation(preProg, "uN"), n)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(preProg, "uHl"), if (opts.highlights) 1 else 0)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(preProg, "uHot"), if (opts.hotPixels) 1 else 0)
                val pa = GLES20.glGetAttribLocation(preProg, "aPos")
                GLES20.glEnableVertexAttribArray(pa)
                GLES20.glVertexAttribPointer(pa, 2, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glUseProgram(prog)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, preTex)
                GLES30.glUniform2i(GLES20.glGetUniformLocation(prog, "uSize"), bw, bh)
            }
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uPre"), 2)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uPreOn"), if (pre) 1 else 0)
            GLES20.glViewport(0, 0, ow, oh)
            val a = GLES20.glGetAttribLocation(prog, "aPos")
            GLES20.glEnableVertexAttribArray(a)
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, max(0L, ptsNs))
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
        }
    }
}

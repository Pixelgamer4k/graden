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
object RawDevelop {
    const val VERSION = 1

    class Result(val file: File, val info: McRawInfo, val width: Int, val height: Int, val tenBit: Boolean)

    /** The master for [uri] lives here (one per recording and develop version). */
    fun masterFile(context: Context, uri: Uri, sizeBytes: Long, half: Boolean = false, shading: Boolean = true): File =
        File(File(context.filesDir, "raw").apply { mkdirs() }, "${uri.toString().hashCode().toUInt()}_${sizeBytes}_d$VERSION${if (half) "h" else "f"}${if (shading) "s" else "n"}.mp4")

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
uniform int uHalf;
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
    int s = site(p);
    float b = s == 0 ? uBlack.x : s == 1 ? uBlack.y : s == 2 ? uBlack.z : uBlack.w;
    float x = (float(texelFetch(uRaw, p, 0).r) - b) / (uWhite - b);
    if (uShadeOn == 1) {
        vec2 uv = vec2(p) / vec2(uSize - 1);
        vec2 ms = vec2(textureSize(uShade, 0));
        vec4 g = texture(uShade, (uv * (ms - 1.0) + 0.5) / ms);
        int c = s == 0 ? uCfa.x : s == 1 ? uCfa.y : s == 2 ? uCfa.z : uCfa.w;
        // Android's map: red, green on even rows, green on odd rows, blue.
        x *= c == 0 ? g.r : c == 2 ? g.a : ((p.y & 1) == 0 ? g.g : g.b);
    }
    return x;
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
    fun develop(context: Context, uri: Uri, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean, half: Boolean = false, shading: Boolean = true): Result? {
        val tmp = File(out.path + ".part")
        tmp.delete()
        McRaw.open(context, uri).use { raw ->
            val info = raw.info
            val frames = raw.frames
            if (frames.isEmpty() || info.width <= 0) return null
            val upright = info.orientation == 90 || info.orientation == 270
            val tenBit = hevc10()
            val mime = if (tenBit) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val sw = if (half) info.width / 2 else info.width; val sh = if (half) info.height / 2 else info.height
            val (ow, oh) = fit(mime, if (upright) sh else sw, if (upright) sw else sh)
            val fps = info.fps
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
            val gl = DevelopGl(input, tenBit, half, shading)
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
                for ((k, ts) in frames.withIndex()) {
                    if (cancelled()) return null
                    val meta = raw.load(ts, buf)
                    gl.draw(buf, info, meta, ow, oh, (ts - info.firstNs))
                    drain(false)
                    progress((k + 1f) / frames.size)
                }
                enc.signalEndOfInputStream()
                drain(true)
                android.util.Log.i("RawDevelop", "developed ${frames.size} frames ${info.width}x${info.height} -> ${ow}x$oh ${if (tenBit) "10-bit HEVC" else "8-bit AVC"} in ${android.os.SystemClock.uptimeMillis() - t0} ms")
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
    private class DevelopGl(surface: Surface, tenBit: Boolean, private val half: Boolean, private val shading: Boolean) {
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
        }

        fun draw(buf: ByteBuffer, info: McRawInfo, meta: org.json.JSONObject, ow: Int, oh: Int, ptsNs: Long) {
            val w = meta.optInt("width", info.width); val h = meta.optInt("height", info.height)
            GLES20.glUseProgram(prog)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            if (rawTex == 0 || w != rawW || h != rawH) {
                if (rawTex == 0) rawTex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, w, h)
                rawW = w; rawH = h
            } else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTex)
            buf.position(0)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED_INTEGER, GLES20.GL_UNSIGNED_SHORT, buf)
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
            // Levels: this frame's own black and white where it has them.
            val bl = meta.optJSONArray("dynamicBlackLevel")?.takeIf { it.length() >= 4 }
            val black = FloatArray(4) { bl?.optDouble(it)?.toFloat() ?: info.black[it] }
            val white = meta.optDouble("dynamicWhiteLevel", info.white.toDouble()).toFloat().takeIf { it > 0f } ?: info.white
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uBlack"), black[0], black[1], black[2], black[3])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uWhite"), white)
            GLES30.glUniform4i(GLES20.glGetUniformLocation(prog, "uCfa"), info.cfa[0], info.cfa[1], info.cfa[2], info.cfa[3])
            GLES30.glUniform2i(GLES20.glGetUniformLocation(prog, "uSize"), w, h)
            GLES30.glUniform2i(GLES20.glGetUniformLocation(prog, "uOut"), ow, oh)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uRot"), info.orientation)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uHalf"), if (half) 1 else 0)
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

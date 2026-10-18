package com.graden.editor

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds: runs the colour shader's own curves and transforms on this phone's GPU and logs the numbers ("ColourTest"),
 * to be checked against the makers' published values. Each curve encodes scene-linear 0.18, 0.5 and 2.0 and is decoded
 * back (the worst relative error over 0.001 … 16 is logged); then whole transforms are run on known colours.
 */
object ColorSelfTest {
    private const val FRAG = """
precision highp float;
uniform float uMode;
uniform float uCode;
uniform vec3 uIn;
uniform vec4 uA;
uniform vec4 uB;
uniform mat3 uM;
uniform vec3 uY;
CURVES
float rt(float v, float g) { vec3 r = decodeG(encodeG(vec3(v), g), g); return abs(r.x - v) / max(v, 0.01); }
void main() {
    vec3 o;
    if (uMode < 0.5) o = encodeG(vec3(0.18, 0.5, 2.0), uCode);
    else if (uMode < 1.5) {
        float e = max(max(rt(0.001, uCode), rt(0.01, uCode)), max(rt(0.05, uCode), rt(0.18, uCode)));
        e = max(e, max(max(rt(0.5, uCode), rt(1.0, uCode)), max(rt(2.0, uCode), rt(16.0, uCode))));
        o = vec3(e);
    } else o = xform(uIn, uA, uB, uY, uM);
    gl_FragColor = vec4(o, 1.0);
}
"""
    private const val VERT = "attribute vec2 aPos; void main() { gl_Position = vec4(aPos, 0.0, 1.0); }"

    fun run() = Thread({ runCatching { go() }.onFailure { android.util.Log.w("ColourTest", "failed", it) } }, "colour-test").start()

    /**
     * Runs [xf] on this GPU over [pts] (RGB triples), returning the results: for checking the shader against numbers
     * measured from other software (GradenLab's "cst" command against Resolve). Call off the main thread.
     */
    fun batch(xf: ColorSpaces.Xf, pts: FloatArray): FloatArray {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(d, v, 0, v, 1)
        val cfg = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(d, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
        val ctx = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val pb = EGL14.eglCreatePbufferSurface(d, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(d, pb, pb, ctx)
        try {
            fun sh(t: Int, s: String) = GLES20.glCreateShader(t).also {
                GLES20.glShaderSource(it, s); GLES20.glCompileShader(it)
                val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) throw IllegalStateException(GLES20.glGetShaderInfoLog(it))
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, VERT))
            GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, FRAG.replace("CURVES", ColorSpaces.GLSL)))
            GLES20.glLinkProgram(p)
            GLES20.glUseProgram(p)
            val count = pts.size / 3
            val W = 256; val H = (count + W - 1) / W
            val tex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, W, H)
            val fbo = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
            val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
            val a = GLES20.glGetAttribLocation(p, "aPos")
            GLES20.glEnableVertexAttribArray(a); GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
            fun u(name: String) = GLES20.glGetUniformLocation(p, name)
            val b = FloatArray(ColorSpaces.XF_SIZE); xf.pack(b, 0)
            GLES20.glUniform1f(u("uMode"), 2f)
            GLES20.glUniform4f(u("uA"), b[0], b[1], b[2], b[3]); GLES20.glUniform4f(u("uB"), b[4], b[5], b[6], b[7])
            GLES20.glUniformMatrix3fv(u("uM"), 1, false, b, 8)
            GLES20.glUniform3f(u("uY"), b[17], b[18], b[19])
            SatCompTable.bind(p)
            val inLoc = u("uIn")
            for (i in 0 until count) {
                GLES20.glViewport(i % W, i / W, 1, 1)
                GLES20.glUniform3f(inLoc, pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2])
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            val out = ByteBuffer.allocateDirect(W * H * 16).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_FLOAT, out)
            val f = out.asFloatBuffer()
            return FloatArray(count * 3) { f.get((it / 3) * 4 + it % 3) }
        } finally {
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            SatCompTable.forget(ctx); EGL14.eglDestroySurface(d, pb); EGL14.eglDestroyContext(d, ctx)
        }
    }

    private fun go() {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(d, v, 0, v, 1)
        val cfg = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(d, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
        val ctx = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val pb = EGL14.eglCreatePbufferSurface(d, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(d, pb, pb, ctx)
        fun sh(t: Int, s: String) = GLES20.glCreateShader(t).also {
            GLES20.glShaderSource(it, s); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException(GLES20.glGetShaderInfoLog(it))
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, VERT))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, FRAG.replace("CURVES", ColorSpaces.GLSL)))
        GLES20.glLinkProgram(p)
        GLES20.glUseProgram(p)
        // A float target, so the numbers come back exact.
        val W = 128
        val tex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, W, 1)
        val fbo = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        val a = GLES20.glGetAttribLocation(p, "aPos")
        GLES20.glEnableVertexAttribArray(a); GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
        fun u(name: String) = GLES20.glGetUniformLocation(p, name)
        var x = 0
        fun pixel(): Int { GLES20.glViewport(x, 0, 1, 1); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4); return x++ }
        val gammas = ColorSpaces.gammas
        val enc = gammas.map { g -> GLES20.glUniform1f(u("uMode"), 0f); GLES20.glUniform1f(u("uCode"), g.code.toFloat()); pixel() }
        val rts = gammas.map { g -> GLES20.glUniform1f(u("uMode"), 1f); GLES20.glUniform1f(u("uCode"), g.code.toFloat()); pixel() }
        // Whole transforms, no tone or gamut mapping, on known colours.
        val cases = listOf(
            Triple("Rec.709 g2.4 -> AWG3 LogC3", ColorSpaces.Xf(ColorSpaces.G709, ColorSpaces.GAMMA24, ColorSpaces.GAWG3, ColorSpaces.LOGC3, "None", "None", 100f, 10000f), null),
            Triple("Rec.709 g2.4 -> S-Gamut3.Cine S-Log3", ColorSpaces.Xf(ColorSpaces.G709, ColorSpaces.GAMMA24, ColorSpaces.GSG3C, ColorSpaces.SLOG3G, "None", "None", 100f, 10000f), null),
            Triple("Rec.709 g2.4 -> DWG DI", ColorSpaces.Xf(ColorSpaces.G709, ColorSpaces.GAMMA24, ColorSpaces.GDWG, ColorSpaces.DI, "None", "None", 100f, 10000f), null),
            Triple("AWG3 LogC3 -> Rec.709 g2.4", ColorSpaces.Xf(ColorSpaces.GAWG3, ColorSpaces.LOGC3, ColorSpaces.G709, ColorSpaces.GAMMA24, "None", "None", 100f, 100f), null),
            Triple("S-Log3 video levels -> Rec.709 g2.4", ColorSpaces.Xf(ColorSpaces.GSG3C, ColorSpaces.SLOG3G, ColorSpaces.G709, ColorSpaces.GAMMA24, "None", "None", 100f, 100f), null),
            Triple("Canon Log 3 -> Rec.709 g2.4", ColorSpaces.Xf(ColorSpaces.GCINEMA, ColorSpaces.CLOG3, ColorSpaces.G709, ColorSpaces.GAMMA24, "None", "None", 100f, 100f), null),
            Triple("Linear 2020 -> Rec.2100 PQ", ColorSpaces.Xf(ColorSpaces.G2020, ColorSpaces.LINEAR, ColorSpaces.G2020, ColorSpaces.PQG, "None", "None", 10000f, 10000f), null),
        )
        val ins = listOf(floatArrayOf(0.48951f, 0.48951f, 0.48951f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
        val xs = ArrayList<Pair<String, Int>>()
        for ((name, xf, _) in cases) {
            val b = FloatArray(ColorSpaces.XF_SIZE); xf.pack(b, 0)
            GLES20.glUniform1f(u("uMode"), 2f)
            GLES20.glUniform4f(u("uA"), b[0], b[1], b[2], b[3]); GLES20.glUniform4f(u("uB"), b[4], b[5], b[6], b[7])
            GLES20.glUniformMatrix3fv(u("uM"), 1, false, b, 8)
            GLES20.glUniform3f(u("uY"), b[17], b[18], b[19])
            for (i in ins) {
                // The LogC3 case reads LogC3 grey (0.391007) and primaries; the linear case 1.0 (203 nits).
                val inp = when {
                    name.startsWith("AWG3") && i[0] == 0.48951f -> floatArrayOf(0.391007f, 0.391007f, 0.391007f)
                    name.startsWith("Linear") && i[0] == 0.48951f -> floatArrayOf(1f, 1f, 1f)
                    // S-Log3 grey is code 420; recorded in video levels it arrives as (420 - 64) / 876.
                    name.startsWith("S-Log3 video") && i[0] == 0.48951f -> floatArrayOf(0.406393f, 0.406393f, 0.406393f)
                    // Canon Log 3 grey: 34.3% in code values (Canon's own figure is 32.8% IRE).
                    name.startsWith("Canon Log 3") && i[0] == 0.48951f -> floatArrayOf(0.34343f, 0.34343f, 0.34343f)
                    else -> i
                }
                GLES20.glUniform3f(u("uIn"), inp[0], inp[1], inp[2])
                xs += "$name  in ${inp.joinToString(",") { "%.4f".format(it) }}" to pixel()
            }
        }
        // The export's own version of the grade shader must still build.
        runCatching {
            val ep = GLES20.glCreateProgram()
            GLES20.glAttachShader(ep, sh(GLES20.GL_VERTEX_SHADER, com.graden.editor.export.Compositor.PIC_VERTEX))
            GLES20.glAttachShader(ep, sh(GLES20.GL_FRAGMENT_SHADER, com.graden.editor.export.Compositor.gradeFragment()))
            GLES20.glLinkProgram(ep)
            val ok = IntArray(1); GLES20.glGetProgramiv(ep, GLES20.GL_LINK_STATUS, ok, 0)
            android.util.Log.i("ColourTest", "export shader " + if (ok[0] != 0) "builds" else "FAILS: " + GLES20.glGetProgramInfoLog(ep))
            GLES20.glUseProgram(p)
        }.onFailure { android.util.Log.e("ColourTest", "export shader FAILS: ${it.message}") }
        val out = ByteBuffer.allocateDirect(W * 16).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, W, 1, GLES20.GL_RGBA, GLES20.GL_FLOAT, out)
        val f = out.asFloatBuffer()
        fun at(i: Int) = floatArrayOf(f.get(i * 4), f.get(i * 4 + 1), f.get(i * 4 + 2))
        for ((k, g) in gammas.withIndex()) {
            val e = at(enc[k]); val r = at(rts[k])[0]
            android.util.Log.i("ColourTest", "curve %-30s 0.18 -> %.5f   0.5 -> %.5f   2.0 -> %.5f   round trip worst %.1e".format(g.name, e[0], e[1], e[2], r))
        }
        for ((label, i) in xs) android.util.Log.i("ColourTest", "xform $label -> ${at(i).joinToString(",") { "%.5f".format(it) }}")
        EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        SatCompTable.forget(ctx); EGL14.eglDestroySurface(d, pb); EGL14.eglDestroyContext(d, ctx)
    }
}

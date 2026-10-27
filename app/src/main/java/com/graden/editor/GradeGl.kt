package com.graden.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared state of the graded preview. Each picture draws on a GL thread of its own (see [GlCore]): handing a
 * frame to the screen can wait several milliseconds, and on a shared thread that wait would hold up every other
 * layer, which shows as dropped frames and a stall at each cut.
 *
 * The view named by [scopeTag] also draws a small copy of its graded picture, read back for the scopes.
 */
object GradeGl {
    private val views = ConcurrentHashMap<String, GradedVideoView>()

    /** Which view feeds the scopes (null: none), and where the read-back pixels go: (rgba, width, height). */
    @Volatile var scopeTag: String? = null
        set(v) {
            if (field == v) return
            field = v
            v?.let { views[it] }?.redraw()
        }
    @Volatile var scopeSink: ((ByteBuffer, Int, Int) -> Unit)? = null
    internal val lastScopeAt = AtomicLong(0L)
    /** Width of the picture read back for the scopes; enough for a sharp waveform, cheap to read. */
    const val SCOPE_W = 384

    /** The view called [key], if it is on screen. */
    fun view(key: String): GradedVideoView? = views[key]

    internal fun register(v: GradedVideoView) {
        views[v.key] = v
    }

    internal fun unregister(v: GradedVideoView) {
        views.remove(v.key, v)
    }

    // ---- HDR on the phone's screen -----------------------------------------------------------------

    /** The screen shows HDR10 and the GPU can write it (set once by [probeHdr]). */
    @Volatile var hdrSupported = false
        private set
    /** The screen's peak brightness in nits, which the HDR preview rolls highlights off to. */
    @Volatile var displayPeak = 500f
        private set

    /** The views draw HDR (Rec.2100 PQ) rather than SDR. */
    @Volatile var hdrOut = false
        private set

    /**
     * Finds out once whether the preview can be HDR here: Android 14 or later (HDR windows), a screen that takes HDR10,
     * and EGL that can write a PQ surface. Only standard APIs; anywhere else the preview stays SDR.
     */
    fun probeHdr(activity: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        runCatching {
            @Suppress("DEPRECATION")
            val caps = activity.display?.hdrCapabilities ?: return
            if (android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 !in caps.supportedHdrTypes) return
            displayPeak = caps.desiredMaxLuminance.takeIf { it > 100f } ?: 500f
            val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            EGL14.eglInitialize(d, v, 0, v, 1)
            hdrSupported = EGL14.eglQueryString(d, EGL14.EGL_EXTENSIONS)?.contains("EGL_EXT_gl_colorspace_bt2020_pq") == true
        }
    }

    /** Switches every view between HDR and SDR output. */
    fun setHdr(on: Boolean) {
        val want = on && hdrSupported
        if (want == hdrOut) return
        hdrOut = want
        views.values.forEach { it.redraw() }
    }

    /** The project's HDR mastering peak: the scopes measure the master, not the screen's roll-off. */
    @Volatile var masterNits = 1000f

    internal const val EGL_GL_COLORSPACE_KHR = 0x309D
    internal const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
}

/** One GL thread and context, with the grade shader, for one view. */
private class GlCore(name: String) {
    private val thread = HandlerThread("grade-$name", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    /** The grade shader and where its inputs are; one reads video frames, the other plain images (native frames at rest). */
    private class Prog(val id: Int) {
        val aPos = GLES20.glGetAttribLocation(id, "aPos")
        val uTex = GLES20.glGetUniformLocation(id, "uTex")
        val uRect = GLES20.glGetUniformLocation(id, "uRect")
        val uG = GLES20.glGetUniformLocation(id, "uG")
        val uOn = GLES20.glGetUniformLocation(id, "uOn")
        val uTexel = GLES20.glGetUniformLocation(id, "uTexel")
        val uSrc = GLES20.glGetUniformLocation(id, "uSrc")
        val uL = GLES20.glGetUniformLocation(id, "uL")
        val uTime = GLES20.glGetUniformLocation(id, "uTime")
    }
    private lateinit var video: Prog
    private lateinit var image: Prog
    private lateinit var quad: FloatBuffer
    private val looks = GradeTextures()
    private val extras = FrameExtras()
    private var copyOes = 0
    private var copy2d = 0
    /** The stamp of the newest frame kept in history, and the layers the last picture was drawn with (for the scopes). */
    private var histNs = Long.MIN_VALUE
    private var drawnLayers: FloatArray? = null
    private var glowReady = false
    private var keepFrames = 0
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private var readBuf: ByteBuffer? = null

    init {
        handler.post(::setUp)
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        fun choose(bits: Int, alpha: Int): EGLConfig? {
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, bits, EGL14.EGL_GREEN_SIZE, bits, EGL14.EGL_BLUE_SIZE, bits, EGL14.EGL_ALPHA_SIZE, alpha,
                EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(8)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, configs.size, n, 0)) return null
            // The first with exactly these sizes (the list can put bigger formats first).
            return (0 until n[0]).mapNotNull { configs[it] }.firstOrNull { c ->
                val r = IntArray(1)
                EGL14.eglGetConfigAttrib(display, c, EGL14.EGL_RED_SIZE, r, 0)
                r[0] == bits
            }
        }
        // Where the screen can show HDR, 10 bits a channel, so the same surface can be SDR or HDR.
        config = (if (GradeGl.hdrSupported) choose(10, 2)?.also { tenBit = true } else null) ?: choose(8, 8)
        // OpenGL ES 3 (the grade shader itself is ES 2), so compiled programs can be saved and loaded back.
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        video = Prog(ShaderCache.program(GradeShader.VERTEX, GradeShader.FRAGMENT))
        // The same grade for a plain image texture.
        image = Prog(ShaderCache.program(GradeShader.VERTEX, GradeShader.IMAGE_FRAGMENT))
        copyOes = ShaderCache.program(GradeShader.VERTEX, FrameExtras.COPY_OES)
        copy2d = ShaderCache.program(GradeShader.VERTEX, FrameExtras.COPY_2D)
        // On ANGLE (Vulkan underneath: Samsung's Xclipse GPUs, among others) the full grade shader reading a video frame
        // directly fails to build its pipeline and draws nothing (black preview). There, each frame is first copied
        // into a plain texture by a tiny shader and graded from that, as pictures are.
        viaCopy = GLES20.glGetString(GLES20.GL_RENDERER)?.contains("ANGLE", ignoreCase = true) == true
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    }


    /** Whether this context's surfaces are 10-bit (needed for HDR). */
    var tenBit = false
        private set

    /** A surface on the view's texture: Rec.2100 PQ when [hdr] (and possible here), else plain SDR. */
    fun windowSurface(st: SurfaceTexture, hdr: Boolean): EGLSurface {
        if (hdr && tenBit) {
            val s = EGL14.eglCreateWindowSurface(display, config, st, intArrayOf(GradeGl.EGL_GL_COLORSPACE_KHR, GradeGl.EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE), 0)
            if (s != null && s != EGL14.EGL_NO_SURFACE) return s
        }
        return EGL14.eglCreateWindowSurface(display, config, st, intArrayOf(EGL14.EGL_NONE), 0)
    }

    /** Replaces [old] with a new surface on [st] (to switch between SDR and HDR). Runs on the thread. */
    fun replaceSurface(old: EGLSurface, st: SurfaceTexture, hdr: Boolean): EGLSurface {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        EGL14.eglDestroySurface(display, old)
        return windowSurface(st, hdr)
    }

    fun newOesTexture(): Int {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    /** Takes everything down and ends the thread. Runs on the thread. */
    fun release(surface: EGLSurface?, tex: Int) {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
        looks.release()
        extras.release()
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
        }
        if (copyFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(copyFbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(copyTex), 0)
        }
        surface?.let { EGL14.eglDestroySurface(display, it) }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        SatCompTable.forget(context)
        EGL14.eglDestroyContext(display, context)
        thread.quitSafely()
    }

    private var fbW = 1
    private var fbH = 1
    /** The stabilisation for the picture being drawn (null: none), and how its edges are filled. */
    private var stabTrack: StabTrack? = null
    private var stabIndex = -1
    private val stabBuf = FloatArray(9)
    private val stabGl = StabGl()

    /** Video frames go through [frameCopy] first (see where it is set). */
    private var viaCopy = false
    private var copyFbo = 0
    private var copyTex = 0
    private var copyW = 0
    private var copyH = 0

    /**
     * Copies video frame [tex] (an external texture, placed by [texM]) into a plain texture of [w] × [h] and returns it,
     * upright as the grade reads it. Leaves the frame buffer that was bound.
     */
    private fun frameCopy(tex: Int, texM: FloatArray, w: Int, h: Int): Int {
        val prev = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
        val vp = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }
        if (copyFbo == 0 || copyW != w || copyH != h) {
            if (copyFbo != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(copyFbo), 0); GLES20.glDeleteTextures(1, intArrayOf(copyTex), 0) }
            copyTex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, copyTex)
            android.opengl.GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, android.opengl.GLES30.GL_RGBA16F, w, h)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            copyFbo = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, copyFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, copyTex, 0)
            copyW = w; copyH = h
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, copyFbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(copyOes)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(copyOes, "uSrc"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyOes, "uTex"), 1, false, texM, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(copyOes, "uRect"), 1, FULL, 0)
        val a = GLES20.glGetAttribLocation(copyOes, "aPos")
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prev)
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
        return copyTex
    }

    private fun drawQuad(tex: Int, texM: FloatArray, rect: FloatArray, grade: FloatArray?, texelX: Float, texelY: Float, still: Boolean = false, cm: FloatArray = CM_OFF, look: Grade? = null, layers: FloatArray? = null) {
        if (!still && viaCopy) {
            val w = (1f / texelX).toInt().coerceIn(1, 4096)
            val h = (1f / texelY).toInt().coerceIn(1, 4096)
            return drawQuad(frameCopy(tex, texM, w, h), IDENTITY_M, rect, grade, texelX, texelY, true, cm, look, layers)
        }
        val p = if (still) image else video
        GLES20.glUseProgram(p.id)
        looks.bind(p.id, if (grade != null) look else null)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (still) GLES20.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(p.uSrc, 0)
        GLES20.glUniformMatrix4fv(p.uTex, 1, false, texM, 0)
        GLES20.glUniform4fv(p.uRect, 1, rect, 0)
        GLES20.glUniform1f(p.uOn, if (grade != null) 1f else 0f)
        if (grade != null) GLES20.glUniform4fv(p.uG, 9, grade, 0)
        GLES20.glUniform2f(p.uTexel, texelX, texelY)
        ColorPipe.set(p.id, cm)
        stabGl.set(p.id, stabTrack, stabIndex, stabBuf)
        GLES20.glUniform4fv(p.uL, LayerPack.SIZE, layers ?: LayerPack.PREVIEW_NONE, 0)
        GLES20.glUniform1f(p.uTime, (SystemClock.uptimeMillis() % 100_000L) / 1000f)
        extras.bindGlow(p.id, glowReady, fbW, fbH)
        extras.bindHistory(p.id, keepFrames)
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Puts [bmp] into image texture [tex] (made if 0) and returns it. The whole picture is the texture, at its own size:
     * nothing in between can crop, letterbox or rescale it.
     */
    fun uploadStill(tex: Int, bmp: android.graphics.Bitmap): Int {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        val t = if (tex != 0) tex else IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return t
    }

    /** Draws the view: its last frame, graded, placed by its fit/fill rule; then the scope copy if it feeds the scopes. */
    fun draw(v: GradedVideoView, surface: EGLSurface, tex: Int, texM: FloatArray, w: Int, h: Int, hasFrame: Boolean) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return
        EGL14.eglSwapInterval(display, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val grade = v.gradeUniforms
        val still = v.stillTex
        // What this grade needs besides the frame: the blurred copy (halation, bloom, deflicker) and earlier frames.
        val look = v.look
        var layers = v.layers
        val needGlow = look != null && layers != null && (look.film.glows || look.deflicker.on) && (still != 0 || hasFrame)
        keepFrames = if (still == 0 && layers != null) look?.noise?.history ?: 0 else 0
        val ns = v.lastFrameNs
        val st = v.stab
        val stUs = if (st != null && (still != 0 || hasFrame)) v.stabClock?.invoke(ns) else null
        stabIndex = if (st != null && stUs != null) st.at(stUs, stabBuf) else -1
        stabTrack = st
        // A jump in time (a seek, a cut) means the kept frames no longer belong to this picture.
        if (keepFrames > 0 && (ns < histNs || ns - histNs > 120_000_000L)) extras.resetHistory()
        if (keepFrames == 0) extras.resetHistory()
        glowReady = false
        if (needGlow) {
            val rect = v.placement(w, h)
            val gl = layers!!.copyOf()
            gl[70 * 4 + 2] = -1f
            gl[70 * 4 + 3] = 1f
            fbW = w; fbH = h
            extras.glowBegin(w, h)
            val pw = (rect[2] - rect[0]) / 2f * w
            val ph = (rect[3] - rect[1]) / 2f * h
            if (still != 0) drawQuad(still, FLIP_Y, rect, grade, 1f / pw.coerceAtLeast(1f), 1f / ph.coerceAtLeast(1f), still = true, cm = v.cmUniforms, look = look, layers = gl)
            else drawQuad(tex, texM, rect, grade, 1f / pw.coerceAtLeast(1f), 1f / ph.coerceAtLeast(1f), cm = v.cmUniforms, look = look, layers = gl)
            extras.glowEnd()
            layers = layers.copyOf()
            if (look!!.deflicker.on) layers[5 * 4] = extras.deflickerGain(extras.mean(), look.deflicker.strength, look.deflicker.speed)
            else extras.resetDeflicker()
            layers[68 * 4 + 3] = 1f
            glowReady = true
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, w, h)
        }
        drawnLayers = layers
        fbW = w; fbH = h
        if (still != 0) {
            // A native frame at rest: the image texture, upright (bitmap rows run top to bottom).
            val rect = v.placement(w, h)
            val pw = (rect[2] - rect[0]) / 2f * w
            val ph = (rect[3] - rect[1]) / 2f * h
            drawQuad(still, FLIP_Y, rect, grade, 1f / pw.coerceAtLeast(1f), 1f / ph.coerceAtLeast(1f), still = true, cm = v.cmUniforms, look = v.look, layers = layers)
        } else if (hasFrame) {
            val rect = v.placement(w, h)
            val pw = (rect[2] - rect[0]) / 2f * w
            val ph = (rect[3] - rect[1]) / 2f * h
            drawQuad(tex, texM, rect, grade, 1f / pw.coerceAtLeast(1f), 1f / ph.coerceAtLeast(1f), cm = v.cmUniforms, look = v.look, layers = layers)
        }
        // The frame just shown joins the history, as read in, for the next frames' noise reduction and motion blur.
        if (keepFrames > 0 && hasFrame && still == 0 && ns != histNs) {
            extras.pushBegin(w, h, keepFrames)
            GLES20.glUseProgram(copyOes)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(copyOes, "uSrc"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyOes, "uTex"), 1, false, texM, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(copyOes, "uRect"), 1, FULL, 0)
            val a = GLES20.glGetAttribLocation(copyOes, "aPos")
            GLES20.glEnableVertexAttribArray(a)
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, w, h)
            histNs = ns
        }
        val t0 = System.nanoTime()
        EGL14.eglSwapBuffers(display, surface)
        val t1 = System.nanoTime()
        if ((t1 - t0) > 6_000_000L) android.util.Log.w("GradeProbe", "slow swap ${v.key} ${(t1 - t0) / 1_000_000}ms")
        if ((hasFrame || still != 0) && v.key == GradeGl.scopeTag) scopeCopy(v, tex, texM)
    }

    private fun scopeCopy(v: GradedVideoView, tex: Int, texM: FloatArray) {
        val sink = GradeGl.scopeSink ?: return
        val now = SystemClock.uptimeMillis()
        if (now - GradeGl.lastScopeAt.get() < 30) {
            // Too soon after the last one; make sure the latest picture still reaches the scopes.
            handler.removeCallbacks(v.lateScope)
            handler.postDelayed(v.lateScope, 34)
            return
        }
        GradeGl.lastScopeAt.set(now)
        val aspect = v.contentAspect.takeIf { it > 0f } ?: 16f / 9f
        val w = GradeGl.SCOPE_W
        val h = (w / aspect).toInt().coerceIn(32, 512)
        if (fbo == 0 || fboW != w || fboH != h) {
            if (fbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            }
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            fboTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val f = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0)
            fbo = f[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
            fboW = w
            fboH = h
            readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        val still = v.stillTex
        // In HDR the scopes read the signal as the master has it (its own peak), not rolled off for this screen.
        val x = ColorSpaces.XF_SIZE
        val cm = v.cmUniforms.let { c -> if (c[x + 2] == ColorSpaces.PQG.code.toFloat()) c.copyOf().also { it[x + 5] = maxOf(it[x + 5], GradeGl.masterNits / 203f) } else c }
        fbW = w; fbH = h
        if (still != 0) drawQuad(still, FLIP_Y, FULL, v.gradeUniforms, 1f / w, 1f / h, still = true, cm = cm, look = v.look, layers = drawnLayers ?: v.layers)
        else drawQuad(tex, texM, FULL, v.gradeUniforms, 1f / w, 1f / h, cm = cm, look = v.look, layers = drawnLayers ?: v.layers)
        val buf = readBuf!!
        buf.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        buf.position(0)
        sink(buf, w, h)
    }

    private companion object {
        val FULL = floatArrayOf(-1f, -1f, 1f, 1f)
        /** No change: a copied frame is already upright. */
        val IDENTITY_M = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val CM_OFF = GradeCore.CM_OFF
        /** Texture transform that turns an image upright: y becomes 1 - y. */
        val FLIP_Y = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    }
}

internal object GradeCore {
    /** Colour management off: Rec.709 all the way. */
    val CM_OFF = FloatArray(ColorPipe.SIZE).also { b ->
        for (at in intArrayOf(0, ColorSpaces.XF_SIZE)) { b[at + 8] = 1f; b[at + 12] = 1f; b[at + 16] = 1f }
    }
}

/**
 * A video picture drawn through the grade, on its own GL thread. The decoder writes into a texture of ours
 * (handed out through [onInput]); each new frame is drawn into the view at once, and because the last frame
 * stays in the texture, a changed grade redraws without decoding anything. The frame is letterboxed (fit) or
 * cropped (fill) to [contentAspect], or stretched over the view when that is 0, and the view is transparent
 * around it so the layers underneath show through.
 */
@SuppressLint("ViewConstructor")
class GradedVideoView(context: Context, val key: String) : TextureView(context), TextureView.SurfaceTextureListener {
    /** Given the decoder's surface when it is ready (true), and again just before it goes away (false). */
    var onInput: ((Surface, Boolean) -> Unit)? = null
        set(v) {
            field = v
            input?.let { v?.invoke(it, true) }
        }
    private var input: Surface? = null
    /** The input surface, as the GL thread knows it (for drawing still pictures into it). */
    @Volatile private var glInput: Surface? = null
    private var core: GlCore? = null
    // GL-thread state.
    @Volatile private var inputTexture: SurfaceTexture? = null
    private var tex = 0
    private var egl: EGLSurface? = null
    /** The view's own texture the picture is drawn into, and whether its surface is HDR right now. */
    private var outSt: SurfaceTexture? = null
    private var surfaceHdr = false
    private var w = 0
    private var h = 0
    private val texM = FloatArray(16)
    private var hasFrame = false
    private var drawPending = false
    @Volatile internal var gradeUniforms: FloatArray? = null
    /** The grade itself, for its curves and LUT. */
    @Volatile internal var look: Grade? = null
    /** Its layers, packed for the shader (see [LayerPack]). */
    @Volatile internal var layers: FloatArray? = null
    /** Colour path for this view, as [ColorPipe] packs it. */
    @Volatile internal var cmUniforms: FloatArray = GradeCore.CM_OFF
    @Volatile private var stillShown: (() -> Unit)? = null
    /** The native frame's image texture (0: none); set and used on the GL thread. */
    @Volatile internal var stillTex = 0
    /** The stamp of the last video frame this view drew (the time the player released it for display). */
    @Volatile var lastFrameNs = 0L
        private set
    /** Stabilisation of the clip shown, and the file time (us) of the frame drawn, from its stamp (null: unknown). */
    @Volatile var stab: StabTrack? = null
    @Volatile var stabClock: ((Long) -> Long?)? = null

    /** Sets the stabilisation; redraws when it changed. */
    fun setStab(track: StabTrack?, clock: ((Long) -> Long?)?) {
        stabClock = clock
        if (track === stab) return
        stab = track
        redraw()
    }
    private val stillPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    @Volatile var contentAspect = 0f
        private set
    @Volatile private var fill = false
    private var lastGrade: Grade? = null
    internal val lateScope = Runnable { drawNow() }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    /** What to show: [grade] (null shows the picture as it is), the picture's shape, and fit or fill. Cheap to call often. */
    fun setLook(grade: Grade?, aspect: Float, fill: Boolean, cm: FloatArray? = null, layers: FloatArray? = null) {
        val g = grade?.takeIf { !it.isIdentity }
        val cmNow = cm ?: GradeCore.CM_OFF
        val cmChanged = !cmNow.contentEquals(cmUniforms)
        cmUniforms = cmNow
        val ly = if (g == null) null else layers
        val lyChanged = !(ly?.contentEquals(this.layers) ?: (this.layers == null))
        this.layers = ly
        if (!cmChanged && !lyChanged && g == lastGrade && aspect == contentAspect && fill == this.fill) return
        lastGrade = g
        look = g
        gradeUniforms = g?.uniforms()
        contentAspect = aspect
        this.fill = fill
        redraw()
    }

    internal fun placement(vw: Int, vh: Int): FloatArray {
        val a = contentAspect
        if (a <= 0f) return floatArrayOf(-1f, -1f, 1f, 1f)
        val viewA = vw.toFloat() / vh.coerceAtLeast(1)
        val wider = a > viewA
        var sx = 1f
        var sy = 1f
        if (wider != fill) sy = viewA / a else sx = a / viewA
        return floatArrayOf(-sx, -sy, sx, sy)
    }

    /**
     * For a view with no decoder attached: puts [bmp] in as its picture. It is then drawn through the grade and placed
     * exactly like a video frame. Returns false if the view is not ready yet.
     */
    fun showStill(bmp: android.graphics.Bitmap, onShown: () -> Unit): Boolean {
        val c = core ?: return false
        if (onInput != null) return false
        c.handler.post {
            if (core !== c || egl == null) return@post
            stillTex = c.uploadStill(stillTex, bmp)
            drawNow()
            // Called back once the picture has been drawn, so whoever shows the view never shows what it had before.
            post(onShown)
        }
        return true
    }

    /**
     * A still picture played by the player: [bmp] goes into the input surface as its next frame, at the picture's own
     * size, and is drawn through the grade and placed exactly like a video frame. (The player gives pictures as
     * bitmaps, never into the video surface: without this a picture on the timeline showed as black.)
     */
    fun drawPicture(bmp: android.graphics.Bitmap) {
        val c = core ?: return
        c.handler.post {
            if (core !== c) return@post
            val t = inputTexture ?: return@post
            val s = glInput ?: return@post
            runCatching {
                t.setDefaultBufferSize(bmp.width, bmp.height)
                val cv = s.lockHardwareCanvas()
                try {
                    cv.drawColor(android.graphics.Color.BLACK, android.graphics.PorterDuff.Mode.SRC)
                    cv.drawBitmap(bmp, null, android.graphics.Rect(0, 0, bmp.width, bmp.height), stillPaint)
                } finally { s.unlockCanvasAndPost(cv) }
            }.onFailure { android.util.Log.w("GradedVideoView", "picture not drawn", it) }
        }
    }

    /** Redraws the last frame soon (coalesced), e.g. after the grade changed. */
    internal fun redraw() {
        val c = core ?: return
        c.handler.post {
            if (drawPending) return@post
            drawPending = true
            c.handler.post {
                drawPending = false
                drawNow()
            }
        }
    }

    private fun drawNow() {
        var s = egl ?: return
        val c0 = core ?: return
        // HDR switched on or off: the surface is made again in the other colour space.
        val hdrNow = GradeGl.hdrOut && c0.tenBit
        val st = outSt
        if (hdrNow != surfaceHdr && st != null) {
            s = c0.replaceSurface(s, st, hdrNow)
            egl = s
            surfaceHdr = hdrNow
        }
        core?.draw(this, s, tex, texM, w, h, hasFrame)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val c = GlCore(key)
        core = c
        c.handler.post {
            w = width
            h = height
            outSt = st
            surfaceHdr = GradeGl.hdrOut && c.tenBit
            egl = c.windowSurface(st, surfaceHdr)
            tex = c.newOesTexture()
            val it = SurfaceTexture(tex)
            it.setOnFrameAvailableListener({ t ->
                if (inputTexture !== t || egl == null) return@setOnFrameAvailableListener
                runCatching {
                    t.updateTexImage()
                    t.getTransformMatrix(texM)
                    hasFrame = true
                    lastFrameNs = t.timestamp
                    drawNow()
                }
                stillShown?.let { cb ->
                    stillShown = null
                    post(cb)
                }
            }, c.handler)
            inputTexture = it
            val surface = Surface(it)
            glInput = surface
            post {
                if (core !== c) return@post
                input = surface
                onInput?.invoke(surface, true)
            }
            drawNow()
        }
        GradeGl.register(this)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        core?.handler?.post {
            w = width
            h = height
            drawNow()
        }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        // The decoder lets go of our surface first, here on the main thread; then the GL side is taken down.
        GradeGl.unregister(this)
        val s = input
        input = null
        if (s != null) onInput?.invoke(s, false)
        val c = core
        core = null
        if (c == null) st.release()
        else c.handler.post {
            glInput = null
            inputTexture?.release()
            inputTexture = null
            s?.release()
            c.release(egl, tex)
            egl = null
            outSt = null
            tex = 0
            hasFrame = false
            st.release()
        }
        return false
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}

/**
 * The grade shader is large: compiling it takes the GPU driver a while, and every preview picture has its own context.
 * So it is compiled once, one at a time, and the program saved (in memory and on disk, keyed by its source and the
 * driver) and loaded back everywhere else. The app warms it up at start ([warmUp]), so a project opens straight away.
 */
internal object ShaderCache {
    private val lock = Any()
    private val mem = HashMap<String, Pair<Int, ByteArray>>()
    @Volatile var dir: java.io.File? = null

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Grade shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        val a = compile(GLES20.GL_VERTEX_SHADER, vs)
        val b = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        GLES20.glAttachShader(p, a)
        GLES20.glAttachShader(p, b)
        android.opengl.GLES30.glProgramParameteri(p, android.opengl.GLES30.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GLES20.GL_TRUE)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(a)
        GLES20.glDeleteShader(b)
        return p
    }

    private fun linked(p: Int): Boolean = IntArray(1).also { GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, it, 0) }[0] != 0

    /** A linked program for [vs] + [fs] in the current (ES 3) context. */
    fun program(vs: String, fs: String): Int = synchronized(lock) {
        val key = Integer.toHexString((vs + fs + GLES20.glGetString(GLES20.GL_RENDERER) + GLES20.glGetString(GLES20.GL_VERSION)).hashCode())
        val file = dir?.let { java.io.File(it, "$key.bin") }
        val saved = mem[key] ?: file?.takeIf { it.exists() }?.let { f ->
            runCatching { java.io.DataInputStream(f.inputStream().buffered()).use { val fmt = it.readInt(); fmt to it.readBytes() } }.getOrNull()
        }
        if (saved != null) {
            val p = GLES20.glCreateProgram()
            val buf = ByteBuffer.allocateDirect(saved.second.size).order(ByteOrder.nativeOrder()).put(saved.second).also { it.position(0) }
            android.opengl.GLES30.glProgramBinary(p, saved.first, buf, saved.second.size)
            if (linked(p)) {
                mem[key] = saved
                return p
            }
            GLES20.glDeleteProgram(p)
            file?.delete()
        }
        val t0 = SystemClock.uptimeMillis()
        val p = link(vs, fs)
        android.util.Log.i("GradeGl", "grade shader compiled in ${SystemClock.uptimeMillis() - t0} ms")
        runCatching {
            val len = IntArray(1).also { GLES20.glGetProgramiv(p, android.opengl.GLES30.GL_PROGRAM_BINARY_LENGTH, it, 0) }[0]
            if (len > 0) {
                val buf = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder())
                val got = IntArray(1)
                val fmt = IntArray(1)
                android.opengl.GLES30.glGetProgramBinary(p, len, got, 0, fmt, 0, buf)
                val bytes = ByteArray(got[0]).also { buf.position(0); buf.get(it) }
                mem[key] = fmt[0] to bytes
                file?.let { f ->
                    f.parentFile?.mkdirs()
                    java.io.DataOutputStream(f.outputStream().buffered()).use { it.writeInt(fmt[0]); it.write(bytes) }
                }
            }
        }
        p
    }

    /** True once the preview's programs are ready (compiled, or loaded from the cache). */
    @Volatile var ready = false
        private set

    /** Compiles (or loads) the preview's programs on a thread of their own, so the first project opens without a wait. */
    fun warmUp(context: Context) {
        dir = java.io.File(context.cacheDir, "shaders")
        Thread({
            runCatching {
                val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val v = IntArray(2)
                EGL14.eglInitialize(d, v, 0, v, 1)
                val configs = arrayOfNulls<EGLConfig>(1)
                val n = IntArray(1)
                EGL14.eglChooseConfig(d, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
                val ctx = EGL14.eglCreateContext(d, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                val pb = EGL14.eglCreatePbufferSurface(d, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                EGL14.eglMakeCurrent(d, pb, pb, ctx)
                GLES20.glDeleteProgram(program(GradeShader.VERTEX, GradeShader.FRAGMENT))
                GLES20.glDeleteProgram(program(GradeShader.VERTEX, GradeShader.IMAGE_FRAGMENT))
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(d, pb)
                SatCompTable.forget(ctx)
                EGL14.eglDestroyContext(d, ctx)
            }.onFailure { android.util.Log.w("GradeGl", "shader warm-up failed", it) }
            ready = true
        }, "shader-warmup").apply { priority = Thread.MIN_PRIORITY }.start()
    }
}

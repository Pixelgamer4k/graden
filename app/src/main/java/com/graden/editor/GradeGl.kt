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
    }
    private lateinit var video: Prog
    private lateinit var image: Prog
    private lateinit var quad: FloatBuffer
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
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0)
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        video = Prog(link(GradeShader.VERTEX, GradeShader.FRAGMENT))
        // The same grade for a plain image texture.
        image = Prog(link(GradeShader.VERTEX, GradeShader.FRAGMENT
            .replace("#extension GL_OES_EGL_image_external : require\n", "")
            .replace("samplerExternalOES", "sampler2D")))
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    }

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
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    fun windowSurface(st: SurfaceTexture): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, st, intArrayOf(EGL14.EGL_NONE), 0)

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
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
        }
        surface?.let { EGL14.eglDestroySurface(display, it) }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        thread.quitSafely()
    }

    private fun drawQuad(tex: Int, texM: FloatArray, rect: FloatArray, grade: FloatArray?, texelX: Float, texelY: Float, still: Boolean = false) {
        val p = if (still) image else video
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (still) GLES20.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(p.uSrc, 0)
        GLES20.glUniformMatrix4fv(p.uTex, 1, false, texM, 0)
        GLES20.glUniform4fv(p.uRect, 1, rect, 0)
        GLES20.glUniform1f(p.uOn, if (grade != null) 1f else 0f)
        if (grade != null) GLES20.glUniform4fv(p.uG, 7, grade, 0)
        GLES20.glUniform2f(p.uTexel, texelX, texelY)
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
        if (still != 0) {
            // A native frame at rest: the image texture, upright (bitmap rows run top to bottom).
            val rect = v.placement(w, h)
            val pw = (rect[2] - rect[0]) / 2f * w
            val ph = (rect[3] - rect[1]) / 2f * h
            drawQuad(still, FLIP_Y, rect, grade, 1f / pw.coerceAtLeast(1f), 1f / ph.coerceAtLeast(1f), still = true)
        } else if (hasFrame) {
            val rect = v.placement(w, h)
            val pw = (rect[2] - rect[0]) / 2f * w
            val ph = (rect[3] - rect[1]) / 2f * h
            drawQuad(tex, texM, rect, grade, 1f / pw.coerceAtLeast(1f), 1f / ph.coerceAtLeast(1f))
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
        if (still != 0) drawQuad(still, FLIP_Y, FULL, v.gradeUniforms, 1f / w, 1f / h, still = true)
        else drawQuad(tex, texM, FULL, v.gradeUniforms, 1f / w, 1f / h)
        val buf = readBuf!!
        buf.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        buf.position(0)
        sink(buf, w, h)
    }

    private companion object {
        val FULL = floatArrayOf(-1f, -1f, 1f, 1f)
        /** Texture transform that turns an image upright: y becomes 1 - y. */
        val FLIP_Y = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
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
    private var core: GlCore? = null
    // GL-thread state.
    @Volatile private var inputTexture: SurfaceTexture? = null
    private var tex = 0
    private var egl: EGLSurface? = null
    private var w = 0
    private var h = 0
    private val texM = FloatArray(16)
    private var hasFrame = false
    private var drawPending = false
    @Volatile internal var gradeUniforms: FloatArray? = null
    @Volatile private var stillShown: (() -> Unit)? = null
    /** The native frame's image texture (0: none); set and used on the GL thread. */
    @Volatile internal var stillTex = 0
    /** The stamp of the last video frame this view drew (the time the player released it for display). */
    @Volatile var lastFrameNs = 0L
        private set
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
    fun setLook(grade: Grade?, aspect: Float, fill: Boolean) {
        val g = grade?.takeIf { !it.isIdentity }
        if (g == lastGrade && aspect == contentAspect && fill == this.fill) return
        lastGrade = g
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
        val s = egl ?: return
        core?.draw(this, s, tex, texM, w, h, hasFrame)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val c = GlCore(key)
        core = c
        c.handler.post {
            w = width
            h = height
            egl = c.windowSurface(st)
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
            inputTexture?.release()
            inputTexture = null
            s?.release()
            c.release(egl, tex)
            egl = null
            tex = 0
            hasFrame = false
            st.release()
        }
        return false
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}

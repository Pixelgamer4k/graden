package com.graden.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Decodes the exact frame at a given time from a video file, at a given size.
 *
 * MediaMetadataRetriever was asked for "the closest frame" but on this hardware returns the keyframe before it, which
 * in footage with keyframes ten seconds apart is a picture from up to ten seconds earlier. Here the file's own decoder
 * seeks to the keyframe before the time and decodes forward, skipping every frame without drawing it, until the frame
 * whose timestamp is the one asked for; only that frame is drawn, scaled on the GPU and read back.
 *
 * The decoder for the last file is kept, so asking for a later time in it carries on decoding instead of starting over.
 * One instance, used from one thread.
 */
class ExactFrameGrabber(private val context: Context) {
    private val stThread = HandlerThread("grab-frames").apply { start() }
    private val stHandler = Handler(stThread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var oesTex = 0
    private lateinit var texture: SurfaceTexture
    private lateinit var surface: Surface
    private val frameLock = Object()
    private var frameReady = false
    private val texM = FloatArray(16)
    private lateinit var quad: FloatBuffer

    // The open file, if any.
    private var source: String? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var inputDone = false
    /** Timestamp of the last frame the decoder put out (drawn or skipped); -1 before the first. */
    private var lastOutUs = -1L
    private var frameUs = 33_333L
    private var mime = ""
    private fun setUpGl() {
        if (eglContext != EGL14.EGL_NO_CONTEXT) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE),
            0, configs, 0, 1, IntArray(1), 0,
        )
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)
        program = link(VERTEX, FRAGMENT)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        texture = SurfaceTexture(oesTex)
        texture.setOnFrameAvailableListener({
            synchronized(frameLock) {
                frameReady = true
                frameLock.notifyAll()
            }
        }, stHandler)
        surface = Surface(texture)
        quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    }

    private fun link(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, sh(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, sh(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }

    /**
     * The frame of [path] (a file path or content URI) whose timestamp is [timeUs] (or the one just after, if none is
     * exactly there), as a [w] × [h] bitmap. Null if it cannot be read. [cancelled] is polled to give up early.
     */
    fun grab(path: String, timeUs: Long, w: Int, h: Int, cancelled: () -> Boolean): Bitmap? {
        setUpGl()
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)
        val t0 = System.nanoTime()
        var decoded = 0
        fun log(what: String) = android.util.Log.d("PlayProbe", "GRAB $what ${path.substringAfterLast('/')} @${timeUs / 1000}ms: ${(System.nanoTime() - t0) / 1_000_000}ms, decoded $decoded")
        try {
            if (source != path || extractor == null) open(path) ?: run { log("could not open"); return null }
            val ex = extractor!!
            val dec = codec!!
            // Carry on from where the decoder is if the frame is ahead of it and not far; otherwise seek to the
            // keyframe before it and start decoding from there.
            if (lastOutUs < 0 || timeUs < lastOutUs - frameUs / 2 || timeUs - lastOutUs > 3_000_000L || inputDone) {
                ex.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                dec.flush()
                inputDone = false
                lastOutUs = -1L
            }
            val info = MediaCodec.BufferInfo()
            var spins = 0
            while (true) {
                if (cancelled()) { log("cancelled"); return null }
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(2_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 2_000)
                if (o >= 0) {
                    val pts = info.presentationTimeUs
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    // The wanted frame: the first whose time reaches the one asked for (within half a frame), or the last.
                    val hit = pts >= timeUs - frameUs / 2 || eos
                    lastOutUs = pts
                    decoded++
                    if (!hit) {
                        dec.releaseOutputBuffer(o, false)
                        continue
                    }
                    synchronized(frameLock) { frameReady = false }
                    dec.releaseOutputBuffer(o, true)
                    synchronized(frameLock) {
                        val until = System.currentTimeMillis() + 1000
                        while (!frameReady && System.currentTimeMillis() < until) frameLock.wait(50)
                        if (!frameReady) { log("no frame drawn"); return null }
                    }
                    texture.updateTexImage()
                    texture.getTransformMatrix(texM)
                    return readBack(w, h).also { log("ok") }
                } else if (o == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++spins > 500) { log("ran out"); return null }
            }
        } catch (e: Exception) {
            log("failed: ${e.javaClass.simpleName} ${e.message}")
            close()
            return null
        }
    }

    private fun open(path: String): Unit? {
        close()
        val ex = MediaExtractor()
        try {
            if (path.startsWith("file://")) ex.setDataSource(Uri.parse(path).path!!)
            else if (path.startsWith("/")) ex.setDataSource(path)
            else ex.setDataSource(context, Uri.parse(path), null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: run { ex.release(); return null }
            val f = ex.getTrackFormat(track)
            ex.selectTrack(track)
            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                val fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                if (fps > 1f) frameUs = (1_000_000f / fps).toLong()
            }
            mime = f.getString(MediaFormat.KEY_MIME)!!
            val dec = MediaCodec.createDecoderByType(mime)
            // As fast as the hardware goes, not paced for real-time playback.
            f.setInteger(MediaFormat.KEY_PRIORITY, 1)
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            dec.configure(f, surface, null, 0)
            dec.start()
            extractor = ex
            codec = dec
            source = path
            inputDone = false
            lastOutUs = -1L
            return Unit
        } catch (e: Exception) {
            runCatching { ex.release() }
            return null
        }
    }

    /** Draws the decoded frame into a [w] × [h] target and reads it back, top row first. */
    private fun readBack(w: Int, h: Int): Bitmap {
        val fb = IntArray(1)
        val tx = IntArray(1)
        GLES20.glGenTextures(1, tx, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tx[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, fb, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tx[0], 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uSrc"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texM, 0)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fb, 0)
        GLES20.glDeleteTextures(1, tx, 0)
        buf.position(0)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    /** Lets go of the decoder (and its hardware), e.g. while proxies are being made. */
    fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
        source = null
        lastOutUs = -1L
    }

    private companion object {
        // Drawn upside down on purpose: glReadPixels reads the bottom row first, so the bitmap comes out upright.
        const val VERTEX = """
attribute vec2 aPos;
uniform mat4 uTex;
varying vec2 vUv;
void main() {
    vec2 t = aPos * 0.5 + 0.5;
    vUv = (uTex * vec4(t, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos.x, -aPos.y, 0.0, 1.0);
}
"""
        const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES uSrc;
void main() { gl_FragColor = vec4(texture2D(uSrc, vUv).rgb, 1.0); }
"""
    }
}

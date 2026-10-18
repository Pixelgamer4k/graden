package com.graden.editor

import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resolve's "Saturation Compression" gamut mapping, as a measured table. Resolve 21 does not publish it; it was measured
 * through its own Color Space Transform: it depends only on a colour's chromaticity (out(k·c) = k·out(c)), acts in the
 * output gamut, and differs slightly between Rec.709, Rec.2020 and P3 outputs. So for each of those, and each face of
 * the colour cube where one channel is largest (scaled to 1), the result was rendered on a 192 × 192 grid of the other
 * two channels (−3 … 1). Stored as half floats: rows (gamut × 3 + face) × 192 + second channel, columns the first.
 */
object SatCompTable {
    const val N = 192
    private const val ROWS = 3 * 3 * N
    /** Texture unit 10: 0–8 are the grade's own, 9 the stabiliser's mesh. */
    private const val UNIT = 10
    private val data: ByteBuffer? by lazy {
        runCatching {
            val bytes = SatCompTable::class.java.getResourceAsStream("/com/graden/editor/satcomp.bin")!!.use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.position(0) }
        }.onFailure { android.util.Log.w("SatComp", "table missing", it) }.getOrNull()
    }
    /** One texture per GL context. */
    private val textures = HashMap<Long, Int>()

    /** Binds the table to its texture unit for [prog] (the current context's copy, made on first use). */
    fun bind(prog: Int) {
        val ctx = EGL14.eglGetCurrentContext()
        val key = ctx.nativeHandle
        var tex = synchronized(textures) { textures[key] } ?: 0
        if (tex == 0) {
            val d = data
            if (d == null) { GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSatCompOn"), 0f); return }
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGB16F, N, ROWS, 0, GLES20.GL_RGB, GLES30.GL_HALF_FLOAT, d)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            synchronized(textures) { textures[key] = tex }
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uSatComp"), UNIT)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSatCompOn"), 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /**
     * Forgets [ctx]'s copy: call when destroying a context, as a new context may be given the same handle and the old
     * texture name would then point at nothing (or at another texture).
     */
    fun forget(ctx: android.opengl.EGLContext) { synchronized(textures) { textures.remove(ctx.nativeHandle) } }

    /** Forgets the current context's copy (call before the context is destroyed). */
    fun release() {
        val key = EGL14.eglGetCurrentContext().nativeHandle
        synchronized(textures) { textures.remove(key) }?.let { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
    }

    /** Which table an output gamut uses: 1 Rec.709 (and anything else), 2 Rec.2020, 3 P3. */
    fun index(g: ColorSpaces.Gamut): Int = when (g) {
        ColorSpaces.G2020 -> 2
        ColorSpaces.GP3D65, ColorSpaces.GP3DCI, ColorSpaces.GP3D60 -> 3
        else -> 1
    }
}

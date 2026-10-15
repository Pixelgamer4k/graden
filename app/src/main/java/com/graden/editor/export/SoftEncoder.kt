package com.graden.editor.export

import java.nio.ByteBuffer

/**
 * x264 / x265, built into the app: software encoders with no size limit, true constant quality (CRF) and 10-bit
 * output on any phone. Frames are given as read back from the renderer, bottom row first: [FMT_RGBA8] or [FMT_RGB10].
 */
class SoftEncoder(codec: VideoCodec, w: Int, h: Int, fpsNum: Int, fpsDen: Int, depth: Int, rc: Int, value: Int, keyint: Int, preset: String) : AutoCloseable {
    private var handle: Long = nativeOpen(if (codec == VideoCodec.H264) 0 else 1, w, h, fpsNum, fpsDen, depth, rc, value, keyint, preset)
    val ok: Boolean get() = handle != 0L

    /** The stream's parameter sets, Annex B. */
    val headers: ByteArray get() = nativeHeaders(handle)

    /**
     * Encodes [frame] (null to drain). Returns the bytes written to [out] (0: nothing yet, -1: fully drained); [meta]
     * receives the frame number and whether it is a keyframe.
     */
    fun encode(frame: ByteBuffer?, fmt: Int, out: ByteBuffer, meta: LongArray): Int = nativeEncode(handle, frame, fmt, out, meta)

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    companion object {
        const val FMT_RGBA8 = 0
        const val FMT_RGB10 = 2
        val available: Boolean = runCatching { System.loadLibrary("softenc"); true }.getOrDefault(false)

        /** Speed presets, fastest to best compression. */
        val presets = listOf("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow")

        @JvmStatic private external fun nativeOpen(codec: Int, w: Int, h: Int, fpsNum: Int, fpsDen: Int, depth: Int, rc: Int, value: Int, keyint: Int, preset: String): Long
        @JvmStatic private external fun nativeHeaders(h: Long): ByteArray
        @JvmStatic private external fun nativeEncode(h: Long, frame: ByteBuffer?, fmt: Int, out: ByteBuffer, meta: LongArray): Int
        @JvmStatic external fun nativeToP010(frame: ByteBuffer, fmt: Int, w: Int, h: Int, out: ByteBuffer, stride: Int, sliceHeight: Int)
        @JvmStatic private external fun nativeClose(h: Long)
    }
}

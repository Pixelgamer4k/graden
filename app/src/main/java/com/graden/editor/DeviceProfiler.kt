package com.graden.editor

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/**
 * Decides at import how a file should be played on *this* device. It asks the platform whether
 * a hardware decoder covers the file, then times a short real decode of the file itself.
 * The result is headroom = measured decode speed / the file's frame rate.
 */
object DeviceProfiler {
    const val TIER_NATIVE = 0
    const val TIER_720 = 1
    const val TIER_540 = 2

    class Result(val tier: Int, val headroom: Float)

    /**
     * Short side of the playback proxy for [tier]. Playback always runs from a proxy, so even a file
     * this device decodes comfortably gets one; heavier files get a smaller one.
     */
    fun proxyShortSide(tier: Int) = if (tier == TIER_540) 432 else 540

    /**
     * Every video gets an edit proxy, even small H.264: what matters for smooth editing is how close each frame is to
     * a keyframe, and ordinary recordings keep them a second or more apart.
     */
    fun needsPlayProxy(a: MediaAsset): Boolean = !a.isAudio && !a.isImage

    fun profile(context: Context, uri: Uri, a: MediaAsset): Result {
        val hardware = hardwareDecoderCovers(a)
        val measuredFps = benchmark(context, uri)
        val headroom = measuredFps / a.fps
        // Playback keeps two decoders of the same file alive (current and next clip) next to the UI,
        // so "comfortable" needs roughly three times real-time on a single decoder.
        var tier = when {
            !hardware -> TIER_540
            headroom >= 3f -> TIER_NATIVE
            headroom >= 1.6f -> TIER_720
            else -> TIER_540
        }
        forcedTier()?.let { tier = it }
        Log.i("Graden", "profile ${a.name}: ${a.mime} ${a.width}x${a.height}@${a.fps} hw=$hardware decode=${measuredFps.toInt()}fps headroom=${"%.1f".format(headroom)} tier=$tier")
        return Result(tier, headroom)
    }

    private fun hardwareDecoderCovers(a: MediaAsset): Boolean {
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder || !info.isHardwareAccelerated) continue
            val type = info.supportedTypes.firstOrNull { it.equals(a.mime, ignoreCase = true) } ?: continue
            val v = runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull() ?: continue
            val fps = a.fps.toDouble()
            // The stored size is the displayed one; the coded frame may be rotated.
            if (runCatching { v.areSizeAndRateSupported(a.width, a.height, fps) }.getOrDefault(false) ||
                runCatching { v.areSizeAndRateSupported(a.height, a.width, fps) }.getOrDefault(false)
            ) return true
        }
        return false
    }

    /** Decodes the start of the file flat out for about a second and returns frames per second (0 on failure). */
    private fun benchmark(context: Context, uri: Uri): Float {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        try {
            ex.setDataSource(context, uri, null)
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    ex.selectTrack(i)
                    format = f
                    break
                }
            }
            val f = format ?: return 0f
            codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(f, surface, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var frames = 0
            var firstAt = 0L
            var lastAt = 0L
            var inputDone = false
            val deadline = SystemClock.elapsedRealtime() + 1500L
            while (frames < 90 && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(2000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 2000)
                if (o >= 0) {
                    codec.releaseOutputBuffer(o, false)
                    lastAt = SystemClock.elapsedRealtimeNanos()
                    if (frames++ == 0) firstAt = lastAt
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return if (frames < 5 || lastAt <= firstAt) 0f else (frames - 1) * 1e9f / (lastAt - firstAt)
        } catch (e: Exception) {
            return 0f
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
            surface.release()
            texture.release()
        }
    }

    /** Developer override for testing weak-device behaviour: `adb shell setprop debug.graden.tier 1`. */
    private fun forcedTier(): Int? = runCatching {
        val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.graden.tier") as String
        v.toIntOrNull()?.takeIf { it in TIER_NATIVE..TIER_540 }
    }.getOrNull()
}

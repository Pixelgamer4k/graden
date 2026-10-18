package com.graden.editor.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.graden.editor.MediaAsset
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * The bitrate the footage itself was recorded at, so an export never throws away what the camera kept: "Automatic ·
 * Best" gives at least the source's bitrate, scaled to the export's size and frame rate, and raised for H.264 (which
 * needs about half as much again as HEVC, AV1 or VP9 for the same picture).
 */
object SourceRate {
    private val cache = ConcurrentHashMap<String, Int>()

    /** A video file's own bitrate (bits a second): what its video track says, else its size over its length; 0 unknown. */
    fun of(context: Context, uri: String, durationUs: Long): Int = cache.getOrPut(uri) {
        runCatching {
            val ex = MediaExtractor()
            try {
                if (uri.startsWith("content:")) ex.setDataSource(context, Uri.parse(uri), null)
                else ex.setDataSource(if (uri.startsWith("file:")) Uri.parse(uri).path!! else uri)
                val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                if (f != null && f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE).takeIf { it > 0 }
                else null
            } finally { ex.release() }
        }.getOrNull() ?: run {
            val bytes = runCatching {
                if (uri.startsWith("content:")) context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length } ?: -1L
                else File(if (uri.startsWith("file:")) Uri.parse(uri).path!! else uri).length()
            }.getOrDefault(-1L)
            if (bytes > 0 && durationUs > 0) (bytes * 8.0 / (durationUs / 1e6)).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
        }
    }

    /** What the export of [assets] at [w]×[h] and [fps] in [codec] needs to keep each source's quality (bits a second). */
    fun floor(context: Context, assets: Collection<MediaAsset>, w: Int, h: Int, fps: Float, codec: VideoCodec): Int {
        var best = 0.0
        for (a in assets) {
            if (a.isImage || a.width <= 0 || a.height <= 0) continue
            val bps = of(context, a.uri, a.durationUs).takeIf { it > 0 } ?: continue
            val px = (w.toDouble() * h) / (a.width.toDouble() * a.height)
            val rate = if (a.fps > 1f && fps > 1f) fps / a.fps.toDouble() else 1.0
            val src = a.mime.lowercase()
            val k = if (codec == VideoCodec.H264 && (src.contains("hevc") || src.contains("av01") || src.contains("vp9") || src.contains("dolby"))) 1.5 else 1.0
            best = max(best, bps * px * rate * k)
        }
        return best.toLong().coerceIn(0L, 2_000_000_000L).toInt()
    }
}

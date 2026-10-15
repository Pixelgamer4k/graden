package com.graden.editor

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

object MediaProbe {
    /** Reads container/track metadata only; returns null if there is no video track. */
    fun probe(context: Context, uri: Uri): MediaAsset? {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (type?.startsWith("image/") == true) return picture(context, uri, type)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var video: MediaFormat? = null
            var hasAudio = false
            var audio: MediaFormat? = null
            var videoTrack = -1
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && video == null) {
                    video = f
                    videoTrack = i
                }
                else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    if (audio == null) audio = f
                }
            }
            if (video == null) return audioOnly(context, uri, audio ?: return null)
            val v = video
            var w = v.getInteger(MediaFormat.KEY_WIDTH)
            var h = v.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (v.containsKey(MediaFormat.KEY_ROTATION)) v.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rotation == 90 || rotation == 270) w = h.also { h = w }
            var fps = if (v.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    v.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                } catch (e: ClassCastException) {
                    v.getFloat(MediaFormat.KEY_FRAME_RATE)
                }
            } else 0f
            val transfer = if (v.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) v.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
            val hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
            var durationUs = if (v.containsKey(MediaFormat.KEY_DURATION)) v.getLong(MediaFormat.KEY_DURATION) else 0L
            // Some files carry duration and frame rate only at container level, not on the video track.
            if (durationUs <= 0L || fps <= 0f) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, uri)
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (durationUs <= 0L) durationUs = ms * 1000L
                    val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
                    if (fps <= 0f && frames > 0 && ms > 0) fps = (frames * 1000.0 / ms).roundToInt().toFloat()
                } finally {
                    r.release()
                }
            }
            // Last resort (e.g. fragmented MP4 with no duration anywhere): measure from the samples themselves.
            if (durationUs <= 0L || fps <= 0f) {
                ex.selectTrack(videoTrack)
                var first = Long.MAX_VALUE
                var last = Long.MIN_VALUE
                var n = 0
                while (n < 48 && ex.sampleTime >= 0) {
                    first = minOf(first, ex.sampleTime)
                    last = maxOf(last, ex.sampleTime)
                    n++
                    if (!ex.advance()) break
                }
                if (fps <= 0f) {
                    // Samples arrive in decode order, so the estimate is slightly off; snap to a standard rate.
                    val est = if (n > 1 && last > first) ((n - 1) * 1e6 / (last - first)).toFloat() else 30f
                    fps = floatArrayOf(24f, 25f, 30f, 48f, 50f, 60f, 90f, 120f, 240f).minByOrNull { abs(it - est) / it }
                        ?.takeIf { abs(it - est) / it < 0.06f } ?: est.roundToInt().toFloat()
                }
                if (durationUs <= 0L) {
                    ex.seekTo(Long.MAX_VALUE / 2, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var end = last
                    while (ex.sampleTime >= 0) {
                        end = maxOf(end, ex.sampleTime)
                        if (!ex.advance()) break
                    }
                    durationUs = end - minOf(first, 0L) + (1e6 / fps).toLong()
                }
            }
            if (durationUs <= 0L) return null
            return MediaAsset(
                id = 0,
                uri = uri.toString(),
                name = displayName(context, uri),
                durationUs = durationUs,
                width = w,
                height = h,
                fps = if (fps > 0f) fps else 30f,
                hasAudio = hasAudio,
                hdr = hdr,
                mime = v.getString(MediaFormat.KEY_MIME) ?: "",
            )
        } catch (e: Exception) {
            Log.w("Graden", "probe failed for $uri", e)
            return null
        } finally {
            ex.release()
        }
    }

    /** A still picture. It starts as a five-second clip and can be stretched on the timeline. */
    private fun picture(context: Context, uri: Uri, type: String): MediaAsset? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return MediaAsset(0, uri.toString(), displayName(context, uri), PICTURE_US, bounds.outWidth, bounds.outHeight, 30f, false, false, type)
    }

    const val PICTURE_US = 5_000_000L

    private fun audioOnly(context: Context, uri: Uri, f: MediaFormat): MediaAsset? {
        var durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
        if (durationUs <= 0L) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            } finally {
                r.release()
            }
        }
        if (durationUs <= 0L) return null
        return MediaAsset(0, uri.toString(), displayName(context, uri), durationUs, 0, 0, 30f, true, false, f.getString(MediaFormat.KEY_MIME) ?: "")
    }

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "Clip"
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "Clip"
    }
}

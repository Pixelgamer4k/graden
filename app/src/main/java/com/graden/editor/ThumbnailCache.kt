package com.graden.editor

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Filmstrip thumbnails. Lookups never block the UI thread: a miss queues a decode and
 * returns null. Newest requests are served first and stale ones are dropped, so a fast
 * scroll through hours of footage only decodes what ends up on screen.
 */
class ThumbnailCache(private val context: Context) {
    /** Bumped on the main thread whenever a thumbnail lands; read it in draw to redraw. */
    var version by mutableIntStateOf(0)
        private set

    private class Request(val key: Long, val source: String, val timeUs: Long, val image: Boolean)

    private val cache = object : LruCache<Long, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
    }
    private val lock = Object()
    private val stack = ArrayDeque<Request>()
    private val pending = HashSet<Long>()
    private val failed = HashSet<Long>()
    private val main = Handler(Looper.getMainLooper())

    /**
     * While true (playing, scrubbing) nothing new is read: reading needs a hardware decoder, and playback needs every one
     * the phone has. Pictures already cached are still shown; the rest are read once this is false again.
     */
    @Volatile var paused = false
        set(v) {
            field = v
            if (!v) synchronized(lock) { lock.notifyAll() }
        }

    init {
        repeat(WORKERS) { i -> Thread({ work() }, "thumbs-$i").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }.start() }
    }

    /** [proxyPath], when present, is decoded instead of the original: it is small and every frame is a keyframe. */
    fun get(asset: MediaAsset, timeUs: Long, proxyPath: String? = null): ImageBitmap? {
        // Keyed by the file, since asset ids repeat from one project to the next.
        val key = (asset.uri.hashCode().toLong() shl 32) xor (timeUs / 1000)
        cache.get(key)?.let { return it }
        synchronized(lock) {
            if (key !in failed && pending.add(key)) {
                stack.addLast(Request(key, proxyPath ?: asset.uri, timeUs, asset.isImage))
                if (stack.size > MAX_QUEUE) pending.remove(stack.removeFirst().key)
                lock.notify()
            }
        }
        return null
    }

    private fun work() {
        // Retrievers are not thread-safe, so each worker keeps its own small set open.
        val retrievers = LinkedHashMap<String, MediaMetadataRetriever>(8, 0.75f, true)
        while (true) {
            val req = synchronized(lock) {
                if (stack.isEmpty() || paused) {
                    // Nothing more to read for now: give the decoders back at once.
                    retrievers.values.forEach { runCatching { it.release() } }
                    retrievers.clear()
                }
                while (stack.isEmpty() || paused) lock.wait()
                stack.removeLast()
            }
            val bitmap = if (req.image) picture(req.source) else try {
                val r = retrievers.getOrPut(req.source) {
                    if (retrievers.size >= 1) {
                        val eldest = retrievers.keys.first()
                        retrievers.remove(eldest)?.release()
                    }
                    MediaMetadataRetriever().apply {
                        if (req.source.startsWith("/")) setDataSource(req.source) else setDataSource(context, Uri.parse(req.source))
                    }
                }
                r.getScaledFrameAtTime(req.timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_W, THUMB_H)
            } catch (e: Exception) {
                retrievers.remove(req.source)?.let { runCatching { it.release() } }
                null
            }
            if (bitmap != null) cache.put(req.key, bitmap.asImageBitmap())
            synchronized(lock) {
                pending.remove(req.key)
                if (bitmap == null) failed.add(req.key)
            }
            if (bitmap != null) main.post { version++ }
        }
    }

    /** Decodes a still picture at roughly thumbnail size. */
    private fun picture(source: String): android.graphics.Bitmap? = try {
        val uri = Uri.parse(source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_W && bounds.outHeight / (sample * 2) >= THUMB_H / 2) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        // Each worker holds a decoder while it reads; the phone has few, and playback needs them more.
        const val WORKERS = 2
        // A screen of eight lanes can ask for a couple of hundred filmstrip pictures at once.
        const val MAX_QUEUE = 256
        const val THUMB_W = 160
        const val THUMB_H = 160
    }
}

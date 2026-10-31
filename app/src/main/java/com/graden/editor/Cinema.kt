package com.graden.editor

import android.content.Context
import android.net.Uri
import com.graden.editor.cinema.AiEngine
import com.graden.editor.cinema.ClipCache
import com.graden.editor.cinema.FocusEdits
import com.graden.editor.cinema.FocusTrack
import com.graden.editor.cinema.Settings
import org.json.JSONArray
import org.json.JSONObject

/** One focus decision on a clip, at file time [us]. */
data class CinemaKey(
    val us: Long, val mode: Int, val x: Float, val y: Float, val depth: Float, val seconds: Float, val strong: Boolean = true,
    /** The pull's curve: how it leaves and how it arrives, 0..1 each. */
    val outHandle: Float = 0f, val inHandle: Float = 1f,
) {
    fun toJson(): JSONObject = JSONObject().put("us", us).put("m", mode).put("x", x.toDouble()).put("y", y.toDouble()).put("d", depth.toDouble())
        .put("s", seconds.toDouble()).put("st", strong).put("oh", outHandle.toDouble()).put("ih", inHandle.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = CinemaKey(o.optLong("us"), o.optInt("m"), o.optDouble("x", .5).toFloat(), o.optDouble("y", .5).toFloat(), o.optDouble("d", .5).toFloat(),
            o.optDouble("s", .8).toFloat(), o.optBoolean("st", true), o.optDouble("oh", 0.0).toFloat(), o.optDouble("ih", 1.0).toFloat())
    }
}

/**
 * Cinema on a clip: a lens on the frame as it comes in, before the grade, with focus decided automatically and by [keys].
 * Depth, matte and subjects are measured once per file and kept by [ClipCache]; nothing here reruns them.
 */
data class Cinema(
    val on: Boolean = true,
    val strength: Float = 0.45f,
    val shape: Int = 0,
    val rotation: Float = 0f,
    val highlight: Float = 0f,
    val rim: Float = 0f,
    val breathing: Float = 0f,
    val samples: Int = 96,
    val pull: Float = 0.8f,
    val keys: List<CinemaKey> = emptyList(),
) {
    fun settings(): Settings = Settings().also {
        it.strength = strength; it.shape = shape; it.rotation = rotation; it.highlight = highlight; it.rim = rim; it.breathing = breathing; it.samples = samples
    }

    fun edits(): FocusEdits = FocusEdits().also { e ->
        e.duration = pull
        for (k in keys) e.put(FocusEdits.Key(k.us, k.mode, k.x, k.y, k.depth, k.seconds).also { it.strong = k.strong; it.outHandle = k.outHandle; it.inHandle = k.inHandle })
    }

    fun toJson(): JSONObject = JSONObject().put("on", on).put("st", strength.toDouble()).put("sh", shape).put("ro", rotation.toDouble()).put("hi", highlight.toDouble())
        .put("ri", rim.toDouble()).put("br", breathing.toDouble()).put("sa", samples).put("pu", pull.toDouble()).put("k", JSONArray().apply { keys.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject?): Cinema? = o?.let {
            Cinema(it.optBoolean("on", true), it.optDouble("st", .45).toFloat(), it.optInt("sh"), it.optDouble("ro", 0.0).toFloat(), it.optDouble("hi", 0.0).toFloat(), it.optDouble("ri", 0.0).toFloat(),
                it.optDouble("br", 0.0).toFloat(), it.optInt("sa", 96), it.optDouble("pu", .8).toFloat(),
                it.optJSONArray("k")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(CinemaKey::fromJson) } } ?: emptyList())
        }
    }
}

/** A clip's analysis with its focus resolved, ready to draw: used by the preview and by export alike. */
class CinemaRun(val cache: ClipCache, val track: FocusTrack.Result, val settings: Settings) {
    private val held = object : LinkedHashMap<Int, AiEngine.Maps>(4, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, AiEngine.Maps>?): Boolean {
            if (size > 3) { e?.value?.close(); return true }
            return false
        }
    }

    fun index(us: Long) = cache.nearest(us)

    @Synchronized fun maps(i: Int): AiEngine.Maps = held[i] ?: cache.maps(i).also { held[i] = it }

    /** Frees what's held: only once nothing is drawing from it. */
    @Synchronized fun close() { held.values.forEach { it.close() }; held.clear() }

    companion object {
        /** The cache of [uri], whether or not it has been measured. */
        fun cache(c: Context, uri: String) = ClipCache(c, Uri.parse(uri))

        private var kept: Array<ClipCache.Feature?> = emptyArray()
        private var keptFor = ""

        /** Resolves focus for [cinema]; null when the file hasn't been measured. Tracking data stays in memory, so taps answer at once. */
        @Synchronized fun build(c: Context, uri: String, cinema: Cinema): CinemaRun? {
            val cc = cache(c, uri)
            if (!cc.ready()) return null
            val tag = uri + ":" + cc.times.size + ":" + cc.times.last()
            if (tag != keptFor) { kept = arrayOfNulls(cc.times.size); keptFor = tag }
            val memo = kept
            val src = object : FocusTrack.Source {
                override fun size() = memo.size
                override fun feature(i: Int): ClipCache.Feature = memo[i] ?: cc.feature(i).also { memo[i] = it }
            }
            return CinemaRun(cc, FocusTrack.resolve(src, cinema.edits()), cinema.settings())
        }
    }
}

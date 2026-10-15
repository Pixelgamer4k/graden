package com.graden.editor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Editor state that is not the edit itself but should come back the way it was left. */
data class UiState(
    val playheadUs: Long = 0L,
    val pxPerSec: Float = 0f,
    val laneScale: Float = 1f,
    val snap: Boolean = true,
    val muted: Set<Int> = emptySet(),
    val hidden: Set<Int> = emptySet(),
    val locked: Set<Int> = emptySet(),
)

/**
 * Saves the single working project as JSON in app storage. A write goes to a temporary file that
 * then replaces the project in one step, and the version it replaces is kept as a backup, so a
 * crash or a full disk mid-write can never leave a broken project: at worst the previous one loads.
 */
class ProjectStore(private val dir: File) {
    private val file = File(dir, "project.json")
    private val backup = File(dir, "project.json.bak")

    @Synchronized
    fun save(assets: Collection<MediaAsset>, tracks: List<Track>, ui: UiState, meta: ProjectMeta) {
        dir.mkdirs()
        val root = JSONObject()
        root.put("meta", meta.toJson())
        root.put("assets", JSONArray().apply {
            for (a in assets) put(
                JSONObject()
                    .put("id", a.id).put("uri", a.uri).put("name", a.name)
                    .put("durationUs", a.durationUs).put("width", a.width).put("height", a.height)
                    .put("fps", a.fps.toDouble()).put("hasAudio", a.hasAudio).put("hdr", a.hdr).put("mime", a.mime).put("tier", a.tier)
                    .apply { if (a.isReversed) put("revOf", a.reverseOf).put("revIn", a.revInUs).put("revOut", a.revOutUs) }
            )
        })
        root.put("tracks", JSONArray().apply {
            for (t in tracks) put(JSONObject().put("id", t.id).put("video", t.video).put("name", t.name).put("clips", clipsJson(t.clips)))
        })
        fun ids(set: Set<Int>) = JSONArray().apply { set.forEach { put(it) } }
        root.put(
            "ui", JSONObject().put("playheadUs", ui.playheadUs).put("pxPerSec", ui.pxPerSec.toDouble()).put("laneScale", ui.laneScale.toDouble())
                .put("snap", ui.snap).put("muted", ids(ui.muted)).put("hidden", ids(ui.hidden)).put("locked", ids(ui.locked))
        )
        val tmp = File(file.path + ".tmp")
        tmp.writeText(root.toString())
        if (file.exists()) file.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun clipsJson(list: List<Clip>) = JSONArray().apply {
        fun fx(f: Fx?) = f?.let { JSONObject().put("type", it.type).put("us", it.durationUs).put("xa", it.crossfade) }
        for (c in list) put(
            JSONObject().put("id", c.id).put("assetId", c.assetId).put("inUs", c.inUs).put("outUs", c.outUs).put("startUs", c.startUs)
                .putOpt("transition", fx(c.transition)).putOpt("animIn", fx(c.animIn)).putOpt("animOut", fx(c.animOut))
                .putOpt("tf", if (c.tf.isIdentity) null else JSONObject().put("x", c.tf.x.toDouble()).put("y", c.tf.y.toDouble())
                    .put("s", c.tf.scale.toDouble()).put("r", c.tf.rot.toDouble()).put("cl", c.tf.cropL.toDouble()).put("ct", c.tf.cropT.toDouble())
                    .put("cr", c.tf.cropR.toDouble()).put("cb", c.tf.cropB.toDouble()).put("fh", c.tf.flipH).put("fv", c.tf.flipV))
                .put("sp", c.speed.toDouble())
                .putOpt("gr", if (c.grade.isIdentity) null else c.grade.toJson())
                .putOpt("snd", if (c.snd.isDefault) null else JSONObject().put("v", c.snd.volume.toDouble()).put("m", c.snd.muted)
                    .put("n", c.snd.normalize).put("fi", c.snd.fadeInUs).put("fo", c.snd.fadeOutUs).put("p", c.snd.pan.toDouble())
                    .put("b", c.snd.bass.toDouble()).put("mi", c.snd.mid.toDouble()).put("t", c.snd.treble.toDouble()).put("pi", c.snd.pitch.toDouble()))
        )
    }

    private fun readClips(ca: JSONArray): List<Clip> = (0 until ca.length()).map { ca.getJSONObject(it) }
        .map { o ->
            fun fx(key: String) = o.optJSONObject(key)?.let { Fx(it.getString("type"), it.getLong("us"), it.optBoolean("xa", true)) }
            val t = o.optJSONObject("tf")
            fun f(k: String, d: Double = 0.0) = (t?.optDouble(k, d) ?: d).toFloat()
            Clip(
                o.getLong("id"), o.getInt("assetId"), o.getLong("inUs"), o.getLong("outUs"), o.optLong("startUs", 0L),
                fx("transition"), fx("animIn"), fx("animOut"), Tf(f("x"), f("y"), f("s", 1.0), f("r"), f("cl"), f("ct"), f("cr"), f("cb"), t?.optBoolean("fh") == true, t?.optBoolean("fv") == true),
                o.optJSONObject("snd")?.let { j ->
                    Snd(
                        j.optDouble("v", 1.0).toFloat(), j.optBoolean("m"), j.optBoolean("n"), j.optLong("fi"), j.optLong("fo"),
                        j.optDouble("p").toFloat(), j.optDouble("b").toFloat(), j.optDouble("mi").toFloat(), j.optDouble("t").toFloat(), j.optDouble("pi").toFloat(),
                    )
                } ?: Snd(),
                o.optDouble("sp", 1.0).toFloat().coerceIn(0.1f, 10f),
                Grade.fromJson(o.optJSONObject("gr")),
            )
        }

    private val history = File(dir, "history.json")

    private fun tracksJson(tracks: List<Track>) = JSONArray().apply {
        for (t in tracks) put(JSONObject().put("id", t.id).put("video", t.video).put("name", t.name).put("clips", clipsJson(t.clips)))
    }

    /** The undo and redo steps, kept beside the project so they survive closing the app. */
    @Synchronized
    fun saveHistory(undo: List<List<Track>>, redo: List<List<Track>>) {
        dir.mkdirs()
        val root = JSONObject()
            .put("undo", JSONArray().apply { undo.forEach { put(tracksJson(it)) } })
            .put("redo", JSONArray().apply { redo.forEach { put(tracksJson(it)) } })
        val tmp = File(history.path + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(history)) {
            history.delete()
            tmp.renameTo(history)
        }
    }

    fun loadHistory(): Pair<List<List<Track>>, List<List<Track>>>? {
        if (!history.exists()) return null
        val root = JSONObject(history.readText())
        fun steps(key: String): List<List<Track>> {
            val a = root.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { i ->
                val ta = a.getJSONArray(i)
                (0 until ta.length()).map { ta.getJSONObject(it) }.map { o -> Track(o.getInt("id"), o.getBoolean("video"), readClips(o.getJSONArray("clips")), o.optString("name", "")) }
            }
        }
        return steps("undo") to steps("redo")
    }

    class Loaded(val assets: Map<Int, MediaAsset>, val tracks: List<Track>, val ui: UiState, val meta: ProjectMeta)

    /** The project, or the backup if the project cannot be read. */
    fun load(): Loaded? = read(file) ?: read(backup)

    private fun read(from: File): Loaded? {
        if (!from.exists()) return null
        return try {
            val root = JSONObject(from.readText())
            val assets = HashMap<Int, MediaAsset>()
            val aa = root.getJSONArray("assets")
            for (i in 0 until aa.length()) {
                val o = aa.getJSONObject(i)
                assets[o.getInt("id")] = MediaAsset(
                    o.getInt("id"), o.getString("uri"), o.getString("name"), o.getLong("durationUs"),
                    o.getInt("width"), o.getInt("height"), o.getDouble("fps").toFloat(),
                    o.getBoolean("hasAudio"), o.getBoolean("hdr"), o.getString("mime"), o.optInt("tier", 0),
                    o.optInt("revOf", -1), o.optLong("revIn", 0L), o.optLong("revOut", 0L),
                )
            }
            fun clips(ca: JSONArray) = readClips(ca).filter { it.assetId in assets }
            val ta = root.optJSONArray("tracks") ?: JSONArray()
            val tracks = (0 until ta.length()).map { ta.getJSONObject(it) }
                .map { o -> Track(o.getInt("id"), o.getBoolean("video"), clips(o.getJSONArray("clips")), o.optString("name", "")) }
            // Older projects kept V1 as a gapless list: lay those clips end to end as track 0.
            val legacy = root.optJSONArray("clips")?.let(::clips) ?: emptyList()
            var at = 0L
            val v1 = legacy.map { c -> c.copy(startUs = at).also { at += c.durationUs } }
            val all = if (v1.isEmpty()) tracks else listOf(Track(0, true, v1)) + tracks.filter { it.id != 0 }
            // Projects saved before lanes could be reordered listed video layers bottom-up, then audio lanes
            // top-down, without names. The stack is now stored bottom to top with audio wherever it was put.
            val ordered = if (all.all { it.name.isNotEmpty() }) all else {
                val video = all.filter { it.video }.mapIndexed { n, t -> t.copy(name = "V$n") }
                val audio = all.filter { !it.video }.mapIndexed { n, t -> t.copy(name = "A${n + 1}") }
                audio.asReversed() + video
            }
            val u = root.optJSONObject("ui")
            fun ids(key: String) = u?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
            val ui = if (u == null) UiState() else UiState(
                u.optLong("playheadUs"), u.optDouble("pxPerSec", 0.0).toFloat(), u.optDouble("laneScale", 1.0).toFloat(),
                u.optBoolean("snap", true), ids("muted"), ids("hidden"), ids("locked"),
            )
            val meta = ProjectMeta.fromJson(root.optJSONObject("meta"), ProjectMeta.defaultName(from.lastModified()), from.lastModified())
            Loaded(assets, ordered, ui, meta)
        } catch (e: Exception) {
            null
        }
    }
}

package com.graden.editor

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Settings fixed when a project is created, as in a desktop editor's project settings: the canvas
 * shape (which also names the platform it suits), the timeline resolution, frame rate and colour
 * space. The canvas shapes the preview now; resolution and colour space are what export will use.
 */
data class ProjectMeta(
    val name: String,
    val created: Long,
    val preset: String = "YouTube",
    val aspectW: Int = 16,
    val aspectH: Int = 9,
    val shortSide: Int = 1080,
    val fps: Float = 30f,
    /** Output colour space: what export writes and the viewer aims to show. */
    val colorSpace: String = "Rec.709 Gamma 2.4",
    /** Timeline colour space: the space grading and compositing are done in. */
    val workingSpace: String = "Rec.709 Gamma 2.4",
    /** Peak brightness the picture is mastered for, in nits; only used when the output is HDR. */
    val masterNits: Int = 1000,
    /** How light beyond the output's range is brought into it, and colours outside its gamut (see ColorSpaces). */
    val toneMap: String = "DaVinci",
    val gamutMap: String = "Saturation Compression",
    /** A size typed in by hand; when set it overrides the preset shape and [shortSide]. */
    val customW: Int = 0,
    val customH: Int = 0,
    /** How a clip whose shape differs from the canvas starts out: "Fit", "Fill" or "Centre" (no scaling). Each clip can then be placed by hand. */
    val scaling: String = "Fit",
    val bitDepth: Int = 8,
    val sampleRate: Int = 48_000,
    /** Integrated loudness to aim for on export, in LUFS; null leaves the level as it is. */
    val loudness: Float? = -14f,
    /** The timecode the timeline starts at, in seconds (3600 = 01:00:00:00). */
    val startTcSec: Int = 0,
) {
    val aspect: Float get() = aspectW.toFloat() / aspectH
    val width: Int get() = if (customW > 0) customW else if (aspectW >= aspectH) even(shortSide * aspectW / aspectH) else shortSide
    val height: Int get() = if (customH > 0) customH else if (aspectW >= aspectH) shortSide else even(shortSide * aspectH / aspectW)
    /** "16:9" for familiar shapes; a typed size that reduces to large numbers (2048×858 is 1024:429) reads as "2.39:1". */
    val ratioLabel: String
        get() = if (aspectW <= 32 && aspectH <= 32) "$aspectW:$aspectH"
        else if (aspectW >= aspectH) "%.2f:1".format(aspectW.toFloat() / aspectH) else "1:%.2f".format(aspectH.toFloat() / aspectW)
    val fpsLabel: String get() = if (fps % 1f == 0f) "${fps.toInt()}" else "%.3f".format(fps).trimEnd('0')

    fun toJson(): JSONObject = JSONObject().put("name", name).put("created", created).put("preset", preset)
        .put("aspectW", aspectW).put("aspectH", aspectH).put("shortSide", shortSide).put("fps", fps.toDouble()).put("colorSpace", colorSpace)
        .put("workingSpace", workingSpace).put("masterNits", masterNits).put("toneMap", toneMap).put("gamutMap", gamutMap)
        .put("customW", customW).put("customH", customH).put("scaling", scaling).put("bitDepth", bitDepth)
        .put("sampleRate", sampleRate).put("loudness", loudness?.toDouble() ?: JSONObject.NULL).put("startTcSec", startTcSec)

    companion object {
        private fun even(v: Int) = v / 2 * 2

        fun fromJson(o: JSONObject?, fallbackName: String, fallbackTime: Long): ProjectMeta =
            if (o == null) ProjectMeta(fallbackName, fallbackTime) else ProjectMeta(
                o.optString("name", fallbackName), o.optLong("created", fallbackTime), o.optString("preset", "YouTube"),
                o.optInt("aspectW", 16), o.optInt("aspectH", 9), o.optInt("shortSide", 1080),
                o.optDouble("fps", 30.0).toFloat(), legacySpace(o.optString("colorSpace", "Rec.709 Gamma 2.4")),
                legacySpace(o.optString("workingSpace", o.optString("colorSpace", "Rec.709 Gamma 2.4"))), o.optInt("masterNits", 1000),
                o.optString("toneMap", "DaVinci"), o.optString("gamutMap", "Saturation Compression"),
                o.optInt("customW", 0), o.optInt("customH", 0), o.optString("scaling", "Fit").let { if (it == "Stretch") "Fit" else it }, o.optInt("bitDepth", 8),
                o.optInt("sampleRate", 48_000), if (o.isNull("loudness")) null else o.optDouble("loudness", -14.0).toFloat(), o.optInt("startTcSec", 0),
            )

        /** Names used before the colour space list was extended. */
        private fun legacySpace(n: String) = when (n) {
            "Rec.709 · SDR" -> "Rec.709 Gamma 2.4"
            "Rec.2020 · HLG" -> "Rec.2100 HLG"
            "Rec.2020 · PQ (HDR10)" -> "Rec.2100 ST2084 (PQ)"
            "Display P3" -> "P3-D65"
            else -> n
        }

        /** HDR outputs: they need 10-bit and a mastering brightness. */
        fun isHdr(space: String) = "HLG" in space || "ST2084" in space

        /** A typed-in size, with its shape reduced to the simplest ratio (1920×1080 → 16:9). */
        fun custom(base: ProjectMeta, w: Int, h: Int): ProjectMeta {
            tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
            val g = gcd(w, h).coerceAtLeast(1)
            return base.copy(preset = "Custom", aspectW = w / g, aspectH = h / g, customW = w, customH = h)
        }

        /** The name a project gets when none is typed, e.g. "2:34PM_Oct_2026_IST". */
        fun defaultName(at: Long = System.currentTimeMillis()): String {
            val stamp = SimpleDateFormat("h:mma_MMM_yyyy", Locale.ENGLISH).format(Date(at))
            // Short zone names are often just "GMT+05:30"; the initials of the full name ("India Standard Time") read better.
            val zone = java.util.TimeZone.getDefault()
            val long = zone.getDisplayName(zone.inDaylightTime(Date(at)), java.util.TimeZone.LONG, Locale.ENGLISH)
            val initials = long.split(' ').filter { it.firstOrNull()?.isLetter() == true }.joinToString("") { it.first().uppercase() }
            val tz = if (initials.length in 2..5) initials else zone.getDisplayName(false, java.util.TimeZone.SHORT, Locale.ENGLISH)
            return "${stamp}_$tz".take(MAX_NAME)
        }

        const val MAX_NAME = 20
    }
}

/** What the dashboard shows for a project, read from its file without opening it. */
class ProjectSummary(
    val id: String,
    val meta: ProjectMeta,
    val modified: Long,
    val durationUs: Long,
    val clips: Int,
    /** The first picture on the base layer, for the card. */
    val cover: MediaAsset?,
)

/** All projects, one folder each under files/projects. Proxies and waveforms are shared between them. */
class Projects(private val context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun store(id: String) = ProjectStore(File(root, id))

    fun create(meta: ProjectMeta): String {
        val id = UUID.randomUUID().toString().take(8)
        store(id).save(emptyList(), listOf(Track(0, true, emptyList())), UiState(), meta)
        return id
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun list(): List<ProjectSummary> {
        migrate()
        return (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { dir ->
            val store = ProjectStore(dir)
            store.summary(dir.name)?.let { return@mapNotNull it }
            val loaded = store.load() ?: return@mapNotNull null
            store.writeSummary(loaded.meta, loaded.tracks, loaded.assets)
            val base = loaded.tracks.firstOrNull { it.id == 0 }
            ProjectSummary(
                dir.name, loaded.meta, File(dir, "project.json").lastModified(),
                loaded.tracks.maxOfOrNull { it.endUs } ?: 0L, loaded.tracks.sumOf { it.clips.size },
                base?.clips?.firstNotNullOfOrNull { loaded.assets[it.assetId]?.takeIf { a -> !a.isAudio } },
            )
        }.sortedByDescending { it.modified }
    }

    /**
     * Deletes proxies and waveforms of media that no project uses any more. Both are named after the file
     * they were made from ("<hash>_<duration>…"), so anything not starting with an in-use name can go.
     */
    fun cleanCaches() {
        val inUse = HashSet<String>()
        val uris = HashSet<String>()
        for (dir in root.listFiles() ?: emptyArray()) {
            val loaded = ProjectStore(dir).load() ?: continue
            loaded.assets.values.forEach {
                inUse += "${it.uri.hashCode().toUInt()}_${it.durationUs}"
                uris += it.uri
            }
        }
        // Reversed copies no project points at any more, and half-made ones left by a closed app.
        File(context.filesDir, "reversed").listFiles()?.forEach { f ->
            if (android.net.Uri.fromFile(f).toString() !in uris) f.deleteRecursively()
        }
        for (name in listOf("proxies", "waves")) {
            File(context.filesDir, name).listFiles()?.forEach { f ->
                if (inUse.none { f.name.startsWith(it) }) f.delete()
            }
        }
    }

    /** The app used to keep a single project at files/project.json; it becomes the first project in the list. */
    private fun migrate() {
        val old = File(context.filesDir, "project.json")
        if (!old.exists()) return
        val dir = File(root, UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        old.copyTo(File(dir, "project.json"), overwrite = true)
        File(context.filesDir, "project.json.bak").takeIf { it.exists() }?.copyTo(File(dir, "project.json.bak"), overwrite = true)
        old.delete()
        File(context.filesDir, "project.json.bak").delete()
    }
}


/** The size of a picture's base rectangle (fitted to, or covering, a [w] × [h] canvas), as (width, height). */
object CanvasGeoMath {
    fun base(w: Float, h: Float, pictureAspect: Float, fill: Boolean): Pair<Float, Float> {
        val wide = pictureAspect > w / h
        val bw = if (wide != fill) w else h * pictureAspect
        return bw to bw / pictureAspect
    }
}

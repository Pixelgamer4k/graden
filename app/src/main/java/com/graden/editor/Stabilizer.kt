package com.graden.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLES30
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

fun Stab.toJson(): JSONObject = JSONObject().put("on", on).put("m2", mode).put("l", lock).put("s", smooth.toDouble()).put("st", strength.toDouble())
    .put("h", horizon).put("f", framing).put("z", zoom.toDouble()).put("dz2", dynamicZoom).put("cl", cropLess.toDouble())
    .put("rs", rollingShutter.toDouble()).put("d", detailed).put("md", method).put("fo", focus).put("ar", analysisRes)

fun Stab.Companion.fromJson(o: JSONObject?): Stab? = o?.let {
    Stab(it.optBoolean("on", true), it.optInt("m2", WARP), it.optBoolean("l"), it.optDouble("s", 0.5).toFloat(), it.optDouble("st", 1.0).toFloat(),
        it.optBoolean("h"), it.optInt("f", AUTO_ZOOM), it.optDouble("z", 1.0).toFloat(), it.optBoolean("dz2", false), it.optDouble("cl", 0.5).toFloat(),
        it.optDouble("rs", 0.0).toFloat(), it.optBoolean("d"), it.optInt("md", CINEMATIC), it.optInt("fo", 1), it.optInt("ar", 960))
}

/**
 * Optical stabilisation on the phone: decodes the clip small and feeds [StabTracker] (see StabCore.kt), keeps the
 * measurements, and hands the shader each frame's correction ([StabGl]).
 */
object Stabilizer {
    private fun dir(context: Context) = File(context.filesDir, "stab").apply { mkdirs() }

    private fun prefix(a: MediaAsset) = "${a.uri.hashCode().toUInt()}_${a.durationUs}_v4_"

    /** A measurement's name: the clip span, quick or detailed, and the analysis resolution where it is not the default. */
    fun key(a: MediaAsset, inUs: Long, outUs: Long, detailed: Boolean, res: Int = VGyro.LONG) =
        prefix(a) + "${inUs}_${outUs}_${if (detailed) "d" else "q"}" + if (res != VGyro.LONG) "_r$res" else ""

    /**
     * A measurement of [a] that covers [inUs]..[outUs] (made for this clip, or for the clip it was split or trimmed
     * from), the detailed kind first if [detailed]. Null when there is none yet.
     */
    fun find(context: Context, a: MediaAsset, inUs: Long, outUs: Long, detailed: Boolean): Pair<String, StabAnalysis>? {
        val p = prefix(a)
        val names = dir(context).list()?.filter { it.startsWith(p) && it.endsWith(".bin") } ?: return null
        val fits = names.mapNotNull { n ->
            val parts = n.removePrefix(p).removeSuffix(".bin").split("_")
            val i = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val o = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val d = parts.getOrNull(2) == "d"
            if (i <= inUs + 40_000 && o >= outUs - 40_000 && (d || !detailed)) Triple(n, o - i, d) else null
        }.sortedWith(compareBy({ !it.third }, { it.second }))
        for ((n, _, _) in fits) StabAnalysis.load(File(dir(context), n))?.let { return n.removeSuffix(".bin") to it }
        return null
    }

    /**
     * Measures the motion of [path] between [inUs] and [outUs] (see [StabDecode]), reporting progress 0..1; [cancelled]
     * stops it. Saved under [key] for next time.
     */
    fun analyse(context: Context, path: String, rotation: Int, inUs: Long, outUs: Long, detailed: Boolean, key: String,
                progress: (Float) -> Unit, cancelled: () -> Boolean): StabAnalysis? {
        val a = StabDecode.analyse(context, path, rotation, inUs, outUs, detailed, progress, cancelled) ?: return null
        a.save(File(dir(context), "$key.bin"))
        return a
    }

    // The visual gyro's measurements (VGyro.kt), kept the same way in their own folder.
    private fun vgDir(context: Context) = File(context.filesDir, "vgyro").apply { mkdirs() }

    /** Whether the visual gyro can run here (its tracker is built for ARM). */
    val gyroAvailable: Boolean get() = VGyroNative.available

    /** [s] as the engine that will draw it: the visual gyro where it can run, else Warp. */
    fun engineOf(s: Stab): Int = if (s.mode == Stab.GYRO && !gyroAvailable) Stab.WARP else s.mode

    /** The visual gyro's measurement covering [inUs]..[outUs], as [find] does for the other methods. */
    fun findGyro(context: Context, a: MediaAsset, inUs: Long, outUs: Long, detailed: Boolean, res: Int? = null): VGyroAnalysis? {
        val p = prefix(a)
        val names = vgDir(context).list()?.filter { it.startsWith(p) && it.endsWith(".bin") } ?: return null
        class Fit(val n: String, val span: Long, val d: Boolean, val r: Int)
        val fits = names.mapNotNull { n ->
            val parts = n.removePrefix(p).removeSuffix(".bin").split("_")
            val i = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val o = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val d = parts.getOrNull(2) == "d"
            val r = parts.getOrNull(3)?.removePrefix("r")?.toIntOrNull() ?: VGyro.LONG
            if (i <= inUs + 40_000 && o >= outUs - 40_000 && (d || !detailed) && (res == null || r == res)) Fit(n, o - i, d, r) else null
        }.sortedWith(compareBy({ !it.d }, { it.span }))
        for (f in fits) VGyroAnalysis.load(File(vgDir(context), f.n))?.let { return it }
        return null
    }

    /** Measures [path] for the visual gyro (the original file: the sensor's own rows, at full detail) and keeps it. */
    fun analyseGyro(context: Context, path: String, rotation: Int, inUs: Long, outUs: Long, detailed: Boolean, key: String,
                    progress: (Float) -> Unit, cancelled: () -> Boolean, res: Int = VGyro.LONG): VGyroAnalysis? {
        val a = VGyro.analyse(context, path, rotation, inUs, outUs, detailed, progress, cancelled, res) ?: return null
        a.save(File(vgDir(context), "$key.bin"))
        return a
    }

    /** Whether [a] has the measurement [s]'s method needs for [inUs]..[outUs]. */
    fun measured(context: Context, a: MediaAsset, inUs: Long, outUs: Long, s: Stab, res: Int? = null): Boolean =
        if (engineOf(s) == Stab.GYRO) findGyro(context, a, inUs, outUs, false, res) != null else find(context, a, inUs, outUs, false) != null

    /**
     * [a]'s stabilisation with settings [s] over [inUs]..[outUs], from what has been measured (the detailed measurement
     * first when asked for); null when it has not been measured yet.
     */
    fun track(context: Context, a: MediaAsset, inUs: Long, outUs: Long, s: Stab): StabTrack? {
        val fps = a.fps.takeIf { it > 1f } ?: 30f
        return if (engineOf(s) == Stab.GYRO) {
            // The measurement at the chosen resolution first, else any there is.
            val m = findGyro(context, a, inUs, outUs, s.detailed, s.analysisRes) ?: findGyro(context, a, inUs, outUs, false, s.analysisRes)
                ?: findGyro(context, a, inUs, outUs, s.detailed) ?: findGyro(context, a, inUs, outUs, false) ?: return null
            VGyro.track(m, VGyro.Settings.of(s), fps, inUs, outUs)
        } else {
            val m = find(context, a, inUs, outUs, s.detailed) ?: find(context, a, inUs, outUs, false) ?: return null
            StabCore.track(m.second, s.copy(mode = engineOf(s)), fps, inUs, outUs)
        }
    }
}

/**
 * The stabilisation as the shader takes it: the frame's matrix and edge mode, and in Warp its displacement mesh as a
 * small half-float texture (unit 9). One per GL context.
 */
class StabGl {
    private var tex = 0
    private var shown = -2
    private var shownGrid: FloatArray? = null
    private val buf = ByteBuffer.allocateDirect(StabMesh.NV * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val cm = FloatArray(9)

    /** Sets [prog]'s stabilisation to [track]'s frame [index] (null or -1: none); [m] is that frame's matrix. */
    fun set(prog: Int, track: StabTrack?, index: Int, m: FloatArray?) {
        val on = track != null && index >= 0 && m != null
        if (on) for (r in 0 until 3) for (c in 0 until 3) cm[c * 3 + r] = m!![r * 3 + c] else StabTrack.identity(cm)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(prog, "uStab"), 1, false, cm, 0)
        val grid = if (on) track!!.grids else null
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uStabP"), if (on) 1f else 0f, (track?.framing ?: 0).toFloat(), if (grid != null) 1f else 0f, 0f)
        val loc = GLES20.glGetUniformLocation(prog, "uStabG")
        if (loc < 0) return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE9)
        if (tex == 0) {
            tex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            buf.position(0); for (i in 0 until StabMesh.NV * 4) buf.put(0f); buf.position(0)
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, StabMesh.VX, StabMesh.VY, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT, buf)
        } else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        if (grid != null && (index != shown || grid !== shownGrid)) {
            buf.position(0)
            val o = index * StabMesh.NV * 2
            for (v in 0 until StabMesh.NV) { buf.put(grid[o + v * 2]); buf.put(grid[o + v * 2 + 1]); buf.put(0f); buf.put(0f) }
            buf.position(0)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, StabMesh.VX, StabMesh.VY, GLES20.GL_RGBA, GLES20.GL_FLOAT, buf)
            shown = index; shownGrid = grid
        }
        GLES20.glUniform1i(loc, 9)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    fun release() {
        if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
        tex = 0
    }
}

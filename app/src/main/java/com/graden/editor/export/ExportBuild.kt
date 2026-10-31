package com.graden.editor.export

import com.graden.editor.Clip
import com.graden.editor.ColorPipe
import com.graden.editor.ColorSpaces
import com.graden.editor.LayerPack
import com.graden.editor.MediaAsset
import com.graden.editor.ProjectMeta
import com.graden.editor.RawLayer
import com.graden.editor.Stab
import com.graden.editor.StabTrack
import com.graden.editor.Track

/**
 * A frozen copy of a project for export, written into [output]: each clip's colour path from what it was shot in to
 * the delivery space, its grade's layers, and its stabilisation. Shared by the editor and GradenLab, so both export
 * exactly alike. [raw] gives a RAW asset's development block; [stab] a stabilised clip's correction (null: none yet).
 */
object ExportBuild {
    fun build(
        meta: ProjectMeta, tracks: List<Track>, assets: Map<Int, MediaAsset>, sources: Map<Int, String>, muted: Set<Int>, hidden: Set<Int>,
        norm: Map<Long, Float>, output: String, raw: (MediaAsset, RawLayer) -> FloatArray?, stab: (Clip, MediaAsset, Stab) -> StabTrack?,
        cinemaRun: (Clip, MediaAsset, com.graden.editor.Cinema) -> com.graden.editor.CinemaRun? = { _, _, _ -> null },
    ): ExportProject {
        val dest = ColorSpaces.output(output)
        val nits = meta.masterNits.toFloat()
        val pipes = HashMap<Long, FloatArray>()
        val layerPacks = HashMap<Long, FloatArray>()
        val deep = HashSet<Long>()
        for (t in tracks) for (c in t.clips) {
            val a = assets[c.assetId] ?: continue
            val input = if (a.isImage) ColorSpaces.REC709 else ColorSpaces.inputOf(c, a)
            pipes[c.id] = ColorPipe.block(c, input, meta, dest, nits, raw = raw(a, c.grade.raw), fullRange = a.fullRange)
            layerPacks[c.id] = LayerPack.pack(c.grade, ColorPipe.workGamma(c, input, meta), yUp = false)
            if (input.hdr || input.log || input.gamma.code != ColorSpaces.GAMMA24.code) deep += c.id
        }
        // Each stabilised clip's correction, frame by frame (the same the preview shows).
        val stabs = HashMap<Long, StabTrack>()
        val t0 = android.os.SystemClock.elapsedRealtime()
        var stabFrames = 0L
        for (t in tracks) for (c in t.clips) {
            val st = c.stab?.takeIf { it.on } ?: continue
            val a = assets[c.assetId]?.takeIf { !it.isImage } ?: continue
            stab(c, a, st)?.let { stabs[c.id] = it; stabFrames += ((c.outUs - c.inUs) * meta.fps / 1_000_000.0).toLong() }
        }
        val stabPrepMs = android.os.SystemClock.elapsedRealtime() - t0
        val cinema = HashMap<Long, com.graden.editor.CinemaRun>()
        for (t in tracks) for (c in t.clips) {
            val ci = c.cinema?.takeIf { it.on } ?: continue
            val a = assets[c.assetId]?.takeIf { !it.isImage } ?: continue
            cinemaRun(c, a, ci)?.let { cinema[c.id] = it }
        }
        return ExportProject(
            meta, tracks, assets, sources, muted, hidden, norm, cinema,
            pipes, ColorPipe.plain(meta, dest, nits), deep, dest.code, nits, layerPacks,
            preview = if (dest === ColorSpaces.REC709) null else ColorPipe.plain(meta, ColorSpaces.REC709, 100f).also {
                // Shows the delivery as Rec.709: the second step goes from the output space.
                ColorSpaces.xf(dest, ColorSpaces.REC709, meta.toneMap, meta.gamutMap, 100f, nits).pack(it, ColorSpaces.XF_SIZE)
            },
            stabs = stabs,
            stabPrepMs = stabPrepMs,
            stabFrames = stabFrames,
        )
    }
}

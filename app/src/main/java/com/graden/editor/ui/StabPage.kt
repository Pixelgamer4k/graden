package com.graden.editor.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.Cam3D
import com.graden.editor.EditorViewModel
import com.graden.editor.Stab
import com.graden.editor.StabTrack
import com.graden.editor.Stabilizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Stabilize: the clip's camera motion measured and smoothed, on its own page under the preview. */
@Composable
fun StabTopBar(vm: EditorViewModel) {
    BackHandler { vm.closeStab() }
    val l = vm.stabTarget
    val name = l?.let { vm.assets[it.clip.assetId]?.name } ?: "No clip"
    Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BarIcon(Lucide.X, "Close") { vm.closeStab() }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text("Stabilize", color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(name, color = Palette.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BarIcon(Lucide.RotateCcw, "Remove stabilization", enabled = l?.clip?.stab != null) { vm.removeStab() }
        BarIcon(Lucide.Undo, "Undo", enabled = vm.canUndo) { vm.undo() }
        BarIcon(Lucide.Redo, "Redo", enabled = vm.canRedo) { vm.redo() }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.clip(RoundedCornerShape(50)).background(Palette.Accent).clickable { vm.closeStab() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
            Text("Done", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = Pro.Text, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (enabled) tint else Pro.Faint, modifier = Modifier.size(18.dp))
    }
}

/** Ready-made settings for the usual kinds of shot. */
private class StabPreset(val name: String, val note: String, val apply: (Stab) -> Stab)

private val PRESETS = listOf(
    StabPreset("Handheld", "Keeps the move, takes out the shake") {
        it.copy(lock = false, mode = Stab.GYRO, smooth = 0.35f, strength = 1f, horizon = false, framing = Stab.AUTO_ZOOM, dynamicZoom = false, cropLess = 0.5f, focus = 1)
    },
    StabPreset("Walking", "Steadies footsteps and bounce") {
        it.copy(lock = false, mode = Stab.GYRO, smooth = 0.65f, strength = 1f, horizon = true, framing = Stab.AUTO_ZOOM, dynamicZoom = false, cropLess = 0.35f, focus = 1)
    },
    StabPreset("Gimbal", "Glides like a gimbal shot") {
        it.copy(lock = false, mode = Stab.GYRO, smooth = 0.9f, strength = 1f, horizon = true, framing = Stab.AUTO_ZOOM, dynamicZoom = false, cropLess = 0.2f, focus = 1)
    },
    StabPreset("Tripod", "Holds the camera still") {
        it.copy(lock = true, mode = Stab.GYRO, strength = 1f, horizon = true, framing = Stab.AUTO_ZOOM, dynamicZoom = false, cropLess = 0.1f, focus = 1)
    },
    StabPreset("Vehicle", "Takes out vibration and jello") {
        it.copy(lock = false, mode = Stab.GYRO, smooth = 0.2f, strength = 0.9f, horizon = false, framing = Stab.AUTO_ZOOM, dynamicZoom = false, cropLess = 0.6f, focus = 0)
    },
)

@Composable
fun StabBody(vm: EditorViewModel, modifier: Modifier) {
    val l = vm.stabTarget
    if (l == null) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("The clip is gone", color = Pro.Dim, fontSize = 13.sp) }
        return
    }
    val c = l.clip
    val s = c.stab ?: Stab()
    val progress = vm.stabProgress[c.id]
    // Read here, so it is kept made while the page is open, even with the compare switch on.
    val track = vm.stabTrackOf(c)
    val entry = vm.stabTracks[c.id]
    val analysed = entry?.analysed == true && c.stab != null
    // Settings exist but the track is still being made: the graph waits rather than offering to measure.
    val unknown = c.stab != null && entry == null
    // Playing on the page loops the clip.
    LaunchedEffect(vm.isPlaying, c.id) {
        if (!vm.isPlaying) return@LaunchedEffect
        while (vm.isPlaying) {
            androidx.compose.runtime.withFrameNanos { }
            if (vm.playheadUs >= c.endUs - 20_000 || vm.playheadUs < c.startUs) {
                vm.seek(c.startUs)
                vm.togglePlay()
            }
        }
    }
    Column(modifier.fillMaxWidth().background(Palette.Bg)) {
        StabTransport(vm, c.startUs, c.endUs)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            MotionCard(vm, c, s, track, progress, analysed, unknown)
            AnimatedVisibility(analysed && progress == null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Presets(vm, s)
                    AdjustCard(vm, s, c.stab != null)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Timecode, frame steps, play, and the compare switch: as shot, or stabilised. */
@Composable
private fun StabTransport(vm: EditorViewModel, startUs: Long, endUs: Long) {
    val view = LocalView.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val u = maxWidth / 400f
        Row(Modifier.fillMaxWidth().height(u * 52f).padding(horizontal = u * 10f), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(vm.tc((vm.playheadUs - startUs).coerceAtLeast(0L)), color = Palette.Accent, fontSize = 13.sp, fontFamily = Pro.Mono, maxLines = 1, softWrap = false)
                Text("of " + vm.tc(endUs - startUs), color = Pro.Dim, fontSize = 10.sp, fontFamily = Pro.Mono, maxLines = 1, softWrap = false)
            }
            Box(Modifier.size(u * 36f).clip(CircleShape).clickable { vm.stepFrames(-1) }, contentAlignment = Alignment.Center) {
                Icon(Lucide.ChevronsLeft, "Back one frame", tint = Pro.Text, modifier = Modifier.size(u * 18f))
            }
            Box(
                Modifier.size(u * 40f).clip(CircleShape).background(Palette.Accent).clickable {
                    if (vm.playheadUs >= endUs - 40_000) vm.seek(startUs)
                    vm.togglePlay()
                },
                contentAlignment = Alignment.Center,
            ) { Icon(if (vm.isPlaying) Lucide.Pause else Lucide.Play, "Play", tint = Color.Black, modifier = Modifier.size(u * 18f)) }
            Box(Modifier.size(u * 36f).clip(CircleShape).clickable { vm.stepFrames(1) }, contentAlignment = Alignment.Center) {
                Icon(Lucide.ChevronsRight, "Forward one frame", tint = Pro.Text, modifier = Modifier.size(u * 18f))
            }
            Spacer(Modifier.width(u * 8f))
            // Before / after, as a two-way switch.
            Row(
                Modifier.weight(1.25f).height(u * 34f).clip(RoundedCornerShape(50)).background(Pro.Well).border(1.dp, Pro.Line, RoundedCornerShape(50)).padding(u * 3f),
            ) {
                for ((i, label) in listOf("As shot", "Stable").withIndex()) {
                    val on = (i == 0) == vm.stabCompare
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).background(if (on) Color(0xFF2E2418) else Color.Transparent)
                            .clickable {
                                if (vm.stabCompare != (i == 0)) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                vm.stabCompare = i == 0
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (on) Palette.Accent else Pro.Dim, fontSize = 11.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * The camera's path through the clip, as shot (faint) and as it now moves (accent), for the side-to-side, up-and-down
 * and turning motion, with the zoom it needs underneath. Drag along it to scrub the clip. Before the motion is
 * measured, it is where the measuring starts and shows how far along it is.
 */
@Composable
private fun MotionCard(vm: EditorViewModel, c: com.graden.editor.Clip, s: Stab, track: StabTrack?, progress: Float?, analysed: Boolean, unknown: Boolean) {
    var axis by remember { mutableStateOf(0) }
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1C23), Color(0xFF131419))))
            .border(1.dp, Color(0xFF22232B), RoundedCornerShape(18.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Activity, null, tint = Palette.Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Camera motion", color = Pro.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (analysed && progress == null) {
                for ((i, a) in listOf("X", "Y", "Turn", "3D").withIndex()) {
                    val on = axis == i
                    Box(
                        Modifier.padding(start = 4.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFF2E2418) else Pro.CardHi)
                            .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.5f) else Pro.Line, RoundedCornerShape(14.dp)).clickable { axis = i }
                            .padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(a, color = if (on) Palette.Accent else Pro.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            progress != null -> Measuring(vm, progress, c)
            unknown -> MotionGraph(vm, c, null, axis)
            !analysed -> Unmeasured(vm, vm.stabMeasured == true)
            else -> {
                if (axis == 3) Camera3DView(vm, c, track) else MotionGraph(vm, c, track, axis)
                Spacer(Modifier.height(10.dp))
                Stats(track, s)
            }
        }
    }
}

/**
 * Before anything happens: press Analyse to measure the clip's motion (once per clip); when it is measured already,
 * Stabilize applies it. Nothing starts until a button is pressed.
 */
/**
 * The resolution the motion is measured at (long side): lower is quicker, higher follows finer detail and smaller
 * shake. Above the footage's own size the footage's own size is used.
 */
@Composable
private fun AnalysisResolution(vm: EditorViewModel, footageLong: Int) {
    val view = LocalView.current
    val r = vm.stabRes
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Analysis resolution", color = Pro.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${minOf(r, footageLong.takeIf { it > 0 } ?: r)} px", color = Palette.Accent, fontSize = 12.sp, fontFamily = Pro.Mono)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(10.dp)).background(Pro.Well).border(1.dp, Pro.Line, RoundedCornerShape(10.dp)).padding(3.dp)) {
            for (v in com.graden.editor.VGyro.RESOLUTIONS) {
                val on = v == r
                val over = footageLong in 1 until v
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF2E2418) else Color.Transparent)
                        .clickable {
                            if (!on) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            vm.chooseStabRes(v)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (v) { 2880 -> "2.9K"; 3840 -> "4K"; else -> "$v" },
                        color = if (on) Palette.Accent else if (over) Pro.Faint else Pro.Dim, fontSize = 10.5.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false,
                    )
                }
            }
        }
        // Time grows with the pixels tracked: about the square of the resolution, against the default 960.
        val eff = minOf(r, footageLong.takeIf { it > 0 } ?: r)
        val k = (eff / 960f) * (eff / 960f)
        Text(
            when {
                eff < 960 -> "Quicker (about %.1f× as fast), for steady or simple shots".format(1f / k)
                eff == 960 -> "Balanced: the default, right for most footage"
                else -> "Finer detail and smaller shake followed; about %.1f× slower to analyse".format(k)
            } + if (footageLong in 1 until r) " · the footage is ${footageLong} px, so that is used" else "",
            color = Pro.Dim, fontSize = 10.5.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Unmeasured(vm: EditorViewModel, measured: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val a = vm.stabTarget?.let { vm.assets[it.clip.assetId] }
        AnalysisResolution(vm, a?.let { maxOf(it.width, it.height) } ?: 0)
        Spacer(Modifier.height(14.dp))
        Text(
            if (measured) "The clip's motion is measured. Press Stabilize to apply it."
            else "Press Analyse to measure the clip's motion first. Stabilizing starts once it is measured.",
            color = Pro.Dim, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Accent).clickable { vm.analyseOrApply() },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (measured) Lucide.Stabilize else Lucide.Activity, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (measured) "Stabilize" else "Analyse", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Measuring: a live trace sweeping along with the progress, and how far it is. */
@Composable
private fun Measuring(vm: EditorViewModel, progress: Float, c: com.graden.editor.Clip) {
    val shown by animateFloatAsState(progress, tween(260), label = "measure")
    // Analysis speed: frames measured per second since it started, and the time left.
    val startedAt = remember(c.id) { android.os.SystemClock.elapsedRealtime() }
    val fps = vm.assets[c.assetId]?.fps?.takeIf { it > 1f } ?: vm.meta.fps
    val frames = (c.outUs - c.inUs) / 1e6f * fps
    val secs = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000f
    val rate = if (secs > 0.5f && progress > 0.01f) progress * frames / secs else 0f
    val left = if (rate > 0f) ((1f - progress) * frames / rate).toInt() else -1
    val wave = rememberInfiniteTransition(label = "wave")
    val phase by wave.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(Pro.Well)) {
            val w = size.width; val h = size.height
            for (i in 1..3) drawLine(Pro.Line, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1f)
            val x1 = w * shown
            // A made-up shake under the sweep, settling into the line it will become.
            val p = Path()
            var first = true
            var x = 0f
            while (x <= x1) {
                val t = x / w
                val y = h / 2f + (kotlin.math.sin((t * 34f + phase * 6.283f).toDouble()) * 0.18 + kotlin.math.sin((t * 13f).toDouble()) * 0.12).toFloat() * h
                if (first) p.moveTo(x, y) else p.lineTo(x, y)
                first = false
                x += 3f
            }
            drawPath(p, Pro.Faint, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(Palette.Accent.copy(alpha = 0.8f), Offset(0f, h / 2f), Offset(x1, h / 2f), 2.dp.toPx(), StrokeCap.Round)
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Palette.Accent.copy(alpha = 0.18f)), startX = x1 - 60.dp.toPx(), endX = x1), Offset(x1 - 60.dp.toPx(), 0f), Size(60.dp.toPx(), h))
            drawLine(Palette.Accent, Offset(x1, 0f), Offset(x1, h), 2.dp.toPx())
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Analysing at ${vm.stabRes} px  ${(progress * 100).toInt()}%", color = Pro.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (rate > 0f) "%.0f fps · %s".format(rate, if (left >= 0) "${left} s left" else "") else "Every frame is tracked; this happens once per clip",
                    color = Pro.Dim, fontSize = 11.sp,
                )
            }
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Pro.CardHi).border(1.dp, Pro.Line, RoundedCornerShape(18.dp))
                    .clickable { vm.cancelStabAnalysis() }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel", color = Pro.Text, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun MotionGraph(vm: EditorViewModel, c: com.graden.editor.Clip, track: StabTrack?, axis: Int) {
    val view = LocalView.current
    val clip by rememberUpdatedState(c)
    val vmNow by rememberUpdatedState(vm)
    var w by remember { mutableStateOf(1f) }
    Canvas(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(Pro.Well)
            .pointerInput(Unit) {
                w = size.width.toFloat()
                fun seekTo(x: Float) {
                    val cl = clip
                    val target = cl.startUs + ((x / size.width).coerceIn(0f, 1f) * (cl.durationUs - 1)).toLong()
                    vmNow.scrubBy(target - vmNow.playheadUs)
                }
                detectHorizontalDragGestures(
                    onDragStart = { o -> vmNow.scrubStart(); view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); seekTo(o.x) },
                    onDragEnd = { vmNow.scrubEnd() },
                    onDragCancel = { vmNow.scrubEnd() },
                ) { ch, _ -> seekTo(ch.position.x) }
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val cl = clip
                    vmNow.scrubStart()
                    vmNow.scrubBy(cl.startUs + ((o.x / size.width).coerceIn(0f, 1f) * (cl.durationUs - 1)).toLong() - vmNow.playheadUs)
                    vmNow.scrubEnd()
                }
            },
    ) {
        val W = size.width; val H = size.height
        val graphH = H * 0.74f
        for (i in 1..3) drawLine(Color(0xFF1C1D24), Offset(0f, graphH * i / 4f), Offset(W, graphH * i / 4f), 1f)
        drawLine(Pro.Line, Offset(0f, graphH), Offset(W, graphH), 1f)
        if (track == null || track.times.size < 2) return@Canvas
        val n = track.times.size
        // The values for this axis, as shot and as smoothed; scaled to fit with some room.
        fun v(path: FloatArray, i: Int) = path[i * 3 + axis] * (if (axis == 2) 57.3f else 100f)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in 0 until n) { val a = v(track.rawPath, i); val b = v(track.smoothPath, i); lo = min(lo, min(a, b)); hi = max(hi, max(a, b)) }
        val span = max(hi - lo, if (axis == 2) 0.5f else 0.5f)
        val mid = (hi + lo) / 2f
        fun y(x: Float) = graphH / 2f - (x - mid) / span * graphH * 0.82f
        fun x(i: Int) = i.toFloat() / (n - 1) * W
        val step = max(1, n / 600)
        val raw = Path(); val sm = Path()
        var i = 0
        while (i < n) {
            if (i == 0) { raw.moveTo(x(i), y(v(track.rawPath, i))); sm.moveTo(x(i), y(v(track.smoothPath, i))) }
            else { raw.lineTo(x(i), y(v(track.rawPath, i))); sm.lineTo(x(i), y(v(track.smoothPath, i))) }
            i += step
        }
        drawPath(raw, Color(0xFF6E7180), style = Stroke(1.2.dp.toPx(), join = StrokeJoin.Round))
        drawPath(sm, Palette.Accent, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // The zoom it needs, as a band under the graph.
        val zTop = graphH + 4.dp.toPx(); val zH = H - zTop - 4.dp.toPx()
        val zMax = max(1.02f, track.zooms.maxOrNull() ?: 1f)
        val zp = Path()
        zp.moveTo(0f, zTop + zH)
        i = 0
        while (i < n) { zp.lineTo(x(i), zTop + zH - (track.zooms[i] - 1f) / (zMax - 1f) * zH); i += step }
        zp.lineTo(W, zTop + zH); zp.close()
        drawPath(zp, Palette.Accent.copy(alpha = 0.22f))
        // Playhead.
        val f = ((vm.playheadUs - c.startUs).toFloat() / max(1L, c.durationUs)).coerceIn(0f, 1f) * W
        drawLine(Color.White.copy(alpha = 0.85f), Offset(f, 0f), Offset(f, H), 1.5.dp.toPx())
        drawCircle(Color.White, 3.5.dp.toPx(), Offset(f, 4.dp.toPx()))
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Legend(Color(0xFF6E7180), "As shot")
        Spacer(Modifier.width(14.dp))
        Legend(Palette.Accent, "Stabilized")
        Spacer(Modifier.width(14.dp))
        Legend(Palette.Accent.copy(alpha = 0.35f), "Zoom")
        Spacer(Modifier.weight(1f))
        Text("Drag to scrub", color = Pro.Faint, fontSize = 10.sp)
    }
}

/**
 * The camera in 3D, moving exactly as the visual gyro measured it at the playhead, at its real size of motion: the
 * same shake, the same turns. "Stabilized" shows the same camera on the stabilised path instead - steady where the
 * shot was shaky. Seen from behind, as the person holding it would; drag to look around, double-tap to reset.
 */
@Composable
private fun Camera3DView(vm: EditorViewModel, c: com.graden.editor.Clip, track: StabTrack?) {
    var yaw by remember { mutableStateOf(Cam3D.View().yaw) }
    var pitch by remember { mutableStateOf(Cam3D.View().pitch) }
    var calmOn by remember { mutableStateOf(false) }
    // Glides between the two paths when switched, rather than jumping.
    val mix by animateFloatAsState(if (calmOn) 1f else 0f, tween(450), label = "path")
    // Rotations per frame: the gyro's own; for the older tracker, estimated from its path (a 70° lens assumed).
    val rots = remember(track) {
        if (track == null || track.times.size < 2) null
        else if (track.rawRot != null && track.smoothRot != null) Triple(track.rawRot, track.smoothRot, true)
        else {
            fun est(p: FloatArray) = FloatArray(p.size) { i -> when (i % 3) { 0 -> -p[i + 1] * 0.79f; 1 -> -p[i - 1] * 1.40f; else -> -p[i] } }
            Triple(est(track.rawPath), est(track.smoothPath), false)
        }
    }
    val n = track?.times?.size ?: 0
    val frac = ((vm.playheadUs - c.startUs).toDouble() / max(1L, c.durationUs)).coerceIn(0.0, 1.0)
    val t = frac * (n - 1).coerceAtLeast(0)
    val rv = rots?.let { r -> val a = Cam3D.at(r.first, t); val b = Cam3D.at(r.second, t); DoubleArray(3) { a[it] + (b[it] - a[it]) * mix } } ?: DoubleArray(3)
    val edge = androidx.compose.ui.graphics.lerp(Color(0xFFE9ECF4), Palette.Accent, mix)
    Canvas(
        Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF12131A), Pro.Well)))
            .pointerInput(Unit) {
                detectDragGestures { ch, d ->
                    ch.consume()
                    yaw += d.x * 0.008
                    pitch = (pitch + d.y * 0.006).coerceIn(-0.15, 1.35)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { yaw = Cam3D.View().yaw; pitch = Cam3D.View().pitch }) },
    ) {
        val shapes = Cam3D.render(
            size.width, size.height, Cam3D.View(yaw, pitch), rv, null, emptyList(), emptyList(), 1.0,
            (track?.aspect?.takeIf { it > 0f } ?: (16f / 9f)).toDouble(),
            (track?.hfov?.takeIf { it > 0f } ?: Math.toRadians(70.0).toFloat()).toDouble(), 1.dp.toPx(),
            edge = (edge.alpha * 255).toInt() shl 24 or ((edge.red * 255).toInt() shl 16) or ((edge.green * 255).toInt() shl 8) or (edge.blue * 255).toInt(),
        )
        val path = Path()
        for (s in shapes) {
            val col = Color(s.color)
            if (s.n == 2) { drawLine(col, Offset(s.xy[0], s.xy[1]), Offset(s.xy[2], s.xy[3]), s.stroke, cap = StrokeCap.Round); continue }
            path.reset(); path.moveTo(s.xy[0], s.xy[1])
            for (i in 1 until s.n) path.lineTo(s.xy[i * 2], s.xy[i * 2 + 1])
            if (s.closed) path.close()
            if (s.stroke > 0f) drawPath(path, col, style = Stroke(s.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)) else drawPath(path, col)
        }
    }
    Spacer(Modifier.height(8.dp))
    val ang = Cam3D.angles(Cam3D.matrix(rv[0], rv[1], rv[2]))
    Row(verticalAlignment = Alignment.CenterVertically) {
        for ((label, i) in listOf("Tilt" to 0, "Pan" to 1, "Roll" to 2)) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Pro.Dim, fontSize = 10.sp)
                Text(if (rots != null) Cam3D.fmt(ang[i]) else "–", color = Pro.Text, fontSize = 13.sp, fontFamily = Pro.Mono, maxLines = 1)
            }
        }
        for ((label, on) in listOf("As shot" to !calmOn, "Stabilized" to calmOn)) {
            Box(
                Modifier.padding(start = 4.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFF2E2418) else Pro.CardHi)
                    .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.5f) else Pro.Line, RoundedCornerShape(14.dp)).clickable { calmOn = label == "Stabilized" }
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) Palette.Accent else Pro.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        (if (rots?.third == false) "Estimated · " else "") + "Real size of motion · drag to look around, double-tap to reset",
        color = Pro.Faint, fontSize = 10.sp,
    )
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(12.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Pro.Dim, fontSize = 10.sp)
    }
}

/** What the stabilisation does to the clip: how much shake was taken out, the zoom, how sure the tracking was. */
@Composable
private fun Stats(track: StabTrack?, s: Stab) {
    if (track == null || track.times.size < 3) return
    val n = track.times.size
    // Shake: the frame-to-frame jitter left, as shot and now.
    fun jitter(p: FloatArray): Float {
        var sum = 0f
        for (i in 1 until n - 1) for (k in 0..1) sum += abs(p[(i + 1) * 3 + k] - 2 * p[i * 3 + k] + p[(i - 1) * 3 + k])
        return sum / (n - 2)
    }
    val before = jitter(track.rawPath)
    val after = jitter(track.smoothPath)
    val removed = if (before > 1e-6f) ((1f - after / before) * 100f).coerceIn(0f, 100f) else 0f
    val zLo = (track.zooms.minOrNull() ?: 1f) * 100f
    val zHi = (track.zooms.maxOrNull() ?: 1f) * 100f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("Shake removed", "${removed.toInt()}%", Modifier.weight(1f))
        Stat("Zoom", if (s.framing == Stab.AUTO_ZOOM || s.framing == Stab.FIXED_ZOOM) (if (zHi - zLo < 0.5f) "%.0f%%".format(zHi) else "%.0f–%.0f%%".format(zLo, zHi)) else "None", Modifier.weight(1f))
        Stat("Frames", "$n", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Pro.Well).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(value, color = Pro.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Pro.Mono, maxLines = 1, softWrap = false)
        Text(label, color = Pro.Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Presets(vm: EditorViewModel, s: Stab) {
    val view = LocalView.current
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PRESETS.size) { i ->
            val p = PRESETS[i]
            val on = p.apply(s) == s
            val scale by animateFloatAsState(if (on) 1f else 0.97f, spring(0.55f, 500f), label = "preset")
            Column(
                Modifier.width(132.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(14.dp))
                    .background(if (on) Color(0xFF2A2117) else Pro.Card)
                    .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.6f) else Pro.Line, RoundedCornerShape(14.dp))
                    .clickable { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); vm.setStabOnce(p.apply) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(p.name, color = if (on) Palette.Accent else Pro.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(p.note, color = Pro.Dim, fontSize = 10.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 28.dp))
            }
        }
    }
}

/** A slider over a 0..1 setting shown as a percentage (or any range with [scale]). */
@Composable
private fun StabSlider(vm: EditorViewModel, label: String, value: Float, neutral: Float, set: (Stab, Float) -> Stab, min: Float = 0f, max: Float = 1f, scale: Float = 100f, enabled: Boolean = true) {
    var typing by remember { mutableStateOf(false) }
    ProSliderRaw(
        label, value * scale, min * scale, max * scale, neutral * scale, "%.0f%%",
        onStart = { vm.gradeStart() },
        onChange = { v -> vm.setStab { set(it, v / scale) } },
        onEnd = { vm.gradeEnd() },
        onReset = { vm.setStabOnce { set(it, neutral) } },
        onType = { typing = true },
        enabled = enabled,
    )
    if (typing) NumberEntry(label, value * scale, min * scale, max * scale, onDismiss = { typing = false }) { v ->
        typing = false
        vm.setStabOnce { set(it, (v / scale).coerceIn(min, max)) }
    }
}

/**
 * The few things worth changing by hand: how smooth, how much may be cropped for it, and a level horizon; then
 * measuring again or taking the stabilisation off. Everything else is the visual gyro's own (it finds the lens and the
 * rolling shutter from the footage), or a preset.
 */
@Composable
private fun AdjustCard(vm: EditorViewModel, s: Stab, applied: Boolean) {
    val d = Stab()
    val changed = s.smooth != d.smooth || s.cropLess != d.cropLess || s.horizon != d.horizon
    SectionCard("Adjust", note = if (s.lock) "Tripod: held as still as it can be" else "Smoothness and crop", changed = changed,
        onReset = { vm.setStabOnce { it.copy(smooth = d.smooth, cropLess = d.cropLess, horizon = d.horizon, lock = false) } }) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            if (!s.lock) StabSlider(vm, "Smoothness", s.smooth, d.smooth, { st, v -> st.copy(smooth = v, mode = Stab.GYRO) })
            // Premiere's trade-off: hold the picture closer to the frame (less crop), or let it move more (smoother).
            ProSliderRaw(
                "Crop less  ·  Smooth more", (1f - s.cropLess) * 100f, 0f, 100f, (1f - d.cropLess) * 100f, "%.0f%%",
                onStart = { vm.gradeStart() },
                onChange = { v -> vm.setStab { it.copy(cropLess = 1f - v / 100f) } },
                onEnd = { vm.gradeEnd() },
                onReset = { vm.setStabOnce { it.copy(cropLess = d.cropLess) } },
                onType = null,
                track = Brush.horizontalGradient(listOf(Color(0xFF3A3D48), Palette.Accent)),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Horizon, null, tint = if (s.horizon) Palette.Accent else Pro.Dim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Lock horizon", color = Pro.Text, fontSize = 13.sp)
                    Text("Keeps the picture level all through", color = Pro.Dim, fontSize = 10.5.sp)
                }
                ProToggle(if (s.horizon) "On" else "Off", s.horizon) { vm.setStabOnce { it.copy(horizon = !it.horizon) } }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WideButton("Measure again", Pro.Text, Modifier.weight(1f)) { vm.analyseStab() }
                WideButton("Remove", Palette.Danger, Modifier.weight(1f), enabled = applied) { vm.removeStab() }
            }
        }
    }
}

@Composable
private fun WideButton(label: String, ink: Color, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).background(Pro.CardHi).border(1.dp, Pro.Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

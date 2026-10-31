package com.graden.editor.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures

import com.graden.editor.VEC_SCALE

import kotlin.math.pow

import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.CieMap
import com.graden.editor.CieSpace
import com.graden.editor.EditorViewModel
import com.graden.editor.Grade
import com.graden.editor.ParadeMode
import com.graden.editor.ScopeScale
import com.graden.editor.ScopeSettings
import com.graden.editor.ScopeType
import com.graden.editor.WaveMode
import com.graden.editor.Wheel
import com.graden.editor.WheelKind
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.calculateZoom
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The Color page. It replaces the edit tools under the preview: scopes first, then a scrub-only timeline, then
 * the wheels or the primary controls. Everything is sized from the room there is, so nothing overlaps whatever
 * the phone, and every control is big enough for a thumb. Changes show on the picture and the scopes at once.
 */

private val Ink = Color(0xFFC9CBD3)
private val Panel = Color(0xFF121318)
private val Well = Color(0xFF08090C)
private val RCh = Color(0xFFFF5A52)
private val GCh = Color(0xFF52E07A)
private val BCh = Color(0xFF5C8DFF)
private val Mono = FontFamily.Monospace

/**
 * Haptic steps for a value being dragged: a light tick each time it crosses a step, a firm click when it reaches its
 * neutral value. Ticks are spaced at least 22 ms apart so a fast drag buzzes softly instead of rattling.
 */
private class Ticker(private val view: android.view.View, private val step: Float, private val neutral: Float) {
    private var last = Int.MIN_VALUE
    private var lastAt = 0L
    private var wasNeutral = true

    fun start(v: Float) {
        last = kotlin.math.floor((v - neutral) / step).toInt()
        wasNeutral = abs(v - neutral) < step * 0.01f
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun at(v: Float) {
        val atNeutral = abs(v - neutral) < step * 0.01f
        if (atNeutral && !wasNeutral) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            lastAt = android.os.SystemClock.uptimeMillis()
        }
        wasNeutral = atNeutral
        val n = kotlin.math.floor((v - neutral) / step).toInt()
        if (n != last) {
            last = n
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastAt > 22 && !atNeutral) {
                lastAt = now
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }
}

/** How far a finger must travel before a control starts changing: a stray touch changes nothing. */
private const val SLOP_DP = 10f

// ---- top bar ---------------------------------------------------------------------------------------

@Composable
fun ColorTopBar(vm: EditorViewModel) {
    BackHandler { vm.closeColor() }
    val target = vm.gradeTarget
    val name = target?.let { vm.assets[it.clip.assetId]?.name } ?: "No clip under the playhead"
    Row(
        Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(Lucide.X, "Close") { vm.closeColor() }
        var picking by remember { mutableStateOf(false) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text("Color", color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            // What the clip was shot in: tap to set a camera log by hand.
            val clip = target?.clip
            val asset = clip?.let { vm.assets[it.assetId] }
            vm.colourProbes
            val space = if (asset?.isImage == true) null else com.graden.editor.ColorSpaces.inputOf(clip, asset)
            Text(
                if (space == null) name else name + "  ·  " + (if (clip?.cs == null) "Auto: " else "") + shortSpace(space.name),
                color = Palette.TextDim, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = space != null) { picking = true },
            )
        }
        if (picking && target != null) InputSpaceDialog(vm, target.clip) { picking = false }
        RoundIcon(Lucide.Undo, "Undo", enabled = vm.canUndo) { vm.undo() }
        RoundIcon(Lucide.Redo, "Redo", enabled = vm.canRedo) { vm.redo() }
        // Bypass: the picture ungraded, held as long as it is on.
        RoundIcon(if (vm.colorBypass) Lucide.EyeOff else Lucide.Eye, "Bypass", tint = if (vm.colorBypass) Palette.Accent else Ink) {
            vm.colorBypass = !vm.colorBypass
        }
        // Before/after wipe across the picture.
        RoundIcon(Lucide.SplitView, "Compare before and after", tint = if (vm.wipe >= 0f) Palette.Accent else Ink) {
            vm.wipe = if (vm.wipe >= 0f) -1f else 0.5f
        }
        RoundIcon(Lucide.RotateCcw, "Reset grade", enabled = target?.clip?.grade?.isIdentity == false) { vm.gradeOnce { Grade() } }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Palette.Accent).clickable { vm.closeColor() }.padding(horizontal = 14.dp, vertical = 7.dp),
        ) { Text("Done", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = Ink, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(19.dp)) }
}

private fun shortSpace(n: String) = n.replace(" Gamma ", " γ").replace("Rec.2100 ", "").replace(" (PQ)", "")

/** Choose what the clip was shot in: read from the file (Auto), or a camera log and the like. */
@Composable
private fun InputSpaceDialog(vm: EditorViewModel, clip: com.graden.editor.Clip, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF16171D)).border(1.dp, Color(0xFF23252D), RoundedCornerShape(20.dp))
                .padding(vertical = 14.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("Shot in", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp))
            Text("The colour space of this clip's file. Auto reads it from the file.", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 8.dp))
            val options = listOf<com.graden.editor.ColorSpaces.Space?>(null) + com.graden.editor.ColorSpaces.inputs
            for (o in options) {
                val on = (o == null && clip.cs == null) || (o != null && o.name == clip.cs)
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setClipSpace(clip.id, o?.name); onDismiss() }.padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o?.name ?: "Auto", color = if (on) Palette.Accent else Ink, fontSize = 14.sp)
                        Text(o?.note ?: "From the file: HDR files by their curve, others as Rec.709", color = Palette.TextDim, fontSize = 11.sp)
                    }
                    if (on) Icon(Lucide.Check, null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---- body ------------------------------------------------------------------------------------------

/**
 * The page under the picture: the scope (or timeline) above, the controls below, split by a handle. The handle follows
 * the finger; let go and it springs, with a little overshoot, to the nearest of three rests (scope folded away,
 * balanced, scope large), thrown further by a fling. A tick as it passes each rest, a click where it lands.
 */
@Composable
fun ColorBody(vm: EditorViewModel, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().background(Palette.Bg)) {
        val total = maxHeight
        val density = androidx.compose.ui.platform.LocalDensity.current
        val view = LocalView.current
        val scope = rememberCoroutineScope()
        val minControls = 200.dp
        val maxControls = (total - 48.dp - 44.dp).coerceAtLeast(minControls)
        // The rests, as the share of the page the controls take.
        val lo = (minControls / total).coerceIn(0.2f, 0.6f)
        val hi = (maxControls / total).coerceIn(lo, 1f)
        val rests = listOf(0.42f.coerceIn(lo, hi), 0.62f.coerceIn(lo, hi), hi)
        val frac = remember { androidx.compose.animation.core.Animatable(vm.controlsFraction.coerceIn(lo, hi)) }
        var dragging by remember { mutableStateOf(false) }
        // The scope's size button takes it to the scope-large rest.
        LaunchedEffect(vm.scopeBig) {
            if (vm.scopeBig) frac.animateTo(rests[0], spring(0.62f, 380f))
        }
        val controlsH = total * frac.value.coerceIn(lo, hi)
        val scopeH = (total - controlsH - 48.dp - 44.dp).coerceAtLeast(0.dp)
        val grip by animateFloatAsState(if (dragging) 1f else 0f, spring(0.6f, 700f), label = "grip")
        Column(Modifier.fillMaxSize()) {
            ScopePanel(vm, scopeH)
            ColorTransport(vm)
            Box(
                Modifier.fillMaxWidth().height(18.dp).background(Panel).pointerInput(total) {
                    val totalPx = with(density) { total.toPx() }
                    val velocity = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    var last = frac.value
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            velocity.resetTracking()
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        },
                        onDragEnd = {
                            dragging = false
                            val v = -velocity.calculateVelocity().y / totalPx
                            // Where a fling would carry it, then the rest nearest that.
                            val aim = (frac.value + v * 0.18f).coerceIn(lo, hi)
                            val target = rests.minByOrNull { abs(it - aim) } ?: frac.value
                            vm.scopeBig = false
                            vm.controlsFraction = target
                            scope.launch {
                                frac.animateTo(target, spring(0.58f, 360f), initialVelocity = v)
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                        },
                        onDragCancel = { dragging = false },
                    ) { change, dy ->
                        change.consume()
                        velocity.addPosition(change.uptimeMillis, change.position)
                        // A little resistance beyond the ends.
                        var next = frac.value - dy / totalPx
                        if (next < lo) next = lo - (lo - next) * 0.35f
                        if (next > hi) next = hi + (next - hi) * 0.35f
                        for (r in rests) if ((last - r) * (next - r) < 0f) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        last = next
                        scope.launch { frac.snapTo(next) }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.width((40 + 22 * grip).dp).height((4 + 1.5f * grip).dp).clip(RoundedCornerShape(3.dp))
                        .background(androidx.compose.ui.graphics.lerp(Color(0xFF3A3C46), Palette.Accent, grip)),
                )
            }
            Controls(vm, Modifier.height(controlsH - 18.dp))
        }
    }
}

// ---- scopes ----------------------------------------------------------------------------------------

@Composable
private fun ScopePanel(vm: EditorViewModel, height: androidx.compose.ui.unit.Dp) {
    val s = vm.scopeSettings
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
            // The panel shows the timeline or one scope; which scope is picked from a list, so the row never overflows.
            Pill("Timeline", vm.colorShowTimeline) { vm.colorShowTimeline = true }
            Spacer(Modifier.width(6.dp))
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(if (!vm.colorShowTimeline) Palette.Accent.copy(alpha = 0.18f) else Color(0xFF1A1B21))
                        .border(1.dp, if (!vm.colorShowTimeline) Palette.Accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(50))
                        .clickable { if (vm.colorShowTimeline) vm.colorShowTimeline = false else menu = true }.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.type.label, color = if (!vm.colorShowTimeline) Palette.Accent else Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(2.dp))
                    Icon(Lucide.ChevronDown, "Choose scope", tint = if (!vm.colorShowTimeline) Palette.Accent else Ink, modifier = Modifier.size(15.dp).clickable { menu = true })
                }
                androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Color(0xFF1B1C23)) {
                    for (t in ScopeType.entries) androidx.compose.material3.DropdownMenuItem(
                        text = { Text(t.label + if (t == ScopeType.HDR) "  ·  nits, MaxCLL" else "", color = if (t == s.type) Palette.Accent else Ink, fontSize = 14.sp) },
                        onClick = { menu = false; vm.colorShowTimeline = false; vm.setScopes(s.copy(type = t)) },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (!vm.colorShowTimeline) RoundIcon(Lucide.Settings, "Scope settings", tint = if (vm.scopeOptionsOpen) Palette.Accent else Ink) { vm.scopeOptionsOpen = !vm.scopeOptionsOpen }
            RoundIcon(if (vm.scopeBig) Lucide.Minimize else Lucide.Maximize, "Scope size") { vm.scopeBig = !vm.scopeBig }
        }
        Spacer(Modifier.height(4.dp))
        if (height < 40.dp) Unit
        else if (vm.colorShowTimeline) ColorTimeline(vm, height)
        else Box(
            Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp)).background(Well)
                .border(1.dp, Color(0xFF1C1E25), RoundedCornerShape(10.dp)),
        ) {
            ScopeView(vm, s, Modifier.fillMaxSize())
            if (vm.gradeTarget == null) Text("Move the playhead onto a clip", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun Pill(label: String, on: Boolean, color: Color = Palette.Accent, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) color.copy(alpha = 0.18f) else Color(0xFF1A1B21))
            .border(1.dp, if (on) color.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp),
    ) { Text(label, color = if (on) color else Ink, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
}

private class ScopePaints(density: Float) {
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f * density; typeface = Typeface.MONOSPACE }
    val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f * density; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
}

@Composable
private fun ScopeView(vm: EditorViewModel, s: ScopeSettings, modifier: Modifier) {
    val density = LocalDensity.current.density
    val paints = remember(density) { ScopePaints(density) }
    val image = vm.scopeImage
    val hdr = vm.hdrPreview
    val nits = s.scale == ScopeScale.NITS || (s.scale == ScopeScale.AUTO && hdr)
    val stats = vm.scopeStats
    Canvas(modifier) {
        val dp = density
        val g = 0.08f + s.graticule * 0.32f
        val grid = Color.White.copy(alpha = g)
        val labels = Color.White.copy(alpha = (g * 1.6f).coerceAtMost(0.75f))
        // Left gutter as wide as the widest label, whatever the screen density.
        val gutter = paints.label.measureText(if (nits) "10k" else if (s.scale == ScopeScale.PERCENT) "100" else "1023") + 8 * dp
        val pad = 6 * dp
        clipRect {
            when (s.type) {
                ScopeType.WAVEFORM, ScopeType.PARADE -> {
                    val area = Rect(gutter, pad + 2 * dp, size.width - pad, size.height - pad)
                    if (nits) nitsLines(area, grid, labels, paints, dp, s) else levelLines(area, s, grid, labels, paints, dp)
                    image?.let { drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset(area.left.roundToInt(), area.top.roundToInt()), IntSize(area.width.roundToInt(), area.height.roundToInt()), filterQuality = FilterQuality.Medium) }
                    if (s.type == ScopeType.PARADE) paradeLabels(area, s, grid, paints, dp)
                    if (s.refLevels && !nits) refLines(area, s, paints, dp)
                }
                ScopeType.HISTOGRAM -> {
                    val area = Rect(pad, pad, size.width - pad, size.height - pad - paints.label.textSize - 4 * dp)
                    histogramGrid(area, grid, labels, paints, dp, nits && hdr)
                    image?.let { drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset(area.left.roundToInt(), area.top.roundToInt()), IntSize(area.width.roundToInt(), area.height.roundToInt()), filterQuality = FilterQuality.Medium) }
                }
                ScopeType.HDR -> {
                    val statsH = paints.label.textSize + 8 * dp
                    val area = Rect(paints.label.measureText("10k") + 8 * dp, pad + statsH, size.width - pad, size.height - pad)
                    nitsLines(area, grid, labels, paints, dp, s)
                    image?.let { drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset(area.left.roundToInt(), area.top.roundToInt()), IntSize(area.width.roundToInt(), area.height.roundToInt()), filterQuality = FilterQuality.Medium) }
                    val st = stats
                    val line = if (st == null) "Measuring…" else "MaxCLL %.0f   MaxFALL %.0f nits   %.1f%% above SDR white   %.1f%% over peak".format(st.maxCll, st.maxFall, st.aboveSdr, st.abovePeak)
                    text(fit(line, paints.label, size.width - 2 * pad), pad, pad + paints.label.textSize, paints.label, Color.White.copy(alpha = 0.8f))
                }
                ScopeType.VECTOR -> {
                    val side = min(size.width, size.height) - 2 * pad
                    val box = Rect(Offset((size.width - side) / 2, (size.height - side) / 2), Size(side, side))
                    vectorGrid(box, s, grid, labels, paints, dp)
                    image?.let { drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset(box.left.roundToInt(), box.top.roundToInt()), IntSize(side.roundToInt(), side.roundToInt()), filterQuality = FilterQuality.Medium) }
                    vectorTargets(box, s, labels, paints, dp, hdr)
                    if (hdr) text("Rec.2020", box.left + 2 * dp, box.top + paints.label.textSize, paints.label, labels)
                }
                ScopeType.CIE -> {
                    val side = min(size.width, size.height) - 2 * pad
                    val box = Rect(Offset((size.width - side) / 2, (size.height - side) / 2), Size(side, side))
                    cieGrid(box, s, grid, labels, paints, dp, under = true)
                    image?.let { drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset(box.left.roundToInt(), box.top.roundToInt()), IntSize(side.roundToInt(), side.roundToInt()), filterQuality = FilterQuality.Medium) }
                    cieGrid(box, s, grid, labels, paints, dp, under = false)
                }
            }
        }
    }
}

/** [t] shortened with an ellipsis to fit [w] pixels. */
private fun fit(t: String, p: Paint, w: Float): String {
    if (p.measureText(t) <= w) return t
    var n = t.length
    while (n > 1 && p.measureText(t.substring(0, n) + "…") > w) n--
    return t.substring(0, n) + "…"
}

private fun DrawScope.text(t: String, x: Float, y: Float, p: Paint, c: Color, alignRight: Boolean = false) = drawIntoCanvas {
    p.color = c.toArgb()
    p.textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
    it.nativeCanvas.drawText(t, x, y, p)
}

/** PQ signal level of [nits] (SMPTE ST 2084), 0..1. */
private fun pq(nits: Float): Float {
    val y = (nits / 10000f).coerceIn(0f, 1f).toDouble().pow(0.1593017578125)
    return ((0.8359375 + 18.8515625 * y) / (1 + 18.6875 * y)).pow(78.84375).toFloat()
}

/**
 * HDR waveform and parade scale, in nits on the PQ curve: the trace is the PQ signal, so each line sits where that
 * brightness is coded. SDR reference white (203 nits) and the master's peak are marked.
 */
private fun DrawScope.nitsLines(area: Rect, grid: Color, labels: Color, p: ScopePaints, dp: Float, s: ScopeSettings) {
    val levels = listOf(0, 1, 10, 50, 100, 203, 400, 1000, 2000, 4000, 10000)
    var lastY = Float.MAX_VALUE
    val gap = p.label.textSize + 2 * dp
    for (n in levels) {
        val y = area.bottom - pq(n.toFloat()) * area.height
        val strong = n == 0 || n == 10000
        drawLine(grid, Offset(area.left, y), Offset(area.right, y), if (strong) 1.2f * dp else 0.7f * dp)
        // Labels only where there is room for them, always the top and bottom.
        if (lastY - y >= gap || n == 0) {
            text(if (n >= 1000) "${n / 1000}k" else "$n", area.left - 4 * dp, y + p.label.textSize * 0.35f, p.label, labels, alignRight = true)
            lastY = y
        }
    }
    fun mark(n: Float, name: String, c: Color) {
        val y = area.bottom - pq(n) * area.height
        drawLine(c, Offset(area.left, y), Offset(area.right, y), 1.1f * dp, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6 * dp, 4 * dp)))
        text(name, area.right - 3 * dp, y - 3 * dp, p.label, c, alignRight = true)
    }
    if (s.refSdrWhite) mark(203f, "SDR white", Color.White.copy(alpha = 0.55f))
    if (s.refPeak) mark(s.peakNits.toFloat(), "Peak ${s.peakNits}", Color(0xFFF2C14E))
    if (s.refScreen && s.screenNits != s.peakNits) mark(s.screenNits.toFloat(), "Screen ${s.screenNits}", Color(0xFF7FC8FF))
}

/** Waveform and parade levels: 0–1023 in 10-bit steps of 128, or 0–100%. */
private fun DrawScope.levelLines(area: Rect, s: ScopeSettings, grid: Color, labels: Color, p: ScopePaints, dp: Float) {
    val tenBit = s.scale != ScopeScale.PERCENT
    val steps = if (tenBit) (0..8).map { min(1023, it * 128) to (min(1023, it * 128) / 1023f) } else (0..10).map { it * 10 to it / 10f }
    // Labels at least 11 dp apart: on a short scope only every second or fourth one, always the bottom and top.
    val gap = area.height / (steps.size - 1)
    val every = when { gap >= 11 * dp -> 1; gap * 2 >= 11 * dp -> 2; gap * 4 >= 11 * dp -> 4; else -> steps.size - 1 }
    for ((i, lv) in steps.withIndex()) {
        val (label, v) = lv
        val y = area.bottom - v * area.height
        drawLine(grid, Offset(area.left, y), Offset(area.right, y), if (label == 0 || v >= 0.999f) 1.2f * dp else 0.7f * dp)
        val show = (i % every == 0 || i == steps.size - 1) && (tenBit || label % 20 == 0 || every > 1)
        if (show) text(label.toString(), area.left - 4 * dp, y + 3 * dp, p.label, labels, alignRight = true)
    }
}

private fun DrawScope.histogramGrid(area: Rect, grid: Color, labels: Color, p: ScopePaints, dp: Float, nits: Boolean) {
    val marks = if (nits) listOf(0, 10, 100, 1000, 10000).map { (if (it >= 1000) "${it / 1000}k" else "$it") to pq(it.toFloat()) }
        else listOf("0", "256", "512", "768", "1023").mapIndexed { i, l -> l to i / 4f }
    for ((i, m) in marks.withIndex()) {
        val x = area.left + m.second * area.width
        drawLine(grid, Offset(x, area.top), Offset(x, area.bottom), 0.7f * dp)
        val last = i == marks.size - 1
        text(m.first, x + if (last) -2 * dp else 2 * dp, area.bottom + p.label.textSize + 2 * dp, p.label, labels, alignRight = last)
    }
    drawLine(grid, Offset(area.left, area.bottom), Offset(area.right, area.bottom), 1.2f * dp)
}

private fun DrawScope.paradeLabels(area: Rect, s: ScopeSettings, grid: Color, p: ScopePaints, dp: Float) {
    val chans = when (s.parade) {
        ParadeMode.RGB -> listOfNotNull("R".takeIf { s.showR }, "G".takeIf { s.showG }, "B".takeIf { s.showB })
        ParadeMode.YRGB -> listOfNotNull("Y".takeIf { s.showY }, "R".takeIf { s.showR }, "G".takeIf { s.showG }, "B".takeIf { s.showB })
        ParadeMode.YCBCR -> listOf("Y", "Cb", "Cr")
    }
    if (chans.isEmpty()) return
    val w = area.width / chans.size
    for ((i, c) in chans.withIndex()) {
        val x = area.left + i * w
        if (i > 0) drawLine(grid.copy(alpha = grid.alpha * 1.6f), Offset(x, area.top), Offset(x, area.bottom), 1f * dp)
        val col = when (c) { "R", "Cr" -> RCh; "G" -> GCh; "B", "Cb" -> BCh; else -> Color.White }
        text(c, x + 5 * dp, area.top + 11 * dp, p.tag, col.copy(alpha = 0.85f))
    }
}

/** "Highs and lows": the reference levels, with their values. */
private fun DrawScope.refLines(area: Rect, s: ScopeSettings, p: ScopePaints, dp: Float) {
    for ((lvl, name) in listOf(s.refHigh to "H", s.refLow to "L")) {
        val y = area.bottom - lvl / 1023f * area.height
        drawLine(Palette.Accent.copy(alpha = 0.85f), Offset(area.left, y), Offset(area.right, y), 1.1f * dp,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6 * dp, 4 * dp)))
        text("$name $lvl", area.right - 3 * dp, y - 3 * dp, p.label, Palette.Accent, alignRight = true)
    }
}

private fun cbcr(r: Float, g: Float, b: Float): Pair<Float, Float> {
    val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
    return (b - y) / 1.8556f to (r - y) / 1.5748f
}

private fun DrawScope.vectorGrid(box: Rect, s: ScopeSettings, grid: Color, labels: Color, p: ScopePaints, dp: Float) {
    val c = box.center
    // The circle is chroma 0.6 (VEC_SCALE), where the engine puts it.
    val r = box.width * VEC_SCALE * 0.6f
    drawCircle(grid, r, c, style = Stroke(1.1f * dp))
    drawCircle(grid.copy(alpha = grid.alpha * 0.6f), r * 0.5f, c, style = Stroke(0.7f * dp))
    drawLine(grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 0.7f * dp)
    drawLine(grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 0.7f * dp)
    // Ticks every 10°, longer every 30°.
    for (a in 0 until 360 step 10) {
        val t = a * PI / 180
        val l = if (a % 30 == 0) 7 * dp else 3.5f * dp
        drawLine(grid, Offset(c.x + cos(t).toFloat() * r, c.y - sin(t).toFloat() * r), Offset(c.x + cos(t).toFloat() * (r - l), c.y - sin(t).toFloat() * (r - l)), 0.8f * dp)
    }
    if (s.skinLine) {
        // The skin tone line, between red and yellow, at 123°.
        val t = 123 * PI / 180
        drawLine(Color(0xFFE8B48A).copy(alpha = 0.55f), c, Offset(c.x + cos(t).toFloat() * r, c.y - sin(t).toFloat() * r), 1.1f * dp)
    }
    if (s.vecZoom2x) text("2×", box.right - 4 * dp, box.top + 12 * dp, p.label, labels, alignRight = true)
}

/** The colour-bar targets: R, Mg, B, Cy, G, Yl at 75% or 100%, where a pure bar would land; labels inside them. */
private fun DrawScope.vectorTargets(box: Rect, s: ScopeSettings, labels: Color, p: ScopePaints, dp: Float, hdr: Boolean) {
    val c = box.center
    val k = box.width * VEC_SCALE * (if (s.vecZoom2x) 2f else 1f)
    val amp = if (s.vecTargets75) 0.75f else 1f
    val kr = if (hdr) 0.2627f else 0.2126f
    val kb = if (hdr) 0.0593f else 0.0722f
    val bars = listOf(
        Triple("R", RCh, floatArrayOf(1f, 0f, 0f)), Triple("Mg", Color(0xFFE060E0), floatArrayOf(1f, 0f, 1f)),
        Triple("B", BCh, floatArrayOf(0f, 0f, 1f)), Triple("Cy", Color(0xFF50E0E0), floatArrayOf(0f, 1f, 1f)),
        Triple("G", GCh, floatArrayOf(0f, 1f, 0f)), Triple("Yl", Color(0xFFE8E050), floatArrayOf(1f, 1f, 0f)),
    )
    val half = 4.5f * dp
    for ((name, col, rgb) in bars) {
        val r = rgb[0] * amp; val g = rgb[1] * amp; val b = rgb[2] * amp
        val y0 = kr * r + (1 - kr - kb) * g + kb * b
        val cb = (b - y0) / (2 * (1 - kb)); val cr = (r - y0) / (2 * (1 - kr))
        val x = c.x + cb * k
        val y = c.y - cr * k
        if (!box.contains(Offset(x, y))) continue
        drawRect(col.copy(alpha = 0.75f), Offset(x - half, y - half), Size(2 * half, 2 * half), style = Stroke(1.1f * dp))
        // The name sits on the centre side of its box, so it never leaves the scope.
        val dx = x - c.x; val dy = y - c.y
        val n = hypot(dx, dy).coerceAtLeast(1f)
        val tw = p.label.measureText(name)
        val lx = x - dx / n * (half + 4 * dp + tw / 2) - tw / 2
        val ly = y - dy / n * (half + 4 * dp + p.label.textSize / 2) + p.label.textSize * 0.35f
        text(name, lx, ly, p.label, col.copy(alpha = 0.85f))
    }
}

private fun DrawScope.cieGrid(box: Rect, s: ScopeSettings, grid: Color, labels: Color, p: ScopePaints, dp: Float, under: Boolean) {
    val uv = s.cie == CieSpace.UV
    val side = box.width
    fun at(x: Float, y: Float): Offset {
        val (a, b) = if (uv) CieMap.xyToUv(x, y) else x to y
        val q = CieMap.toPx(a, b, uv, side)
        return Offset(box.left + q.first, box.top + q.second)
    }
    if (under) {
        // Axes and a light grid, then the horseshoe filled faintly so its shape reads even on a dark picture.
        val steps = if (uv) listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f) else listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f)
        for (v in steps) {
            val a = CieMap.toPx(v, 0f, uv, side)
            val b = CieMap.toPx(v, if (uv) 0.65f else 0.9f, uv, side)
            drawLine(grid.copy(alpha = grid.alpha * 0.5f), Offset(box.left + a.first, box.top + a.second), Offset(box.left + b.first, box.top + b.second), 0.6f * dp)
            val c = CieMap.toPx(0f, v, uv, side)
            val d = CieMap.toPx(if (uv) 0.65f else 0.8f, v, uv, side)
            drawLine(grid.copy(alpha = grid.alpha * 0.5f), Offset(box.left + c.first, box.top + c.second), Offset(box.left + d.first, box.top + d.second), 0.6f * dp)
        }
        val path = Path()
        val l = CieMap.locus
        for (i in l.indices step 2) at(l[i], l[i + 1]).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) }
        path.close()
        drawPath(path, Color.White.copy(alpha = 0.035f))
        drawPath(path, grid.copy(alpha = (grid.alpha * 1.8f).coerceAtMost(0.8f)), style = Stroke(1.2f * dp))
        text(if (uv) "u′v′" else "xy", box.left + 4 * dp, box.top + 12 * dp, p.label, labels)
        return
    }
    val gamuts = listOfNotNull(
        (CieMap.rec709 to Color.White).takeIf { s.gamut709 },
        (CieMap.p3 to Color(0xFFE8D060)).takeIf { s.gamutP3 },
        (CieMap.rec2020 to Color(0xFF60D0E8)).takeIf { s.gamut2020 },
    )
    for ((gm, col) in gamuts) {
        val a = at(gm.xy[0], gm.xy[1]); val b = at(gm.xy[2], gm.xy[3]); val c = at(gm.xy[4], gm.xy[5])
        val tri = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }
        drawPath(tri, col.copy(alpha = 0.7f), style = Stroke(1.1f * dp))
        text(gm.name, a.x + 4 * dp, a.y + 3 * dp, p.label, col.copy(alpha = 0.85f))
    }
    val w = at(CieMap.D65_X, CieMap.D65_Y)
    drawCircle(Color.White, 2.5f * dp, w)
    drawCircle(Color.Black, 2.5f * dp, w, style = Stroke(0.8f * dp))
}

// ---- scope options ---------------------------------------------------------------------------------

/** The scope settings sheet, over the bottom of the page. */
@Composable
fun ColorOverlays(vm: EditorViewModel) {
    val open = vm.scopeOptionsOpen
    if (open) BackHandler { vm.scopeOptionsOpen = false }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(remember { MutableInteractionSource() }, null) { vm.scopeOptionsOpen = false })
        }
        AnimatedVisibility(
            open, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(0.85f, 420f)) { it } + fadeIn(), exit = slideOutVertically(spring(0.9f, 500f)) { it } + fadeOut(),
        ) { ScopeOptions(vm) }
    }
}

@Composable
private fun ScopeOptions(vm: EditorViewModel) {
    val s = vm.scopeSettings
    fun set(n: ScopeSettings) = vm.setScopes(n)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Color(0xFF16171D))
            .clickable(remember { MutableInteractionSource() }, null) {}
            .navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3C46)))
        Spacer(Modifier.height(10.dp))
        Text("${s.type.label} settings", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        when (s.type) {
            ScopeType.WAVEFORM -> {
                Section("Mode") { for (m in WaveMode.entries) Pill(m.label, s.wave == m) { set(s.copy(wave = m)) } }
                Channels(s, ::set, y = s.wave != WaveMode.RGB, rgb = s.wave != WaveMode.Y)
                Toggles {
                    Toggle("Colorize", s.colorize, enabled = s.wave == WaveMode.Y) { set(s.copy(colorize = it)) }
                    Toggle("Extents", s.extents) { set(s.copy(extents = it)) }
                    Toggle("Low pass", s.lowPass) { set(s.copy(lowPass = it)) }
                }
                Levels(s, ::set)
            }
            ScopeType.PARADE -> {
                Section("Mode") { for (m in ParadeMode.entries) Pill(m.label, s.parade == m) { set(s.copy(parade = m)) } }
                Channels(s, ::set, y = s.parade == ParadeMode.YRGB, rgb = s.parade != ParadeMode.YCBCR)
                Toggles {
                    Toggle("Extents", s.extents) { set(s.copy(extents = it)) }
                    Toggle("Low pass", s.lowPass) { set(s.copy(lowPass = it)) }
                }
                Levels(s, ::set)
            }
            ScopeType.VECTOR -> {
                Toggles {
                    Toggle("Colorize", s.colorize) { set(s.copy(colorize = it)) }
                    Toggle("2× zoom", s.vecZoom2x) { set(s.copy(vecZoom2x = it)) }
                    Toggle("Low pass", s.lowPass) { set(s.copy(lowPass = it)) }
                }
                Section("Targets") {
                    Pill("75%", s.vecTargets75) { set(s.copy(vecTargets75 = true)) }
                    Pill("100%", !s.vecTargets75) { set(s.copy(vecTargets75 = false)) }
                }
                Toggles { Toggle("Skin tone line", s.skinLine) { set(s.copy(skinLine = it)) } }
            }
            ScopeType.HISTOGRAM -> {
                Section("Mode") { for (m in WaveMode.entries) Pill(m.label, s.wave == m) { set(s.copy(wave = m)) } }
                Channels(s, ::set, y = s.wave != WaveMode.RGB, rgb = s.wave != WaveMode.Y)
                Toggles { Toggle("Log scale", s.histLog) { set(s.copy(histLog = it)) } }
                Section("Scale") { for (sc in listOf(ScopeScale.AUTO, ScopeScale.TEN_BIT, ScopeScale.NITS)) Pill(sc.label, s.scale == sc) { set(s.copy(scale = sc)) } }
            }
            ScopeType.HDR -> {
                Toggles {
                    Toggle("Zones", s.hdrZones) { set(s.copy(hdrZones = it)) }
                    Toggle("Log count", s.hdrLog) { set(s.copy(hdrLog = it)) }
                }
                HdrRefs(s, ::set)
            }
            ScopeType.CIE -> {
                Section("Diagram") { for (c in CieSpace.entries) Pill(c.label, s.cie == c) { set(s.copy(cie = c)) } }
                Section("Gamuts") {
                    Pill("Rec.709", s.gamut709) { set(s.copy(gamut709 = !s.gamut709)) }
                    Pill("P3-D65", s.gamutP3) { set(s.copy(gamutP3 = !s.gamutP3)) }
                    Pill("Rec.2020", s.gamut2020) { set(s.copy(gamut2020 = !s.gamut2020)) }
                }
            }
        }
        OptionSlider("Scope brightness", s.brightness) { set(s.copy(brightness = it)) }
        OptionSlider("Graticule", s.graticule) { set(s.copy(graticule = it)) }
    }
}

@Composable
private fun Section(title: String, content: @Composable RowScope.() -> Unit) {
    Text(title, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun Toggles(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun Toggle(label: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (on && enabled) Palette.Accent.copy(alpha = 0.16f) else Color(0xFF1E1F26))
            .clickable(enabled = enabled) { onChange(!on) }.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(15.dp).clip(RoundedCornerShape(4.dp)).background(if (on && enabled) Palette.Accent else Color.Transparent)
                .border(1.3.dp, if (on && enabled) Palette.Accent else Color(0xFF555866), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (on && enabled) Icon(Lucide.Check, null, tint = Color.Black, modifier = Modifier.size(11.dp)) }
        Spacer(Modifier.width(7.dp))
        Text(label, color = if (enabled) Ink else Ink.copy(alpha = 0.35f), fontSize = 12.sp)
    }
}

/** Channel switches: each trace can be turned off, or all of them. */
@Composable
private fun Channels(s: ScopeSettings, set: (ScopeSettings) -> Unit, y: Boolean, rgb: Boolean) {
    Section("Channels") {
        if (y) Pill("Y", s.showY, Color.White) { set(s.copy(showY = !s.showY)) }
        if (rgb) {
            Pill("R", s.showR, RCh) { set(s.copy(showR = !s.showR)) }
            Pill("G", s.showG, GCh) { set(s.copy(showG = !s.showG)) }
            Pill("B", s.showB, BCh) { set(s.copy(showB = !s.showB)) }
            val all = s.showR && s.showG && s.showB && (!y || s.showY)
            Pill(if (all) "None" else "All", false) { set(s.copy(showY = !all, showR = !all, showG = !all, showB = !all)) }
        }
    }
}

@Composable
private fun HdrRefs(s: ScopeSettings, set: (ScopeSettings) -> Unit) {
    Section("HDR reference lines") {
        Pill("SDR white 203", s.refSdrWhite) { set(s.copy(refSdrWhite = !s.refSdrWhite)) }
        Pill("Peak ${s.peakNits}", s.refPeak) { set(s.copy(refPeak = !s.refPeak)) }
        Pill("Screen ${s.screenNits}", s.refScreen) { set(s.copy(refScreen = !s.refScreen)) }
    }
}

@Composable
private fun Levels(s: ScopeSettings, set: (ScopeSettings) -> Unit) {
    Section("Scale") { for (sc in ScopeScale.entries) Pill(sc.label, s.scale == sc) { set(s.copy(scale = sc)) } }
    Text("Auto shows nits for HDR and 10-bit levels for SDR.", color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    if (s.scale == ScopeScale.NITS || (s.scale == ScopeScale.AUTO && s.hdr)) HdrRefs(s, set)
    Toggles { Toggle("Highs and lows", s.refLevels) { set(s.copy(refLevels = it)) } }
    if (s.refLevels) {
        Stepper("High", s.refHigh, 512..1023) { set(s.copy(refHigh = it)) }
        Stepper("Low", s.refLow, 0..511) { set(s.copy(refLow = it)) }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 12.sp, modifier = Modifier.width(54.dp))
        for ((t, d) in listOf("−16" to -16, "−1" to -1)) Pill(t, false) { onChange((value + d).coerceIn(range)) }
        Text("$value", color = Palette.Accent, fontSize = 14.sp, fontFamily = Mono, modifier = Modifier.width(60.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        for ((t, d) in listOf("+1" to 1, "+16" to 16)) Pill(t, false) { onChange((value + d).coerceIn(range)) }
        Spacer(Modifier.width(8.dp))
        val preset = if (label == "High") listOf(940, 1019) else listOf(64, 4)
        for (p in preset) Pill("$p", value == p) { onChange(p) }
    }
}

@Composable
private fun OptionSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Text(label, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    val v by rememberUpdatedState(value)
    Box(
        Modifier.fillMaxWidth().height(30.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                onChange((down.position.x / size.width).coerceIn(0f, 1f))
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    onChange((c.position.x / size.width).coerceIn(0f, 1f))
                    c.consume()
                }
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            drawLine(Color(0xFF2C2E37), Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(Palette.Accent, Offset(0f, y), Offset(size.width * v, y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(Color.White, 9.dp.toPx(), Offset(size.width * v, y))
        }
    }
}

@Composable
private fun Timecode(vm: EditorViewModel, modifier: Modifier) {
    Text(vm.tc(vm.playheadUs), color = Palette.Accent, fontSize = 13.sp, fontFamily = Mono, modifier = modifier)
}

// ---- transport and timeline ------------------------------------------------------------------------

@Composable
internal fun ColorTransport(vm: EditorViewModel) {
    // Everything here is sized from the width of the screen (one unit is 1/400 of it), as the editor's transport is,
    // so the row keeps its proportions on any phone, at any display size or font size, and nothing wraps or crowds.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val u = maxWidth / 400f
        val density = LocalDensity.current
        fun sp(v: Float) = with(density) { (u * v).toPx().toSp() / fontScale }
        @Composable
        fun Btn(icon: ImageVector, label: String, onClick: () -> Unit) {
            Box(Modifier.size(u * 34f).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = Ink, modifier = Modifier.size(u * 18f))
            }
        }
        Row(Modifier.fillMaxWidth().height(u * 44f).padding(horizontal = u * 10f), verticalAlignment = Alignment.CenterVertically) {
            // The timecode and the HDR switch get the room they need; the clip count is short.
            Row(Modifier.weight(1.7f), verticalAlignment = Alignment.CenterVertically) {
                Text(vm.tc(vm.playheadUs), color = Palette.Accent, fontSize = sp(12.5f), fontFamily = Mono, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(u * 6f))
                HdrToggle(vm, sp(10f), u * 7f, u * 3f)
            }
            Btn(Lucide.SkipBack, "Previous clip") { vm.jumpClip(-1) }
            Btn(Lucide.ChevronsLeft, "Back one frame") { vm.stepFrames(-1) }
            Box(
                Modifier.size(u * 38f).clip(CircleShape).background(Palette.Accent).clickable { vm.togglePlay() },
                contentAlignment = Alignment.Center,
            ) { Icon(if (vm.isPlaying) Lucide.Pause else Lucide.Play, "Play", tint = Color.Black, modifier = Modifier.size(u * 18f)) }
            Btn(Lucide.ChevronsRight, "Forward one frame") { vm.stepFrames(1) }
            Btn(Lucide.SkipForward, "Next clip") { vm.jumpClip(1) }
            // Which clip of the project this is, counting the pictures on every layer in time order.
            val all = vm.tracks.filter { it.pictures }.flatMap { it.clips }.sortedBy { it.startUs }
            val i = vm.gradeTarget?.let { t -> all.indexOfFirst { it.id == t.clip.id } } ?: -1
            Text(
                if (i >= 0) "${i + 1}/${all.size}" else "–/${all.size}", color = Palette.TextDim, fontSize = sp(12f), fontFamily = Mono,
                maxLines = 1, softWrap = false, modifier = Modifier.weight(0.8f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/** Geometry of the Color page's timeline, in pixels. */
private class CtGeo(density: Float) {
    val ruler = 22 * density
    val lane = 44 * density
    val header = 34 * density
    val gap = 3 * density
}

/**
 * The project's picture lanes, sized for a finger: the playhead in the middle, drag sideways to scrub (a tick at every
 * cut), pinch to zoom, drag up and down through the lanes. Tap a clip to grade it; long-press clips to group them, and
 * the grade then goes to all of them at once. It shares the top panel with the scopes. Nothing can be moved or
 * trimmed here.
 */
@Composable
internal fun ColorTimeline(vm: EditorViewModel, height: androidx.compose.ui.unit.Dp) {
    val view = LocalView.current
    val screen = LocalDensity.current
    val vmNow by rememberUpdatedState(vm)
    val geo = remember(screen) { CtGeo(screen.density) }
    val pps = remember { mutableFloatStateOf(with(screen) { 28.dp.toPx() }) }
    val laneScroll = remember { mutableFloatStateOf(0f) }
    val lanes = lanes(vm)
    val bodyH = height
    val shownLanes = with(screen) { ((height.toPx() - geo.ruler - geo.gap) / geo.lane).toInt() }.coerceIn(1, maxOf(1, lanes.size))
    val maxScroll = ((lanes.size - shownLanes).coerceAtLeast(0) * geo.lane)
    // The lane of the clip being graded is always brought into view.
    val target = vm.gradeTarget
    LaunchedEffect(target?.clip?.id, shownLanes) {
        val i = lanes.indexOfFirst { it.id == target?.trackId }
        if (i < 0) return@LaunchedEffect
        val top = i * geo.lane
        val sc = laneScroll.floatValue
        if (top < sc) laneScroll.floatValue = top
        else if (top + geo.lane > sc + shownLanes * geo.lane) laneScroll.floatValue = (top + geo.lane - shownLanes * geo.lane).coerceAtMost(maxScroll)
    }
    laneScroll.floatValue = laneScroll.floatValue.coerceIn(0f, maxScroll)
    val small = remember(screen) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f * screen.density; color = android.graphics.Color.argb(190, 225, 226, 232); typeface = android.graphics.Typeface.MONOSPACE }
    }
    val namePaint = remember(screen) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f * screen.density; color = android.graphics.Color.WHITE
            setShadowLayer(3f * screen.density, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    val lanePaint = remember(screen) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * screen.density; color = android.graphics.Color.argb(220, 140, 170, 255)
            typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
    }
    Box(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(bodyH).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0C0D11))
                .pointerInput(geo) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val v = vmNow
                        var mode = 0 // 0 undecided, 1 scrub, 2 lanes up/down, 3 pinch
                        var tx = 0f
                        var ty = 0f
                        var longDone = false
                        val slop = SLOP_DP * density
                        var under = cutsUnder(v)
                        var lastTick = 0L
                        while (true) {
                            val e = if (mode == 0 && !longDone) withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitPointerEvent() } else awaitPointerEvent()
                            if (e == null) {
                                // A long press on a clip: into or out of the group graded together.
                                clipAt(v, lanes(v), down.position, size.width.toFloat(), pps.floatValue, laneScroll.floatValue, geo)?.let {
                                    v.toggleGradeGroup(it)
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                }
                                longDone = true
                                continue
                            }
                            val pressed = e.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (mode == 1) v.scrubEnd()
                                mode = 3
                                val z = e.calculateZoom()
                                pps.floatValue = (pps.floatValue * z).coerceIn(1.5f * density, 400f * density)
                                e.changes.forEach { it.consume() }
                                continue
                            }
                            if (mode == 3) continue
                            val c = pressed.first()
                            val d = c.positionChange()
                            tx += d.x
                            ty += d.y
                            if (mode == 0 && !longDone && (abs(tx) > slop || abs(ty) > slop)) {
                                mode = if (abs(tx) >= abs(ty)) 1 else 2
                                if (mode == 1) v.scrubStart()
                            }
                            when (mode) {
                                1 -> {
                                    v.scrubBy((-d.x / pps.floatValue * 1e6f).toLong())
                                    // A tick each time the playhead crosses a cut on any lane.
                                    val now = cutsUnder(v)
                                    if (now != under) {
                                        under = now
                                        val t = android.os.SystemClock.uptimeMillis()
                                        if (t - lastTick > 30) {
                                            lastTick = t
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                    }
                                }
                                2 -> {
                                    val max = ((lanes(v).size - shownLanes).coerceAtLeast(0) * geo.lane)
                                    laneScroll.floatValue = (laneScroll.floatValue - d.y).coerceIn(0f, max)
                                }
                            }
                            if (mode != 0) c.consume()
                        }
                        if (mode == 1) v.scrubEnd()
                        if (mode == 0 && !longDone) {
                            // A tap: the clip under the finger becomes the one graded.
                            clipAt(v, lanes(v), down.position, size.width.toFloat(), pps.floatValue, laneScroll.floatValue, geo)?.let {
                                v.gradeClip(it)
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            }
                        }
                    }
                },
        ) {
            val dp = density
            vm.thumbs.version // redraw as filmstrip pictures arrive
            val areaL = geo.header
            val cx = areaL + (size.width - areaL) / 2f
            val ph = vm.playheadUs
            val px = pps.floatValue
            fun xOf(us: Long) = cx + ((us - ph) / 1e6f) * px
            val tL = ph - ((cx - areaL) / px * 1e6f).toLong()
            val tR = ph + ((size.width - cx) / px * 1e6f).toLong()
            // Ruler, with a label at least 64 dp apart.
            drawRect(Color(0xFF14151B), Offset.Zero, Size(size.width, geo.ruler))
            val step = listOf(0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f).firstOrNull { it * px >= 64 * dp } ?: 600f
            val stepUs = (step * 1e6f).toLong()
            var t = (maxOf(0L, tL) / stepUs) * stepUs
            drawIntoCanvas { canvas ->
                while (t <= tR) {
                    val x = xOf(t)
                    if (x > areaL) {
                        drawLine(Color.White.copy(alpha = 0.25f), Offset(x, geo.ruler - 7 * dp), Offset(x, geo.ruler), dp)
                        val sec = t / 1_000_000L
                        canvas.nativeCanvas.drawText("%d:%02d".format(sec / 60, sec % 60), x + 4 * dp, geo.ruler - 8 * dp, small)
                    }
                    t += stepUs
                }
            }
            val targetId = vm.gradeTarget?.clip?.id
            val group = vm.gradeGroup
            val r = CornerRadius(7 * dp)
            clipRect(top = geo.ruler) {
                for ((i, lane) in lanes.withIndex()) {
                    val top = geo.ruler + i * geo.lane - laneScroll.floatValue + geo.gap
                    if (top > size.height || top + geo.lane < geo.ruler) continue
                    val h = geo.lane - geo.gap * 2
                    // Alternate lane shading so rows read apart.
                    if (i % 2 == 1) drawRect(Color.White.copy(alpha = 0.025f), Offset(areaL, top - geo.gap), Size(size.width - areaL, geo.lane))
                    clipRect(left = areaL) {
                        for (c in lane.clips) {
                            if (c.endUs < tL || c.startUs > tR) continue
                            val x0 = max(xOf(c.startUs) + 1.5f * dp, areaL - 10 * dp)
                            val x1 = min(xOf(c.endUs) - 1.5f * dp, size.width + 10 * dp)
                            val w = max(x1 - x0, 2 * dp)
                            val on = c.id == targetId
                            val inGroup = c.id in group
                            val a = vm.assets[c.assetId]
                            val base = if (a?.isImage == true) Color(0xFF4A3D70) else Color(0xFF22324F)
                            drawRoundRect(base, Offset(x0, top), Size(w, h), r)
                            // Filmstrip from the scrub proxy, in the file's own shape.
                            if (a != null && !a.isAudio && a.width > 0 && w > 12 * dp) {
                                val proxy = vm.proxies[a.id]
                                val tw = (h * a.width / a.height.toFloat()).coerceAtLeast(16 * dp)
                                val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x0, top, x0 + w, top + h, r)) }
                                clipPath(path) {
                                    val startX = xOf(c.startUs)
                                    var k = (((x0 - startX) / tw).toInt()).coerceAtLeast(0)
                                    while (true) {
                                        val fx = startX + k * tw
                                        if (fx > x1) break
                                        val us = (c.inUs + ((k * tw + tw / 2) / px * 1e6f * c.speed).toLong()) / 250_000L * 250_000L
                                        if (a.isImage || proxy != null) vm.thumbs.get(a, us, proxy)?.let { img ->
                                            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(fx.roundToInt(), top.roundToInt()), IntSize(kotlin.math.ceil(tw).toInt() + 1, h.roundToInt()))
                                        }
                                        k++
                                    }
                                    // Dim a little and shade the top, so names and marks stay readable.
                                    drawRect(Color.Black.copy(alpha = if (on) 0.05f else 0.25f), Offset(x0, top), Size(w, h))
                                    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent), top, top + 20 * dp), Offset(x0, top), Size(w, 20 * dp))
                                }
                            }
                            if (inGroup) drawRoundRect(Palette.Accent.copy(alpha = 0.22f), Offset(x0, top), Size(w, h), r)
                            if (on) drawRoundRect(Palette.Accent, Offset(x0, top), Size(w, h), r, style = Stroke(2.5f * dp))
                            else if (inGroup) drawRoundRect(Palette.Accent.copy(alpha = 0.8f), Offset(x0, top), Size(w, h), r, style = Stroke(1.5f * dp))
                            else drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(x0, top), Size(w, h), r, style = Stroke(dp))
                            var nameX = max(x0, areaL) + 7 * dp
                            // Graded: a small orange dot before the name.
                            if (!c.grade.isIdentity && w > 16 * dp) {
                                drawCircle(Palette.Accent, 3.5f * dp, Offset(nameX + 3.5f * dp, top + 10 * dp))
                                nameX += 11 * dp
                            }
                            if (w > 70 * dp) drawIntoCanvas { canvas ->
                                canvas.save()
                                canvas.nativeCanvas.clipRect(x0, top, x0 + w - 4 * dp, top + h)
                                canvas.nativeCanvas.drawText(a?.name ?: "", nameX, top + 14 * dp, namePaint)
                                canvas.restore()
                            }
                        }
                    }
                    // The lane's name in its own column, never covered by clips.
                    drawRect(Color(0xFF14151B), Offset(0f, top - geo.gap), Size(areaL, geo.lane))
                    drawIntoCanvas { it.nativeCanvas.drawText(lane.name, areaL / 2f, top + h / 2f + 4 * dp, lanePaint) }
                }
            }
            drawLine(Color.White.copy(alpha = 0.06f), Offset(areaL, 0f), Offset(areaL, size.height), dp)
            // The playhead: a line with a small handle on the ruler.
            drawLine(Palette.Accent, Offset(cx, geo.ruler - 4 * dp), Offset(cx, size.height), 2 * dp, StrokeCap.Round)
            val tri = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx - 6 * dp, 2 * dp); lineTo(cx + 6 * dp, 2 * dp); lineTo(cx + 6 * dp, 10 * dp); lineTo(cx, 16 * dp); lineTo(cx - 6 * dp, 10 * dp); close()
            }
            drawPath(tri, Palette.Accent)
        }
        // Grouped clips: how many, and a way out, in the ruler's corner.
        if (vm.gradeGroup.isNotEmpty()) Row(
            Modifier.align(Alignment.TopEnd).padding(3.dp).clip(RoundedCornerShape(50)).background(Color(0xE61C1D24))
                .clickable { vm.clearGradeGroup() }.padding(horizontal = 9.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Accent))
            Text("  ${vm.gradeGroup.size} grouped  ✕", color = Palette.Accent, fontSize = 11.sp)
        }
    }
}

private fun lanes(vm: EditorViewModel) = vm.tracks.filter { it.pictures }.reversed()

/** Which clips are under the playhead on every picture lane, to feel each cut while scrubbing. */
private fun cutsUnder(vm: EditorViewModel): List<Long?> = vm.tracks.filter { it.pictures }.map { it.clipAt(vm.playheadUs)?.id }

/** The clip under [p] in the Color page's timeline, if any. */
private fun clipAt(vm: EditorViewModel, lanes: List<com.graden.editor.Track>, p: Offset, width: Float, pps: Float, scroll: Float, geo: CtGeo): Long? {
    if (p.y < geo.ruler || p.x < geo.header) return null
    val i = ((p.y - geo.ruler + scroll) / geo.lane).toInt()
    if (i !in lanes.indices) return null
    val cx = geo.header + (width - geo.header) / 2f
    val us = vm.playheadUs + ((p.x - cx) / pps * 1e6f).toLong()
    return lanes[i].clipAt(us)?.id
}

// ---- controls --------------------------------------------------------------------------------------

/** Room the grading controls keep: the tab row, a 112 dp wheel, its master strip and its channel pills. */
private val ControlsH = 300.dp

@Composable
private fun Controls(vm: EditorViewModel, modifier: Modifier) {
    var layer by remember { mutableStateOf(Layer.CORRECTION) }
    var tab by remember { mutableIntStateOf(0) }
    var wheels by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(Panel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        // MCRAW only for MotionCam RAW clips: another clip under the playhead goes back to the grade.
        val isRaw = vm.gradeTarget?.clip?.let { vm.assets[it.assetId] }?.isRaw == true
        if (layer == Layer.RAW && !isRaw) layer = Layer.CORRECTION
        LayerStrip(vm, layer) { layer = it }
        if (layer == Layer.CORRECTION) {
            val g = vm.gradeTarget?.clip?.grade ?: Grade()
            val cv = g.curves
            val marks = listOf(
                !(g.lift.isZero && g.gamma.isZero && g.gain.isZero && g.offset.isZero),
                g.temp != 0f || g.tint != 0f || g.contrast != 1f || g.pivot != 0.435f || g.shadows != 0f || g.highlights != 0f || g.saturation != 50f || g.hue != 50f || g.colorBoost != 0f || g.lumMix != 100f,
                g.zones.active, g.logWheels.active,
                cv.y != com.graden.editor.Curves.LINE || cv.r != com.graden.editor.Curves.LINE || cv.g != com.graden.editor.Curves.LINE || cv.b != com.graden.editor.Curves.LINE,
                cv.hueHue.isNotEmpty() || cv.hueSat.isNotEmpty() || cv.hueLum.isNotEmpty() || cv.lumSat.isNotEmpty() || cv.satSat.isNotEmpty(),
                g.mixer.active, g.warper.active, g.slice.active, g.detail.active || g.midDetail != 0f,
            )
            PaletteTabs(listOf("Wheels", "Primaries", "HDR zones", "Log", "Curves", "Hue curves", "Mixer", "Warper", "Slice", "Detail"), tab, marks) { tab = it }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (vm.gradeTarget == null) {
                Text("No clip under the playhead", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
            } else if (layer != Layer.CORRECTION) when (layer) {
                Layer.RAW -> RawPanel(vm)
                Layer.NOISE -> NoisePanel(vm)
                Layer.LENS -> LensPanel(vm)
                Layer.DEFLICKER -> DeflickerPanel(vm)
                Layer.CST -> CstPalette(vm) { picking = true }
                Layer.SECONDARIES -> SecondariesPanel(vm)
                Layer.LUTS -> LutPalette(vm)
                Layer.FILM -> FilmPanel(vm)
                else -> OutputPanel(vm)
            }
            else when (tab) {
                // All four wheels when there is room for them, as on Resolve's palette; otherwise two at a time.
                0 -> if (maxHeight >= 420.dp) Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                    Box(Modifier.weight(1f)) { Wheels(vm, listOf(WheelKind.LIFT, WheelKind.GAMMA)) }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(Palette.Line))
                    Box(Modifier.weight(1f)) { Wheels(vm, listOf(WheelKind.GAIN, WheelKind.OFFSET)) }
                } else Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(34.dp)) {
                        Segmented(listOf("Lift · Gamma", "Gain · Offset"), wheels) { wheels = it }
                    }
                    Box(Modifier.weight(1f)) {
                        Wheels(vm, if (wheels == 0) listOf(WheelKind.LIFT, WheelKind.GAMMA) else listOf(WheelKind.GAIN, WheelKind.OFFSET))
                    }
                }
                1 -> Primaries(vm, maxHeight)
                2 -> ZonesPanel(vm)
                3 -> LogPanel(vm)
                4 -> CurvesPalette(vm)
                5 -> HslPalette(vm)
                6 -> MixerPanel(vm)
                7 -> WarperPanel(vm)
                8 -> SlicePanel(vm)
                else -> DetailPanel(vm)
            }
        }
    }
    val target = vm.gradeTarget
    if (picking && target != null) InputSpaceDialog(vm, target.clip) { picking = false }
}

@Composable
private fun Segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(50)).background(Color(0xFF1B1C22))) {
        val w = maxWidth / items.size
        val x by animateDpAsState(w * selected, spring(0.8f, 500f), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(50)).background(Color(0xFF2C2116)))
        Row(Modifier.fillMaxSize()) {
            for ((i, t) in items.withIndex()) Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(t, color = if (i == selected) Palette.Accent else Ink, fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

/** Two wheels side by side, as big as the room allows. */
@Composable
private fun Wheels(vm: EditorViewModel, kinds: List<WheelKind>) {
    Row(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (k in kinds) key(k) { ColorWheel(vm, k, Modifier.weight(1f).fillMaxHeight()) }
    }
}

/** The colour of the wheel's hue at [angle] (radians, counter-clockwise from blue at the right), for the ring. */
private fun hueAt(angle: Double, amount: Float = 0.42f): Color {
    val cb = cos(angle).toFloat() * amount
    val cr = sin(angle).toFloat() * amount
    val y = 0.5f
    return Color(
        (y + 1.5748f * cr).coerceIn(0f, 1f), (y - 0.1873f * cb - 0.4681f * cr).coerceIn(0f, 1f), (y + 1.8556f * cb).coerceIn(0f, 1f),
    )
}

// Compose's sweep starts at 3 o'clock and runs clockwise on screen, i.e. towards negative angles.
private val ringColors = List(37) { i -> hueAt(-2 * PI * i / 36) }

@Composable
private fun ColorWheel(vm: EditorViewModel, kind: WheelKind, modifier: Modifier) {
    val view = LocalView.current
    val grade = vm.gradeTarget?.clip?.grade ?: Grade()
    val w = kind.of(grade)
    val vmNow by rememberUpdatedState(vm)
    var active by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (active) 1f else 0f, spring(0.8f, 600f), label = "glow")
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            // The name and its reset sit in the wheel's top corners, so the disc gets all the height.
            Text(kind.label, color = if (w.isZero) Ink else Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp))
            Box(
                Modifier.align(Alignment.TopEnd).size(30.dp).clip(CircleShape).clickable(enabled = !w.isZero) { vm.gradeOnce { kind.set(it, Wheel()) } },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.RotateCcw, "Reset ${kind.label}", tint = if (w.isZero) Ink.copy(alpha = 0.25f) else Ink, modifier = Modifier.size(15.dp)) }
            // A moderate disc: big enough for fine moves, small enough to leave room for the timeline and scopes.
            val d = minOf(maxWidth.value, maxHeight.value, 112f).dp
            Canvas(
                Modifier.size(d).pointerInput(kind) {
                    // Drag anywhere on the wheel: the puck follows the finger's movement (not its position), at a
                    // gentle rate, so it never jumps and fine moves are easy. A soft click holds it at the centre.
                    // Double tap: back to neutral.
                    var lastTapAt = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val v = vmNow
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val r = size.width / 2f * 0.84f
                        val start = kind.of(v.gradeTarget?.clip?.grade ?: Grade())
                        var fu = start.u
                        var fv = start.v
                        var snapped = start.u == 0f && start.v == 0f
                        var travel = 0f
                        // Only a touch that starts on the disc itself, and only once it has really moved, changes anything.
                        val onDisc = (down.position - center).getDistance() <= size.width / 2f
                        var engaged = false
                        val ticks = Ticker(view, 0.05f, 0f)
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            if (e.changes.size > 1) break
                            val dlt = c.positionChange()
                            travel += abs(dlt.x) + abs(dlt.y)
                            if (!engaged) {
                                if (!onDisc || travel < SLOP_DP * density) continue
                                engaged = true
                                active = true
                                v.gradeStart()
                                ticks.start(hypot(fu, fv))
                            }
                            // Slower near the centre, where small amounts matter most.
                            val k = 0.42f + 0.25f * min(1f, hypot(fu, fv))
                            fu += dlt.x / r * k
                            fv -= dlt.y / r * k
                            val m = hypot(fu, fv)
                            if (m > 1f) { fu /= m; fv /= m }
                            val inDetent = hypot(fu, fv) < 0.035f
                            if (inDetent && !snapped) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            snapped = inDetent
                            val (ou, ov) = if (inDetent) 0f to 0f else fu to fv
                            if (!inDetent) ticks.at(hypot(ou, ov))
                            v.setGrade { g -> kind.set(g, kind.of(g).copy(u = ou, v = ov)) }
                            c.consume()
                        }
                        active = false
                        val now = System.currentTimeMillis()
                        if (!engaged && onDisc && travel < SLOP_DP * density) {
                            // A double tap on the disc puts the colour back to neutral.
                            if (now - lastTapAt < 320) {
                                v.gradeOnce { g -> kind.set(g, kind.of(g).copy(u = 0f, v = 0f)) }
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                lastTapAt = 0L
                            } else lastTapAt = now
                        }
                        if (engaged) v.gradeEnd()
                    }
                },
            ) {
                val c = center
                val rOut = size.width / 2f
                val ring = 6.dp.toPx()
                val rIn = rOut * 0.84f
                // Hue ring, then a dark field with the faintest hue in it, so the direction reads without glare.
                drawCircle(Brush.sweepGradient(ringColors, c), rOut - ring / 2, c, style = Stroke(ring))
                drawCircle(Color(0xFF0D0E12), rOut - ring, c)
                drawCircle(Brush.sweepGradient(ringColors.map { it.copy(alpha = 0.16f) }, c), rOut - ring, c)
                drawCircle(Brush.radialGradient(listOf(Color(0xFF0D0E12), Color(0xFF0D0E12).copy(alpha = 0f)), c, rIn * 0.9f), rIn, c)
                val gl = Color.White.copy(alpha = 0.10f)
                drawLine(gl, Offset(c.x - rIn, c.y), Offset(c.x + rIn, c.y), 1.dp.toPx())
                drawLine(gl, Offset(c.x, c.y - rIn), Offset(c.x, c.y + rIn), 1.dp.toPx())
                drawCircle(gl, rIn * 0.5f, c, style = Stroke(1.dp.toPx()))
                if (glow > 0f) drawCircle(Palette.Accent.copy(alpha = 0.35f * glow), rOut - ring / 2, c, style = Stroke(ring + 2.dp.toPx()))
                val p = Offset(c.x + w.u * rIn, c.y - w.v * rIn)
                if (!w.isZero || w.reach > 0f) drawLine(Color.White.copy(alpha = 0.45f), c, p, 1.5.dp.toPx(), StrokeCap.Round)
                drawCircle(Color.White.copy(alpha = 0.5f), 2.dp.toPx(), c)
                val pr = (if (active) 11f else 9f).dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.5f), pr + 2.dp.toPx(), p)
                drawCircle(hueAt(kotlin.math.atan2(w.v, w.u).toDouble(), 0.42f * min(1f, w.reach * 3f)), pr, p)
                drawCircle(Color.White, pr, p, style = Stroke(2.dp.toPx()))
            }
        }
        MasterJog(vm, kind, w)
        ChannelStrip(vm, kind, w)
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Y, R, G and B of a wheel, each a strip of its own: drag one sideways to change just that channel (the wheel follows),
 * double tap to put that channel back to neutral. A deliberate sideways drag is needed, so touching changes nothing.
 */
@Composable
private fun ChannelStrip(vm: EditorViewModel, kind: WheelKind, w: Wheel) {
    val n = kind.readout(w)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((ch, col) in listOf(Color.White, RCh, GCh, BCh).withIndex()) {
            ChannelPill(vm, kind, ch, n[ch], col, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChannelPill(vm: EditorViewModel, kind: WheelKind, ch: Int, value: Float, col: Color, modifier: Modifier) {
    val view = LocalView.current
    val vmNow by rememberUpdatedState(vm)
    var active by remember { mutableStateOf(false) }
    val changed = abs(value - kind.neutral) > kind.step * 0.5f
    Box(
        modifier.height(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) col.copy(alpha = 0.28f) else col.copy(alpha = if (changed) 0.14f else 0.07f))
            .border(1.dp, if (active) col.copy(alpha = 0.9f) else Color.Transparent, RoundedCornerShape(8.dp))
            .pointerInput(kind, ch) {
                var lastTapAt = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val v = vmNow
                    var free = kind.readout(kind.of(v.gradeTarget?.clip?.grade ?: Grade()))[ch]
                    var travelX = 0f
                    var travelY = 0f
                    var engaged = false
                    val ticks = Ticker(view, kind.step, kind.neutral)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val d = c.positionChange()
                        travelX += abs(d.x)
                        travelY += abs(d.y)
                        if (!engaged) {
                            if (travelY > SLOP_DP * density && travelY > travelX) break
                            if (travelX < SLOP_DP * density) continue
                            engaged = true
                            active = true
                            v.gradeStart()
                            ticks.start(free)
                        }
                        // A full sweep across about three strip widths covers the channel's range: fine control.
                        free += d.x / (size.width * 3f) * kind.sweep
                        val out = if (abs(free - kind.neutral) < kind.step * 0.6f) kind.neutral else free
                        ticks.at(out)
                        v.setGrade { g -> kind.set(g, kind.withChannel(kind.of(g), ch, out)) }
                        c.consume()
                    }
                    active = false
                    if (engaged) v.gradeEnd()
                    else if (travelX + travelY < SLOP_DP * density) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt < 320) {
                            v.gradeOnce { g -> kind.set(g, kind.withChannel(kind.of(g), ch, kind.neutral)) }
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            lastTapAt = 0L
                        } else lastTapAt = now
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(listOf("Y", "R", "G", "B")[ch], color = col.copy(alpha = 0.75f), fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (kind == WheelKind.OFFSET) "%.1f".format(value) else "%.2f".format(value),
                color = if (changed || active) col else col.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = Mono,
            )
        }
    }
}

/**
 * The wheel's master ring as a jog strip: drag sideways to move all channels together, the ticks running
 * under the finger. Double tap resets it.
 */
@Composable
private fun MasterJog(vm: EditorViewModel, kind: WheelKind, w: Wheel) {
    val view = LocalView.current
    val vmNow by rememberUpdatedState(vm)
    // Full strip width moves this much.
    val perWidth = when (kind) { WheelKind.GAIN -> 1.0f; WheelKind.OFFSET -> 0.4f; else -> 0.5f }
    Canvas(
        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF17181E))
            .pointerInput(kind) {
                var lastTapAt = 0L
                awaitEachGesture {
                    awaitFirstDown()
                    val v = vmNow
                    var travel = 0f
                    var travelY = 0f
                    var free = kind.master(kind.of(v.gradeTarget?.clip?.grade ?: Grade()))
                    var engaged = false
                    val ticks = Ticker(view, perWidth / 50f, 0f)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val d = c.positionChange()
                        travel += abs(d.x)
                        travelY += abs(d.y)
                        if (!engaged) {
                            if (travelY > SLOP_DP * density && travelY > travel) break
                            if (travel < SLOP_DP * density) continue
                            engaged = true
                            v.gradeStart()
                            ticks.start(free)
                        }
                        free = (free + d.x / size.width * perWidth).coerceIn(-1f, 1f)
                        val inDetent = abs(free) < perWidth * 0.012f
                        val y = if (inDetent) 0f else free
                        ticks.at(y)
                        v.setGrade { g -> kind.set(g, kind.withMaster(kind.of(g), y)) }
                        c.consume()
                    }
                    if (engaged) v.gradeEnd()
                    else if (travel + travelY < SLOP_DP * density) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt < 320) {
                            v.gradeOnce { g -> kind.set(g, kind.withMaster(kind.of(g), 0f)) }
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            lastTapAt = 0L
                        } else lastTapAt = now
                    }
                }
            },
    ) {
        val mid = size.width / 2
        val spacing = 7.dp.toPx()
        // Ticks scroll with the value, so the strip feels like a wheel turning under the thumb.
        val shift = (kind.master(w) / perWidth * size.width) % spacing
        var x = mid + shift - spacing * (mid / spacing).toInt() - spacing
        var i = 0
        while (x < size.width + spacing) {
            val tall = i % 5 == 0
            drawLine(Color.White.copy(alpha = if (tall) 0.28f else 0.14f), Offset(x, size.height * (if (tall) 0.25f else 0.38f)), Offset(x, size.height * (if (tall) 0.75f else 0.62f)), 1.dp.toPx())
            x += spacing
            i++
        }
        // How far from neutral, as a bar from the centre.
        val bar = (kind.master(w) / perWidth * size.width).coerceIn(-mid, mid)
        if (bar != 0f) drawRect(Palette.Accent.copy(alpha = 0.35f), Offset(min(mid, mid + bar), size.height - 3.dp.toPx()), Size(abs(bar), 3.dp.toPx()))
        drawLine(Palette.Accent, Offset(mid, 3.dp.toPx()), Offset(mid, size.height - 3.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
    }
}

// ---- primaries -------------------------------------------------------------------------------------

internal class Primary(
    val label: String, val min: Float, val max: Float, val default: Float, val format: String,
    val get: (Grade) -> Float, val set: (Grade, Float) -> Grade,
)

private val primaries = listOf(
    Primary("Temp", -2000f, 2000f, 0f, "%.0f", { it.temp }, { g, v -> g.copy(temp = v) }),
    Primary("Tint", -100f, 100f, 0f, "%.1f", { it.tint }, { g, v -> g.copy(tint = v) }),
    Primary("Contrast", 0f, 2f, 1f, "%.3f", { it.contrast }, { g, v -> g.copy(contrast = v) }),
    Primary("Pivot", 0f, 1f, 0.435f, "%.3f", { it.pivot }, { g, v -> g.copy(pivot = v) }),
    Primary("Mid/Detail", -100f, 100f, 0f, "%.1f", { it.midDetail }, { g, v -> g.copy(midDetail = v) }),
    Primary("Color Boost", -100f, 100f, 0f, "%.1f", { it.colorBoost }, { g, v -> g.copy(colorBoost = v) }),
    Primary("Shadows", -100f, 100f, 0f, "%.1f", { it.shadows }, { g, v -> g.copy(shadows = v) }),
    Primary("Highlights", -100f, 100f, 0f, "%.1f", { it.highlights }, { g, v -> g.copy(highlights = v) }),
    Primary("Saturation", 0f, 100f, 50f, "%.1f", { it.saturation }, { g, v -> g.copy(saturation = v) }),
    Primary("Hue", 0f, 100f, 50f, "%.1f", { it.hue }, { g, v -> g.copy(hue = v) }),
    Primary("Lum Mix", 0f, 100f, 100f, "%.1f", { it.lumMix }, { g, v -> g.copy(lumMix = v) }),
    Primary("Correction mix", 0f, 1f, 1f, "%.2f", { it.correctionMix }, { g, v -> g.copy(correctionMix = v) }),
)

/** The primaries, grouped as they are used: balance, tone, colour; each value a slider over what it does. */
@Composable
private fun Primaries(vm: EditorViewModel, @Suppress("UNUSED_PARAMETER") height: androidx.compose.ui.unit.Dp) {
    val g = vm.gradeTarget?.clip?.grade ?: Grade()
    fun pr(label: String) = primaries.first { it.label == label }
    val tempTrack = Brush.horizontalGradient(listOf(Color(0xFF4F7BD9), Color(0xFF8A8A8A), Color(0xFFE0A84A)))
    val tintTrack = Brush.horizontalGradient(listOf(Color(0xFF4CC36A), Color(0xFF8A8A8A), Color(0xFFD052C8)))
    val satTrack = Brush.horizontalGradient(listOf(Color(0xFF7A7A7A), Color(0xFFE0544A)))
    val hueTrack = Brush.horizontalGradient((0..12).map { hueColour(it / 12f) })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)) {
        SectionCard("Balance", "White balance of the whole picture", g.temp != 0f || g.tint != 0f, onReset = { vm.gradeOnce { it.copy(temp = 0f, tint = 0f) } }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProSlider(vm, pr("Temp"), Modifier.weight(1f), track = tempTrack)
                ProSlider(vm, pr("Tint"), Modifier.weight(1f), track = tintTrack)
            }
        }
        SectionCard("Tone", "Contrast about the pivot, and the ends of the range", g.contrast != 1f || g.pivot != 0.435f || g.shadows != 0f || g.highlights != 0f || g.midDetail != 0f,
            onReset = { vm.gradeOnce { it.copy(contrast = 1f, pivot = 0.435f, shadows = 0f, highlights = 0f, midDetail = 0f) } }) {
            SliderGrid(vm, listOf(pr("Contrast"), pr("Pivot"), pr("Shadows"), pr("Highlights"), pr("Mid/Detail")))
        }
        SectionCard("Colour", "How much colour, which way round, and how the wheels' masters act", g.saturation != 50f || g.hue != 50f || g.colorBoost != 0f || g.lumMix != 100f,
            onReset = { vm.gradeOnce { it.copy(saturation = 50f, hue = 50f, colorBoost = 0f, lumMix = 100f) } }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProSlider(vm, pr("Saturation"), Modifier.weight(1f), track = satTrack)
                ProSlider(vm, pr("Hue"), Modifier.weight(1f), track = hueTrack)
            }
            SliderGrid(vm, listOf(pr("Color Boost"), pr("Lum Mix")))
        }
        SectionCard("Correction layer", "How strongly the whole correction applies", g.correctionMix != 1f, onReset = { vm.gradeOnce { it.copy(correctionMix = 1f) } }) {
            ProSlider(vm, pr("Correction mix"), Modifier.fillMaxWidth())
        }
    }
}

/**
 * One primary control: drag sideways anywhere on the card to change it (about two and a half card widths for
 * the full range, so it is precise), with a click as it passes its neutral value. Double tap resets.
 */
@Composable
internal fun JogCard(vm: EditorViewModel, p: Primary, modifier: Modifier) {
    val view = LocalView.current
    val g = vm.gradeTarget?.clip?.grade ?: Grade()
    val value = p.get(g)
    val vmNow by rememberUpdatedState(vm)
    var active by remember { mutableStateOf(false) }
    val changed = abs(value - p.default) > (p.max - p.min) * 0.0005f
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (active) Color(0xFF2A2017) else Color(0xFF1A1B21))
            .border(1.dp, if (active) Palette.Accent.copy(alpha = 0.7f) else Color(0xFF24262E), RoundedCornerShape(12.dp))
            .pointerInput(p.label) {
                var lastTapAt = 0L
                awaitEachGesture {
                    awaitFirstDown()
                    val v = vmNow
                    var free = p.get(v.gradeTarget?.clip?.grade ?: Grade())
                    var travel = 0f
                    var tx = 0f
                    var ty = 0f
                    var horizontal: Boolean? = null
                    var dragged = false
                    val ticks = Ticker(view, (p.max - p.min) / 100f, p.default)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val d = c.positionChange()
                        travel += abs(d.x) + abs(d.y)
                        tx += d.x
                        ty += d.y
                        // Decided once, from the whole movement after a deliberate distance: sideways is a change, up and
                        // down is left to the page's scroll. A touch alone never changes a value.
                        if (horizontal == null && travel > SLOP_DP * density) horizontal = abs(tx) >= abs(ty) * 1.2f
                        if (horizontal == true) {
                            if (!dragged) {
                                dragged = true
                                active = true
                                v.gradeStart()
                                ticks.start(free)
                            }
                            free = (free + d.x / (size.width * 2.5f) * (p.max - p.min)).coerceIn(p.min, p.max)
                            val inDetent = abs(free - p.default) < (p.max - p.min) * 0.008f
                            val out = if (inDetent) p.default else free
                            ticks.at(out)
                            v.setGrade { gr -> p.set(gr, out) }
                            c.consume()
                        } else if (horizontal == false) break
                    }
                    active = false
                    if (dragged) v.gradeEnd()
                    else if (travel < SLOP_DP * density) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt < 320) {
                            v.gradeOnce { gr -> p.set(gr, p.default) }
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            lastTapAt = 0L
                        } else lastTapAt = now
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(p.label, color = if (changed) Palette.Accent else Palette.TextDim, fontSize = 11.sp, maxLines = 1)
            Text(p.format.format(value), color = Palette.Text, fontSize = 16.sp, fontFamily = Mono, fontWeight = FontWeight.Medium)
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val t = (value - p.min) / (p.max - p.min)
                val z = (p.default - p.min) / (p.max - p.min)
                drawRoundRect(Color(0xFF2C2E37), size = size, cornerRadius = CornerRadius(size.height / 2))
                val a = min(t, z) * size.width
                val b = max(t, z) * size.width
                drawRoundRect(Palette.Accent, Offset(a, 0f), Size(max(b - a, 2.dp.toPx()), size.height), CornerRadius(size.height / 2))
                drawLine(Color.White.copy(alpha = 0.5f), Offset(z * size.width, -2.dp.toPx()), Offset(z * size.width, size.height + 2.dp.toPx()), 1.dp.toPx())
            }
        }
    }
}

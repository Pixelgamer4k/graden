package com.graden.editor.ui

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.animation.splineBasedDecay
import androidx.compose.animation.core.animateDecay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.graden.editor.EditorViewModel
import com.graden.editor.MIN_CLIP_US
import com.graden.editor.R
import com.graden.editor.WaveformCache
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val PanelBg = Color(0xFF1E1F25)
private val ChipBg = Color(0xFF2C2D34)
private val Presets = listOf(100_000L, 300_000L, 1_000_000L, 2_500_000L)

private enum class Grab { NONE, IN, OUT, POS }

/**
 * Trimming one clip on a page of its own. The whole source is laid out as a filmstrip (or waveform,
 * for sound) with the kept part framed; pull either handle, or drag inside the frame to look through
 * it. Drag a handle with the finger moved down below the strip for fine control, a tenth of the speed.
 * Edges snap to whole frames, and the arrows beside the time nudge the last edge touched by one frame.
 */
@OptIn(UnstableApi::class)
@Composable
fun TrimPage(vm: EditorViewModel) {
    val t = vm.trimSession ?: return
    val a = t.asset
    var lastEdge by remember { mutableStateOf(Grab.OUT) }
    var fine by remember { mutableStateOf(false) }
    BackHandler { vm.closeTrim() }
    LaunchedEffect(vm.trimPlaying) {
        while (vm.trimPlaying) withFrameNanos { vm.trimTick() }
    }
    val speedMode = vm.trimBySpeed
    // Which value is being typed in, if any.
    var editing by remember { mutableStateOf<Edit?>(null) }
    val accent = if (speedMode) SpeedBlue else Palette.Accent

    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null) {}
            .systemBarsPadding()
    ) {
        // Top bar: cancel, what is being trimmed, done.
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.X, "Cancel", tint = Palette.Text, modifier = Modifier.size(40.dp).clip(CircleShape).clickable { vm.closeTrim() }.padding(10.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (speedMode) "Speed" else "Trim", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(a.name, color = Palette.TextDim, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            // Undo and redo for what is done on this page only.
            Icon(
                Lucide.Undo, "Undo trim step", tint = if (vm.canTrimUndo) Palette.Text else Palette.Text.copy(alpha = 0.3f),
                modifier = Modifier.size(38.dp).clip(CircleShape).clickable(enabled = vm.canTrimUndo) { vm.trimUndo() }.padding(9.dp),
            )
            Icon(
                Lucide.Redo, "Redo trim step", tint = if (vm.canTrimRedo) Palette.Text else Palette.Text.copy(alpha = 0.3f),
                modifier = Modifier.padding(end = 6.dp).size(38.dp).clip(CircleShape).clickable(enabled = vm.canTrimRedo) { vm.trimRedo() }.padding(9.dp),
            )
            Text(
                "Done", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(accent).clickable { vm.applyTrim() }.padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }

        // Preview, with the moment shown and the speed (when not 1×) over the picture.
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            if (a.isAudio) Icon(Lucide.Music, null, tint = Palette.AudioLabel, modifier = Modifier.size(56.dp))
            else AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.preview_player, null) as PlayerView).apply { player = vm.trimPlayer } },
            )
            Row(Modifier.align(Alignment.BottomStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Overlay(clock(vm.trimPos - vm.trimIn) + "  /  " + clock(vm.trimOut - vm.trimIn))
                if (vm.trimSpeed != 1f) Overlay(speedLabel(vm.trimSpeed), SpeedBlue)
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF202128), Color(0xFF17181D)))).padding(top = 14.dp, bottom = 6.dp)
        ) {
            // Mode switch, and which clip of the lane this is.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Segmented(speedMode) { vm.trimBySpeed = it }
                Spacer(Modifier.weight(1f))
                val (place, count) = vm.trimPlace()
                if (count > 1) Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(ChipBg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.ChevronsLeft, "Previous clip", tint = Palette.Text, modifier = Modifier.size(32.dp).clip(CircleShape).clickable { vm.trimSwitch(toNext = false) }.padding(8.dp))
                    Text("$place / $count", color = Palette.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Icon(Lucide.ChevronsRight, "Next clip", tint = Palette.Text, modifier = Modifier.size(32.dp).clip(CircleShape).clickable { vm.trimSwitch(toNext = true) }.padding(8.dp))
                }
            }
            Spacer(Modifier.height(14.dp))

            // The three numbers that matter: where it starts in the file, how long it lasts, where it ends.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Readout("IN", clock(vm.trimIn), Modifier.weight(1f), lit = lastEdge == Grab.IN)
                Readout(
                    if (speedMode) "LENGTH · ${speedLabel(vm.trimSpeed)}" else "LENGTH", secs(vm.trimLengthUs), Modifier.weight(1.2f), big = true, color = accent,
                    editable = true, onClick = { editing = Edit.LENGTH },
                )
                Readout("OUT", clock(vm.trimOut), Modifier.weight(1f), lit = lastEdge == Grab.OUT)
            }
            Spacer(Modifier.height(12.dp))

            // Where the clip sits in its whole file.
            Canvas(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 16.dp)) {
                val w = size.width
                drawRoundRect(Color(0xFF2C2D34), cornerRadius = CornerRadius(2.dp.toPx()))
                val l = vm.trimIn / a.durationUs.toFloat() * w
                val r = vm.trimOut / a.durationUs.toFloat() * w
                drawRoundRect(accent.copy(alpha = 0.85f), Offset(l, 0f), Size(max(r - l, 2.dp.toPx()), size.height), CornerRadius(2.dp.toPx()))
            }
            Spacer(Modifier.height(12.dp))

            LaneStrip(vm, onEdge = { lastEdge = it }, onFine = { fine = it }, fine = fine)

            // Transport: a frame back, play the kept part, a frame on (nudging the last edge touched).
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                StepButton("−1f") {
                    if (lastEdge == Grab.IN) vm.setTrimIn(vm.trimIn - vm.trimFrameUs()) else vm.setTrimOut(vm.trimOut - vm.trimFrameUs())
                    vm.trimSettle()
                    vm.trimMark()
                }
                Spacer(Modifier.width(18.dp))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(accent).clickable { vm.trimTogglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (vm.trimPlaying) Lucide.Pause else Lucide.Play, if (vm.trimPlaying) "Pause" else "Play the kept part", tint = Color(0xFF15161C),
                        modifier = Modifier.padding(start = if (vm.trimPlaying) 0.dp else 2.dp).size(20.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                StepButton("+1f") {
                    if (lastEdge == Grab.IN) vm.setTrimIn(vm.trimIn + vm.trimFrameUs()) else vm.setTrimOut(vm.trimOut + vm.trimFrameUs())
                    vm.trimSettle()
                    vm.trimMark()
                }
            }
            Text(
                "Frame steps move the ${if (lastEdge == Grab.IN) "start" else "end"}",
                color = Palette.TextDim.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
            )
            Spacer(Modifier.height(14.dp))

            // What each side offers, crossfading between them.
            androidx.compose.animation.AnimatedContent(
                speedMode, label = "mode",
                transitionSpec = { androidx.compose.animation.fadeIn(tween(160)) togetherWith androidx.compose.animation.fadeOut(tween(120)) },
            ) { speed ->
                if (speed) Column {
                    // The speed itself, tap to type it.
                    Row(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF26272E))
                        .clickable { editing = Edit.SPEED }.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(speedLabel(vm.trimSpeed), color = SpeedBlue, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        Icon(Lucide.Pencil, "Type a speed", tint = Palette.TextDim, modifier = Modifier.size(13.dp))
                    }
                    SpeedRuler(vm.trimSpeed, onDone = { vm.trimMark() }) { vm.changeTrimSpeed(it) }
                    Spacer(Modifier.height(10.dp))
                    ChipRow("Speed") {
                        for (v in listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 4f, 8f)) Chip(speedLabel(v), vm.trimSpeed == v, SpeedBlue) { vm.changeTrimSpeed(v); vm.trimMark() }
                    }
                    ChipRow("Fit to") {
                        for (p in listOf(1_000_000L, 2_500_000L, 5_000_000L, 10_000_000L, 15_000_000L, 30_000_000L)) {
                            Chip(secsShort(p), vm.trimLengthUs == p, SpeedBlue, enabled = p <= vm.trimRoomRight()) { vm.setTrimLengthBySpeed(p); vm.trimMark() }
                        }
                    }
                    Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Info, null, tint = Palette.TextDim, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pitch is kept. Smooth slow motion (frame blending, optical flow) is coming.", color = Palette.TextDim, fontSize = 10.sp)
                    }
                } else Column {
                    ChipRow("Length") {
                        val full = min(a.durationUs, vm.trimRoomRight())
                        Chip("Original", vm.trimOut - vm.trimIn == a.durationUs && vm.trimIn == 0L && vm.trimSpeed == 1f, Palette.Accent) { vm.resetTrim(); vm.trimMark() }
                        for (p in Presets + listOf(5_000_000L, 10_000_000L)) Chip(secsShort(p), vm.trimLengthUs == p, Palette.Accent, enabled = p <= full && p >= MIN_CLIP_US) { vm.setTrimDuration(p); vm.trimMark() }
                    }
                    Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Info, null, tint = Palette.TextDim, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Hold a handle and slide below the strip for frame-accurate control.", color = Palette.TextDim, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    editing?.let { e -> NumberEntry(e, vm) { editing = null } }
}

private enum class Edit { LENGTH, SPEED }

private val SpeedBlue = Color(0xFF7FA8FF)

/**
 * Typing an exact value. Length takes seconds ("2.5", "1:05.2" for a minute and five); speed takes a
 * multiple ("0.8", "2x"). Out-of-range values are brought within what the clip allows.
 */
@Composable
private fun NumberEntry(edit: Edit, vm: EditorViewModel, onDone: () -> Unit) {
    val start = if (edit == Edit.LENGTH) "%.2f".format(vm.trimLengthUs / 1e6) else "%.2f".format(vm.trimSpeed).trimEnd('0').trimEnd('.')
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(start, androidx.compose.ui.text.TextRange(0, start.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun parsed(): Float? {
        val raw = text.text.trim().lowercase().removeSuffix("x").removeSuffix("×").removeSuffix("s").replace(',', '.')
        return if (":" in raw) {
            val (m, sec) = raw.split(":", limit = 2)
            val mm = m.toFloatOrNull() ?: return null
            val ss = sec.toFloatOrNull() ?: return null
            mm * 60f + ss
        } else raw.toFloatOrNull()
    }
    fun commit() {
        val v = parsed()
        if (v != null && v > 0f) {
            if (edit == Edit.SPEED) vm.changeTrimSpeed(v.coerceIn(0.1f, 10f))
            else if (vm.trimBySpeed) vm.setTrimLengthBySpeed((v * 1e6).toLong())
            else vm.setTrimDuration((v * 1e6).toLong())
            vm.trimMark()
        }
        onDone()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        containerColor = Color(0xFF1E1F25),
        title = { Text(if (edit == Edit.LENGTH) "Length" else "Speed", color = Palette.Text) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF26272E)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        text, { text = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.Text, fontSize = 22.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(if (edit == Edit.SPEED || vm.trimBySpeed) SpeedBlue else Palette.Accent),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                    Text(if (edit == Edit.LENGTH) "s" else "×", color = Palette.TextDim, fontSize = 18.sp)
                }
                Text(
                    if (edit == Edit.LENGTH) (if (vm.trimBySpeed) "The clip plays faster or slower to last this long." else "The end moves to make it this long.")
                    else "From 0.1× to 10×. Pitch is kept.",
                    color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
                )
                if (parsed() == null && text.text.isNotBlank()) Text("That isn't a number", color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(::commit) { Text("Set", color = if (edit == Edit.SPEED || vm.trimBySpeed) SpeedBlue else Palette.Accent) } },
        dismissButton = { androidx.compose.material3.TextButton(onDone) { Text("Cancel", color = Palette.Text) } },
    )
}

@Composable
private fun Overlay(text: String, color: Color = Palette.Text) {
    Text(
        text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB314151A)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Cut or Speed, with an indicator that slides between them. */
@Composable
private fun Segmented(speed: Boolean, onChange: (Boolean) -> Unit) {
    val segW = 78.dp
    val offset by androidx.compose.animation.core.animateDpAsState(if (speed) segW else 0.dp, spring(0.8f, 500f), label = "seg")
    val color by androidx.compose.animation.animateColorAsState(if (speed) SpeedBlue else Palette.Accent, label = "segColor")
    Box(Modifier.width(segW * 2 + 6.dp).height(34.dp).clip(RoundedCornerShape(17.dp)).background(ChipBg).padding(3.dp)) {
        Box(Modifier.offset(x = offset).width(segW).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(color))
        Row(Modifier.fillMaxSize()) {
            for ((label, v) in listOf("Cut" to false, "Speed" to true)) Box(
                Modifier.width(segW).fillMaxHeight().clip(RoundedCornerShape(14.dp)).clickable { onChange(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (speed == v) Color(0xFF15161C) else Palette.Text, fontSize = 13.sp, fontWeight = if (speed == v) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun Readout(
    label: String, value: String, modifier: Modifier, big: Boolean = false, lit: Boolean = false, color: Color = Palette.Text,
    editable: Boolean = false, onClick: () -> Unit = {},
) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF26272E)).clickable(enabled = editable, onClick = onClick)
            .border(1.dp, if (lit) Color.White.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(12.dp)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Palette.TextDim, fontSize = 9.sp, letterSpacing = 0.8.sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = color, fontSize = if (big) 16.sp else 13.sp, fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal, fontFamily = FontFamily.Monospace, maxLines = 1)
            // A tappable value says so with a small pencil.
            if (editable) Icon(Lucide.Pencil, null, tint = Palette.TextDim, modifier = Modifier.padding(start = 5.dp).size(11.dp))
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).width(52.dp).clip(RoundedCornerShape(16.dp)).background(ChipBg).border(1.dp, Palette.Line, RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Palette.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = Palette.TextDim, fontSize = 9.sp, letterSpacing = 0.8.sp, modifier = Modifier.width(64.dp).padding(start = 18.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, color = if (on) Color(0xFF15161C) else Palette.Text, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) color else ChipBg).clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f).padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

/**
 * The speed as a ruler from 0.1× to 10×, even per doubling, with ticks and labels at the usual speeds. Drag
 * anywhere along it; it clicks onto the labelled speeds with a light tick as they pass.
 */
@Composable
private fun SpeedRuler(speed: Float, onDone: () -> Unit, onChange: (Float) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val marks = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f, 10f)
    fun pos(v: Float) = ((kotlin.math.ln(v) - kotlin.math.ln(0.1f)) / (kotlin.math.ln(10f) - kotlin.math.ln(0.1f))).coerceIn(0f, 1f)
    fun valueAt(p: Float) = Math.exp(kotlin.math.ln(0.1) + p.coerceIn(0f, 1f) * (kotlin.math.ln(10.0) - kotlin.math.ln(0.1))).toFloat()
    val last = remember { floatArrayOf(speed) }
    Canvas(
        Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 26.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun set(x: Float) {
                    val v = snapSpeed(valueAt(x / size.width))
                    if (v != last[0] && marks.any { it == v }) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    last[0] = v
                    onChange(v)
                }
                set(down.position.x)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.pressed } ?: break
                    ch.consume()
                    set(ch.position.x)
                }
                onDone()
            }
        }
    ) {
        val w = size.width
        val y = 18.dp.toPx()
        drawRoundRect(Color(0xFF2C2D34), Offset(0f, y - 2.dp.toPx()), Size(w, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        val one = pos(1f) * w
        val here = pos(speed) * w
        // The stretch from normal speed to the chosen one is lit.
        drawRoundRect(SpeedBlue, Offset(min(one, here), y - 2.dp.toPx()), Size(abs(here - one), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        val labels = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.MONOSPACE
        }
        // Minor ticks every half doubling, labelled ticks at the usual speeds.
        var v = 0.1f
        while (v <= 10.01f) {
            val x = pos(v) * w
            drawLine(Color.White.copy(alpha = 0.18f), Offset(x, y + 6.dp.toPx()), Offset(x, y + 9.dp.toPx()), 1.dp.toPx())
            v *= 1.41421f
        }
        for (m in marks) {
            val x = pos(m) * w
            drawLine(Color.White.copy(alpha = 0.45f), Offset(x, y + 5.dp.toPx()), Offset(x, y + 12.dp.toPx()), 1.dp.toPx())
            labels.color = (if (abs(m - speed) < 0.001f) SpeedBlue else Palette.TextDim).toArgb()
            drawIntoCanvas { it.nativeCanvas.drawText(speedLabel(m), x, y + 26.dp.toPx(), labels) }
        }
        // The thumb: a small pill with the value.
        drawCircle(Color.Black.copy(alpha = 0.35f), 10.dp.toPx(), Offset(here, y + 1.dp.toPx()))
        drawCircle(Color.White, 9.dp.toPx(), Offset(here, y))
        drawCircle(SpeedBlue, 4.dp.toPx(), Offset(here, y))
    }
}

@Composable
private fun Nudge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(ChipBg).border(1.dp, Palette.Line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Palette.Text, modifier = Modifier.size(16.dp)) }
}

@Composable
private fun Preset(label: String, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(ChipBg).clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Palette.Accent else Palette.Text, fontSize = 13.sp, maxLines = 1, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
}

private fun speedLabel(v: Float) = if (v >= 1f && v % 1f == 0f) "${v.toInt()}×" else if (v >= 1f) "%.1f×".format(v) else "%.2f×".format(v).replace("0.", ".")

/** Pulls a slider value onto the common speeds when close. */
private fun snapSpeed(v: Float): Float {
    for (s in listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 8f)) if (kotlin.math.abs(v - s) / s < 0.04f) return s
    return (v * 100).roundToInt() / 100f
}

/** "101.79s" */
private fun secs(us: Long) = "%.2fs".format(us / 1e6)

/** "0.1s", "2.5s", "1s" */
private fun secsShort(us: Long): String {
    val s = us / 1e6
    return if (s % 1.0 == 0.0) "${s.toInt()}s" else "${s}s"
}

/** "01:41.79": minutes, seconds and hundredths of the source. */
private fun clock(us: Long): String {
    val cs = us / 10_000L
    return "%02d:%02d.%02d".format(cs / 6000, (cs / 100) % 60, cs % 100)
}


/**
 * The lane as a little timeline of its own: this clip framed, its neighbours dimmed, gaps dashed and timed.
 * Drag to scroll (with a fling), pinch to zoom in on a gap, tap another clip to trim that one instead. Pull an
 * edge by its handle tab: the edge follows the finger exactly, pushes neighbours when it reaches them, and the
 * view scrolls along when the finger nears either end. A drag that starts on the white line moves through the clip.
 */
@Composable
private fun LaneStrip(vm: EditorViewModel, onEdge: (Grab) -> Unit, onFine: (Boolean) -> Unit, fine: Boolean) {
    val t = vm.trimSession ?: return
    val a = t.asset
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val stripH = 58.dp
    // The view: timeline time at the strip's left edge, and pixels per microsecond of timeline.
    val viewStart = remember { Animatable(0f) }
    var scale by remember { mutableStateOf(0f) }
    var widthPx by remember { mutableStateOf(0f) }
    val decay = remember(density) { splineBasedDecay<Float>(density) }

    /** Centres the clip being trimmed; on first opening also chooses a scale that fits it to 60% of the width. */
    fun frameClip(animate: Boolean) {
        if (widthPx <= 0f) return
        val len = vm.trimLengthUs.coerceAtLeast(1L).toFloat()
        if (scale <= 0f) scale = 0.6f * widthPx / len
        val target = vm.trimStartUs + len / 2 - widthPx / 2 / scale
        scope.launch { if (animate) viewStart.animateTo(target, spring(0.85f, 300f)) else viewStart.snapTo(target) }
    }
    LaunchedEffect(t.orig.id, widthPx) { frameClip(animate = scale > 0f) }

    Canvas(
        Modifier.fillMaxWidth().height(stripH + 30.dp).padding(horizontal = 16.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(t.orig.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val sc0 = scale.coerceAtLeast(1e-9f)
                    fun xOf(us: Float) = (us - viewStart.value) * scale
                    fun usAt(x: Float) = viewStart.value + x / scale
                    val left = xOf(vm.trimStartUs.toFloat())
                    val right = xOf(vm.trimEndUs.toFloat())
                    val x = down.position.x
                    val tab = 16.dp.toPx()
                    val onIn = x >= left - tab - 8.dp.toPx() && x <= left + 6.dp.toPx()
                    val onOut = x >= right - 6.dp.toPx() && x <= right + tab + 8.dp.toPx()
                    val posX = left + (vm.trimPos - vm.trimIn) / vm.trimSpeed * scale
                    val onPos = abs(x - posX) < 14.dp.toPx() && x > left + 6.dp.toPx() && x < right - 6.dp.toPx()
                    val grab = when {
                        onIn && onOut -> if (abs(x - left) <= abs(x - right)) Grab.IN else Grab.OUT
                        onIn -> Grab.IN
                        onOut -> Grab.OUT
                        onPos -> Grab.POS
                        else -> Grab.NONE
                    }
                    if (vm.trimPlaying) vm.trimTogglePlay()
                    scope.launch { viewStart.stop() }
                    if (grab == Grab.IN || grab == Grab.OUT) {
                        onEdge(grab)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // The edge sits at timeline time edgeUs, which follows the finger (a tenth as fast in fine mode).
                        var edgeUs = if (grab == Grab.IN) vm.trimStartUs.toFloat() else vm.trimEndUs.toFloat()
                        val fixedUs = if (grab == Grab.IN) vm.trimEndUs else vm.trimStartUs
                        val meet = if (grab == Grab.OUT) t.next?.startUs else t.prev?.endUs?.takeIf { !t.onV0 }
                        var snapped = false
                        var pushing = false
                        var fingerX = x
                        var isFine = false
                        // Near either end the view scrolls along by itself, faster the closer the finger is.
                        val edgeScroll = scope.launch {
                            var last = 0L
                            while (true) {
                                withFrameNanos { now ->
                                    if (last != 0L) {
                                        val zone = 36.dp.toPx()
                                        val f = when {
                                            fingerX < zone -> -(zone - fingerX) / zone
                                            fingerX > w - zone -> (fingerX - (w - zone)) / zone
                                            else -> 0f
                                        }
                                        if (f != 0f) {
                                            val dUs = f * 900.dp.toPx() * (now - last) / 1e9f / scale
                                            scope.launch { viewStart.snapTo(viewStart.value + dUs) }
                                            edgeUs += dUs
                                        }
                                    }
                                    last = now
                                }
                            }
                        }
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.pressed } ?: break
                            ch.consume()
                            fingerX = ch.position.x
                            val f = ch.position.y > stripH.toPx() + 30.dp.toPx()
                            if (f != isFine) {
                                isFine = f
                                onFine(f)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            edgeUs += (ch.position.x - ch.previousPosition.x) / scale * (if (f) 0.1f else 1f)
                            var len = (if (grab == Grab.OUT) edgeUs - fixedUs else fixedUs - edgeUs).toLong()
                            // Click onto the neighbour; past it, push it along (with a firmer bump as it starts to move).
                            val meetLen = meet?.let { if (grab == Grab.OUT) it - fixedUs else fixedUs - it }
                            val near = meetLen != null && abs(len - meetLen) * scale < 10.dp.toPx()
                            if (near && !snapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            snapped = near
                            if (near) len = meetLen!!
                            val pushNow = meetLen != null && len > meetLen
                            if (pushNow && !pushing) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            pushing = pushNow
                            if (vm.trimBySpeed) vm.setTrimLengthBySpeed(len, fromLeft = grab == Grab.IN)
                            else if (grab == Grab.OUT) vm.setTrimOut(vm.trimIn + (len * vm.trimSpeed).toLong(), snap = !near)
                            else vm.setTrimIn(vm.trimOut - (len * vm.trimSpeed).toLong(), snap = !near)
                        }
                        edgeScroll.cancel()
                        onFine(false)
                        vm.trimSettle()
                        vm.trimMark()
                        return@awaitEachGesture
                    }
                    if (grab == Grab.POS) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.pressed } ?: break
                            ch.consume()
                            val localUs = usAt(ch.position.x) - vm.trimStartUs
                            vm.trimSeek((vm.trimIn + (localUs * vm.trimSpeed).toLong()).coerceIn(vm.trimIn, vm.trimOut), fast = true)
                        }
                        vm.trimSettle()
                        return@awaitEachGesture
                    }
                    // Scroll and zoom. Two fingers pinch the scale about their middle; one finger pans.
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var moved = 0f
                    var pinched = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            pinched = true
                            val zoom = ev.calculateZoom()
                            val centre = ev.calculateCentroid()
                            val pan = ev.calculatePan()
                            val focusUs = usAt(centre.x)
                            // Out far enough to see the whole lane, in close enough to see single frames.
                            val laneLen = vm.trimLaneNow().maxOfOrNull { it.endUs }?.coerceAtLeast(1L) ?: 1L
                            scale = (scale * zoom).coerceIn(0.4f * w / laneLen, w / 200_000f)
                            scope.launch { viewStart.snapTo(focusUs - centre.x / scale - pan.x / scale) }
                        } else {
                            val ch = pressed.first()
                            val dx = ch.position.x - ch.previousPosition.x
                            moved += abs(dx)
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            scope.launch { viewStart.snapTo(viewStart.value - dx / scale) }
                        }
                        ev.changes.forEach { it.consume() }
                    }
                    if (!pinched && moved < viewConfiguration.touchSlop) {
                        // A tap: on another clip, trim that one; inside this one, show that moment.
                        val atUs = usAt(x).toLong()
                        val other = vm.trimLaneNow().firstOrNull { it.id != t.orig.id && atUs >= it.startUs && atUs < it.endUs }
                        if (other != null) {
                            if (vm.trimSwitchTo(other.id)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (x > left && x < right) {
                            vm.trimSeek((vm.trimIn + ((atUs - vm.trimStartUs) * vm.trimSpeed).toLong()).coerceIn(vm.trimIn, vm.trimOut))
                        }
                        return@awaitEachGesture
                    }
                    if (!pinched) {
                        val v = -tracker.calculateVelocity().x / scale
                        if (abs(v) > 50f / sc0) scope.launch { viewStart.animateDecay(v, decay) }
                    }
                }
            }
    ) {
        val w = size.width
        val h = stripH.toPx()
        if (scale <= 0f) return@Canvas
        fun xOf(us: Long) = (us - viewStart.value) * scale
        val r = CornerRadius(6.dp.toPx())
        val lane = vm.trimLaneNow()
        val me = lane.firstOrNull { it.id == t.orig.id } ?: return@Canvas
        val x0 = xOf(me.startUs)
        val x1 = xOf(me.endUs)
        val inkGap = Color.White.copy(alpha = 0.4f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color(0xFFB9BCC6).toArgb(); textSize = 10.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
        }
        // Gaps between consecutive clips, dashed and timed.
        val sorted = lane.sortedBy { it.startUs }
        for (k in 0 until sorted.size - 1) {
            val gl = xOf(sorted[k].endUs)
            val gr = xOf(sorted[k + 1].startUs)
            if (gr - gl > 3.dp.toPx() && gr > 0f && gl < w) {
                drawLine(inkGap, Offset(gl, h / 2), Offset(gr, h / 2), 1.dp.toPx(), pathEffect = dash)
                if (gr - gl > 46.dp.toPx()) drawIntoCanvas {
                    it.nativeCanvas.drawText("gap " + secs(sorted[k + 1].startUs - sorted[k].endUs), (gl + gr) / 2, h / 2 - 7.dp.toPx(), label)
                }
            }
        }
        // Every clip of the lane: this one bright, the others dimmed.
        for (c in sorted) {
            val cx0 = xOf(c.startUs)
            val cx1 = xOf(c.endUs) - 2.dp.toPx()
            if (cx1 < 0f || cx0 > w) continue
            val ca = vm.assets[c.assetId] ?: continue
            val mine = c.id == me.id
            val shape = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(cx0, 0f, cx1, h, r)) }
            clipPath(shape) {
                drawRect(Color(0xFF16171C), Offset(cx0, 0f), Size(cx1 - cx0, h))
                if (ca.isAudio) {
                    val peaks = vm.waves.get(ca)
                    if (peaks != null) {
                        val step = 2.dp.toPx()
                        var xx = maxOf(cx0, 0f)
                        while (xx < minOf(cx1, w)) {
                            val src = c.srcAt(((xx - cx0) / scale).toLong())
                            val i = (src * WaveformCache.PEAKS_PER_SEC / 1_000_000L).toInt().coerceIn(0, peaks.size - 1)
                            val v = (peaks[i].toInt() and 0xFF) / 255f * h * 0.42f
                            drawLine(Palette.AudioLabel.copy(alpha = if (mine) 0.8f else 0.35f), Offset(xx, h / 2 - v), Offset(xx, h / 2 + v), step * 0.6f)
                            xx += step
                        }
                    }
                } else {
                    vm.thumbs.version
                    val tileW = h * (ca.width.toFloat() / ca.height).coerceIn(0.5f, 1.8f)
                    // Tiles sit on a grid tied to the source, so they hold still while an edge is pulled.
                    val stepSrc = (tileW / scale * c.speed).toLong().coerceIn(1L, Long.MAX_VALUE / 4)
                    var n = c.inUs / stepSrc
                    var guard = 0
                    while (guard++ < 200) {
                        val srcT = n * stepSrc
                        val tx = cx0 + (srcT - c.inUs) / c.speed * scale
                        if (tx > minOf(cx1, w)) break
                        val tw = stepSrc / c.speed * scale
                        if (tx + tw > maxOf(cx0, 0f)) {
                            val img = vm.thumbs.get(ca, minOf(srcT + stepSrc / 2, ca.durationUs - 1), vm.proxies[ca.id])
                            if (img != null) {
                                val dstA = tw / h
                                val srcA = img.width.toFloat() / img.height
                                val swi = if (dstA > srcA) img.width else maxOf(1, (img.height * dstA).toInt())
                                val shi = if (dstA > srcA) maxOf(1, (img.width / dstA).toInt()) else img.height
                                drawImage(
                                    img, IntOffset((img.width - swi) / 2, (img.height - shi) / 2), IntSize(swi, shi),
                                    IntOffset(tx.roundToInt(), 0), IntSize(ceil(tw).toInt() + 1, h.roundToInt()),
                                    alpha = if (mine) 1f else 0.35f, filterQuality = FilterQuality.Low,
                                )
                            }
                        }
                        n++
                    }
                }
            }
            if (!mine) {
                val pushed = (c.id == t.prev?.id && vm.trimPushing(prev = true)) || (c.id == t.next?.id && vm.trimPushing(prev = false))
                drawRoundRect(if (pushed) Palette.Accent else Color.White.copy(alpha = 0.22f), Offset(cx0, 0f), Size(cx1 - cx0, h), r, style = Stroke((if (pushed) 2f else 1f) * 1.dp.toPx()))
                if (c.speed != 1f && cx1 - cx0 > 40.dp.toPx()) drawIntoCanvas { it.nativeCanvas.drawText(speedLabel(c.speed), (cx0 + cx1) / 2, h - 6.dp.toPx(), label) }
            }
        }
        // This clip's frame and handles, as one piece.
        val hw = 16.dp.toPx()
        val bar = 3.dp.toPx()
        val frame = androidx.compose.ui.graphics.Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            addRoundRect(androidx.compose.ui.geometry.RoundRect(x0 - hw, -bar, x1 + hw, h + bar, CornerRadius(9.dp.toPx())))
            if (x1 - x0 > 8.dp.toPx()) addRoundRect(androidx.compose.ui.geometry.RoundRect(x0, 0f, x1, h, CornerRadius(4.dp.toPx())))
        }
        drawPath(frame, Color.Black.copy(alpha = 0.4f), style = Stroke(3.dp.toPx()))
        drawPath(frame, if (vm.trimBySpeed) Color(0xFF7FA8FF) else Palette.Accent)
        val ink = Color(0xFF241608)
        val cy = h / 2
        val a6 = 3.2.dp.toPx()
        val sw = 2.dp.toPx()
        drawLine(ink, Offset(x0 - hw / 2 + a6 / 2, cy - a6 * 1.5f), Offset(x0 - hw / 2 - a6 / 2, cy), sw, StrokeCap.Round)
        drawLine(ink, Offset(x0 - hw / 2 - a6 / 2, cy), Offset(x0 - hw / 2 + a6 / 2, cy + a6 * 1.5f), sw, StrokeCap.Round)
        drawLine(ink, Offset(x1 + hw / 2 - a6 / 2, cy - a6 * 1.5f), Offset(x1 + hw / 2 + a6 / 2, cy), sw, StrokeCap.Round)
        drawLine(ink, Offset(x1 + hw / 2 + a6 / 2, cy), Offset(x1 + hw / 2 - a6 / 2, cy + a6 * 1.5f), sw, StrokeCap.Round)
        val px = x0 + (vm.trimPos - vm.trimIn) / vm.trimSpeed * scale
        if (px in x0..x1) drawLine(Color.White, Offset(px, -2.dp.toPx()), Offset(px, h + 2.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        if (x1 - x0 > 60.dp.toPx()) drawIntoCanvas {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.White.toArgb(); textSize = 13.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
                setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())
            }
            it.nativeCanvas.drawText(secs(vm.trimLengthUs) + if (vm.trimSpeed != 1f) "  ·  ${speedLabel(vm.trimSpeed)}" else "", ((x0 + x1) / 2).coerceIn(40.dp.toPx(), w - 40.dp.toPx()), cy + 5.sp.toPx(), p)
        }
        if (fine) drawIntoCanvas {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Palette.Accent.toArgb(); textSize = 11.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
            }
            it.nativeCanvas.drawText("FINE  ·  1/10 speed", w / 2, h + 22.dp.toPx(), p)
        }
    }
    Text(
        "Drag to scroll · pinch to zoom · tap a clip to trim it",
        color = Palette.TextDim.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

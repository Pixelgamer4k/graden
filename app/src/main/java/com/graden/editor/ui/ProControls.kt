package com.graden.editor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.EditorViewModel
import com.graden.editor.Grade
import kotlin.math.abs
import kotlin.math.floor

/*
 * The control kit of the Color page: every palette is built from these, so they all feel the same under a finger.
 *
 *  - ProSlider: a full-width slider you drag anywhere on (sideways; up and down still scrolls the page), with a click
 *    at its neutral value, a light tick per step, double tap to reset and a tap on the number to type it.
 *  - RangeSlider and HueRange: two-handled ranges over the colours they choose from.
 *  - SectionCard: a titled group that folds away, with its reset.
 *  - Choice: an equal-width segmented choice.
 *
 * Sizes are in dp and the text never sets the layout's width, so nothing overlaps on any screen or font size.
 */

internal object Pro {
    val Text = Color(0xFFD5D7DE)
    val Dim = Color(0xFF878A96)
    val Faint = Color(0xFF4A4D57)
    val Card = Color(0xFF17181E)
    val CardHi = Color(0xFF1D1E25)
    val Line = Color(0xFF262830)
    val Track = Color(0xFF2B2D36)
    val Well = Color(0xFF0C0D11)
    val Mono = FontFamily.Monospace
}

/** How far a finger moves before a control decides what the gesture is. */
private const val SLOP = 8f

/** Light ticks every [step] while dragging, a firm click on reaching [neutral]. */
private class Haptics(private val view: android.view.View, private val step: Float, private val neutral: Float) {
    private var last = Int.MIN_VALUE
    private var lastAt = 0L
    private var wasNeutral = true
    fun start(v: Float) { last = floor((v - neutral) / step).toInt(); wasNeutral = abs(v - neutral) < step * 0.01f }
    fun at(v: Float) {
        val n0 = abs(v - neutral) < step * 0.01f
        if (n0 && !wasNeutral) { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); lastAt = android.os.SystemClock.uptimeMillis() }
        wasNeutral = n0
        val n = floor((v - neutral) / step).toInt()
        if (n != last) {
            last = n
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastAt > 24 && !n0) { lastAt = now; view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        }
    }
}

/**
 * The slider itself, for any value. [onStart] / [onChange] / [onEnd] bracket a drag (one undo step); [onReset] is a
 * double tap; [onType] a tap on the number. [track] colours the track (a gradient of what it chooses), else the
 * accent fills from the neutral value.
 */
@Composable
internal fun ProSliderRaw(
    label: String, value: Float, min: Float, max: Float, neutral: Float, format: String,
    onStart: () -> Unit, onChange: (Float) -> Unit, onEnd: () -> Unit, onReset: () -> Unit, onType: (() -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true, track: Brush? = null,
) {
    val view = LocalView.current
    val now by rememberUpdatedState(value)
    val start by rememberUpdatedState(onStart)
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    val reset by rememberUpdatedState(onReset)
    val type by rememberUpdatedState(onType)
    var active by remember { mutableStateOf(false) }
    // Fine: the finger moved well above or below the slider while dragging, which slows it to a quarter.
    var fine by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(if (active) 1f else 0f, spring(0.7f, 600f), label = "thumb")
    val changed = abs(value - neutral) > (max - min) * 0.0005f
    var valueRight by remember { mutableStateOf(0f) }
    Column(
        modifier.height(52.dp).graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .pointerInput(enabled, min, max) {
                if (!enabled) return@pointerInput
                var lastTap = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var free = now
                    var tx = 0f; var ty = 0f
                    var mode = 0 // 0 undecided, 1 dragging, -1 not ours
                    val ticks = Haptics(view, (max - min) / 100f, neutral)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val d = c.positionChange()
                        tx += d.x; ty += d.y
                        if (mode == 0 && abs(tx) + abs(ty) > SLOP * density) mode = if (abs(tx) >= abs(ty)) 1 else -1
                        if (mode == -1) break
                        if (mode == 1) {
                            if (!active) { active = true; start(); ticks.start(free) }
                            c.consume()
                            val f = abs(c.position.y - down.position.y) > 44 * density
                            if (f != fine) { fine = f; view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                            free = (free + d.x / size.width * (max - min) * (if (fine) 0.2f else 1f)).coerceIn(min, max)
                            val out = if (abs(free - neutral) < (max - min) * 0.01f) neutral else free
                            ticks.at(out)
                            change(out)
                        }
                    }
                    if (active) { active = false; fine = false; end() }
                    else if (mode == 0) {
                        val t = android.os.SystemClock.uptimeMillis()
                        if (t - lastTap < 300) { reset(); view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); lastTap = 0L }
                        else {
                            lastTap = t
                            // A single tap on the number types it.
                            if (down.position.x > valueRight - 90 * density && down.position.y < 26 * density) type?.invoke()
                        }
                    }
                }
            }
            .padding(horizontal = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (changed) Pro.Text else Pro.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (fine) Text("FINE", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Accent).padding(horizontal = 4.dp))
            Text(
                format.format(value), color = if (active) Palette.Accent else if (changed) Palette.Accent.copy(alpha = 0.9f) else Pro.Text,
                fontSize = 13.sp, fontFamily = Pro.Mono, maxLines = 1,
                modifier = Modifier.padding(start = 6.dp).onGloballyPositioned { valueRight = it.positionInParent().x + it.size.width },
            )
        }
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val y = size.height / 2
            val h = 4.dp.toPx()
            val t = ((value - min) / (max - min)).coerceIn(0f, 1f)
            val z = ((neutral - min) / (max - min)).coerceIn(0f, 1f)
            val pad = 9.dp.toPx()
            val w = size.width - 2 * pad
            if (track != null) drawRoundRect(track, Offset(pad, y - h / 2), Size(w, h), CornerRadius(h / 2))
            else {
                drawRoundRect(Pro.Track, Offset(pad, y - h / 2), Size(w, h), CornerRadius(h / 2))
                val a = pad + minOf(t, z) * w; val b = pad + maxOf(t, z) * w
                if (b - a > 0.5f) drawRoundRect(Palette.Accent, Offset(a, y - h / 2), Size(b - a, h), CornerRadius(h / 2))
            }
            // The neutral mark, then the thumb (bigger while held).
            if (z > 0.001f && z < 0.999f) drawLine(Color.White.copy(alpha = 0.35f), Offset(pad + z * w, y - 6.dp.toPx()), Offset(pad + z * w, y + 6.dp.toPx()), 1.dp.toPx())
            val tr = (7f + 3f * grow).dp.toPx()
            if (grow > 0f) drawCircle(Palette.Accent.copy(alpha = 0.22f * grow), tr + 7.dp.toPx() * grow, Offset(pad + t * w, y))
            drawCircle(Color.Black.copy(alpha = 0.5f), tr + 1.dp.toPx(), Offset(pad + t * w, y + 1.dp.toPx()))
            drawCircle(Color.White, tr, Offset(pad + t * w, y))
        }
    }
}

/** A slider for one value of the grade being worked on ([p] says which). Each drag is one undo step. */
@Composable
internal fun ProSlider(vm: EditorViewModel, p: Primary, modifier: Modifier = Modifier, enabled: Boolean = true, track: Brush? = null) {
    val g = vm.gradeTarget?.clip?.grade ?: Grade()
    var typing by remember { mutableStateOf(false) }
    ProSliderRaw(
        p.label, p.get(g), p.min, p.max, p.default, p.format,
        onStart = { vm.gradeStart() }, onChange = { v -> vm.setGrade { gr -> p.set(gr, v) } }, onEnd = { vm.gradeEnd() },
        onReset = { vm.gradeOnce { gr -> p.set(gr, p.default) } }, onType = { typing = true },
        modifier = modifier, enabled = enabled, track = track,
    )
    if (typing) NumberEntry(p.label, p.get(g), p.min, p.max, { typing = false }) { v -> vm.gradeOnce { gr -> p.set(gr, v) } }
}

/** Sliders two to a row (one on narrow screens), for a palette's group of values. */
@Composable
internal fun SliderGrid(vm: EditorViewModel, params: List<Primary>, columns: Int = 2, enabled: Boolean = true) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth < 300.dp) 1 else columns
        Column {
            for (row in params.chunked(cols)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (p in row) androidx.compose.runtime.key(p.label) { ProSlider(vm, p, Modifier.weight(1f), enabled) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Typing an exact value. */
@Composable
internal fun NumberEntry(label: String, value: Float, min: Float, max: Float, onDismiss: () -> Unit, onDone: (Float) -> Unit) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("%.3f".format(value).trimEnd('0').trimEnd('.').ifEmpty { "0" })) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1C23),
        title = { Text(label, color = Pro.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    text, { text = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Pro.Text, fontFamily = Pro.Mono, fontSize = 18.sp),
                )
                Text("From ${"%.2f".format(min)} to ${"%.2f".format(max)}", color = Pro.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            Text("Set", color = Palette.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                text.text.replace(',', '.').toFloatOrNull()?.let { onDone(it.coerceIn(min, max)) }
                onDismiss()
            }.padding(horizontal = 14.dp, vertical = 10.dp))
        },
        dismissButton = { Text("Cancel", color = Pro.Text, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 10.dp)) },
    )
}

/**
 * A range with two handles over a [track] of what it chooses (a brightness or saturation ramp): drag a handle, or the
 * band between them to move both. [soft] draws the soft edges outside the range.
 */
@Composable
internal fun RangeSlider(
    label: String, lo: Float, hi: Float, soft: Float, track: Brush, onStart: () -> Unit, onChange: (Float, Float) -> Unit, onEnd: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    val view = LocalView.current
    val loNow by rememberUpdatedState(lo)
    val hiNow by rememberUpdatedState(hi)
    val change by rememberUpdatedState(onChange)
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)
    var grab by remember { mutableStateOf(0) }
    Column(modifier.height(54.dp).graphicsLayer { alpha = if (enabled) 1f else 0.38f }) {
        Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Pro.Dim, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text("%.2f – %.2f".format(lo, hi), color = Pro.Text, fontSize = 12.sp, fontFamily = Pro.Mono, maxLines = 1)
        }
        Canvas(
            Modifier.fillMaxWidth().height(32.dp).pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pad = 10.dp.toPx(); val w = size.width - 2 * pad
                    fun at(x: Float) = ((x - pad) / w).coerceIn(0f, 1f)
                    val x0 = at(down.position.x)
                    var a = loNow; var b = hiNow
                    // The nearer handle, or the band between them.
                    grab = when {
                        abs(x0 - a) < 0.06f && abs(x0 - a) <= abs(x0 - b) -> 1
                        abs(x0 - b) < 0.06f -> 2
                        x0 > a && x0 < b -> 3
                        else -> if (abs(x0 - a) < abs(x0 - b)) 1 else 2
                    }
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val dx = c.positionChange().x / w
                        if (!moved && abs(c.position.x - down.position.x) > SLOP * density) { moved = true; start(); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                        if (!moved) continue
                        c.consume()
                        when (grab) {
                            1 -> a = (a + dx).coerceIn(0f, b - 0.01f)
                            2 -> b = (b + dx).coerceIn(a + 0.01f, 1f)
                            else -> { val d = dx.coerceIn(-a, 1f - b); a += d; b += d }
                        }
                        change(a, b)
                    }
                    if (moved) end()
                    grab = 0
                }
            },
        ) {
            val pad = 10.dp.toPx(); val w = size.width - 2 * pad
            val y = size.height / 2; val h = 8.dp.toPx()
            drawRoundRect(track, Offset(pad, y - h / 2), Size(w, h), CornerRadius(h / 2))
            // Outside the range dimmed; the soft edges as a fade.
            drawRect(Color.Black.copy(alpha = 0.55f), Offset(pad, y - h / 2), Size(maxOf(lo - soft, 0f) * w, h))
            drawRect(Color.Black.copy(alpha = 0.55f), Offset(pad + minOf(hi + soft, 1f) * w, y - h / 2), Size(maxOf(1f - hi - soft, 0f) * w, h))
            drawRect(Color.White.copy(alpha = 0.9f), Offset(pad + lo * w, y - h / 2 - 3.dp.toPx()), Size((hi - lo) * w, 2.dp.toPx()))
            for ((i, v) in listOf(lo, hi).withIndex()) {
                val x = pad + v * w
                val big = grab == i + 1 || grab == 3
                drawRoundRect(Color.Black.copy(alpha = 0.5f), Offset(x - 5.dp.toPx(), y - 11.dp.toPx() + 1.dp.toPx()), Size(10.dp.toPx(), 22.dp.toPx()), CornerRadius(4.dp.toPx()))
                drawRoundRect(if (big) Palette.Accent else Color.White, Offset(x - 5.dp.toPx(), y - 11.dp.toPx()), Size(10.dp.toPx(), 22.dp.toPx()), CornerRadius(4.dp.toPx()))
            }
        }
    }
}

/**
 * A hue range on the spectrum (which wraps round): drag the band to move it, its edges to widen or narrow it. [soft] is
 * shown as fading edges. Values are on the hue curves' axis (0 = red).
 */
@Composable
internal fun HueRange(centre: Float, width: Float, soft: Float, onStart: () -> Unit, onChange: (Float, Float) -> Unit, onEnd: () -> Unit, enabled: Boolean = true) {
    val view = LocalView.current
    val cNow by rememberUpdatedState(centre)
    val wNow by rememberUpdatedState(width)
    val change by rememberUpdatedState(onChange)
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)
    Column(Modifier.fillMaxWidth().height(58.dp).graphicsLayer { alpha = if (enabled) 1f else 0.38f }) {
        Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Hue", color = Pro.Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("centre %.0f°  ·  width %.0f°".format(centre * 360f, width * 720f), color = Pro.Text, fontSize = 12.sp, fontFamily = Pro.Mono, maxLines = 1)
        }
        Canvas(
            Modifier.fillMaxWidth().height(36.dp).pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pad = 10.dp.toPx(); val w = size.width - 2 * pad
                    val x0 = (down.position.x - pad) / w
                    var c = cNow; var hw = wNow
                    fun dist(a: Float, b: Float): Float { var d = a - b; d -= Math.round(d).toFloat(); return d }
                    val off = dist(x0, c)
                    // An edge when the finger is near one, else the band.
                    val edge = if (abs(abs(off) - hw) < 0.05f) (if (off > 0) 1 else -1) else 0
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        val dx = ch.positionChange().x / w
                        if (!moved && abs(ch.position.x - down.position.x) > SLOP * density) { moved = true; start(); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                        if (!moved) continue
                        ch.consume()
                        if (edge == 0) c = ((c + dx) % 1f + 1f) % 1f else hw = (hw + dx * edge).coerceIn(0.01f, 0.5f)
                        change(c, hw)
                    }
                    if (moved) end()
                }
            },
        ) {
            val pad = 10.dp.toPx(); val w = size.width - 2 * pad
            val y = size.height / 2; val h = 14.dp.toPx()
            drawRoundRect(Brush.horizontalGradient((0..24).map { hueColour(it / 24f) }, pad, pad + w), Offset(pad, y - h / 2), Size(w, h), CornerRadius(4.dp.toPx()))
            // Dim what is outside the selection (and its soft edges), drawn across the wrap.
            val steps = 120
            for (i in 0 until steps) {
                val x = i / steps.toFloat()
                var d = x - centre; d -= Math.round(d).toFloat()
                val ad = abs(d)
                val a = if (ad <= width) 0f else if (ad <= width + soft) (ad - width) / soft * 0.6f else 0.6f
                if (a > 0f) drawRect(Color.Black.copy(alpha = a), Offset(pad + x * w, y - h / 2), Size(w / steps + 1f, h))
            }
            for (k in -1..1) {
                val l = centre - width + k; val r = centre + width + k
                for (v in listOf(l, r)) if (v in 0f..1f) {
                    val x = pad + v * w
                    drawRoundRect(Color.White, Offset(x - 2.dp.toPx(), y - h / 2 - 4.dp.toPx()), Size(4.dp.toPx(), h + 8.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
                val cx = centre + k
                if (cx in 0f..1f) drawCircle(Color.White, 4.dp.toPx(), Offset(pad + cx * w, y + h / 2 + 7.dp.toPx()))
            }
        }
    }
}

/** The display colour of a hue on the hue curves' axis (0 = red), for bars and swatches. */
internal fun hueColour(x: Float, light: Float = 0.6f, chroma: Float = 0.32f): Color {
    val a = (x + com.graden.editor.Curves.HUE0) * 2 * Math.PI
    val cb = (Math.cos(a) * chroma).toFloat(); val cr = (Math.sin(a) * chroma).toFloat()
    return Color((light + 1.5748f * cr).coerceIn(0f, 1f), (light - 0.1873f * cb - 0.4681f * cr).coerceIn(0f, 1f), (light + 1.8556f * cb).coerceIn(0f, 1f))
}

/**
 * A titled group of a palette that folds away, with what it does underneath the title, a dot when it changes the
 * picture, and its reset.
 */
@Composable
internal fun SectionCard(
    title: String, note: String? = null, changed: Boolean = false, initiallyOpen: Boolean = true, onReset: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null, content: @Composable () -> Unit,
) {
    var open by remember(title) { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 0f else -90f, spring(0.8f, 500f), label = "fold")
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1B21), Color(0xFF15161B))))
            .border(1.dp, if (changed) Palette.Accent.copy(alpha = 0.22f) else Color(0xFF1F2028), RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.ChevronDown, null, tint = Pro.Dim, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn })
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Pro.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (changed) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Accent)) }
                }
                if (note != null && open) Text(note, color = Pro.Dim, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke(this)
            if (onReset != null) Icon(
                Lucide.RotateCcw, "Reset $title", tint = if (changed) Pro.Text else Pro.Faint,
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable(enabled = changed, onClick = onReset).padding(9.dp),
            )
        }
        AnimatedVisibility(open, enter = expandVertically(spring(0.9f, 500f)) + fadeIn(), exit = shrinkVertically(spring(0.9f, 600f)) + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) { content() }
        }
    }
}

/** An equal-width segmented choice; the chosen one lifts. */
@Composable
internal fun Choice(options: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onPick: (Int) -> Unit) {
    Row(
        modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(Pro.Well).border(1.dp, Pro.Line, RoundedCornerShape(10.dp))
            .padding(3.dp).graphicsLayer { alpha = if (enabled) 1f else 0.38f },
    ) {
        for ((i, o) in options.withIndex()) {
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF2E2418) else Color.Transparent)
                    .clickable(enabled = enabled) { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(o, color = if (on) Palette.Accent else Pro.Text, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

/** A labelled [Choice] row. */
@Composable
internal fun ChoiceRow(label: String, options: List<String>, selected: Int, enabled: Boolean = true, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Pro.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Choice(options, selected, enabled = enabled, onPick = onPick)
    }
}

/** An on/off pill. */
@Composable
internal fun ProToggle(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Color(0xFF2E2418) else Pro.CardHi)
            .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.6f) else Pro.Line, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Palette.Accent else Pro.Faint))
        Spacer(Modifier.width(7.dp))
        Text(label, color = if (on) Palette.Accent else Pro.Text, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/**
 * A palette's header: its name with its on switch and reset; underneath, what it does, and any extra switches on a row
 * of their own, so the title never has to give way.
 */
@Composable
internal fun PaletteHeader(title: String, note: String, on: Boolean?, changed: Boolean, onToggle: () -> Unit, onReset: () -> Unit, extra: (@Composable RowScope.() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp, top = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Pro.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (on != null) ProToggle(if (on) "On" else "Off", on, onClick = onToggle)
            Icon(Lucide.RotateCcw, "Reset $title", tint = if (changed) Pro.Text else Pro.Faint,
                modifier = Modifier.padding(start = 2.dp).size(38.dp).clip(CircleShape).clickable(enabled = changed, onClick = onReset).padding(10.dp))
        }
        Text(note, color = Pro.Dim, fontSize = 10.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
        if (extra != null) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { extra() }
    }
}

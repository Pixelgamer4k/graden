package com.graden.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.EditorViewModel
import com.graden.editor.EditorViewModel.FxSlot
import com.graden.editor.Look
import com.graden.editor.Transition
import com.graden.editor.Transitions
import kotlin.math.hypot

private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/**
 * The transition picker, sliding up from the bottom while the preview stays in view. Picking a
 * tile applies it and plays the cut once; the slider sets how long it lasts. The same sheet picks
 * a clip's opening or closing animation.
 */
@Composable
fun TransitionSheet(vm: EditorViewModel, modifier: Modifier = Modifier) {
    com.graden.editor.RecomposeProbe.hit("TransitionSheet")
    val target = vm.fxTarget
    AnimatedVisibility(
        target != null, modifier,
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
    ) {
        // Keep showing the last target while the sheet slides away.
        var last by remember { mutableStateOf(target) }
        if (target != null) last = target
        val t = last ?: return@AnimatedVisibility
        val current = vm.fxOf(t)
        var group by remember { mutableStateOf("All") }
        Column(
            Modifier.fillMaxWidth().clip(SheetShape).background(Color(0xFF16171D)).border(1.dp, Palette.Line, SheetShape)
                .pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (t.slot) {
                        FxSlot.TRANSITION -> "Transition"
                        FxSlot.IN -> "Opening animation"
                        FxSlot.OUT -> "Closing animation"
                    },
                    color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                if (t.slot == FxSlot.TRANSITION && current != null) Text(
                    "Apply to all cuts", color = Palette.Accent, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { vm.applyFxToAllCuts() }.padding(horizontal = 10.dp, vertical = 8.dp),
                )
                Box(Modifier.size(42.dp).clip(CircleShape).clickable { vm.closeFx() }, contentAlignment = Alignment.Center) {
                    Icon(Lucide.Check, "Done", tint = Palette.Text, modifier = Modifier.size(22.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (name in listOf("All") + Transitions.groups) Text(
                    name, color = if (group == name) Color.Black else Palette.TextDim, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (group == name) Palette.Accent else Palette.Surface)
                        .clickable { group = name }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            val shown = if (group == "All") Transitions.all else Transitions.all.filter { it.group == group }
            LazyVerticalGrid(
                GridCells.Fixed(4), Modifier.fillMaxWidth().height(196.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "none") {
                    Tile("None", current == null, onClick = { vm.setFx(null) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f), contentAlignment = Alignment.Center) {
                            Icon(Lucide.Ban, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                items(shown, key = { it.id }) { def ->
                    Tile(def.name, current?.type == def.id, onClick = { vm.setFx(def.id) }) {
                        Miniature(def, t.slot)
                    }
                }
            }
            // Sound: on by default; untick it to keep the sound cutting at the edit.
            if (t.slot == FxSlot.TRANSITION && current != null) Row(
                Modifier.padding(start = 12.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.setCrossfade(!current.crossfade) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (current.crossfade) Palette.Accent else Color.Transparent)
                        .border(1.5.dp, if (current.crossfade) Palette.Accent else Palette.TextDim, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (current.crossfade) Icon(Lucide.Check, null, tint = Color.Black, modifier = Modifier.size(13.dp)) }
                Spacer(Modifier.width(8.dp))
                Text("Crossfade sound", color = Palette.Text, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(if (current.crossfade) "sound blends with the picture" else "sound cuts at the edit", color = Palette.TextDim, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Duration", color = Palette.TextDim, fontSize = 12.sp)
                val max = vm.fxMaxUs(t)
                Slider(
                    value = (current?.durationUs ?: 500_000L).toFloat().coerceIn(100_000f, max.toFloat()),
                    onValueChange = { vm.setFxDuration(it.toLong()) },
                    onValueChangeFinished = { vm.previewFx() },
                    valueRange = 100_000f..max.toFloat().coerceAtLeast(100_001f),
                    enabled = current != null,
                    colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Line),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(
                    if (current == null) "–" else "%.1fs".format(current.durationUs / 1e6), color = Palette.Text, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.width(36.dp),
                )
            }
        }
    }
}

@Composable
private fun Tile(name: String, selected: Boolean, onClick: () -> Unit, picture: @Composable () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0E0F13))
                .border(if (selected) 2.dp else 1.dp, if (selected) Palette.Accent else Palette.Line, RoundedCornerShape(8.dp))
        ) { picture() }
        Spacer(Modifier.height(4.dp))
        Text(
            name, color = if (selected) Palette.Accent else Palette.TextDim, fontSize = 10.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

/** Which way a transition moves, shown as an arrow over its tile. */
private enum class Hint { NONE, LEFT, RIGHT, UP, DOWN, TL, TR, BL, BR, CW, CCW, GROW, SHRINK, OPEN_H, OPEN_V, OPEN_BOX, CLOSE_BOX, CLOSE_H, IRIS, FLIP_H, FLIP_V, SHAKE }

private fun hintOf(id: String): Hint = when (id) {
    "slide_left", "push_left", "whip_left" -> Hint.LEFT
    "slide_right", "push_right", "whip_right", "swing", "bounce_in" -> Hint.RIGHT
    "slide_up", "push_up", "rise", "stack", "float_up" -> Hint.UP
    "slide_down", "push_down", "drop" -> Hint.DOWN
    // Corner slides arrive from a corner, so the arrow points away from it.
    "corner_tl" -> Hint.BR
    "corner_tr" -> Hint.BL
    "corner_bl" -> Hint.TR
    "corner_br" -> Hint.TL
    "wipe_right" -> Hint.RIGHT
    "wipe_left" -> Hint.LEFT
    "wipe_down" -> Hint.DOWN
    "wipe_up" -> Hint.UP
    "split_h" -> Hint.OPEN_H
    "split_v" -> Hint.OPEN_V
    "box_open" -> Hint.OPEN_BOX
    "box_close" -> Hint.CLOSE_BOX
    "curtain_close" -> Hint.CLOSE_H
    "iris_open", "iris_close" -> Hint.IRIS
    "zoom_in", "pull_in", "pop", "soft_zoom" -> Hint.GROW
    "zoom_out", "pull_out" -> Hint.SHRINK
    "spin_cw", "whirl" -> Hint.CW
    "spin_ccw" -> Hint.CCW
    "flip_h" -> Hint.FLIP_H
    "flip_v" -> Hint.FLIP_V
    "shake" -> Hint.SHAKE
    else -> Hint.NONE
}

/**
 * A live miniature of the transition, looping: a grey night scene leaves and an orange day scene
 * arrives, each shaped by its half of the transition, with an arrow showing the movement.
 */
@Composable
private fun Miniature(def: Transition, slot: FxSlot) {
    val loop = rememberInfiniteTransition(label = def.id)
    val p by loop.animateFloat(
        0f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 1900
            0f at 350
            1f at 1450
        }),
        label = "progress",
    )
    Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
        val leaving = Look().also { def.out(it, p) }
        val arriving = Look().also { def.into(it, p) }
        clipRect {
            drawRect(def.backdrop ?: Color(0xFF0B0C10))
            def.backdrop?.let { drawRect(it) }
            val order = if (def.outOnTop) listOf(arriving to false, leaving to true) else listOf(leaving to true, arriving to false)
            for ((look, isLeaving) in order) {
                if (slot == FxSlot.IN && isLeaving) continue
                if (slot == FxSlot.OUT && !isLeaving) continue
                scene(look, isLeaving)
            }
            arrow(hintOf(def.id))
        }
    }
}

/** One picture of the miniature: a little landscape, so movement and turning are easy to read. */
private fun DrawScope.scene(look: Look, night: Boolean) {
    val w = size.width
    val h = size.height
    withTransform({
        translate(look.tx * w, look.ty * h)
        rotate(look.rot)
        scale(look.sx, look.sy)
    }) {
        val a = look.alpha.coerceIn(0f, 1f)
        val mask = Path().apply {
            if (look.circle >= 0f) addOval(androidx.compose.ui.geometry.Rect(Offset(w / 2, h / 2), look.circle * hypot(w, h) / 2))
            else addRect(androidx.compose.ui.geometry.Rect(look.left * w, look.top * h, look.right * w, look.bottom * h))
        }
        clipPath(mask) {
            val sky = if (night) listOf(Color(0xFF39404F), Color(0xFF262B36)) else listOf(Color(0xFFF6A04D), Color(0xFFE0702A))
            drawRect(Brush.verticalGradient(sky), alpha = a)
            drawCircle(if (night) Color(0xFFD9DEE8) else Color(0xFFFFF1C9), radius = h * 0.11f, center = Offset(w * 0.73f, h * 0.3f), alpha = a)
            val far = Path().apply { moveTo(0f, h); lineTo(w * 0.28f, h * 0.48f); lineTo(w * 0.55f, h); close() }
            val near = Path().apply { moveTo(w * 0.32f, h); lineTo(w * 0.66f, h * 0.56f); lineTo(w, h * 0.86f); lineTo(w, h); close() }
            drawPath(far, if (night) Color(0xFF1C2029) else Color(0xFFB2541C), alpha = a)
            drawPath(near, if (night) Color(0xFF151820) else Color(0xFF8E3F14), alpha = a)
        }
    }
}

private fun DrawScope.arrow(hint: Hint) {
    if (hint == Hint.NONE) return
    val ink = Color.White.copy(alpha = 0.92f)
    val shadow = Color.Black.copy(alpha = 0.45f)
    val sw = 1.8.dp.toPx()
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.height * 0.26f
    val head = 4.5.dp.toPx()
    fun line(from: Offset, to: Offset, tip: Boolean = true) {
        for ((color, off) in listOf(shadow to 0.8.dp.toPx(), ink to 0f)) {
            val o = Offset(off, off)
            drawLine(color, from + o, to + o, sw, cap = StrokeCap.Round)
            if (tip) {
                val d = to - from
                val len = d.getDistance().coerceAtLeast(0.001f)
                val u = Offset(d.x / len, d.y / len)
                val n = Offset(-u.y, u.x)
                drawLine(color, to + o, to + o - u * head + n * head * 0.75f, sw, cap = StrokeCap.Round)
                drawLine(color, to + o, to + o - u * head - n * head * 0.75f, sw, cap = StrokeCap.Round)
            }
        }
    }
    fun curved(clockwise: Boolean) {
        val start = if (clockwise) -200f else 20f
        val sweep = if (clockwise) 250f else -250f
        val box = androidx.compose.ui.geometry.Rect(c, r)
        for ((color, off) in listOf(shadow to 0.8.dp.toPx(), ink to 0f)) {
            drawArc(color, start, sweep, false, box.topLeft + Offset(off, off), box.size, style = Stroke(sw, cap = StrokeCap.Round))
        }
        val endAngle = Math.toRadians((start + sweep).toDouble())
        val end = Offset(c.x + r * kotlin.math.cos(endAngle).toFloat(), c.y + r * kotlin.math.sin(endAngle).toFloat())
        // The tangent at the end of the arc, so the arrowhead follows the turn.
        val dir = if (clockwise) 1f else -1f
        val tangent = Offset(-kotlin.math.sin(endAngle).toFloat() * dir, kotlin.math.cos(endAngle).toFloat() * dir)
        line(end - tangent * 0.01f, end, tip = true)
    }
    val s = r * 1.15f
    when (hint) {
        Hint.LEFT -> line(c + Offset(s, 0f), c - Offset(s, 0f))
        Hint.RIGHT -> line(c - Offset(s, 0f), c + Offset(s, 0f))
        Hint.UP -> line(c + Offset(0f, r), c - Offset(0f, r))
        Hint.DOWN -> line(c - Offset(0f, r), c + Offset(0f, r))
        Hint.TL -> line(c + Offset(r, r * 0.8f), c - Offset(r, r * 0.8f))
        Hint.TR -> line(c + Offset(-r, r * 0.8f), c + Offset(r, -r * 0.8f))
        Hint.BL -> line(c + Offset(r, -r * 0.8f), c + Offset(-r, r * 0.8f))
        Hint.BR -> line(c - Offset(r, r * 0.8f), c + Offset(r, r * 0.8f))
        Hint.CW -> curved(true)
        Hint.CCW -> curved(false)
        Hint.GROW, Hint.SHRINK -> {
            for ((dx, dy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                val near = c + Offset(dx * r * 0.35f, dy * r * 0.3f)
                val far = c + Offset(dx * r * 1.2f, dy * r * 0.95f)
                if (hint == Hint.GROW) line(near, far) else line(far, near)
            }
        }
        Hint.OPEN_H -> { line(c - Offset(r * 0.2f, 0f), c - Offset(s, 0f)); line(c + Offset(r * 0.2f, 0f), c + Offset(s, 0f)) }
        Hint.CLOSE_H -> { line(c - Offset(s, 0f), c - Offset(r * 0.2f, 0f)); line(c + Offset(s, 0f), c + Offset(r * 0.2f, 0f)) }
        Hint.OPEN_V -> { line(c - Offset(0f, r * 0.2f), c - Offset(0f, r)); line(c + Offset(0f, r * 0.2f), c + Offset(0f, r)) }
        Hint.OPEN_BOX, Hint.CLOSE_BOX -> {
            for ((dx, dy) in listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)) {
                val near = c + Offset(dx * r * 0.25f, dy * r * 0.2f)
                val far = c + Offset(dx * s, dy * r)
                if (hint == Hint.OPEN_BOX) line(near, far) else line(far, near)
            }
        }
        Hint.IRIS -> {
            drawCircle(shadow, r * 0.75f, c + Offset(0.8.dp.toPx(), 0.8.dp.toPx()), style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            drawCircle(ink, r * 0.75f, c, style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        }
        Hint.FLIP_H -> line(c - Offset(s, 0f), c + Offset(s, 0f)).also { line(c + Offset(s, 0f), c - Offset(s, 0f)) }
        Hint.FLIP_V -> line(c - Offset(0f, r), c + Offset(0f, r)).also { line(c + Offset(0f, r), c - Offset(0f, r)) }
        Hint.SHAKE -> {
            val zig = Path().apply {
                moveTo(c.x - s, c.y)
                var x = c.x - s
                var up = true
                while (x < c.x + s) {
                    x += s / 3
                    lineTo(x, c.y + if (up) -r * 0.45f else r * 0.45f)
                    up = !up
                }
            }
            drawPath(zig, shadow, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(zig, ink, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Hint.NONE -> Unit
    }
}

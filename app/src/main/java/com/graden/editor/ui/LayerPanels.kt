package com.graden.editor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.DeflickerLayer
import com.graden.editor.DetailLayer
import com.graden.editor.EditorViewModel
import com.graden.editor.FilmLayer
import com.graden.editor.Grade
import com.graden.editor.LayerPack
import com.graden.editor.LensLayer
import com.graden.editor.LimiterLayer
import com.graden.editor.LogLayer
import com.graden.editor.MixerLayer
import com.graden.editor.NoiseLayer
import com.graden.editor.Qualifier
import com.graden.editor.Secondary
import com.graden.editor.SliceLayer
import com.graden.editor.WarperLayer
import com.graden.editor.Wheel
import com.graden.editor.Window
import com.graden.editor.ZonesLayer
import com.graden.editor.HdrZone
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The panels of the layers beyond the colour space transform and the LUTs. Each is a header (name, on switch, reset)
 * over jog cards: drag sideways on a card to change it, double tap to reset, as on the Primaries palette.
 */

private val PTxt = Color(0xFFC9CBD3)
private val PDim = Color(0xFF7D808C)
private val PCard = Color(0xFF1A1B21)
private val PLine = Color(0xFF262830)
private val PWell = Color(0xFF0B0C10)

/** A grid of jog cards, [cols] across. */
@Composable
private fun ParamGrid(vm: EditorViewModel, params: List<Primary>, cols: Int = 3, cardH: Dp = 58.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in params.chunked(cols)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in row) JogCard(vm, p, Modifier.weight(1f).height(cardH))
            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A layer's header: its name and what it does, an on switch, and a reset. */
@Composable
private fun LayerHeader(title: String, note: String, on: Boolean?, canReset: Boolean, onToggle: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PTxt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(note, color = PDim, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (on != null) Text(
            if (on) "On" else "Off", color = if (on) Color.Black else PTxt, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Palette.Accent else PCard).border(1.dp, PLine, RoundedCornerShape(14.dp))
                .clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 5.dp),
        )
        Text(
            "Reset", color = if (canReset) PTxt else PDim.copy(alpha = 0.5f), fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = canReset, onClick = onReset).padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SmallChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (on) Palette.Accent else PTxt, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFF2C2116) else PCard)
            .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.6f) else PLine, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)) { content() }
}

private fun g(vm: EditorViewModel) = vm.gradeTarget?.clip?.grade ?: Grade()

// ---- input layers ------------------------------------------------------------------------------



// ---- correction tabs ---------------------------------------------------------------------------



/** Colour warper: drag any point of the hue–saturation mesh; double tap a point to put it back. */
@Composable
internal fun WarperPanel(vm: EditorViewModel) {
    val w = g(vm).warper
    val view = LocalView.current
    val vmNow by rememberUpdatedState(vm)
    var sel by remember { mutableIntStateOf(-1) }
    Panel {
        PaletteHeader("Colour Warper", "Drag a point of the mesh: round for hue, out and in for saturation; double tap to put it back", w.on, w != WarperLayer(),
            { vm.gradeOnce { it.copy(warper = it.warper.copy(on = !it.warper.on)) } }, { vm.gradeOnce { it.copy(warper = WarperLayer()) } })
        BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            Canvas(
                Modifier.size(side).clip(CircleShape).background(PWell).pointerInput(Unit) {
                    var lastTap = 0L
                    var lastIdx = -1
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val v = vmNow
                        val r0 = size.width / 2f
                        val ww = v.gradeTarget?.clip?.grade?.warper ?: WarperLayer()
                        // The nearest mesh point to the finger.
                        var idx = -1; var best = 30.dp.toPx()
                        for (j in 0 until WarperLayer.RINGS) for (i in 0 until WarperLayer.HUES) {
                            val k = j * WarperLayer.HUES + i
                            val (px, py) = LayerPack.warpPoint(i.toFloat() / WarperLayer.HUES + ww.dHue[k], j + (ww.dSat[k] - 1f) * 1.2f)
                            val d = hypot(r0 + px * r0 * 0.92f - down.position.x, r0 - py * r0 * 0.92f - down.position.y)
                            if (d < best) { best = d; idx = k }
                        }
                        if (idx < 0) return@awaitEachGesture
                        sel = idx
                        v.gradeStart()
                        var moved = 0f
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            moved += abs(c.positionChange().x) + abs(c.positionChange().y)
                            c.consume()
                            if (moved < 6.dp.toPx()) continue
                            val x = (c.position.x - r0) / (r0 * 0.92f)
                            val y = -(c.position.y - r0) / (r0 * 0.92f)
                            val i = idx % WarperLayer.HUES; val j = idx / WarperLayer.HUES
                            val base = i.toFloat() / WarperLayer.HUES
                            var hueAt = (atan2(y, x) / (2 * PI)).toFloat() - com.graden.editor.Curves.HUE0
                            var dh = hueAt - base
                            dh -= Math.round(dh).toFloat()
                            dh = dh.coerceIn(-0.2f, 0.2f)
                            val ringPos = hypot(x, y) * WarperLayer.RINGS - 1f
                            val ds = (1f + (ringPos - j) / 1.2f).coerceIn(0f, 2f)
                            v.setGrade { gr -> gr.copy(warper = gr.warper.copy(dHue = gr.warper.dHue.copyOf().also { it[idx] = dh }, dSat = gr.warper.dSat.copyOf().also { it[idx] = ds })) }
                        }
                        if (moved < 6.dp.toPx()) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastTap < 320 && lastIdx == idx) {
                                v.setGrade { gr -> gr.copy(warper = gr.warper.copy(dHue = gr.warper.dHue.copyOf().also { it[idx] = 0f }, dSat = gr.warper.dSat.copyOf().also { it[idx] = 1f })) }
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                            lastTap = now; lastIdx = idx
                        }
                        v.gradeEnd()
                    }
                },
            ) {
                val r0 = size.width / 2f
                val c = Offset(r0, r0)
                // A faint hue wheel, rings and spokes, then the mesh as moved.
                drawCircle(Brush.sweepGradient((0..12).map { k -> hueSwatch(-(k / 12f) - com.graden.editor.Curves.HUE0 + 0.25f).copy(alpha = 0.35f) }, c), r0 * 0.92f, c)
                drawCircle(PWell.copy(alpha = 0.55f), r0 * 0.92f, c)
                for (j in 0 until WarperLayer.RINGS) drawCircle(Color.White.copy(alpha = 0.1f), r0 * 0.92f * (j + 1) / WarperLayer.RINGS, c, style = Stroke(1f))
                fun pt(i: Int, j: Int): Offset {
                    val k = j * WarperLayer.HUES + i
                    val (px, py) = LayerPack.warpPoint(i.toFloat() / WarperLayer.HUES + w.dHue[k], j + (w.dSat[k] - 1f) * 1.2f)
                    return Offset(r0 + px * r0 * 0.92f, r0 - py * r0 * 0.92f)
                }
                for (j in 0 until WarperLayer.RINGS) for (i in 0 until WarperLayer.HUES) {
                    val a = pt(i, j)
                    drawLine(Color.White.copy(alpha = 0.35f), a, pt((i + 1) % WarperLayer.HUES, j), 1.2f)
                    if (j + 1 < WarperLayer.RINGS) drawLine(Color.White.copy(alpha = 0.2f), a, pt(i, j + 1), 1f)
                }
                for (j in 0 until WarperLayer.RINGS) for (i in 0 until WarperLayer.HUES) {
                    val k = j * WarperLayer.HUES + i
                    val moved = w.dHue[k] != 0f || w.dSat[k] != 1f
                    drawCircle(if (k == sel) Palette.Accent else if (moved) Color.White else Color.White.copy(alpha = 0.6f), if (k == sel) 6.dp.toPx() else 3.5.dp.toPx(), pt(i, j))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        ParamGrid(vm, listOf(Primary("Mix", 0f, 1f, 1f, "%.2f", { it.warper.mix }, { gr, v -> gr.copy(warper = gr.warper.copy(mix = v)) })))
    }
}

/** The display colour of hue [x] on the curves' axis. */
private fun hueSwatch(x: Float): Color {
    val a = (x + com.graden.editor.Curves.HUE0) * 2 * PI
    val cb = (cos(a) * 0.3).toFloat(); val cr = (sin(a) * 0.3).toFloat()
    return Color((0.6f + 1.5748f * cr).coerceIn(0f, 1f), (0.6f - 0.1873f * cb - 0.4681f * cr).coerceIn(0f, 1f), (0.6f + 1.8556f * cb).coerceIn(0f, 1f))
}



// ---- secondaries ---------------------------------------------------------------------------------



@Composable
private fun SectionTitle(t: String) {
    Text(t.uppercase(), color = PDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(bottom = 6.dp))
}

// ---- look and output ---------------------------------------------------------------------------


// ---- a small colour pad -------------------------------------------------------------------------

/** A colour disc with a puck: drag to push colour; double tap to clear. [set] builds the change from (u, v). */
@Composable
internal fun DiscPad(vm: EditorViewModel, u: Float, v: Float, modifier: Modifier, set: (Float, Float) -> (Grade) -> Grade) {
    val vmNow by rememberUpdatedState(vm)
    val setNow by rememberUpdatedState(set)
    val uNow by rememberUpdatedState(u)
    val vNow by rememberUpdatedState(v)
    val view = LocalView.current
    Canvas(
        modifier.clip(CircleShape).pointerInput(Unit) {
            var lastTap = 0L
            awaitEachGesture {
                awaitFirstDown().consume()
                val m = vmNow
                var pu = uNow; var pv = vNow
                var moved = 0f
                m.gradeStart()
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    val d = c.positionChange()
                    c.consume()
                    moved += abs(d.x) + abs(d.y)
                    if (moved < 6.dp.toPx()) continue
                    // Half the speed of the finger, so small moves are precise.
                    pu = (pu + d.x / size.width).coerceIn(-1f, 1f)
                    pv = (pv - d.y / size.height).coerceIn(-1f, 1f)
                    val k = hypot(pu, pv)
                    if (k > 1f) { pu /= k; pv /= k }
                    m.setGrade(setNow(pu, pv))
                }
                if (moved < 6.dp.toPx()) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTap < 320) {
                        m.setGrade(setNow(0f, 0f))
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    lastTap = now
                }
                m.gradeEnd()
            }
        },
    ) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        // Blue to the right and red up, as the wheels.
        val ring = (0..12).map { k ->
            val a = -k / 12.0 * 2 * PI
            val cb = cos(a).toFloat() * 0.3f; val cr = sin(a).toFloat() * 0.3f
            Color((0.55f + 1.5748f * cr).coerceIn(0f, 1f), (0.55f - 0.1873f * cb - 0.4681f * cr).coerceIn(0f, 1f), (0.55f + 1.8556f * cb).coerceIn(0f, 1f))
        }
        drawCircle(Brush.sweepGradient(ring, c), r, c)
        drawCircle(Color(0xFF0D0E12).copy(alpha = 0.78f), r * 0.9f, c)
        drawLine(Color.White.copy(alpha = 0.12f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(Color.White.copy(alpha = 0.12f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        val p = Offset(c.x + u * r * 0.9f, c.y - v * r * 0.9f)
        drawCircle(Color.White, 7.dp.toPx(), p)
        drawCircle(Color.Black, 4.dp.toPx(), p)
    }
}

// ---- Resolve-style fields ------------------------------------------------------------------------





// ---- Motion Effects (noise reduction and motion blur) ---------------------------------------------


// ---- High Dynamic Range color wheels ---------------------------------------------------------------






// ---- Film Look ---------------------------------------------------------------------------------------


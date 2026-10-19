package com.graden.editor.ui

import androidx.compose.ui.draw.drawBehind

import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.ColorSpaces
import com.graden.editor.CurvePt
import com.graden.editor.Curves
import com.graden.editor.EditorViewModel
import com.graden.editor.Grade
import com.graden.editor.LutLayer
import com.graden.editor.Cst
import com.graden.editor.GradeTextures
import androidx.compose.ui.graphics.graphicsLayer
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The Color page's other palettes: curves you shape with a finger, the hue / saturation / luminance curves, the LUT
 * library, and colour management. They share one look with the wheels: dark wells, thin lines, the accent for what is
 * being touched.
 */

private val Txt = Color(0xFFC9CBD3)
private val Dim = Color(0xFF7D808C)
private val WellBg = Color(0xFF0B0C10)
private val Card = Color(0xFF1A1B21)
private val CardLine = Color(0xFF262830)
private val Grid = Color(0xFF1E2028)
private val RedC = Color(0xFFFF5A52)
private val GreenC = Color(0xFF52E07A)
private val BlueC = Color(0xFF5C8DFF)

// ---- tab strip -------------------------------------------------------------------------------------

/** The palettes under the scopes, as a row of tabs with a line under the chosen one. */
@Composable
internal fun PaletteTabs(items: List<String>, selected: Int, marks: List<Boolean> = emptyList(), onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((i, t) in items.withIndex()) {
            val on = i == selected
            Column(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }.padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t, color = if (on) Palette.Accent else Dim, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                    // A dot on palettes that are changing the picture.
                    if (marks.getOrNull(i) == true) { Spacer(Modifier.width(4.dp)); Box(Modifier.size(5.dp).clip(CircleShape).background(Palette.Accent.copy(alpha = if (on) 1f else 0.7f))) }
                }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.width(18.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (on) Palette.Accent else Color.Transparent))
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, dot: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFF2C2116) else Card)
            .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.6f) else CardLine, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (on) Palette.Accent else Txt, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) Txt else Dim.copy(alpha = 0.5f), fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

// ---- the curve editor ------------------------------------------------------------------------------

/**
 * One curve, shaped with a finger. Touch the line to add a point there and drag it; drag a point to move it; tap a
 * point to select it, double tap to remove it. The custom curves keep their two end points (they can move up and down);
 * hue curves wrap round. Every touch is one undo step.
 */
@Composable
private fun CurveEditor(
    vm: EditorViewModel,
    points: List<CurvePt>,
    periodic: Boolean,
    custom: Boolean,
    color: Color,
    others: List<Pair<List<CurvePt>, Color>>,
    background: DrawScope.() -> Unit,
    set: (Grade, List<CurvePt>) -> Grade,
    get: (Grade) -> List<CurvePt>,
    modifier: Modifier,
) {
    val view = LocalView.current
    val vmNow by rememberUpdatedState(vm)
    val setNow by rememberUpdatedState(set)
    val getNow by rememberUpdatedState(get)
    var selected by remember { mutableIntStateOf(-1) }
    fun flat(x: Float) = if (custom) x else 0.5f
    Canvas(
        modifier.clip(RoundedCornerShape(12.dp)).background(WellBg).border(1.dp, CardLine, RoundedCornerShape(12.dp))
            .pointerInput(periodic, custom) {
                var lastTapAt = 0L
                var lastTapIndex = -1
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val v = vmNow
                    val pad = 10.dp.toPx()
                    val w = size.width - 2 * pad
                    val h = size.height - 2 * pad
                    fun toX(px: Float) = ((px - pad) / w).coerceIn(0f, 1f)
                    fun toY(py: Float) = (1f - (py - pad) / h).coerceIn(0f, 1f)
                    var pts = getNow(v.gradeTarget?.clip?.grade ?: Grade()).toMutableList()
                    // The nearest point within reach of the finger, else a new one on the curve under it.
                    val reach = 26.dp.toPx()
                    var idx = -1
                    var best = reach
                    for ((i, p) in pts.withIndex()) {
                        val d = hypot(pad + p.x * w - down.position.x, pad + (1 - p.y) * h - down.position.y)
                        if (d < best) { best = d; idx = i }
                    }
                    val existing = idx >= 0
                    v.gradeStart()
                    if (!existing) {
                        val x = toX(down.position.x)
                        val y = Curves.eval(pts, x, periodic, flat(x)).coerceIn(0f, 1f)
                        pts.add(CurvePt(x, y))
                        pts.sortBy { it.x }
                        idx = pts.indexOfFirst { it.x == x && it.y == y }
                        v.setGrade { setNow(it, pts.toList()) }
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    selected = idx
                    var travel = 0f
                    val start = pts[idx]
                    var acc = Offset.Zero
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val d = c.positionChange()
                        c.consume()
                        travel += abs(d.x) + abs(d.y)
                        acc += d
                        if (existing && travel < 6.dp.toPx()) continue
                        val endPoint = custom && (idx == 0 || idx == pts.size - 1)
                        val lo = if (idx > 0) pts[idx - 1].x + 0.01f else 0f
                        val hi = if (idx < pts.size - 1) pts[idx + 1].x - 0.01f else 1f
                        val nx = if (endPoint) start.x else (start.x + acc.x / w).coerceIn(lo, hi)
                        val ny = (start.y - acc.y / h).coerceIn(0f, 1f)
                        pts[idx] = CurvePt(nx, ny)
                        v.setGrade { setNow(it, pts.toList()) }
                    }
                    if (existing && travel < 6.dp.toPx()) {
                        val now = android.os.SystemClock.uptimeMillis()
                        val removable = !(custom && (idx == 0 || idx == pts.size - 1))
                        if (now - lastTapAt < 320 && lastTapIndex == idx && removable) {
                            pts.removeAt(idx)
                            v.setGrade { setNow(it, pts.toList()) }
                            selected = -1
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            lastTapAt = 0L
                        } else {
                            lastTapAt = now
                            lastTapIndex = idx
                        }
                    }
                    v.gradeEnd()
                }
            },
    ) {
        val pad = 10.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        background()
        for (i in 1..3) {
            drawLine(Grid, Offset(pad + w * i / 4f, pad), Offset(pad + w * i / 4f, pad + h), 1f)
            drawLine(Grid, Offset(pad, pad + h * i / 4f), Offset(pad + w, pad + h * i / 4f), 1f)
        }
        // The neutral line: the diagonal for the custom curves, the middle for the others.
        if (custom) drawLine(Color(0xFF2E3039), Offset(pad, pad + h), Offset(pad + w, pad), 1.5f)
        else drawLine(Color(0xFF3A3D47), Offset(pad, pad + h / 2), Offset(pad + w, pad + h / 2), 1.5f)
        fun path(pts: List<CurvePt>): Path {
            val p = Path()
            for (i in 0..160) {
                val x = i / 160f
                val y = Curves.eval(pts, x, periodic, if (custom) x else 0.5f).coerceIn(0f, 1f)
                val px = pad + x * w; val py = pad + (1 - y) * h
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            return p
        }
        for ((pts, c) in others) if (pts != Curves.LINE) drawPath(path(pts), c.copy(alpha = 0.35f), style = Stroke(1.5.dp.toPx()))
        drawPath(path(points), color, style = Stroke(2.dp.toPx()))
        for ((i, p) in points.withIndex()) {
            val o = Offset(pad + p.x * w, pad + (1 - p.y) * h)
            if (i == selected) {
                drawCircle(Palette.Accent, 7.dp.toPx(), o)
                drawCircle(Color.Black, 3.dp.toPx(), o)
            } else {
                drawCircle(Color.Black, 6.dp.toPx(), o)
                drawCircle(color, 6.dp.toPx(), o, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** The display colour of a point on the hue axis, matching the shader's hue angle. */
private fun hueColor(x: Float, light: Float = 0.62f, chroma: Float = 0.3f): Color {
    val a = (x + Curves.HUE0) * 2 * PI
    val cb = (cos(a) * chroma).toFloat(); val cr = (sin(a) * chroma).toFloat()
    return Color(
        (light + 1.5748f * cr).coerceIn(0f, 1f),
        (light - 0.1873f * cb - 0.4681f * cr).coerceIn(0f, 1f),
        (light + 1.8556f * cb).coerceIn(0f, 1f),
    )
}

private val hueStops = (0..12).map { hueColor(it / 12f) }

// ---- Curves palette --------------------------------------------------------------------------------

@Composable
internal fun CurvesPalette(vm: EditorViewModel) {
    var ch by remember { mutableIntStateOf(0) }
    val g = vm.gradeTarget?.clip?.grade ?: Grade()
    val cv = g.curves
    val names = listOf("Y", "R", "G", "B")
    val colors = listOf(Color(0xFFE8E9EE), RedC, GreenC, BlueC)
    fun of(c: Curves, i: Int) = when (i) { 0 -> c.y; 1 -> c.r; 2 -> c.g; else -> c.b }
    fun with(c: Curves, i: Int, p: List<CurvePt>) = when (i) { 0 -> c.copy(y = p); 1 -> c.copy(r = p); 2 -> c.copy(g = p); else -> c.copy(b = p) }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..3) Chip(names[i], ch == i, dot = colors[i].takeIf { of(cv, i) != Curves.LINE }) { ch = i }
            Spacer(Modifier.weight(1f))
            SmallAction("Reset", enabled = of(cv, ch) != Curves.LINE) { vm.gradeOnce { it.copy(curves = with(it.curves, ch, Curves.LINE)) } }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.weight(1f)) {
            CurveEditor(
                vm, of(cv, ch), periodic = false, custom = true, color = colors[ch],
                others = (0..3).filter { it != ch }.map { of(cv, it) to colors[it] },
                background = {
                    // A faint ramp along the bottom: input brightness.
                    drawRect(Brush.horizontalGradient(listOf(Color.Black, colors[ch].copy(alpha = 0.55f))), Offset(10.dp.toPx(), size.height - 14.dp.toPx()), Size(size.width - 20.dp.toPx(), 3.dp.toPx()))
                },
                set = { gr, p -> gr.copy(curves = with(gr.curves, ch, p)) },
                get = { gr -> of(gr.curves, ch) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Text("Touch the line to add a point · drag to shape · double tap a point to remove", color = Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---- Hue / Sat / Lum curves ------------------------------------------------------------------------

private class HslMode(val label: String, val hueAxis: Boolean, val get: (Curves) -> List<CurvePt>, val set: (Curves, List<CurvePt>) -> Curves, val top: String, val bottom: String)

private val hslModes = listOf(
    HslMode("Hue vs Hue", true, { it.hueHue }, { c, p -> c.copy(hueHue = p) }, "+180°", "−180°"),
    HslMode("Hue vs Sat", true, { it.hueSat }, { c, p -> c.copy(hueSat = p) }, "×2", "×0"),
    HslMode("Hue vs Lum", true, { it.hueLum }, { c, p -> c.copy(hueLum = p) }, "Brighter", "Darker"),
    HslMode("Lum vs Sat", false, { it.lumSat }, { c, p -> c.copy(lumSat = p) }, "×2", "×0"),
    HslMode("Sat vs Sat", false, { it.satSat }, { c, p -> c.copy(satSat = p) }, "×2", "×0"),
)

@Composable
internal fun HslPalette(vm: EditorViewModel) {
    var mode by remember { mutableIntStateOf(0) }
    val m = hslModes[mode]
    val g = vm.gradeTarget?.clip?.grade ?: Grade()
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((i, h) in hslModes.withIndex()) Chip(h.label, i == mode, dot = Palette.Accent.takeIf { h.get(g.curves).isNotEmpty() }) { mode = i }
            SmallAction("Reset", enabled = m.get(g.curves).isNotEmpty()) { vm.gradeOnce { it.copy(curves = m.set(it.curves, emptyList())) } }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.weight(1f)) {
            Column(Modifier.fillMaxHeight().width(46.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(m.top, color = Dim, fontSize = 10.sp)
                Text(m.bottom, color = Dim, fontSize = 10.sp)
            }
            CurveEditor(
                vm, m.get(g.curves), periodic = m.hueAxis, custom = false, color = Color(0xFFE8E9EE), others = emptyList(),
                background = {
                    val pad = 10.dp.toPx()
                    val band = Brush.horizontalGradient(
                        when (mode) {
                            3 -> listOf(Color.Black, Color.White)
                            4 -> listOf(Color(0xFF8A8A8A), hueColor(0f, 0.55f, 0.32f))
                            else -> hueStops
                        },
                        startX = pad, endX = size.width - pad,
                    )
                    drawRect(band, Offset(pad, size.height - pad - 6.dp.toPx()), Size(size.width - 2 * pad, 6.dp.toPx()))
                    drawRect(band, Offset(pad, pad), Size(size.width - 2 * pad, size.height - 2 * pad), alpha = 0.07f)
                },
                set = { gr, p -> gr.copy(curves = m.set(gr.curves, p)) },
                get = { gr -> m.get(gr.curves) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

// ---- layer 3: the LUT stack ------------------------------------------------------------------------

/**
 * The clip's LUTs, applied top to bottom after the correction. Each can be switched off, weakened, moved, and told what
 * it converts to (a log-to-Rec.709 LUT hands on Rec.709), so the step into the output always starts from the right space.
 */
@Composable
internal fun LutPalette(vm: EditorViewModel) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importLut(uri) }
    remember { vm.refreshLuts(); 0 }
    val g = vm.gradeTarget?.clip?.grade ?: Grade()
    val stack = g.luts
    var sel by remember { mutableIntStateOf(0) }
    var library by remember { mutableStateOf(false) }
    var toDialog by remember { mutableStateOf(false) }
    val i = sel.coerceIn(0, (stack.size - 1).coerceAtLeast(0))
    fun update(f: (List<LutLayer>) -> List<LutLayer>) = vm.gradeOnce { it.copy(luts = f(it.luts)) }
    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1.15f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(WellBg).border(1.dp, CardLine, RoundedCornerShape(12.dp))) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LUTs, applied top to bottom", color = Dim, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = stack.size < GradeTextures.MAX_LUTS) { library = true }.padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Plus, null, tint = Palette.Accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Add", color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (stack.isEmpty()) Text(
                "No LUTs. Add a camera LUT (log to Rec.709) or a creative look; stack as many as ${GradeTextures.MAX_LUTS}.",
                color = Dim, fontSize = 11.sp, modifier = Modifier.padding(12.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(stack.size) { k ->
                    val l = stack[k]
                    Row(
                        Modifier.fillMaxWidth().background(if (k == i) Color(0xFF2C2116) else Color.Transparent).clickable { sel = k }
                            .padding(start = 6.dp, end = 2.dp).height(42.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).clickable { update { s -> s.mapIndexed { j, x -> if (j == k) x.copy(on = !x.on) else x } } },
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(10.dp).clip(CircleShape).background(if (l.on) Palette.Accent else Color(0xFF3A3C45))) }
                        Column(Modifier.weight(1f)) {
                            Text(l.name, color = if (l.on) Txt else Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(l.to?.let { "Converts to ${it.substringBefore(" Gamma").substringBefore(" (")}" } ?: "Look", color = Dim, fontSize = 10.sp, maxLines = 1)
                        }
                        if (k == i) {
                            IconButtonSmall(Lucide.ChevronDown, "Move up", rotate = true, enabled = k > 0) {
                                update { s -> s.toMutableList().also { java.util.Collections.swap(it, k, k - 1) } }; sel = k - 1
                            }
                            IconButtonSmall(Lucide.ChevronDown, "Move down", enabled = k < stack.size - 1) {
                                update { s -> s.toMutableList().also { java.util.Collections.swap(it, k, k + 1) } }; sel = k + 1
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val l = stack.getOrNull(i)
            if (l == null) {
                Text("Pick a LUT on the left to adjust it.", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            } else {
                Text(l.name, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Slider01("Strength", l.strength, "${(l.strength * 100).toInt()}%") { v, final ->
                    if (final) vm.gradeEnd() else {
                        vm.gradeStart()
                        vm.setGrade { it.copy(luts = it.luts.mapIndexed { j, x -> if (j == i) x.copy(strength = v) else x }) }
                    }
                }
                PickerBox("Converts to", l.to ?: "Same as input (a look)") { toDialog = true }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (l.on) "On" else "Off", l.on) { update { s -> s.mapIndexed { j, x -> if (j == i) x.copy(on = !x.on) else x } } }
                    Chip("Remove", false) { update { s -> s.filterIndexed { j, _ -> j != i } } }
                }
            }
        }
    }
    if (library) LutLibraryDialog(vm, onImport = { pick.launch(arrayOf("*/*")) }, onDismiss = { library = false }) { f ->
        vm.applyLut(f)
        sel = stack.size
        library = false
    }
    if (toDialog) stack.getOrNull(i)?.let { l ->
        SpaceDialog(
            "What this LUT converts to",
            listOf("Same as input (a look)" to "A creative look: the picture stays in the space it was in") + ColorSpaces.outputs.map { it.name to it.note },
            l.to ?: "Same as input (a look)", { toDialog = false },
        ) { name -> update { s -> s.mapIndexed { j, x -> if (j == i) x.copy(to = ColorSpaces.byName(name)?.name) else x } } }
    }
}

@Composable
private fun IconButtonSmall(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, rotate: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Icon(
        icon, label, tint = if (enabled) Txt else Dim.copy(alpha = 0.4f),
        modifier = Modifier.size(30.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).padding(7.dp)
            .then(if (rotate) Modifier.graphicsLayer { rotationZ = 180f } else Modifier),
    )
}

/** The phone's LUT library: pick one to add, import more, remove ones no longer wanted. */
@Composable
private fun LutLibraryDialog(vm: EditorViewModel, onImport: () -> Unit, onDismiss: () -> Unit, onPick: (File) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF16171D)).border(1.dp, Color(0xFF23252D), RoundedCornerShape(20.dp))
                .padding(vertical = 12.dp).heightIn(max = 520.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Add a LUT", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Accent).clickable(onClick = onImport).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Plus, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import .cube", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (vm.lutLibrary.isEmpty()) Text("Nothing imported yet.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                items(vm.lutLibrary, key = { it.path }) { f ->
                    var confirm by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clickable { onPick(f) }.padding(start = 18.dp, end = 6.dp).height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.nameWithoutExtension, color = Txt, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(LutLayer.guessTo(f.nameWithoutExtension)?.let { "Converts to $it" } ?: "Look", color = Dim, fontSize = 11.sp)
                        }
                        Icon(Lucide.Trash, "Remove from library", tint = Dim, modifier = Modifier.size(36.dp).clip(CircleShape).clickable { confirm = true }.padding(10.dp))
                    }
                    if (confirm) androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirm = false },
                        containerColor = Color(0xFF1B1C23),
                        title = { Text("Remove ${f.nameWithoutExtension}?", color = Txt, fontSize = 16.sp) },
                        text = { Text("It leaves the LUT library on this phone. Clips using it lose it.", color = Dim, fontSize = 13.sp) },
                        confirmButton = { SmallAction("Remove") { confirm = false; vm.deleteLut(f) } },
                        dismissButton = { SmallAction("Keep") { confirm = false } },
                    )
                }
            }
        }
    }
}

/** A labelled box showing a choice; tap to change it. */
@Composable
private fun PickerBox(label: String, value: String, modifier: Modifier = Modifier, dim: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Card).border(1.dp, CardLine, RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = Dim, fontSize = 10.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = if (dim) Dim else Txt, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Lucide.ChevronDown, null, tint = Dim, modifier = Modifier.size(14.dp))
        }
    }
}

/** A plain 0..1 slider with its value on the right; [onChange] gets final = true when the finger lifts. */
@Composable
private fun Slider01(label: String, value: Float, shown: String, onChange: (Float, Boolean) -> Unit) {
    val now by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, color = Dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(shown, color = Txt, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Box(
            Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown()
                    var v = now
                    change(v, false)
                    var x = d.position.x
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val dx = c.position.x - x
                        x = c.position.x
                        c.consume()
                        v = (v + dx / size.width).coerceIn(0f, 1f)
                        change(v, false)
                    }
                    change(v, true)
                }
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF2A2C35)))
            Box(Modifier.fillMaxWidth(value).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
            Box(Modifier.fillMaxWidth(value.coerceAtLeast(0.001f)), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

// ---- layer 1: colour space transform ---------------------------------------------------------------

/**
 * The clip's colour space transform, as Resolve's CST: input and output gamut and gamma chosen separately, tone and
 * gamut mapping, and the light levels they work between. Left alone it follows the project: from what the clip is
 * shot in to the working space. The project's own settings are below.
 */
@Composable
internal fun CstPalette(vm: EditorViewModel, onPickInput: () -> Unit) {
    val target = vm.gradeTarget
    val clip = target?.clip
    val asset = clip?.let { vm.assets[it.assetId] }
    vm.colourProbes
    val cst = clip?.grade?.cst ?: Cst()
    val input = if (asset?.isImage == true) ColorSpaces.REC709 else ColorSpaces.inputOf(clip, asset)
    val working = ColorSpaces.working(vm.meta.workingSpace)
    val output = ColorSpaces.output(vm.meta.colorSpace)
    var dialog by remember { mutableIntStateOf(0) }
    fun set(f: (Cst) -> Cst) = vm.gradeOnce { it.copy(cst = f(it.cst)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Colour Space Transform", color = Txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            SmallAction("Reset", enabled = cst != Cst()) { set { Cst() } }
        }
        SectionLabel("Input")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerBox("Colour space", cst.inGamut ?: "Clip · ${input.gamutName}", Modifier.weight(1f), dim = cst.inGamut == null) { dialog = 1 }
            PickerBox("Gamma", cst.inGamma ?: "Clip · ${input.gammaName}", Modifier.weight(1f), dim = cst.inGamma == null) { dialog = 2 }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Camera preset", false, onClick = onPickInput)
            Text(if (clip?.cs == null) "Shot in: auto (${input.name})" else "Shot in: ${input.name}", color = Dim, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SectionLabel("Output")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerBox("Colour space", cst.outGamut ?: "Working · ${working.gamutName}", Modifier.weight(1f), dim = cst.outGamut == null) { dialog = 3 }
            PickerBox("Gamma", cst.outGamma ?: "Working · ${working.gammaName}", Modifier.weight(1f), dim = cst.outGamma == null) { dialog = 4 }
        }
        SectionLabel("Mapping")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerBox("Tone mapping", cst.tone, Modifier.weight(1f)) { dialog = 5 }
            PickerBox("Gamut mapping", cst.gamutMap, Modifier.weight(1f)) { dialog = 6 }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerBox("Max input", cst.inNits?.let { "$it nits" } ?: "Auto", Modifier.weight(1f), dim = cst.inNits == null) { dialog = 7 }
            PickerBox("Max output", cst.outNits?.let { "$it nits" } ?: "Auto", Modifier.weight(1f), dim = cst.outNits == null) { dialog = 8 }
        }
        // As Resolve's clip attribute: how the file's numbers are to be read (most video is recorded in video levels).
        SectionLabel("Data levels")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val fileFull = asset?.fullRange == true
            Chip("Auto · ${if (fileFull) "Full" else "Video"}", cst.levels == 0) { set { it.copy(levels = 0) } }
            Chip("Video", cst.levels == 1) { set { it.copy(levels = 1) } }
            Chip("Full", cst.levels == 2) { set { it.copy(levels = 2) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Layer on", color = Txt, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Chip(if (cst.on) "On" else "Off", cst.on) { set { it.copy(on = !it.on) } }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CardLine))
        SectionLabel("Project")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerBox("Working space", working.name, Modifier.weight(1f)) { dialog = 9 }
            PickerBox("Timeline output", output.name, Modifier.weight(1f)) { dialog = 10 }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerBox("Output tone mapping", vm.meta.toneMap, Modifier.weight(1f)) { dialog = 11 }
            PickerBox("HDR master peak", "${vm.meta.masterNits} nits", Modifier.weight(1f)) { dialog = 12 }
        }
        Text(
            "Order: this transform, then the correction, then the LUTs, then into the timeline output.",
            color = Dim, fontSize = 10.sp,
        )
    }
    val gamutNames = ColorSpaces.gamuts.map { it.first to "" }
    val gammaNames = ColorSpaces.gammas.map { it.name to "" }
    val nits = listOf(100, 203, 300, 400, 500, 600, 800, 1000, 1500, 2000, 4000, 10000)
    val close = { dialog = 0 }
    when (dialog) {
        1 -> SpaceDialog("Input colour space", listOf("Clip" to "Follow what the clip is shot in") + gamutNames, cst.inGamut ?: "Clip", close) { n -> set { it.copy(inGamut = n.takeIf { it != "Clip" }) } }
        2 -> SpaceDialog("Input gamma", listOf("Clip" to "Follow what the clip is shot in") + gammaNames, cst.inGamma ?: "Clip", close) { n -> set { it.copy(inGamma = n.takeIf { it != "Clip" }) } }
        3 -> SpaceDialog("Output colour space", listOf("Working" to "The project's working space") + gamutNames, cst.outGamut ?: "Working", close) { n -> set { it.copy(outGamut = n.takeIf { it != "Working" }) } }
        4 -> SpaceDialog("Output gamma", listOf("Working" to "The project's working space") + gammaNames, cst.outGamma ?: "Working", close) { n -> set { it.copy(outGamma = n.takeIf { it != "Working" }) } }
        5 -> SpaceDialog("Tone mapping", ColorSpaces.toneMaps.map { it to toneNote(it) }, cst.tone, close) { n -> set { it.copy(tone = n) } }
        6 -> SpaceDialog("Gamut mapping", ColorSpaces.gamutMaps.map { it to (if (it == "None") "Colours outside the target are clipped" else "Colours outside the target are eased in") }, cst.gamutMap, close) { n -> set { it.copy(gamutMap = n) } }
        7 -> SpaceDialog("Max input luminance", listOf("Auto" to "From the input gamma") + nits.map { "$it nits" to "" }, cst.inNits?.let { "$it nits" } ?: "Auto", close) { n -> set { it.copy(inNits = n.substringBefore(' ').toIntOrNull()) } }
        8 -> SpaceDialog("Max output luminance", listOf("Auto" to "From the output gamma") + nits.map { "$it nits" to "" }, cst.outNits?.let { "$it nits" } ?: "Auto", close) { n -> set { it.copy(outNits = n.substringBefore(' ').toIntOrNull()) } }
        9 -> SpaceDialog("Working space", ColorSpaces.working.map { it.name to it.note }, working.name, close) { vm.updateMeta(vm.meta.copy(workingSpace = it)) }
        10 -> SpaceDialog("Timeline output", ColorSpaces.outputs.map { it.name to it.note }, output.name, close) { vm.updateMeta(vm.meta.copy(colorSpace = it)) }
        11 -> SpaceDialog("Output tone mapping", ColorSpaces.toneMaps.map { it to toneNote(it) }, vm.meta.toneMap, close) { vm.updateMeta(vm.meta.copy(toneMap = it)) }
        12 -> SpaceDialog("HDR master peak", listOf(400, 600, 1000, 2000, 4000).map { "$it nits" to (if (it == 1000) "The usual HDR10 master" else "") }, "${vm.meta.masterNits} nits", close) {
            vm.updateMeta(vm.meta.copy(masterNits = it.substringBefore(' ').toInt()))
        }
    }
}

private fun toneNote(t: String) = when (t) {
    "None" -> "No tone mapping: light beyond the output is clipped"
    "Simple" -> "Rolls the brightest channel off; quick and neutral"
    "Luminance Mapping" -> "Maps brightness, keeps hue; very bright colours ease to white"
    "Saturation Preserving" -> "Maps brightness and keeps colours as saturated as possible"
    else -> "Smooth filmic roll-off with natural highlight desaturation"
}

@Composable
private fun SectionLabel(t: String) {
    Text(t.uppercase(), color = Dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}

@Composable
private fun SpaceDialog(title: String, options: List<Pair<String, String>>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF16171D)).border(1.dp, Color(0xFF23252D), RoundedCornerShape(20.dp))
                .padding(vertical = 14.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp))
            for ((name, note) in options) {
                val on = name == selected
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(name); onDismiss() }.padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, color = if (on) Palette.Accent else Txt, fontSize = 14.sp)
                        if (note.isNotEmpty()) Text(note, color = Dim, fontSize = 11.sp)
                    }
                    if (on) Icon(Lucide.Check, null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---- the layer strip -------------------------------------------------------------------------------

/** The layers a clip's grade is made of, in the order they act on the picture. */
internal enum class Layer(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    RAW("MCRAW", Lucide.Aperture), NOISE("Noise", Lucide.Feather), LENS("Lens", Lucide.Circle), DEFLICKER("Deflicker", Lucide.Gauge), CST("Colour space", Lucide.Globe),
    CORRECTION("Correction", Lucide.Palette), SECONDARIES("Secondaries", Lucide.Droplet), LUTS("LUTs", Lucide.Wand), FILM("Film look", Lucide.Film),
    OUTPUT("Output", Lucide.Upload),
}

/**
 * The clip's layers in the order they act. Each card says what its layer does right now and has a dot that lights when
 * it changes the picture; tap to work on it, hold to switch it on or off.
 */
@Composable
internal fun LayerStrip(vm: EditorViewModel, selected: Layer, onSelect: (Layer) -> Unit) {
    val clip = vm.gradeTarget?.clip
    val asset = clip?.let { vm.assets[it.assetId] }
    vm.colourProbes
    val g = clip?.grade ?: Grade()
    val input = if (asset?.isImage == true) ColorSpaces.REC709 else ColorSpaces.inputOf(clip, asset)
    val working = ColorSpaces.working(vm.meta.workingSpace)
    fun short(n: String) = n.replace("Rec.2100 ", "").replace(" Gamma ", " γ").replace(" (PQ)", "").substringBefore(" / ")
    val corrected = g.copy(cst = Cst(), luts = emptyList(), noise = com.graden.editor.NoiseLayer(), lens = com.graden.editor.LensLayer(),
        deflicker = com.graden.editor.DeflickerLayer(), secondaries = emptyList(), film = com.graden.editor.FilmLayer(), limiter = com.graden.editor.LimiterLayer(),
        lutsOn = true) != Grade()
    val state: (Layer) -> Pair<String, Boolean> = { l ->
        when (l) {
            Layer.RAW -> when {
                asset?.isRaw != true -> "Not MCRAW" to false
                !g.raw.on -> "Off" to false
                g.raw.decode == com.graden.editor.RawLayer.CAMERA && asset.developOptions == com.graden.editor.DevelopOptions() -> "As recorded" to false
                else -> "Developed" to true
            }
            Layer.NOISE -> if (!g.noise.on) "Off" to false else if (g.noise.active) listOfNotNull("Temporal".takeIf { g.noise.temporal }, "Spatial".takeIf { g.noise.spatial }, "Blur".takeIf { g.noise.blur }).joinToString(" · ") to true else "None" to false
            Layer.LENS -> if (g.lens.active) "Corrected" to true else (if (g.lens.on) "None" else "Off") to false
            Layer.DEFLICKER -> if (g.deflicker.on) "On" to true else "Off" to false
            Layer.CST -> when {
                !g.cst.on -> "Off" to false
                g.cst.custom -> short(g.cst.inGamma ?: input.gammaName) + " to " + short(g.cst.outGamma ?: working.gammaName) to true
                else -> short(input.name) + " to " + short(working.name) to (input.name != working.name)
            }
            Layer.CORRECTION -> if (!g.correctionOn) "Off" to false else if (corrected) "Graded" to true else "Neutral" to false
            Layer.SECONDARIES -> g.secondaries.count { it.on }.let { n -> if (n == 0) "None" to false else "$n on" to true }
            Layer.LUTS -> if (!g.lutsOn) "Off" to false else if (g.luts.isEmpty()) "None" to false else "${g.luts.count { it.on }} of ${g.luts.size}" to (g.luts.any { it.on })
            Layer.FILM -> if (g.film.active) "On" to true else (if (g.film.on) "None" else "Off") to false
            Layer.OUTPUT -> (if (g.limiter.on) "Limiter on" else short(ColorSpaces.output(vm.meta.colorSpace).name)) to g.limiter.on
        }
    }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scroll = rememberScrollState()
    // The chosen layer is brought into view (it may be off to the side when the page opens).
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.LaunchedEffect(selected) {
        val step = with(density) { (128.dp + 8.dp).toPx() }
        val at = selected.ordinal - (if (asset?.isRaw == true) 0 else 1)
        scroll.animateScrollTo((at * step - step).toInt().coerceAtLeast(0))
    }
    // The layers in the order they act, joined by a line: the picture flows left to right through them.
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 6.dp)
            .drawBehind { drawLine(Color(0xFF2A2C35), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx()) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // MCRAW is there only for MotionCam RAW clips.
        for (l in Layer.entries.filter { it != Layer.RAW || asset?.isRaw == true }) {
            val on = l == selected
            val (text, active) = state(l)
            // Camera RAW only means something for RAW recordings; for other clips it is there, greyed.
            val usable = l != Layer.RAW || asset?.isRaw == true
            Row(
                Modifier.graphicsLayer { alpha = if (usable) 1f else 0.4f }.width(128.dp).height(48.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF2C2116) else Color(0xFF17181E))
                    .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.75f) else Color(0xFF24262E), RoundedCornerShape(12.dp))
                    .pointerInput(l) {
                        detectTapGestures(
                            onTap = { onSelect(l) },
                            onLongPress = {
                                if (usable) {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    vm.toggleLayer(l)
                                }
                            },
                        )
                    }.padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(if (active) Palette.Accent.copy(alpha = 0.18f) else Color(0xFF22232A)),
                    contentAlignment = Alignment.Center,
                ) { Icon(l.icon, null, tint = if (active) Palette.Accent else Dim, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.title, color = if (on) Palette.Accent else Txt, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text, color = Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.DeflickerLayer
import com.graden.editor.DetailLayer
import com.graden.editor.EditorViewModel
import com.graden.editor.FilmLayer
import com.graden.editor.Grade
import com.graden.editor.HdrZone
import com.graden.editor.LensLayer
import com.graden.editor.LimiterLayer
import com.graden.editor.LogLayer
import com.graden.editor.MixerLayer
import com.graden.editor.NoiseLayer
import com.graden.editor.Qualifier
import com.graden.editor.Secondary
import com.graden.editor.SliceLayer
import com.graden.editor.Wheel
import com.graden.editor.Window
import com.graden.editor.ZonesLayer
import kotlin.math.abs

/*
 * The Color page's palettes, built from the control kit (ProControls.kt): sections that fold, full-width sliders,
 * ranges over the colours they choose, and the colour pad. Everything scrolls in the room the controls have.
 */

private fun gr(vm: EditorViewModel) = vm.gradeTarget?.clip?.grade ?: Grade()

@Composable
private fun Scroll(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)) { content() }
}

private fun P(label: String, min: Float, max: Float, def: Float, fmt: String, get: (Grade) -> Float, set: (Grade, Float) -> Grade) = Primary(label, min, max, def, fmt, get, set)

// ---- HDR palette -----------------------------------------------------------------------------------

/** Each zone's colour, as in the zone view and the graph. */
internal val ZoneColours = listOf(
    Color(0xFF3B4FC4), Color(0xFF3E80E0), Color(0xFF3FB6C8), Color(0xFFE8CF4E), Color(0xFFF08A2E), Color(0xFFEF4A4A), Color(0xFFE6E7EC),
)

private fun zoneWeight(i: Int, z: HdrZone, st: Float): Float {
    val f = 0.25f + z.falloff * 4f
    fun ss(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    return if (i < 3) 1f - ss(z.range - f, z.range + f, st) else ss(z.range - f, z.range + f, st)
}

/**
 * The zone graph: the exposure scale in stops from mid grey across, each zone's reach drawn as a curve in its colour.
 * Drag a zone's handle (on its edge) to move its range; the chosen zone is drawn bright, the others faint.
 */
@Composable
private fun ZoneGraph(vm: EditorViewModel, selected: Int, onSelect: (Int) -> Unit) {
    val z = gr(vm).zones
    val view = LocalView.current
    val vmNow by rememberUpdatedState(vm)
    val selNow by rememberUpdatedState(selected)
    val pick by rememberUpdatedState(onSelect)
    val lo = -8f; val hi = 8f
    Canvas(
        Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(12.dp)).background(Pro.Well).border(1.dp, Pro.Line, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pad = 14.dp.toPx(); val w = size.width - 2 * pad
                    fun stop(x: Float) = lo + (x - pad) / w * (hi - lo)
                    val m = vmNow
                    val zones = (m.gradeTarget?.clip?.grade ?: Grade()).zones.zones
                    // The nearest handle to the finger (the chosen zone first when it is close).
                    val s0 = stop(down.position.x)
                    var idx = -1; var best = 0.9f
                    for ((i, zz) in zones.withIndex()) {
                        val d = abs(zz.range - s0) - (if (i == selNow) 0.3f else 0f)
                        if (d < best) { best = d; idx = i }
                    }
                    if (idx < 0) return@awaitEachGesture
                    pick(idx)
                    var range = zones[idx].range
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val dx = c.positionChange().x
                        if (!moved && abs(c.position.x - down.position.x) > 8 * density) { moved = true; m.gradeStart(); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                        if (!moved) continue
                        c.consume()
                        range = (range + dx / w * (hi - lo)).coerceIn(lo, hi)
                        val r = (Math.round(range * 20f) / 20f)
                        m.setGrade { g -> g.copy(zones = g.zones.copy(zones = g.zones.zones.mapIndexed { i, x -> if (i == idx) x.copy(range = r) else x })) }
                    }
                    if (moved) m.gradeEnd()
                }
            },
    ) {
        val pad = 14.dp.toPx(); val w = size.width - 2 * pad
        val top = 10.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val h = bottom - top
        fun xOf(st: Float) = pad + (st - lo) / (hi - lo) * w
        // Stop grid, mid grey marked.
        for (s in -8..8 step 2) {
            val x = xOf(s.toFloat())
            drawLine(Color.White.copy(alpha = if (s == 0) 0.25f else 0.07f), Offset(x, top), Offset(x, bottom), 1f)
        }
        // Each zone's reach, faint unless chosen.
        for (i in 0 until 6) {
            val zz = z.zones[i]
            val col = ZoneColours[i]
            val path = Path()
            val fill = Path()
            fill.moveTo(pad, bottom)
            for (k in 0..120) {
                val st = lo + k / 120f * (hi - lo)
                val y = bottom - zoneWeight(i, zz, st) * h
                if (k == 0) path.moveTo(xOf(st), y) else path.lineTo(xOf(st), y)
                fill.lineTo(xOf(st), y)
            }
            fill.lineTo(pad + w, bottom); fill.close()
            val on = i == selected
            drawPath(fill, col.copy(alpha = if (on) 0.22f else 0.05f))
            drawPath(path, col.copy(alpha = if (on) 1f else 0.35f), style = Stroke((if (on) 2f else 1.2f).dp.toPx()))
            // The handle at its range.
            val hx = xOf(zz.range)
            drawLine(col.copy(alpha = if (on) 0.9f else 0.4f), Offset(hx, top), Offset(hx, bottom), (if (on) 1.5f else 1f).dp.toPx())
            drawCircle(Color.Black, (if (on) 8f else 6f).dp.toPx(), Offset(hx, top + 2.dp.toPx()))
            drawCircle(col, (if (on) 7f else 5f).dp.toPx(), Offset(hx, top + 2.dp.toPx()))
        }
        drawLine(Color.White.copy(alpha = 0.2f), Offset(pad, bottom), Offset(pad + w, bottom), 1f)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
        for (s in listOf("−8", "−4", "grey", "+4", "+8")) Text(s, color = Pro.Dim, fontSize = 10.sp, modifier = Modifier.weight(1f),
            textAlign = when (s) { "−8" -> TextAlign.Start; "+8" -> TextAlign.End; else -> TextAlign.Center })
    }
}

/** The zone chooser: a small swatch of each zone's wheel (where its colour sits) with its name and exposure. */
@Composable
private fun ZoneChips(vm: EditorViewModel, selected: Int, onSelect: (Int) -> Unit) {
    val z = gr(vm).zones
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (i in 0..6) {
            val zone = if (i == 6) z.global else z.zones[i]
            val def = if (i == 6) HdrZone() else ZonesLayer.DEFAULTS[i]
            val on = i == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFF2B2219) else Pro.Card)
                    .border(1.dp, if (on) ZoneColours[i].copy(alpha = 0.8f) else Pro.Line, RoundedCornerShape(10.dp))
                    .clickable { onSelect(i) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(ZoneColours[i].copy(alpha = 0.25f), r)
                    drawCircle(ZoneColours[i], r, style = Stroke(1.5.dp.toPx()))
                    drawCircle(Color.White, 2.5.dp.toPx(), Offset(center.x + zone.x * r * 0.8f, center.y - zone.y * r * 0.8f))
                }
                Text(if (i == 6) "Global" else ZonesLayer.NAMES[i], color = if (on) Pro.Text else Pro.Dim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(top = 3.dp))
                Text(if (zone.neutral(def)) "·" else "%+.1f".format(zone.exp), color = if (zone.neutral(def)) Pro.Faint else Palette.Accent, fontSize = 9.sp, fontFamily = Pro.Mono, maxLines = 1)
            }
        }
    }
}

/** The HDR palette, rebuilt for touch: the zone graph, the zone chooser, the chosen zone's wheel and values, and the global adjustments. */
@Composable
internal fun ZonesPanel(vm: EditorViewModel) {
    val z = gr(vm).zones
    var sel by remember { mutableIntStateOf(3) }
    val i = sel
    val global = i == 6
    val zone = if (global) z.global else z.zones[i]
    val def = if (global) HdrZone() else ZonesLayer.DEFAULTS[i]
    fun of(g: Grade) = if (global) g.zones.global else g.zones.zones[i]
    fun with(g: Grade, f: (HdrZone) -> HdrZone) = g.copy(zones = if (global) g.zones.copy(global = f(g.zones.global))
        else g.zones.copy(zones = g.zones.zones.mapIndexed { k, x -> if (k == i) f(x) else x }))
    Scroll {
        PaletteHeader("High Dynamic Range", "Zones by exposure, in stops from mid grey, worked in linear light", z.on, z != ZonesLayer(),
            { vm.gradeOnce { it.copy(zones = it.zones.copy(on = !it.zones.on)) } }, { vm.gradeOnce { it.copy(zones = ZonesLayer()) } }) {
            ProToggle("Zone view", vm.zoneView) { vm.zoneView = !vm.zoneView }
        }
        ZoneGraph(vm, sel) { sel = it }
        ZoneChips(vm, sel) { sel = it }
        key(i) {
            SectionCard(if (global) "Global" else ZonesLayer.NAMES[i], if (global) "Everything, after the zones" else if (i < 3) "Everything below its range" else "Everything above its range",
                !zone.neutral(def), onReset = { vm.gradeOnce { g -> with(g) { def } } }) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wide = maxWidth > 330.dp
                    val pad = @Composable { m: Modifier ->
                        Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
                            DiscPad(vm, zone.x, zone.y, Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp)) { u, v -> { g: Grade -> with(g) { it.copy(x = u, y = v) } } }
                            Text("X %+.2f   Y %+.2f".format(zone.x, zone.y), color = Pro.Dim, fontSize = 11.sp, fontFamily = Pro.Mono)
                        }
                    }
                    val sliders = @Composable { m: Modifier ->
                        Column(m) {
                            ProSlider(vm, P("Exposure", -6f, 6f, 0f, "%+.2f", { of(it).exp }) { g, v -> with(g) { it.copy(exp = v) } })
                            ProSlider(vm, P("Saturation", 0f, 4f, 1f, "%.2f", { of(it).sat }) { g, v -> with(g) { it.copy(sat = v) } },
                                track = Brush.horizontalGradient(listOf(Color(0xFF7A7A7A), ZoneColours[i])))
                            if (!global) {
                                ProSlider(vm, P("Range", -8f, 8f, def.range, "%+.2f", { of(it).range }) { g, v -> with(g) { it.copy(range = v) } })
                                ProSlider(vm, P("Falloff", 0f, 1f, def.falloff, "%.2f", { of(it).falloff }) { g, v -> with(g) { it.copy(falloff = v) } })
                            }
                        }
                    }
                    if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
                        pad(Modifier.weight(0.8f))
                        Spacer(Modifier.width(12.dp))
                        sliders(Modifier.weight(1.2f))
                    } else Column {
                        pad(Modifier.fillMaxWidth(0.7f).align(Alignment.CenterHorizontally))
                        sliders(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        SectionCard("Adjustments", "Temperature, tint and hue; contrast about the pivot (in stops); detail and black offset",
            z.temp != 0f || z.tint != 0f || z.hue != 0f || z.contrast != 1f || z.pivot != 0f || z.md != 0f || z.bOfs != 0f || z.mix != 1f,
            onReset = { vm.gradeOnce { it.copy(zones = it.zones.copy(temp = 0f, tint = 0f, hue = 0f, contrast = 1f, pivot = 0f, md = 0f, bOfs = 0f, mix = 1f)) } }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProSlider(vm, P("Temp", -2000f, 2000f, 0f, "%.0f", { it.zones.temp }) { g, v -> g.copy(zones = g.zones.copy(temp = v)) }, Modifier.weight(1f),
                    track = Brush.horizontalGradient(listOf(Color(0xFF4F7BD9), Color(0xFF8A8A8A), Color(0xFFE0A84A))))
                ProSlider(vm, P("Tint", -100f, 100f, 0f, "%.1f", { it.zones.tint }) { g, v -> g.copy(zones = g.zones.copy(tint = v)) }, Modifier.weight(1f),
                    track = Brush.horizontalGradient(listOf(Color(0xFF4CC36A), Color(0xFF8A8A8A), Color(0xFFD052C8))))
            }
            SliderGrid(vm, listOf(
                P("Hue", -180f, 180f, 0f, "%.1f°", { it.zones.hue }) { g, v -> g.copy(zones = g.zones.copy(hue = v)) },
                P("Contrast", 0f, 3f, 1f, "%.3f", { it.zones.contrast }) { g, v -> g.copy(zones = g.zones.copy(contrast = v)) },
                P("Pivot", -4f, 4f, 0f, "%+.2f", { it.zones.pivot }) { g, v -> g.copy(zones = g.zones.copy(pivot = v)) },
                P("Mid/Detail", -100f, 100f, 0f, "%.1f", { it.zones.md }) { g, v -> g.copy(zones = g.zones.copy(md = v)) },
                P("Black offset", -1f, 1f, 0f, "%+.3f", { it.zones.bOfs }) { g, v -> g.copy(zones = g.zones.copy(bOfs = v)) },
                P("Mix", 0f, 1f, 1f, "%.2f", { it.zones.mix }) { g, v -> g.copy(zones = g.zones.copy(mix = v)) },
            ))
        }
    }
}

// ---- Secondaries -----------------------------------------------------------------------------------

@Composable
internal fun SecondariesPanel(vm: EditorViewModel) {
    val list = gr(vm).secondaries
    val i = vm.secondaryEdit.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    // While this palette is open the picture shows the window's handles and takes colour picks.
    androidx.compose.runtime.DisposableEffect(Unit) {
        vm.secondaryOverlay = true
        onDispose { vm.secondaryOverlay = false; vm.pickingColour = false; vm.secondaryHighlight = false }
    }
    fun upd(f: (Secondary) -> Secondary) = { g: Grade -> g.copy(secondaries = g.secondaries.mapIndexed { k, s -> if (k == i) f(s) else s }) }
    Scroll {
        PaletteHeader("Secondaries", "Change part of the picture: a colour picked with the qualifier, a window, or both", null, list.isNotEmpty(), {},
            { vm.gradeOnce { it.copy(secondaries = emptyList()) } })
        // The secondaries of the clip, and adding one.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((k, s) in list.withIndex()) {
                val on = k == i
                Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF2E2418) else Pro.Card)
                        .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.7f) else Pro.Line, RoundedCornerShape(12.dp))
                        .clickable { vm.secondaryEdit = k }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (s.on) hueColour(s.qualifier.hueCentre) else Pro.Faint))
                    Spacer(Modifier.width(8.dp))
                    Text(s.name, color = if (on) Palette.Accent else Pro.Text, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
            if (list.size < Secondary.MAX) Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Pro.Line, RoundedCornerShape(12.dp))
                    .clickable { vm.gradeOnce { it.copy(secondaries = it.secondaries + Secondary(name = "Secondary ${it.secondaries.size + 1}")) }; vm.secondaryEdit = list.size }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Plus, null, tint = Palette.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add", color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        val s = list.getOrNull(i)
        if (s == null) {
            Text("Add one, then pick a colour on the picture or draw a window on it.", color = Pro.Dim, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
            return@Scroll
        }
        key(i) {
            // What to do with it: on, show what it selects, pick a colour on the picture, remove.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProToggle(if (s.on) "On" else "Off", s.on) { vm.gradeOnce(upd { it.copy(on = !it.on) }) }
                ProToggle("Highlight", vm.secondaryHighlight) { vm.secondaryHighlight = !vm.secondaryHighlight }
                ProToggle("Pick colour", vm.pickingColour) { vm.pickingColour = !vm.pickingColour; if (vm.pickingColour) vm.gradeOnce(upd { it.copy(qualify = true) }) }
                ProToggle("Remove", false) { vm.gradeOnce { g -> g.copy(secondaries = g.secondaries.filterIndexed { k, _ -> k != i }) }; vm.secondaryEdit = (i - 1).coerceAtLeast(0) }
            }
            val q = s.qualifier
            SectionCard("Qualifier", "Selects by hue, saturation and luminance; tap Pick colour, then the picture", s.qualify && q != Qualifier(),
                onReset = { vm.gradeOnce(upd { it.copy(qualifier = Qualifier()) }) },
                trailing = { ProToggle(if (s.qualify) "On" else "Off", s.qualify) { vm.gradeOnce(upd { it.copy(qualify = !it.qualify) }) } }) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProToggle("Hue", q.useHue, Modifier.weight(1f)) { vm.gradeOnce(upd { it.copy(qualifier = it.qualifier.copy(useHue = !it.qualifier.useHue)) }) }
                    ProToggle("Sat", q.useSat, Modifier.weight(1f)) { vm.gradeOnce(upd { it.copy(qualifier = it.qualifier.copy(useSat = !it.qualifier.useSat)) }) }
                    ProToggle("Lum", q.useLum, Modifier.weight(1f)) { vm.gradeOnce(upd { it.copy(qualifier = it.qualifier.copy(useLum = !it.qualifier.useLum)) }) }
                    ProToggle("Invert", q.invert, Modifier.weight(1f)) { vm.gradeOnce(upd { it.copy(qualifier = it.qualifier.copy(invert = !it.qualifier.invert)) }) }
                }
                HueRange(q.hueCentre, q.hueWidth, q.softness, { vm.gradeStart() }, { c, w -> vm.setGrade(upd { it.copy(qualifier = it.qualifier.copy(hueCentre = c, hueWidth = w)) }) }, { vm.gradeEnd() }, enabled = q.useHue)
                RangeSlider("Saturation", q.satLo, q.satHi, q.softness, Brush.horizontalGradient(listOf(Color(0xFF808080), hueColour(q.hueCentre, 0.55f, 0.4f))),
                    { vm.gradeStart() }, { a, b -> vm.setGrade(upd { it.copy(qualifier = it.qualifier.copy(satLo = a, satHi = b)) }) }, { vm.gradeEnd() }, enabled = q.useSat)
                RangeSlider("Luminance", q.lumLo, q.lumHi, q.softness, Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                    { vm.gradeStart() }, { a, b -> vm.setGrade(upd { it.copy(qualifier = it.qualifier.copy(lumLo = a, lumHi = b)) }) }, { vm.gradeEnd() }, enabled = q.useLum)
                ProSlider(vm, P("Softness", 0.005f, 0.3f, 0.06f, "%.3f", { g -> g.secondaries.getOrNull(i)?.qualifier?.softness ?: 0.06f }) { g, v -> upd { it.copy(qualifier = it.qualifier.copy(softness = v)) }(g) })
            }
            val w = s.window
            SectionCard("Window", "Drag on the picture to move it, pinch to size, twist to turn", w.type != Window.NONE, onReset = { vm.gradeOnce(upd { it.copy(window = Window()) }) }) {
                Choice(Window.NAMES, w.type) { t -> vm.gradeOnce(upd { it.copy(window = it.window.copy(type = t)) }) }
                if (w.type != Window.NONE) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProToggle("Invert", w.invert) { vm.gradeOnce(upd { it.copy(window = it.window.copy(invert = !it.window.invert)) }) }
                    }
                    fun wv(f: (Window, Float) -> Window) = { g: Grade, v: Float -> upd { it.copy(window = f(it.window, v)) }(g) }
                    fun sw(g: Grade) = g.secondaries.getOrNull(i)?.window ?: Window()
                    SliderGrid(vm, listOf(
                        P("Softness", 0.005f, 0.6f, 0.15f, "%.2f", { sw(it).softness }, wv { x, v -> x.copy(softness = v) }),
                        P("Angle", -180f, 180f, 0f, "%.0f°", { sw(it).angle }, wv { x, v -> x.copy(angle = v) }),
                        P("Width", 0.02f, 1.5f, 0.35f, "%.2f", { sw(it).w }, wv { x, v -> x.copy(w = v) }),
                        P("Height", 0.02f, 1.5f, 0.35f, "%.2f", { sw(it).h }, wv { x, v -> x.copy(h = v) }),
                        P("Centre X", 0f, 1f, 0.5f, "%.3f", { sw(it).cx }, wv { x, v -> x.copy(cx = v) }),
                        P("Centre Y", 0f, 1f, 0.5f, "%.3f", { sw(it).cy }, wv { x, v -> x.copy(cy = v) }),
                    ))
                }
            }
            fun ss(g: Grade) = g.secondaries.getOrNull(i) ?: Secondary()
            fun a(f: (Secondary, Float) -> Secondary) = { g: Grade, v: Float -> upd { f(it, v) }(g) }
            SectionCard("Inside the selection", "What changes where it is selected", s.exposure != 0f || s.contrast != 1f || s.saturation != 1f || s.hueShift != 0f || s.temp != 0f || s.tint != 0f || s.mix != 1f,
                onReset = { vm.gradeOnce(upd { it.copy(exposure = 0f, contrast = 1f, saturation = 1f, hueShift = 0f, temp = 0f, tint = 0f, mix = 1f) }) }) {
                SliderGrid(vm, listOf(
                    P("Exposure", -3f, 3f, 0f, "%+.2f", { ss(it).exposure }, a { x, v -> x.copy(exposure = v) }),
                    P("Contrast", 0f, 2f, 1f, "%.2f", { ss(it).contrast }, a { x, v -> x.copy(contrast = v) }),
                ))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ProSlider(vm, P("Saturation", 0f, 2f, 1f, "%.2f", { ss(it).saturation }, a { x, v -> x.copy(saturation = v) }), Modifier.weight(1f),
                        track = Brush.horizontalGradient(listOf(Color(0xFF7A7A7A), Color(0xFFE0544A))))
                    ProSlider(vm, P("Hue", -0.5f, 0.5f, 0f, "%+.3f", { ss(it).hueShift }, a { x, v -> x.copy(hueShift = v) }), Modifier.weight(1f),
                        track = Brush.horizontalGradient((0..12).map { hueColour(it / 12f) }))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ProSlider(vm, P("Temp", -2000f, 2000f, 0f, "%.0f", { ss(it).temp }, a { x, v -> x.copy(temp = v) }), Modifier.weight(1f),
                        track = Brush.horizontalGradient(listOf(Color(0xFF4F7BD9), Color(0xFF8A8A8A), Color(0xFFE0A84A))))
                    ProSlider(vm, P("Tint", -100f, 100f, 0f, "%.1f", { ss(it).tint }, a { x, v -> x.copy(tint = v) }), Modifier.weight(1f),
                        track = Brush.horizontalGradient(listOf(Color(0xFF4CC36A), Color(0xFF8A8A8A), Color(0xFFD052C8))))
                }
                ProSlider(vm, P("Mix", 0f, 1f, 1f, "%.2f", { ss(it).mix }, a { x, v -> x.copy(mix = v) }))
            }
        }
    }
}

// ---- Film Look -------------------------------------------------------------------------------------

/** A preset card: its name, what it is, and a strip of its colours. */
@Composable
private fun PresetCard(name: String, note: String, colours: List<Color>, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(132.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF2B2219) else Pro.Card)
            .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.8f) else Pro.Line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(colours)))
        Text(name, color = if (on) Palette.Accent else Pro.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(note, color = Pro.Dim, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun FilmPanel(vm: EditorViewModel) {
    val f = gr(vm).film
    fun set(x: (FilmLayer) -> FilmLayer) = vm.gradeOnce { it.copy(film = x(it.film)) }
    fun p(label: String, min: Float, max: Float, def: Float, fmt: String, get: (FilmLayer) -> Float, put: (FilmLayer, Float) -> FilmLayer) =
        P(label, min, max, def, fmt, { get(it.film) }) { g, v -> g.copy(film = put(g.film, v).copy(preset = "Custom")) }
    val d = FilmLayer()
    Scroll {
        PaletteHeader("Film Look", "Modelled on Resolve's Film Look Creator", f.on, f != FilmLayer(), { set { it.copy(on = !it.on) } }, { set { FilmLayer() } })
        // Presets, as a strip of cards.
        val notes = mapOf(
            "Clean 65mm" to ("Large format, fine grain" to listOf(Color(0xFF26323C), Color(0xFFB9A88E), Color(0xFFF1E6D2))),
            "35mm Print" to ("Rich print, halation, weave" to listOf(Color(0xFF1C2A33), Color(0xFFA9714B), Color(0xFFF2D7AE))),
            "16mm Documentary" to ("Bold grain, soft blacks" to listOf(Color(0xFF2A2F2A), Color(0xFF8D8A6C), Color(0xFFE2D6B4))),
            "8mm Home Movie" to ("Heavy grain, faded, warm" to listOf(Color(0xFF3A3029), Color(0xFFB57E52), Color(0xFFF0CF96))),
            "Bleach Bypass" to ("Silver-rich, low colour" to listOf(Color(0xFF1E2224), Color(0xFF7D8284), Color(0xFFE6E8E8))),
            "Faded Print" to ("Lifted blacks, soft colour" to listOf(Color(0xFF3B3A40), Color(0xFF9C8F86), Color(0xFFE6D9CC))),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, make) in FilmLayer.PRESETS) {
                val (note, cols) = notes[name] ?: ("" to listOf(Pro.Card, Pro.Text))
                PresetCard(name, note, cols, f.preset == name) { set { make(FilmLayer(lookMix = it.lookMix)) } }
            }
        }
        SectionCard("Look Mix", "How strongly the whole look applies, LUTs included", f.lookMix != 1f, onReset = { set { it.copy(lookMix = 1f) } }) {
            ProSlider(vm, p("Look Mix", 0f, 1f, 1f, "%.2f", { it.lookMix }) { x, v -> x.copy(lookMix = v) })
        }
        SectionCard("Look", "Exposure, film saturation and density, split tone, fade, roll-off",
            f.exposure != 0f || f.subSat != 0f || f.richness != 0f || f.bleach != 0f || f.splitAmount != 0f || f.fade != 0f || f.rolloff != 0f,
            onReset = { set { it.copy(exposure = 0f, subSat = 0f, richness = 0f, bleach = 0f, splitAmount = 0f, splitHue = 0f, splitPivot = 0.45f, fade = 0f, rolloff = 0f) } }) {
            SliderGrid(vm, listOf(
                p("Exposure", -3f, 3f, 0f, "%+.2f", { it.exposure }) { x, v -> x.copy(exposure = v) },
                p("Subtractive Sat", -1f, 1f, 0f, "%.2f", { it.subSat }) { x, v -> x.copy(subSat = v) },
                p("Richness", -1f, 1f, 0f, "%.2f", { it.richness }) { x, v -> x.copy(richness = v) },
                p("Bleach Bypass", 0f, 1f, 0f, "%.2f", { it.bleach }) { x, v -> x.copy(bleach = v) },
                p("Fade", 0f, 1f, 0f, "%.2f", { it.fade }) { x, v -> x.copy(fade = v) },
                p("Highlight Roll-off", 0f, 1f, 0f, "%.2f", { it.rolloff }) { x, v -> x.copy(rolloff = v) },
            ))
            Text("Split tone", color = Pro.Dim, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            ProSlider(vm, p("Amount", 0f, 1f, 0f, "%.2f", { it.splitAmount }) { x, v -> x.copy(splitAmount = v) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProSlider(vm, p("Hue", -0.5f, 0.5f, 0f, "%+.3f", { it.splitHue }) { x, v -> x.copy(splitHue = v) }, Modifier.weight(1f), enabled = f.splitAmount > 0f,
                    track = Brush.horizontalGradient((0..12).map { hueColour(it / 12f) }))
                ProSlider(vm, p("Pivot", 0.1f, 0.9f, 0.45f, "%.2f", { it.splitPivot }) { x, v -> x.copy(splitPivot = v) }, Modifier.weight(1f), enabled = f.splitAmount > 0f)
            }
        }
        SectionCard("Halation", "A red glow round the brightest edges, from light scattered in the film base",
            f.halation != d.halation || f.halSpread != d.halSpread || f.halThreshold != d.halThreshold || f.halHue != d.halHue || f.halSat != d.halSat,
            onReset = { set { it.copy(halation = d.halation, halSpread = d.halSpread, halThreshold = d.halThreshold, halHue = d.halHue, halSat = d.halSat) } }) {
            SliderGrid(vm, listOf(
                p("Amount", 0f, 1f, 0f, "%.2f", { it.halation }) { x, v -> x.copy(halation = v) },
                p("Spread", 0f, 1f, d.halSpread, "%.2f", { it.halSpread }) { x, v -> x.copy(halSpread = v) },
                p("Threshold", 0f, 0.95f, d.halThreshold, "%.2f", { it.halThreshold }) { x, v -> x.copy(halThreshold = v) },
                p("Saturation", 0f, 2f, 1f, "%.2f", { it.halSat }) { x, v -> x.copy(halSat = v) },
            ))
            ProSlider(vm, p("Hue", -0.25f, 0.25f, 0f, "%+.3f", { it.halHue }) { x, v -> x.copy(halHue = v) },
                track = Brush.horizontalGradient(listOf(Color(0xFFD03A6A), Color(0xFFE8532E), Color(0xFFE0A830))))
        }
        SectionCard("Bloom", "A soft wide glow from the highlights", f.bloom != 0f || f.bloomSpread != d.bloomSpread || f.bloomThreshold != d.bloomThreshold || f.bloomWarmth != 0f,
            onReset = { set { it.copy(bloom = 0f, bloomSpread = d.bloomSpread, bloomThreshold = d.bloomThreshold, bloomWarmth = 0f) } }) {
            SliderGrid(vm, listOf(
                p("Amount", 0f, 1f, 0f, "%.2f", { it.bloom }) { x, v -> x.copy(bloom = v) },
                p("Spread", 0f, 1f, d.bloomSpread, "%.2f", { it.bloomSpread }) { x, v -> x.copy(bloomSpread = v) },
                p("Threshold", 0f, 0.95f, d.bloomThreshold, "%.2f", { it.bloomThreshold }) { x, v -> x.copy(bloomThreshold = v) },
            ))
            ProSlider(vm, p("Warmth", -1f, 1f, 0f, "%+.2f", { it.bloomWarmth }) { x, v -> x.copy(bloomWarmth = v) },
                track = Brush.horizontalGradient(listOf(Color(0xFF5A86D8), Color(0xFF8A8A8A), Color(0xFFE0A84A))))
        }
        SectionCard("Grain", "Film grain by stock, with its response in shadows, midtones and highlights", f.grain > 0f,
            onReset = { set { it.copy(grain = 0f, stock = "35mm", grainSize = 1f, grainSoft = d.grainSoft, grainColor = d.grainColor, grainShadows = 1f, grainMids = 1f, grainHighs = d.grainHighs, grainAnimated = true) } }) {
            ChoiceRow("Stock", FilmLayer.STOCKS, FilmLayer.STOCKS.indexOf(f.stock).coerceAtLeast(2)) { k -> set { it.copy(stock = FilmLayer.STOCKS[k], preset = "Custom") } }
            SliderGrid(vm, listOf(
                p("Amount", 0f, 1f, 0f, "%.2f", { it.grain }) { x, v -> x.copy(grain = v) },
                p("Size", 0.4f, 3f, 1f, "%.2f", { it.grainSize }) { x, v -> x.copy(grainSize = v) },
                p("Softness", 0f, 1f, d.grainSoft, "%.2f", { it.grainSoft }) { x, v -> x.copy(grainSoft = v) },
                p("Colour", 0f, 1f, d.grainColor, "%.2f", { it.grainColor }) { x, v -> x.copy(grainColor = v) },
            ))
            Text("Response", color = Pro.Dim, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            SliderGrid(vm, listOf(
                p("Shadows", 0f, 2f, 1f, "%.2f", { it.grainShadows }) { x, v -> x.copy(grainShadows = v) },
                p("Midtones", 0f, 2f, 1f, "%.2f", { it.grainMids }) { x, v -> x.copy(grainMids = v) },
                p("Highlights", 0f, 2f, d.grainHighs, "%.2f", { it.grainHighs }) { x, v -> x.copy(grainHighs = v) },
            ), columns = 3)
            ChoiceRow("Motion", listOf("Animated", "Still"), if (f.grainAnimated) 0 else 1) { k -> set { it.copy(grainAnimated = k == 0) } }
        }
        SectionCard("Vignette", "Darkening (or lightening) towards the edges", f.vignette != 0f || f.vigSize != d.vigSize || f.vigRound != d.vigRound || f.vigSoft != d.vigSoft,
            onReset = { set { it.copy(vignette = 0f, vigSize = d.vigSize, vigRound = d.vigRound, vigSoft = d.vigSoft) } }) {
            SliderGrid(vm, listOf(
                p("Amount", -1f, 1f, 0f, "%+.2f", { it.vignette }) { x, v -> x.copy(vignette = v) },
                p("Size", 0f, 1.2f, d.vigSize, "%.2f", { it.vigSize }) { x, v -> x.copy(vigSize = v) },
                p("Roundness", 0f, 1f, d.vigRound, "%.2f", { it.vigRound }) { x, v -> x.copy(vigRound = v) },
                p("Softness", 0f, 1f, d.vigSoft, "%.2f", { it.vigSoft }) { x, v -> x.copy(vigSoft = v) },
            ))
        }
        SectionCard("Gate weave & flicker", "The frame drifting in the gate, and exposure pulsing, as projected film", f.weave > 0f || f.flicker > 0f,
            onReset = { set { it.copy(weave = 0f, weaveSpeed = 1f, flicker = 0f, flickerSpeed = 1f) } }) {
            SliderGrid(vm, listOf(
                p("Gate weave", 0f, 1f, 0f, "%.2f", { it.weave }) { x, v -> x.copy(weave = v) },
                p("Weave speed", 0.1f, 4f, 1f, "%.2f", { it.weaveSpeed }) { x, v -> x.copy(weaveSpeed = v) },
                p("Flicker", 0f, 1f, 0f, "%.2f", { it.flicker }) { x, v -> x.copy(flicker = v) },
                p("Flicker speed", 0.1f, 4f, 1f, "%.2f", { it.flickerSpeed }) { x, v -> x.copy(flickerSpeed = v) },
            ))
        }
    }
}

// ---- Motion Effects --------------------------------------------------------------------------------

@Composable
internal fun NoisePanel(vm: EditorViewModel) {
    val n = gr(vm).noise
    fun set(f: (NoiseLayer) -> NoiseLayer) = vm.gradeOnce { it.copy(noise = f(it.noise)) }
    fun p(label: String, get: (NoiseLayer) -> Float, put: (NoiseLayer, Float) -> NoiseLayer, def: Float = 0f) =
        P(label, 0f, 100f, def, "%.1f", { get(it.noise) }) { g, v -> g.copy(noise = put(g.noise, v)) }
    val tOn = n.tFrames > 0
    Scroll {
        PaletteHeader("Motion Effects", "Temporal and spatial noise reduction, and motion blur", n.on, n != NoiseLayer(), { set { it.copy(on = !it.on) } }, { set { NoiseLayer() } })
        SectionCard("Temporal NR", "Compares each frame with the ones before it; strongest on still areas", n.temporal,
            onReset = { set { it.copy(tFrames = 0, tMoEst = 1, tRange = 1, tLuma = 0f, tChroma = 0f, tLink = true, tMotion = 50f, tBlend = 0f) } }) {
            ChoiceRow("Frames", (0..5).map { "$it" }, n.tFrames) { k -> set { it.copy(tFrames = k) } }
            ChoiceRow("Motion estimation", NoiseLayer.MO_EST, n.tMoEst, enabled = tOn) { k -> set { it.copy(tMoEst = k) } }
            ChoiceRow("Motion range", NoiseLayer.RANGES, n.tRange, enabled = tOn) { k -> set { it.copy(tRange = k) } }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ProSlider(vm, p("Luma", { it.tLuma }, { x, v -> x.copy(tLuma = v, tChroma = if (x.tLink) v else x.tChroma) }), Modifier.weight(1f), enabled = tOn)
                ProSlider(vm, p("Chroma", { if (it.tLink) it.tLuma else it.tChroma }, { x, v -> x.copy(tChroma = v, tLuma = if (x.tLink) v else x.tLuma) }), Modifier.weight(1f), enabled = tOn)
            }
            ProToggle(if (n.tLink) "Luma and chroma linked" else "Luma and chroma apart", n.tLink) { set { it.copy(tLink = !it.tLink, tChroma = it.tLuma) } }
            SliderGrid(vm, listOf(p("Motion", { it.tMotion }, { x, v -> x.copy(tMotion = v) }, 50f), p("Blend", { it.tBlend }, { x, v -> x.copy(tBlend = v) })), enabled = tOn)
        }
        SectionCard("Spatial NR", "Within each frame; edges are kept", n.spatial,
            onReset = { set { it.copy(sMode = 0, sRadius = 0, sLuma = 0f, sChroma = 0f, sLink = true, sBlend = 0f) } }) {
            ChoiceRow("Mode", NoiseLayer.MODES, n.sMode) { k -> set { it.copy(sMode = k) } }
            ChoiceRow("Radius", NoiseLayer.RANGES, n.sRadius) { k -> set { it.copy(sRadius = k) } }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProSlider(vm, p("Luma", { it.sLuma }, { x, v -> x.copy(sLuma = v, sChroma = if (x.sLink) v else x.sChroma) }), Modifier.weight(1f))
                ProSlider(vm, p("Chroma", { if (it.sLink) it.sLuma else it.sChroma }, { x, v -> x.copy(sChroma = v, sLuma = if (x.sLink) v else x.sLuma) }), Modifier.weight(1f))
            }
            ProToggle(if (n.sLink) "Luma and chroma linked" else "Luma and chroma apart", n.sLink) { set { it.copy(sLink = !it.sLink, sChroma = it.sLuma) } }
            ProSlider(vm, p("Blend", { it.sBlend }, { x, v -> x.copy(sBlend = v) }))
        }
        SectionCard("Motion Blur", "Blur where things move between frames", n.blur, onReset = { set { it.copy(mbAmount = 0f, mbMoEst = 1, mbRange = 1) } }) {
            ChoiceRow("Motion estimation", NoiseLayer.MO_EST, n.mbMoEst) { k -> set { it.copy(mbMoEst = k) } }
            ChoiceRow("Motion range", NoiseLayer.RANGES, n.mbRange) { k -> set { it.copy(mbRange = k) } }
            ProSlider(vm, p("Motion blur", { it.mbAmount }, { x, v -> x.copy(mbAmount = v) }))
        }
    }
}

// ---- the smaller palettes ----------------------------------------------------------------------------

@Composable
internal fun LensPanel(vm: EditorViewModel) {
    val l = gr(vm).lens
    Scroll {
        PaletteHeader("Lens", "Lens faults, put right as the picture comes in", l.on, l != LensLayer(),
            { vm.gradeOnce { it.copy(lens = it.lens.copy(on = !it.lens.on)) } }, { vm.gradeOnce { it.copy(lens = LensLayer()) } })
        SectionCard("Correction", null, l.active) {
            ProSlider(vm, P("Chromatic aberration", -10f, 10f, 0f, "%+.2f", { it.lens.ca }) { g, v -> g.copy(lens = g.lens.copy(ca = v)) })
            ProSlider(vm, P("Distortion (barrel – pincushion)", -30f, 30f, 0f, "%+.1f", { it.lens.distortion }) { g, v -> g.copy(lens = g.lens.copy(distortion = v)) })
            ProSlider(vm, P("Corner falloff", -100f, 100f, 0f, "%+.0f", { it.lens.vignette }) { g, v -> g.copy(lens = g.lens.copy(vignette = v)) })
        }
    }
}

@Composable
internal fun DeflickerPanel(vm: EditorViewModel) {
    val d = gr(vm).deflicker
    Scroll {
        PaletteHeader("Deflicker", "Evens out brightness that pulses from frame to frame (lights, timelapses)", d.on, d != DeflickerLayer(),
            { vm.gradeOnce { it.copy(deflicker = it.deflicker.copy(on = !it.deflicker.on)) } }, { vm.gradeOnce { it.copy(deflicker = DeflickerLayer()) } })
        SectionCard("Settings", "Follow: how quickly real changes in brightness are let through", d.on) {
            SliderGrid(vm, listOf(
                P("Strength", 0f, 100f, 80f, "%.0f", { it.deflicker.strength }) { g, v -> g.copy(deflicker = g.deflicker.copy(strength = v)) },
                P("Follow", 1f, 100f, 20f, "%.0f", { it.deflicker.speed }) { g, v -> g.copy(deflicker = g.deflicker.copy(speed = v)) },
            ), enabled = d.on)
        }
    }
}

@Composable
internal fun DetailPanel(vm: EditorViewModel) {
    val d = gr(vm).detail
    Scroll {
        PaletteHeader("Detail", "Sharpen or soften, and local contrast", d.on, d != DetailLayer() || gr(vm).midDetail != 0f,
            { vm.gradeOnce { it.copy(detail = it.detail.copy(on = !it.detail.on)) } }, { vm.gradeOnce { it.copy(detail = DetailLayer(), midDetail = 0f) } })
        SectionCard("Sharpness", null, d.sharpen > 0f || d.soften > 0f) {
            SliderGrid(vm, listOf(
                P("Sharpen", 0f, 100f, 0f, "%.0f", { it.detail.sharpen }) { g, v -> g.copy(detail = g.detail.copy(sharpen = v)) },
                P("Soften", 0f, 100f, 0f, "%.0f", { it.detail.soften }) { g, v -> g.copy(detail = g.detail.copy(soften = v)) },
                P("Radius", 0.5f, 4f, 1.5f, "%.2f", { it.detail.radius }) { g, v -> g.copy(detail = g.detail.copy(radius = v)) },
                P("Mid/Detail", -100f, 100f, 0f, "%.1f", { it.midDetail }) { g, v -> g.copy(midDetail = v) },
            ))
        }
    }
}

@Composable
internal fun MixerPanel(vm: EditorViewModel) {
    val m = gr(vm).mixer
    val names = listOf("Red", "Green", "Blue")
    val inks = listOf(Color(0xFFE85A52), Color(0xFF52D07A), Color(0xFF5C8DFF))
    Scroll {
        PaletteHeader("RGB Mixer", "Each output channel made from the red, green and blue coming in", m.on, m != MixerLayer(),
            { vm.gradeOnce { it.copy(mixer = it.mixer.copy(on = !it.mixer.on)) } }, { vm.gradeOnce { it.copy(mixer = MixerLayer()) } })
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProToggle("Monochrome", m.mono) { vm.gradeOnce { it.copy(mixer = it.mixer.copy(mono = !it.mixer.mono)) } }
            ProToggle("Preserve luminance", m.preserveLum) { vm.gradeOnce { it.copy(mixer = it.mixer.copy(preserveLum = !it.mixer.preserveLum)) } }
        }
        for (o in 0..2) {
            val row = m.m.copyOfRange(o * 3, o * 3 + 3)
            val ident = row.contentEquals(FloatArray(3) { if (it == o) 1f else 0f })
            SectionCard(if (m.mono && o == 0) "Monochrome output" else "${names[o]} output", null, !ident,
                onReset = { vm.gradeOnce { g -> g.copy(mixer = g.mixer.copy(m = g.mixer.m.copyOf().also { for (i in 0..2) it[o * 3 + i] = if (i == o) 1f else 0f })) } }) {
                for (i in 0..2) ProSlider(vm, P("from ${names[i]}", -2f, 2f, if (o == i) 1f else 0f, "%.2f", { it.mixer.m[o * 3 + i] }) { g, v ->
                    g.copy(mixer = g.mixer.copy(m = g.mixer.m.copyOf().also { it[o * 3 + i] = v }))
                }, track = Brush.horizontalGradient(listOf(Color(0xFF202126), inks[i])))
            }
            if (m.mono) break
        }
        ProSlider(vm, P("Mix", 0f, 1f, 1f, "%.2f", { it.mixer.mix }) { g, v -> g.copy(mixer = g.mixer.copy(mix = v)) })
    }
}

@Composable
internal fun SlicePanel(vm: EditorViewModel) {
    val s = gr(vm).slice
    var sel by remember { mutableIntStateOf(1) }
    Scroll {
        PaletteHeader("Colour Slice", "Hue, saturation and density for seven slices of colour", s.on, s != SliceLayer(),
            { vm.gradeOnce { it.copy(slice = it.slice.copy(on = !it.slice.on)) } }, { vm.gradeOnce { it.copy(slice = SliceLayer()) } })
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for ((k, name) in SliceLayer.NAMES.withIndex()) {
                val used = s.hue[k] != 0f || s.sat[k] != 1f || s.density[k] != 0f
                val on = k == sel
                val col = if (k == 1) Color(0xFFE2A27E) else hueColour(SliceLayer.CENTRES[k], 0.55f, 0.36f)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFF2B2219) else Pro.Card)
                        .border(1.dp, if (on) col else Pro.Line, RoundedCornerShape(10.dp)).clickable { sel = k }.padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(col))
                    Text(name, color = if (on) Pro.Text else Pro.Dim, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                    Box(Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape).background(if (used) Palette.Accent else Color.Transparent))
                }
            }
        }
        val k = sel
        key(k) {
            SectionCard(SliceLayer.NAMES[k], null, s.hue[k] != 0f || s.sat[k] != 1f || s.density[k] != 0f,
                onReset = { vm.gradeOnce { g -> g.copy(slice = g.slice.copy(hue = g.slice.hue.copyOf().also { it[k] = 0f }, sat = g.slice.sat.copyOf().also { it[k] = 1f }, density = g.slice.density.copyOf().also { it[k] = 0f })) } }) {
                ProSlider(vm, P("Hue", -0.1f, 0.1f, 0f, "%+.3f", { it.slice.hue[k] }) { g, v -> g.copy(slice = g.slice.copy(hue = g.slice.hue.copyOf().also { it[k] = v })) },
                    track = Brush.horizontalGradient(listOf(hueColour(SliceLayer.CENTRES[k] - 0.1f), hueColour(SliceLayer.CENTRES[k]), hueColour(SliceLayer.CENTRES[k] + 0.1f))))
                ProSlider(vm, P("Saturation", 0f, 2f, 1f, "%.2f", { it.slice.sat[k] }) { g, v -> g.copy(slice = g.slice.copy(sat = g.slice.sat.copyOf().also { it[k] = v })) },
                    track = Brush.horizontalGradient(listOf(Color(0xFF7A7A7A), hueColour(SliceLayer.CENTRES[k], 0.55f, 0.4f))))
                ProSlider(vm, P("Density", 0f, 1f, 0f, "%.2f", { it.slice.density[k] }) { g, v -> g.copy(slice = g.slice.copy(density = g.slice.density.copyOf().also { it[k] = v })) })
            }
        }
        SectionCard("Whole picture", null, s.globalDensity != 0f || s.mix != 1f, onReset = { vm.gradeOnce { it.copy(slice = it.slice.copy(globalDensity = 0f, mix = 1f)) } }) {
            SliderGrid(vm, listOf(
                P("Global density", 0f, 1f, 0f, "%.2f", { it.slice.globalDensity }) { g, v -> g.copy(slice = g.slice.copy(globalDensity = v)) },
                P("Mix", 0f, 1f, 1f, "%.2f", { it.slice.mix }) { g, v -> g.copy(slice = g.slice.copy(mix = v)) },
            ))
        }
    }
}

@Composable
internal fun LogPanel(vm: EditorViewModel) {
    val l = gr(vm).logWheels
    Scroll {
        PaletteHeader("Log Wheels", "Colour and level for shadows, midtones and highlights", l.on, l != LogLayer(),
            { vm.gradeOnce { it.copy(logWheels = it.logWheels.copy(on = !it.logWheels.on)) } }, { vm.gradeOnce { it.copy(logWheels = LogLayer()) } })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((k, name) in listOf("Shadow", "Midtone", "Highlight").withIndex()) {
                val w = when (k) { 0 -> l.shadow; 1 -> l.midtone; else -> l.highlight }
                fun with(g: Grade, nw: Wheel) = g.copy(logWheels = when (k) { 0 -> g.logWheels.copy(shadow = nw); 1 -> g.logWheels.copy(midtone = nw); else -> g.logWheels.copy(highlight = nw) })
                fun of(g: Grade) = when (k) { 0 -> g.logWheels.shadow; 1 -> g.logWheels.midtone; else -> g.logWheels.highlight }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Pro.Card).border(1.dp, Pro.Line, RoundedCornerShape(14.dp)).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name, color = if (w.isZero) Pro.Dim else Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    DiscPad(vm, w.u, w.v, Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp)) { u, v -> { g: Grade -> with(g, of(g).copy(u = u, v = v)) } }
                    ProSlider(vm, P("Level", -1f, 1f, 0f, "%+.2f", { of(it).y }) { g, v -> with(g, of(g).copy(y = v)) })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SectionCard("Ranges", "Where shadows end and highlights start", l.low != 0.333f || l.high != 0.55f || l.mix != 1f,
            onReset = { vm.gradeOnce { it.copy(logWheels = it.logWheels.copy(low = 0.333f, high = 0.55f, mix = 1f)) } }) {
            RangeSlider("Low – High", l.low, l.high, 0.08f, Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                { vm.gradeStart() }, { a, b -> vm.setGrade { g -> g.copy(logWheels = g.logWheels.copy(low = a.coerceIn(0.05f, 0.6f), high = b.coerceIn(0.4f, 0.95f))) } }, { vm.gradeEnd() })
            ProSlider(vm, P("Mix", 0f, 1f, 1f, "%.2f", { it.logWheels.mix }) { g, v -> g.copy(logWheels = g.logWheels.copy(mix = v)) })
        }
    }
}

@Composable
internal fun OutputPanel(vm: EditorViewModel) {
    val l = gr(vm).limiter
    Scroll {
        PaletteHeader("Output", "The last step before the timeline's colour space", l.on, l != LimiterLayer(),
            { vm.gradeOnce { it.copy(limiter = it.limiter.copy(on = !it.limiter.on)) } }, { vm.gradeOnce { it.copy(limiter = LimiterLayer()) } })
        SectionCard("Limiter", "Eases highlights into the top of the range instead of clipping, and caps saturation", l.on) {
            ChoiceRow("Highlights", listOf("Clip", "Soft clip"), if (l.softClip) 1 else 0, enabled = l.on) { k -> vm.gradeOnce { it.copy(limiter = it.limiter.copy(softClip = k == 1)) } }
            ProSlider(vm, P("Saturation limit", 0.2f, 1f, 1f, "%.2f", { it.limiter.satLimit }) { g, v -> g.copy(limiter = g.limiter.copy(satLimit = v)) }, enabled = l.on)
        }
        Text("The delivery colour space, tone mapping and HDR peak are in the Colour space layer, under Project.", color = Pro.Dim, fontSize = 11.sp, modifier = Modifier.padding(4.dp))
    }
}

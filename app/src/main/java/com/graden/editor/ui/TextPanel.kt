package com.graden.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.Caption
import com.graden.editor.CaptionRender
import com.graden.editor.EditorViewModel
import com.graden.editor.TextAnim
import com.graden.editor.TextFonts
import com.graden.editor.TextLook
import kotlin.math.roundToInt

/** A text drawn as it shows on the video, on a dark tile: for the style lists. [t]: µs into an animation (none: still). */
@Composable
internal fun TextTile(cap: Caption, modifier: Modifier = Modifier, t: Long = -1L, durUs: Long = 2_000_000L) {
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2B2D36), Color(0xFF1C1D24))))) {
        val area = android.graphics.RectF(0f, 0f, size.width, size.height)
        // Big enough to read in a tile, wherever and however big it sits on a video.
        val fit = cap.copy(x = 0.5f, y = 0.5f, rot = 0f, scale = (cap.scale * 2.2f).coerceAtMost(4.2f))
        drawIntoCanvas { c -> if (t < 0) CaptionRender.draw(c.nativeCanvas, fit, area) else CaptionRender.draw(c.nativeCanvas, fit, area, t, durUs) }
    }
}

private val Swatches = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF8A8D99, 0xFFFFE14D, 0xFFFFB547, 0xFFF0872B, 0xFFE5484D, 0xFFFF4D6D, 0xFFFF7AB6,
    0xFFB57CFF, 0xFF6E7BFF, 0xFF3B9BFF, 0xFF7DF9FF, 0xFF2EC4B6, 0xFF4CC38A, 0xFFA3E635, 0xFF8B5A2B, 0xFF2B0A3D,
).map { it.toInt() }

/**
 * The text panel, over the timeline while a text is being styled, the picture still in view: its words and size,
 * its font, its colours (text, outline, shadow, box, decoration) and spacing, and how it comes in and goes out. A
 * caption made from the sound is styled together with the others on its lane unless "Only this" is chosen.
 */
@Composable
fun BoxScope.TextPanel(vm: EditorViewModel) {
    val id = vm.textPanelFor ?: return
    val cap = vm.captionOf(id)
    if (cap == null) { vm.textPanelFor = null; return }
    BackHandler { vm.textPanelFor = null }
    // Captions are styled as a set by default; a text added by hand on its own.
    var lane by remember(id) { mutableStateOf(cap.fromSound) }
    fun edit(f: (Caption) -> Caption) = vm.styleText(id, lane, f)
    // A change made of many steps (a slider) is one undo step.
    val start = { vm.textEditStart() }
    val end = { vm.textEditEnd() }
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(360.dp).clip(shape).background(Kit.Sheet)
            .border(1.dp, Kit.Hair, shape).navigationBarsPadding().clickable(enabled = false) {},
    ) {
        KGrabber()
        Row(Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            KTabs(listOf("Text", "Font", "Style", "Animate"), vm.textTab, Modifier.weight(1f)) { vm.textTab = it }
            if (cap.fromSound) Pill(if (lane) "All captions" else "Only this", lane) { lane = !lane }
            Spacer(Modifier.width(8.dp))
            KIconButton(Lucide.Check, "Done", { vm.textPanelFor = null }, filled = true, size = 38.dp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Kit.Hair))
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 10.dp)) {
            when (vm.textTab) {
                0 -> WordsTab(vm, id, cap, ::edit, start, end)
                1 -> FontTab(cap, ::edit, start, end)
                2 -> StyleTab(cap, ::edit, start, end)
                else -> AnimTab(vm, id, cap, ::edit, start, end)
            }
        }
    }
}

/** An on/off pill, or a choice among a few. */
@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (on) Kit.Ink else Kit.Text, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(if (on) Kit.Text else Kit.Raised).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun WordsTab(vm: EditorViewModel, id: Long, cap: Caption, edit: ((Caption) -> Caption) -> Unit, start: () -> Unit, end: () -> Unit) {
    // Typing is one undo step from the first letter to leaving the field.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        var typing by remember(id) { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(16.dp)).background(Kit.Group).padding(14.dp)) {
            if (cap.text.isEmpty()) Text("Type here", color = Kit.Faint, fontSize = 17.sp)
            BasicTextField(
                cap.text, { t -> if (!typing) { typing = true; vm.textEditStart() }; vm.styleText(id) { it.copy(text = t, tintFrom = if (it.tintFrom > 0 && '\n' in t) t.indexOf('\n') + 1 else -1) } },
                textStyle = TextStyle(color = Kit.Text, fontSize = 17.sp), cursorBrush = SolidColor(Kit.Accent), modifier = Modifier.fillMaxSize(),
            )
        }
        DisposableEffect(id) { onDispose { if (typing) vm.textEditEnd() } }
        Spacer(Modifier.height(12.dp))
        ProSliderRaw("Size", cap.scale * 100f, 20f, 600f, 100f, "%.0f%%", start, { v -> edit { it.copy(scale = v / 100f) } }, end,
            { edit { it.copy(scale = 1f) } }, null)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KSegmented(listOf("Left", "Centre", "Right"), cap.look.align, Modifier.weight(1f)) { a -> edit { it.copy(look = it.look.copy(align = a)) } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Bold", cap.look.weight >= 700) { edit { it.copy(look = it.look.copy(weight = if (it.look.weight >= 700) 500 else 800)) } }
            Pill("Italic", cap.look.italic) { edit { it.copy(look = it.look.copy(italic = !it.look.italic)) } }
            Pill("ALL CAPS", cap.look.caps) { edit { it.copy(look = it.look.copy(caps = !it.look.caps)) } }
            Pill("Reset position", false) { edit { it.copy(x = if (it.kind == Caption.TEXT) 0.5f else Float.NaN, y = if (it.kind == Caption.TEXT) 0.5f else Float.NaN, rot = 0f) } }
        }
        Text("Drag the text on the picture to move it; pinch to resize, twist to turn.", color = Kit.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, start = 2.dp))
    }
}

@Composable
private fun FontTab(cap: Caption, edit: ((Caption) -> Caption) -> Unit, start: () -> Unit, end: () -> Unit) {
    var group by remember { mutableStateOf(TextFonts.of(cap.look.font).group) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in TextFonts.GROUPS) Pill(g, g == group) { group = g }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val fonts = TextFonts.ALL.filter { it.group == group }
            for (row in fonts.chunked(3)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in row) {
                        val on = f.id == cap.look.font
                        val family = remember(f.id) { FontFamily(androidx.compose.ui.text.font.Typeface(TextFonts.typeface(f.id, 600))) }
                        Column(
                            Modifier.weight(1f).height(78.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Kit.AccentSoft else Kit.Group)
                                .border(1.5.dp, if (on) Kit.Accent else Color.Transparent, RoundedCornerShape(14.dp))
                                .clickable { edit { it.copy(look = it.look.copy(font = f.id)) } }.padding(horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            val sample = when (f.group) { "हिन्दी" -> "अआ"; "বাংলা" -> "অআ"; else -> "Aa" }
                            Text(sample, color = if (on) Kit.Accent else Kit.Text, fontSize = 26.sp, fontFamily = family, maxLines = 1)
                            Text(f.name, color = if (on) Kit.Accent else Kit.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (TextFonts.of(cap.look.font).variable) ProSliderRaw("Weight", cap.look.weight.toFloat(), 100f, 900f, 600f, "%.0f", start,
            { v -> edit { it.copy(look = it.look.copy(weight = ((v / 50f).roundToInt() * 50))) } }, end, { edit { it.copy(look = it.look.copy(weight = 600)) } }, null)
    }
}

@Composable
private fun StyleTab(cap: Caption, edit: ((Caption) -> Caption) -> Unit, start: () -> Unit, end: () -> Unit) {
    var part by remember { mutableIntStateOf(0) }
    val l = cap.look
    fun look(f: (TextLook) -> TextLook) = edit { it.copy(look = f(it.look)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, n) in listOf("Colour", "Outline", "Shadow", "Box", "Lines", "Spacing").withIndex()) Pill(n, part == i) { part = i }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (part) {
                0 -> {
                    ColorRow(l.color, false) { c -> look { it.copy(color = c) } }
                    ProSliderRaw("Opacity", l.opacity * 100f, 0f, 100f, 100f, "%.0f%%", start, { v -> look { it.copy(opacity = v / 100f) } }, end, { look { it.copy(opacity = 1f) } }, null)
                    if ('\n' in cap.text) {
                        Text("Lines after the first", color = Kit.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 4.dp))
                        ColorRow(l.subColor, false) { c -> look { it.copy(subColor = c) } }
                        ProSliderRaw("Their size", l.sub * 100f, 30f, 100f, 100f, "%.0f%%", start, { v -> look { it.copy(sub = v / 100f) } }, end, { look { it.copy(sub = 1f) } }, null)
                    }
                }
                1 -> {
                    ColorRow(l.strokeColor, true) { c -> look { it.copy(strokeColor = c) } }
                    ProSliderRaw("Thickness", l.stroke * 100f, 0f, 40f, 13f, "%.0f", start, { v -> look { it.copy(stroke = v / 100f) } }, end, { look { it.copy(stroke = 0.13f) } }, null)
                }
                2 -> {
                    ColorRow(l.shadowColor, true) { c -> look { it.copy(shadowColor = c) } }
                    ProSliderRaw("Softness", l.shadow * 100f, 0f, 80f, 10f, "%.0f", start, { v -> look { it.copy(shadow = v / 100f) } }, end, { look { it.copy(shadow = 0.10f) } }, null)
                }
                3 -> {
                    ColorRow(l.box, true, none = true) { c -> look { it.copy(box = c) } }
                    ProSliderRaw("Box opacity", android.graphics.Color.alpha(l.box) / 2.55f, 0f, 100f, 80f, "%.0f%%", start,
                        { v -> look { it.copy(box = (it.box and 0xFFFFFF) or ((v * 2.55f).roundToInt().coerceIn(0, 255) shl 24)) } }, end, { look { it.copy(box = 0) } }, null)
                    ProSliderRaw("Corners", l.boxRound * 100f, 0f, 80f, 22f, "%.0f", start, { v -> look { it.copy(boxRound = v / 100f) } }, end, { look { it.copy(boxRound = 0.22f) } }, null)
                }
                4 -> {
                    // The decoration, each shown as it looks.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((i, n) in listOf("None", "Underline", "Lines", "Frame", "Side bar").withIndex()) {
                            val on = l.decor == i
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) Kit.AccentSoft else Kit.Group)
                                    .border(1.5.dp, if (on) Kit.Accent else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable { look { it.copy(decor = i) } }.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                TextTile(Caption("Aa", Caption.TEXT, look = TextLook(stroke = 0f, shadow = 0f, decor = i, decorColor = if (on) Kit.Accent.toArgb() else 0xFFFFFFFF.toInt())),
                                    Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp))
                                Text(n, color = if (on) Kit.Accent else Kit.Dim, fontSize = 10.5.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    if (l.decor != TextLook.DECOR_NONE) { Spacer(Modifier.height(10.dp)); ColorRow(l.decorColor, false) { c -> look { it.copy(decorColor = c) } } }
                }
                else -> {
                    ProSliderRaw("Letters", l.letter * 100f, -10f, 60f, 0f, "%.0f", start, { v -> look { it.copy(letter = v / 100f) } }, end, { look { it.copy(letter = 0f) } }, null)
                    ProSliderRaw("Lines", l.line * 100f, 70f, 200f, 108f, "%.0f%%", start, { v -> look { it.copy(line = v / 100f) } }, end, { look { it.copy(line = 1.08f) } }, null)
                }
            }
        }
    }
}

/** Colour swatches; [keepAlpha]: a colour picked keeps the current transparency. [none]: offers "no colour". */
@Composable
private fun ColorRow(current: Int, keepAlpha: Boolean, none: Boolean = false, pick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (none) {
            val off = android.graphics.Color.alpha(current) == 0
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Kit.Group).border(if (off) 2.dp else 1.dp, if (off) Kit.Accent else Kit.Hair, CircleShape).clickable { pick(0) },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Ban, "None", tint = Kit.Dim, modifier = Modifier.size(16.dp)) }
        }
        for (s in Swatches) {
            val on = (s and 0xFFFFFF) == (current and 0xFFFFFF) && android.graphics.Color.alpha(current) > 0
            Box(Modifier.size(36.dp).clip(CircleShape).border(if (on) 2.dp else 0.dp, if (on) Kit.Accent else Color.Transparent, CircleShape).padding(if (on) 4.dp else 0.dp)
                .clip(CircleShape).background(Color(s)).border(1.dp, Color(0x22FFFFFF), CircleShape)
                .clickable {
                    val a = android.graphics.Color.alpha(current)
                    pick(if (keepAlpha && a > 0) (s and 0xFFFFFF) or (a shl 24) else if (none && a == 0) (s and 0xFFFFFF) or (0xCC shl 24) else s)
                })
        }
    }
}

@Composable
private fun AnimTab(vm: EditorViewModel, id: Long, cap: Caption, edit: ((Caption) -> Caption) -> Unit, start: () -> Unit, end: () -> Unit) {
    var out by remember { mutableStateOf(false) }
    val chosen = if (out) cap.animOut else cap.animIn
    Column(Modifier.fillMaxSize()) {
        KSegmented(listOf("In", "Out"), if (out) 1 else 0, Modifier.fillMaxWidth()) { out = it == 1 }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            for (row in TextAnim.ALL.chunked(4)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in row) {
                        val on = k.id == chosen
                        Box(
                            Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Kit.AccentSoft else Kit.Group)
                                .border(1.5.dp, if (on) Kit.Accent else Color.Transparent, RoundedCornerShape(14.dp))
                                .clickable {
                                    edit { if (out) it.copy(animOut = k.id) else it.copy(animIn = k.id) }
                                    // Shown at once: played from where it happens.
                                    if (k.id != TextAnim.NONE) vm.previewTextAnim(id, out)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(k.name, color = if (on) Kit.Accent else Kit.Text, fontSize = 12.5.sp, maxLines = 1, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (chosen != TextAnim.NONE) {
            val us = if (out) cap.outUs else cap.inUs
            ProSliderRaw("Duration", us / 1e6f, 0.1f, 3f, 0.4f, "%.1f s", start,
                { v -> edit { if (out) it.copy(outUs = (v * 1e6f).toLong()) else it.copy(inUs = (v * 1e6f).toLong()) } }, { end(); vm.previewTextAnim(id, out) },
                { edit { if (out) it.copy(outUs = 400_000L) else it.copy(inUs = 400_000L) } }, null)
        }
    }
}

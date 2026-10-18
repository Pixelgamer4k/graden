package com.graden.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.ColorSpaces
import com.graden.editor.EditorViewModel
import com.graden.editor.Grade
import com.graden.editor.RawColor
import com.graden.editor.RawLayer
import kotlin.math.roundToInt

/**
 * Camera RAW, as Resolve's Camera Raw palette: how a RAW recording's sensor data is developed. Decode from the
 * camera's metadata or with the clip's own settings; decode quality and lens shading (the recording's master is
 * developed again); white balance, ISO and exposure; the decode's colour space and gamma; tone, colour, highlight
 * recovery, levels and detail. Everything but the decode quality is live. For clips that are not RAW it is greyed.
 */
@Composable
internal fun RawPanel(vm: EditorViewModel) {
    val clip = vm.gradeTarget?.clip
    val asset = clip?.let { vm.assets[it.assetId] }
    val g = clip?.grade ?: Grade()
    val r = g.raw
    val cal = asset?.let { vm.rawCal(it) }
    val isRaw = asset?.isRaw == true && cal != null
    val view = LocalView.current
    // Editing anything switches to the clip's own settings, as touching a control does in Resolve.
    fun p(label: String, min: Float, max: Float, def: Float, fmt: String, get: (RawLayer) -> Float, put: (RawLayer, Float) -> RawLayer) =
        Primary(label, min, max, def, fmt, { get(it.raw) }) { gr, v -> gr.copy(raw = put(gr.raw, v).copy(decode = RawLayer.CLIP)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)) {
        PaletteHeader("Camera RAW", if (isRaw) "Developed from the sensor's own data, before everything else" else "For RAW recordings (MotionCam .mcraw): this clip is not RAW",
            if (isRaw) r.on else null, isRaw && !r.isDefault,
            { vm.gradeOnce { it.copy(raw = it.raw.copy(on = !it.raw.on)) } }, { vm.gradeOnce { it.copy(raw = RawLayer()) } })
        Column(Modifier.graphicsLayer { alpha = if (isRaw) 1f else 0.38f }) {
            // What was recorded.
            if (isRaw && cal != null) {
                val shutter = if (cal.exposureNs > 0) "1/${(1e9 / cal.exposureNs).roundToInt()}" else "–"
                val (t0, ti0) = RawColor.asShotTempTint(cal)
                SectionCard("Shot", null, false) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Info("ISO", "${cal.iso}", Modifier.weight(1f))
                        Info("Shutter", shutter, Modifier.weight(1f))
                        Info("Aperture", if (cal.aperture > 0) "f/%.1f".format(cal.aperture) else "–", Modifier.weight(1f))
                        Info("WB", "${t0.roundToInt()}K", Modifier.weight(1f))
                    }
                    Text("${cal.model.ifEmpty { "Camera" }}  ·  ${cal.width}×${cal.height}  ·  ${"%.0f".format(cal.focal)} mm  ·  tint ${"%+.0f".format(ti0)}",
                        color = Pro.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp, bottom = 12.dp))
                }
            }
            SectionCard("Decode", "Camera metadata develops as recorded; Clip uses the settings below", r.decode == RawLayer.CLIP) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ChoiceRow("Decode using", listOf("Camera metadata", "Clip"), r.decode, enabled = isRaw) { d -> vm.gradeOnce { it.copy(raw = it.raw.copy(decode = d)) } }
                    val busy = asset?.let { vm.redeveloping[it.id] }
                    ChoiceRow("Decode quality", listOf("Full resolution", "Half resolution"), if (asset?.rawHalf == true) 1 else 0, enabled = isRaw && busy == null) { q ->
                        asset?.let { vm.redevelopRaw(it, q == 1, it.rawShading) }
                    }
                    ChoiceRow("Lens shading correction", listOf("On", "Off"), if (asset?.rawShading == false) 1 else 0, enabled = isRaw && busy == null) { q ->
                        asset?.let { vm.redevelopRaw(it, it.rawHalf, q == 0) }
                    }
                    if (busy != null) Text("Developing again  ${(busy * 100).toInt()}%  ·  the clip plays meanwhile", color = Palette.Accent, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                }
            }
            SectionCard("White balance", null, r.wb == RawLayer.CUSTOM && r.decode == RawLayer.CLIP) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                    val (t0, ti0) = cal?.let { RawColor.asShotTempTint(it) } ?: (5500.0 to 0.0)
                    // As shot, the presets, and custom, as chips.
                    val names = listOf("As shot") + RawLayer.PRESETS.map { it.first } + listOf("Custom")
                    val sel = when {
                        r.wb == RawLayer.AS_SHOT -> 0
                        else -> RawLayer.PRESETS.indexOfFirst { it.second == r.temp && r.tint == 0f }.let { if (it >= 0) it + 1 else names.size - 1 }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((i, n) in names.withIndex()) Chip(n, i == sel && r.decode == RawLayer.CLIP || (i == 0 && r.decode == RawLayer.CAMERA), isRaw) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            vm.gradeOnce { gr ->
                                gr.copy(raw = when (i) {
                                    0 -> gr.raw.copy(wb = RawLayer.AS_SHOT, decode = RawLayer.CLIP)
                                    names.size - 1 -> gr.raw.copy(wb = RawLayer.CUSTOM, temp = if (gr.raw.wb == RawLayer.CUSTOM) gr.raw.temp else t0.toFloat(), tint = if (gr.raw.wb == RawLayer.CUSTOM) gr.raw.tint else ti0.toFloat(), decode = RawLayer.CLIP)
                                    else -> gr.raw.copy(wb = RawLayer.CUSTOM, temp = RawLayer.PRESETS[i - 1].second, tint = 0f, decode = RawLayer.CLIP)
                                })
                            }
                        }
                    }
                    val showT = if (r.wb == RawLayer.CUSTOM && r.decode == RawLayer.CLIP) r.temp else t0.toFloat()
                    val showTi = if (r.wb == RawLayer.CUSTOM && r.decode == RawLayer.CLIP) r.tint else ti0.toFloat()
                    ProSlider(vm, Primary("Colour temperature", 2000f, 12000f, t0.toFloat(), "%.0f K", { showT }) { gr, v ->
                        gr.copy(raw = gr.raw.copy(wb = RawLayer.CUSTOM, temp = v, tint = if (gr.raw.wb == RawLayer.CUSTOM) gr.raw.tint else ti0.toFloat(), decode = RawLayer.CLIP))
                    }, enabled = isRaw, track = Brush.horizontalGradient(listOf(Color(0xFF4F7BFF), Color(0xFFE9EEF7), Color(0xFFFFB24A))))
                    ProSlider(vm, Primary("Tint", -150f, 150f, ti0.toFloat(), "%+.0f", { showTi }) { gr, v ->
                        gr.copy(raw = gr.raw.copy(wb = RawLayer.CUSTOM, tint = v, temp = if (gr.raw.wb == RawLayer.CUSTOM) gr.raw.temp else t0.toFloat(), decode = RawLayer.CLIP))
                    }, enabled = isRaw, track = Brush.horizontalGradient(listOf(Color(0xFF3FBF6A), Color(0xFFE9EEF7), Color(0xFFD45AD2))))
                }
            }
            SectionCard("Exposure", "ISO is relative to the ISO it was shot at", r.decode == RawLayer.CLIP && (r.iso != 0f || r.exposure != 0f)) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                    Text("ISO", color = Pro.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    val shot = cal?.iso ?: 0
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(if (shot > 0) "As shot $shot" else "As shot", r.iso == 0f || r.decode == RawLayer.CAMERA, isRaw) { vm.gradeOnce { it.copy(raw = it.raw.copy(iso = 0f, decode = RawLayer.CLIP)) } }
                        for (iso in RawLayer.ISOS) Chip("$iso", r.decode == RawLayer.CLIP && r.iso == iso.toFloat(), isRaw) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            vm.gradeOnce { it.copy(raw = it.raw.copy(iso = iso.toFloat(), decode = RawLayer.CLIP)) }
                        }
                    }
                    ProSlider(vm, p("Exposure", -6f, 6f, 0f, "%+.2f EV", { it.exposure }) { l, v -> l.copy(exposure = v) }, enabled = isRaw)
                }
            }
            SectionCard("Colour space", "What the RAW is decoded into; the Colour space layer takes it from there", r.outGamut != null || r.outGamma != null) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    val gamuts = listOf("Rec.2020", "Rec.709", "P3-D65", "DaVinci Wide Gamut", "ACES (AP1)", "ARRI Wide Gamut 3", "ARRI Wide Gamut 4", "Sony S-Gamut3.Cine",
                        "Blackmagic Wide Gamut Gen 4/5", "RED Wide Gamut RGB", "Apple Log (Rec.2020)").filter { n -> ColorSpaces.gamuts.any { it.first == n } }
                    val gammasL = listOf("Linear", "DaVinci Intermediate", "ACEScct", "ARRI LogC3", "ARRI LogC4", "Sony S-Log3", "Blackmagic Design Film Gen 5", "RED Log3G10",
                        "Apple Log", "Rec.2100 ST2084", "Rec.2100 HLG", "Gamma 2.4", "Rec.709").filter { n -> ColorSpaces.gammas.any { it.name == n } }
                    Text("Colour space", color = Pro.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in gamuts) Chip(n, (r.outGamut ?: "Rec.2020") == n, isRaw) { vm.gradeOnce { gr -> gr.copy(raw = gr.raw.copy(outGamut = n.takeIf { n != "Rec.2020" })) } }
                    }
                    Text("Gamma", color = Pro.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in gammasL) Chip(n, (r.outGamma ?: "Linear") == n, isRaw) { vm.gradeOnce { it.copy(raw = it.raw.copy(outGamma = n.takeIf { n != "Linear" })) } }
                    }
                }
            }
            SectionCard("Tone", null, r.decode == RawLayer.CLIP && (r.contrast != 1f || r.highlights != 0f || r.shadows != 0f || r.highRolloff != 0f || r.lowRolloff != 0f || r.midpoint != 0.18f)) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                    SliderGrid(vm, listOf(
                        p("Contrast", 0f, 2f, 1f, "%.2f", { it.contrast }) { l, v -> l.copy(contrast = v) },
                        p("Midpoint", 0.02f, 0.6f, 0.18f, "%.3f", { it.midpoint }) { l, v -> l.copy(midpoint = v) },
                        p("Highlights", -1f, 1f, 0f, "%+.2f", { it.highlights }) { l, v -> l.copy(highlights = v) },
                        p("Shadows", -1f, 1f, 0f, "%+.2f", { it.shadows }) { l, v -> l.copy(shadows = v) },
                        p("Highlight roll-off", 0f, 4f, 0f, "%.2f", { it.highRolloff }) { l, v -> l.copy(highRolloff = v) },
                        p("Low roll-off", 0f, 2f, 0f, "%.2f", { it.lowRolloff }) { l, v -> l.copy(lowRolloff = v) },
                    ), enabled = isRaw)
                }
            }
            SectionCard("Colour", null, r.decode == RawLayer.CLIP && (r.saturation != 1f || r.colorBoost != 0f)) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                    SliderGrid(vm, listOf(
                        p("Saturation", 0f, 2f, 1f, "%.2f", { it.saturation }) { l, v -> l.copy(saturation = v) },
                        p("Colour boost", -1f, 1f, 0f, "%+.2f", { it.colorBoost }) { l, v -> l.copy(colorBoost = v) },
                    ), enabled = isRaw)
                }
            }
            SectionCard("Highlight recovery", "Clipped highlights carried to white instead of magenta", r.recovery != 0.5f) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                    ProSlider(vm, p("Recovery", 0f, 1f, 0.5f, "%.2f", { it.recovery }) { l, v -> l.copy(recovery = v) }, enabled = isRaw)
                }
            }
            SectionCard("Levels", "Fine-tunes the sensor's black and white points", r.blackOffset != 0f || r.whiteScale != 1f) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                    SliderGrid(vm, listOf(
                        p("Black level", -0.05f, 0.1f, 0f, "%+.3f", { it.blackOffset }) { l, v -> l.copy(blackOffset = v) },
                        p("White level", 0.5f, 1f, 1f, "%.2f", { it.whiteScale }) { l, v -> l.copy(whiteScale = v) },
                    ), enabled = isRaw)
                }
            }
            SectionCard("Detail", null, r.sharpness != 0f || r.midDetail != 0f) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                    SliderGrid(vm, listOf(
                        p("Sharpness", 0f, 2f, 0f, "%.2f", { it.sharpness }) { l, v -> l.copy(sharpness = v) },
                        p("Midtone detail", -1f, 1f, 0f, "%+.2f", { it.midDetail }) { l, v -> l.copy(midDetail = v) },
                    ), enabled = isRaw)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Info(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Pro.Well).padding(horizontal = 8.dp, vertical = 7.dp)) {
        Text(value, color = Pro.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Pro.Mono, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Text(label, color = Pro.Dim, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun Chip(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Color(0xFF2E2418) else Pro.CardHi)
            .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.6f) else Pro.Line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Palette.Accent else Pro.Text, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false) }
}

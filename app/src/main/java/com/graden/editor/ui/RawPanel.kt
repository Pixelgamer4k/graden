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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.ColorSpaces
import com.graden.editor.DevelopOptions
import com.graden.editor.EditorViewModel
import com.graden.editor.Grade
import com.graden.editor.RawColor
import com.graden.editor.RawLayer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * MCRAW: a MotionCam RAW recording, developed from its sensor data with the tools MotionCam's own apps and the open
 * MCRAW projects offer (MotionCam Fuse, mcraw-tui, mcraw4vulkan, MCRAW Studio). Develop options make the recording's
 * master again (vignette correction from the gain maps or only its colour, clipped photosites completed, hot pixels,
 * exposure and white balance normalised or smoothed across frames, constant frame rate, quad-Bayer binning, data
 * levels, draft quality); everything else is live: white balance, ISO and exposure, highlight recovery and levels,
 * the colour space and log it is decoded into, the colour matrices, the look and noise reduction. Shown only for
 * .mcraw clips.
 */
@Composable
internal fun RawPanel(vm: EditorViewModel) {
    val clip = vm.gradeTarget?.clip
    val asset = clip?.let { vm.assets[it.assetId] }
    val g = clip?.grade ?: Grade()
    val r = g.raw
    val cal = asset?.let { vm.rawCal(it) }
    if (asset == null || !asset.isRaw || cal == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("For MotionCam RAW (.mcraw) clips", color = Pro.Dim, fontSize = 13.sp) }
        return
    }
    val view = LocalView.current
    fun tick() = view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    // Touching a setting develops with the clip's own settings.
    fun p(label: String, min: Float, max: Float, def: Float, fmt: String, get: (RawLayer) -> Float, put: (RawLayer, Float) -> RawLayer) =
        Primary(label, min, max, def, fmt, { get(it.raw) }) { gr, v -> gr.copy(raw = put(gr.raw, v).copy(decode = RawLayer.CLIP)) }
    fun set(f: (RawLayer) -> RawLayer) = vm.gradeOnce { it.copy(raw = f(it.raw).copy(decode = RawLayer.CLIP)) }
    val clipR = r.decode == RawLayer.CLIP

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)) {
        PaletteHeader("MCRAW", "MotionCam RAW, developed from the sensor's own data", r.on, !r.isDefault,
            { vm.gradeOnce { it.copy(raw = it.raw.copy(on = !it.raw.on)) } }, { vm.gradeOnce { it.copy(raw = RawLayer()) } })

        // ---- What was recorded.
        val shutter = if (cal.exposureNs > 0) "1/${(1e9 / cal.exposureNs).roundToInt()}" else "–"
        val (t0, ti0) = RawColor.asShotTempTint(cal)
        SectionCard("Recording", null, false) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Info("ISO", "${cal.iso}", Modifier.weight(1f))
                    Info("Shutter", shutter, Modifier.weight(1f))
                    Info("Aperture", if (cal.aperture > 0) "f/%.1f".format(cal.aperture) else "–", Modifier.weight(1f))
                    Info("As shot", "${t0.roundToInt()}K", Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Info("Sensor", "${cal.width}×${cal.height}", Modifier.weight(1.4f))
                    Info("Frame rate", "%.2f".format(asset.fps), Modifier.weight(1f))
                    Info("Focal", if (cal.focal > 0) "%.1f mm".format(cal.focal) else "–", Modifier.weight(1f))
                    Info("Tint", "%+.0f".format(ti0), Modifier.weight(0.8f))
                }
                Text(cal.model.ifEmpty { "Camera" } + (if (asset.hasAudio) "  ·  with sound" else "") + "  ·  developed ${asset.width}×${asset.height}",
                    color = Pro.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 8.dp))
            }
        }

        // ---- Develop: the master is made again with these.
        val current = asset.developOptions
        var pending by remember(asset.id, current) { mutableStateOf(current) }
        val busy = vm.redeveloping[asset.id]
        val std = DevelopOptions.nearestStandard(asset.fps.toDouble())
        SectionCard("Develop", "Made into the clip's master; changing these develops it again", current != DevelopOptions()) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                // Older MotionCam versions recorded some clips with black 0: lifted, magenta shadows.
                if (cal.black.all { it == 0.0 } && current.black == null) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A1C14))
                        .border(1.dp, Palette.Accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(10.dp)) {
                        Text("This recording says its black level is 0", color = Palette.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("Clips from older MotionCam versions (12/14-bit DCG among them) were saved with the wrong levels, which gives a magenta cast. Set the black level under Black and white levels below (Detect finds it), then Develop again.",
                            color = Pro.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                val on = busy == null
                ChoiceRow("Quality", listOf("Full resolution", "Draft (half)"), if (pending.half) 1 else 0, enabled = on && pending.cfa == 1) { pending = pending.copy(half = it == 1) }
                ChoiceRow("Vignette correction", listOf("Off", "Full", "Colour only"), pending.vignette, enabled = on) { pending = pending.copy(vignette = it) }
                ChoiceRow("Clipped highlights", listOf("As recorded", "Reconstruct"), if (pending.highlights) 1 else 0, enabled = on) { pending = pending.copy(highlights = it == 1) }
                ChoiceRow("Hot pixels", listOf("Keep", "Remove"), if (pending.hotPixels) 1 else 0, enabled = on) { pending = pending.copy(hotPixels = it == 1) }
                ChoiceRow("Exposure changes", listOf("As recorded", "Normalise", "Smooth"), pending.exposure, enabled = on) { pending = pending.copy(exposure = it) }
                ChoiceRow("White balance changes", listOf("First frame", "Follow camera", "Smooth"), pending.whiteBalance, enabled = on) { pending = pending.copy(whiteBalance = it) }
                ChoiceRow("Frame rate", listOf("As recorded", "Constant %s".format(fmtFps(std)), "Even (median)"), pending.frameRate, enabled = on) { pending = pending.copy(frameRate = it) }
                ChoiceRow("Sensor pattern", listOf("Bayer", "Quad", "Nona", "Hexadeca"), pending.cfa - 1, enabled = on) { pending = pending.copy(cfa = it + 1) }
                LevelsEditor(asset, cal, pending, on) { pending = it }
                Text(developNote(pending), color = Pro.Dim, fontSize = 10.5.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp))
                when {
                    busy != null -> Text("Developing  ${(busy * 100).toInt()}%  ·  the clip plays meanwhile", color = Palette.Accent, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                    pending != current -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Accent).clickable { tick(); vm.redevelopRaw(asset, pending) },
                            contentAlignment = Alignment.Center,
                        ) { Text("Develop again", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        Box(
                            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(Pro.CardHi).border(1.dp, Pro.Line, RoundedCornerShape(12.dp))
                                .clickable { pending = current }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Cancel", color = Pro.Text, fontSize = 13.sp) }
                    }
                    current != DevelopOptions() -> Text("Defaults", color = Pro.Dim, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { pending = DevelopOptions() }.padding(horizontal = 6.dp, vertical = 4.dp))
                }
            }
        }

        // ---- White balance (live).
        SectionCard("White balance", null, r.wb == RawLayer.CUSTOM && clipR) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                val names = listOf("As shot") + RawLayer.PRESETS.map { it.first } + listOf("Custom")
                val sel = when {
                    !clipR || r.wb == RawLayer.AS_SHOT -> 0
                    else -> RawLayer.PRESETS.indexOfFirst { it.second == r.temp && r.tint == 0f }.let { if (it >= 0) it + 1 else names.size - 1 }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((i, n) in names.withIndex()) Chip(n, i == sel) {
                        tick()
                        set { l ->
                            when (i) {
                                0 -> l.copy(wb = RawLayer.AS_SHOT)
                                names.size - 1 -> l.copy(wb = RawLayer.CUSTOM, temp = if (l.wb == RawLayer.CUSTOM) l.temp else t0.toFloat(), tint = if (l.wb == RawLayer.CUSTOM) l.tint else ti0.toFloat())
                                else -> l.copy(wb = RawLayer.CUSTOM, temp = RawLayer.PRESETS[i - 1].second, tint = 0f)
                            }
                        }
                    }
                }
                val custom = r.wb == RawLayer.CUSTOM && clipR
                ProSlider(vm, Primary("Temperature", 2000f, 12000f, t0.toFloat(), "%.0f K", { if (custom) it.raw.temp else t0.toFloat() }) { gr, v ->
                    gr.copy(raw = gr.raw.copy(wb = RawLayer.CUSTOM, temp = v, tint = if (gr.raw.wb == RawLayer.CUSTOM) gr.raw.tint else ti0.toFloat(), decode = RawLayer.CLIP))
                }, track = Brush.horizontalGradient(listOf(Color(0xFF4F7BFF), Color(0xFFE9EEF7), Color(0xFFFFB24A))))
                ProSlider(vm, Primary("Tint", -150f, 150f, ti0.toFloat(), "%+.0f", { if (custom) it.raw.tint else ti0.toFloat() }) { gr, v ->
                    gr.copy(raw = gr.raw.copy(wb = RawLayer.CUSTOM, tint = v, temp = if (gr.raw.wb == RawLayer.CUSTOM) gr.raw.temp else t0.toFloat(), decode = RawLayer.CLIP))
                }, track = Brush.horizontalGradient(listOf(Color(0xFF3FBF6A), Color(0xFFE9EEF7), Color(0xFFD45AD2))))
            }
        }

        // ---- ISO and exposure (live).
        SectionCard("ISO and exposure", "ISO is relative to the ISO it was shot at", clipR && (r.iso != 0f || r.exposure != 0f)) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (cal.iso > 0) "As shot ${cal.iso}" else "As shot", !clipR || r.iso == 0f) { set { it.copy(iso = 0f) } }
                    for (iso in RawLayer.ISOS) Chip("$iso", clipR && r.iso == iso.toFloat()) { tick(); set { it.copy(iso = iso.toFloat()) } }
                }
                ProSlider(vm, p("Exposure", -6f, 6f, 0f, "%+.2f EV", { it.exposure }) { l, v -> l.copy(exposure = v) })
            }
        }

        // ---- Highlights and levels (live).
        SectionCard("Highlight recovery and levels", "0 clips highlights to white; 1 rebuilds clipped channels, keeping colour and a stop more detail", r.recovery != 0.5f || r.blackOffset != 0f || r.whiteScale != 1f) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                ProSlider(vm, p("Highlight recovery", 0f, 1f, 0.5f, "%.2f", { it.recovery }) { l, v -> l.copy(recovery = v) })
                // Both go the way they read: right is brighter. White level: where white sits (above 1 the whites
                // brighten and reach the clip sooner). Black level: right lifts the blacks, left crushes them.
                SliderGrid(vm, listOf(
                    p("White level", 0.5f, 2f, 1f, "%.2f×", { 1f / it.whiteScale.coerceIn(0.25f, 2f) }) { l, v -> l.copy(whiteScale = 1f / v.coerceIn(0.5f, 2f)) },
                    p("Black level", -0.1f, 0.1f, 0f, "%+.3f", { -it.blackOffset }) { l, v -> l.copy(blackOffset = -v) },
                ))
            }
        }

        // ---- Colour space and log (live), as mcraw-tui and MCRAW Studio offer them.
        SectionCard("Colour space and log", "What the RAW is decoded into; the Colour space layer takes it from there", r.outGamut != null || r.outGamma != null || r.noForward) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                val gamuts = listOf("Rec.2020", "Rec.709", "sRGB", "Display P3", "DCI-P3", "ACES (AP0)", "ACES (AP1)", "DaVinci Wide Gamut", "Apple Wide Gamut",
                    "ARRI Wide Gamut 3", "ARRI Wide Gamut 4", "Sony S-Gamut3", "Sony S-Gamut3.Cine", "Canon Cinema Gamut", "Panasonic V-Gamut", "Fujifilm F-Gamut",
                    "Fujifilm F-Gamut C", "Blackmagic Wide Gamut Gen 4/5", "RED Wide Gamut RGB").filter { n -> ColorSpaces.gamuts.any { it.first == n } }
                val gammas = listOf("Linear", "Rec.709", "sRGB", "Gamma 2.4", "Apple Log", "ARRI LogC3", "ARRI LogC4", "Sony S-Log3", "Canon Log 3", "Panasonic V-Log",
                    "Fujifilm F-Log2", "DaVinci Intermediate", "ACEScct", "Blackmagic Design Film Gen 5", "RED Log3G10", "Cineon Film Log", "Rec.2100 ST2084", "Rec.2100 HLG")
                    .filter { n -> ColorSpaces.gammas.any { it.name == n } }
                Text("Colour space", color = Pro.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in gamuts) Chip(n, (r.outGamut ?: "Rec.2020") == n) { tick(); vm.gradeOnce { gr -> gr.copy(raw = gr.raw.copy(outGamut = n.takeIf { n != "Rec.2020" })) } }
                }
                Text("Log / gamma", color = Pro.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in gammas) Chip(n, (r.outGamma ?: "Linear") == n) { tick(); vm.gradeOnce { gr -> gr.copy(raw = gr.raw.copy(outGamma = n.takeIf { n != "Linear" })) } }
                }
                // Presets: the common camera pairs, and Apple Log as mcraw4vulkan writes it.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, pair) in listOf("Apple Log" to ("Apple Log (Rec.2020)" to "Apple Log"), "ARRI LogC3" to ("ARRI Wide Gamut 3" to "ARRI LogC3"),
                        "ARRI LogC4" to ("ARRI Wide Gamut 4" to "ARRI LogC4"), "S-Log3" to ("Sony S-Gamut3.Cine" to "Sony S-Log3"),
                        "DaVinci" to ("DaVinci Wide Gamut" to "DaVinci Intermediate"), "ACEScct" to ("ACES (AP1)" to "ACEScct"), "Rec.709" to ("Rec.709" to "Rec.709"))) {
                        val ok = ColorSpaces.gamuts.any { it.first == pair.first } && ColorSpaces.gammas.any { it.name == pair.second }
                        if (ok) Chip("Preset: $label", r.outGamut == pair.first && r.outGamma == pair.second) {
                            tick(); vm.gradeOnce { gr -> gr.copy(raw = gr.raw.copy(outGamut = pair.first.takeIf { it != "Rec.2020" }, outGamma = pair.second)) }
                        }
                    }
                }
                ChoiceRow("Colour matrices", listOf("Forward (DNG)", "Colour only"), if (r.noForward) 1 else 0) { k -> vm.gradeOnce { it.copy(raw = it.raw.copy(noForward = k == 1)) } }
            }
        }

        // ---- Look (live): mcraw-tui's grade.
        SectionCard("Look", null, clipR && (r.contrast != 1f || r.saturation != 1f || r.highlights != 0f || r.shadows != 0f || r.sharpness != 0f)) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                SliderGrid(vm, listOf(
                    p("Contrast", 0f, 2f, 1f, "%.2f", { it.contrast }) { l, v -> l.copy(contrast = v) },
                    p("Saturation", 0f, 2f, 1f, "%.2f", { it.saturation }) { l, v -> l.copy(saturation = v) },
                    p("Highlights", -1f, 1f, 0f, "%+.2f", { it.highlights }) { l, v -> l.copy(highlights = v) },
                    p("Shadows", -1f, 1f, 0f, "%+.2f", { it.shadows }) { l, v -> l.copy(shadows = v) },
                    p("Sharpen", 0f, 2f, 0f, "%.2f", { it.sharpness }) { l, v -> l.copy(sharpness = v) },
                    p("Colour boost", -1f, 1f, 0f, "%+.2f", { it.colorBoost }) { l, v -> l.copy(colorBoost = v) },
                ))
            }
        }

        // ---- Denoise (live): MCRAW Studio's luma and chroma pair, on the clip's spatial noise reduction.
        val nz = g.noise
        SectionCard("Denoise", "Luma and chroma, as the clip's spatial noise reduction (Noise layer)", nz.sLuma > 0f || nz.sChroma > 0f) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                SliderGrid(vm, listOf(
                    Primary("Luma", 0f, 100f, 0f, "%.0f", { it.noise.sLuma }) { gr, v ->
                        gr.copy(noise = gr.noise.copy(on = true, sLink = false, sLuma = v, sChroma = if (gr.noise.sLink) gr.noise.sLuma else gr.noise.sChroma))
                    },
                    Primary("Chroma", 0f, 100f, 0f, "%.0f", { if (it.noise.sLink) it.noise.sLuma else it.noise.sChroma }) { gr, v ->
                        gr.copy(noise = gr.noise.copy(on = true, sLink = false, sChroma = v))
                    },
                ))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Black and white levels, as MotionCam Tools sets them: each frame's own (dynamic), the file's (static), or set by
 * hand per colour (R, Gr, Gb, B) and white, from presets, typed, or detected from the footage's darkest photosites.
 */
@Composable
private fun LevelsEditor(asset: com.graden.editor.MediaAsset, cal: com.graden.editor.RawCal, o: DevelopOptions, enabled: Boolean, set: (DevelopOptions) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var detecting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val custom = o.black != null || o.white > 0f
    val mode = if (custom) 2 else if (o.staticLevels) 1 else 0
    // What the recording claims, per colour.
    val redRow = cal.cfa.indexOf(0).coerceAtLeast(0) / 2
    fun colourOf(s: Int) = when (cal.cfa[s]) { 0 -> 0; 2 -> 3; else -> if (s / 2 == redRow) 1 else 2 }
    val recBlack = List(4) { c -> cal.black[(0 until 4).firstOrNull { colourOf(it) == c } ?: c].toFloat() }
    ChoiceRow("Black and white levels", listOf("Each frame's", "The file's", "Custom"), mode, enabled = enabled) { k ->
        set(when (k) {
            0 -> o.copy(staticLevels = false, black = null, white = 0f)
            1 -> o.copy(staticLevels = true, black = null, white = 0f)
            else -> o.copy(black = o.black ?: recBlack, white = if (o.white > 0f) o.white else cal.white.toFloat())
        })
    }
    Text("Recorded: black ${recBlack.joinToString(" / ") { it.toInt().toString() }}, white ${cal.white.toInt()}",
        color = Pro.Dim, fontSize = 10.5.sp, fontFamily = Pro.Mono, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    if (!custom) return
    val b = o.black ?: recBlack
    // Presets: white / black, as MotionCam Tools lists them.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, wb) in listOf("1023 / 64" to (1023f to 64f), "4095 / 64 (DCG)" to (4095f to 64f), "4095 / 256" to (4095f to 256f),
            "16383 / 64" to (16383f to 64f), "16383 / 1024" to (16383f to 1024f))) {
            Chip(label, o.white == wb.first && b.all { it == wb.second }) { set(o.copy(black = List(4) { wb.second }, white = wb.first)) }
        }
        Chip(if (detecting) "Detecting…" else "Detect", false) {
            if (detecting) return@Chip
            val uri = asset.raw?.let(android.net.Uri::parse) ?: return@Chip
            detecting = true; note = null
            scope.launch {
                val lv = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.graden.editor.RawDevelop.detectLevels(context, uri) }
                detecting = false
                when {
                    lv == null -> note = "Couldn't read the recording"
                    lv.black == null -> note = "No dark areas to measure the black from; type it or pick a preset"
                    else -> { set(o.copy(black = lv.black, white = lv.white)); note = "Found black ${lv.black.joinToString(" / ") { it.toInt().toString() }}, white ${lv.white.toInt()}" }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((i, name) in listOf("R", "Gr", "Gb", "B").withIndex()) {
            LevelField(name, b[i], Modifier.weight(1f), enabled) { v -> set(o.copy(black = b.toMutableList().also { it[i] = v })) }
        }
        LevelField("White", if (o.white > 0f) o.white else cal.white.toFloat(), Modifier.weight(1.3f), enabled) { v -> set(o.copy(white = v.coerceAtLeast(1f))) }
    }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Same black for all", color = Pro.Dim, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) {
            set(o.copy(black = List(4) { b[0] }))
        }.padding(horizontal = 4.dp, vertical = 4.dp))
        Spacer(Modifier.weight(1f))
        Text("Effective: ${b.joinToString(" / ") { it.toInt().toString() }} · ${(if (o.white > 0f) o.white else cal.white.toFloat()).toInt()}",
            color = Palette.Accent, fontSize = 10.5.sp, fontFamily = Pro.Mono)
    }
    note?.let { Text(it, color = Pro.Dim, fontSize = 10.5.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
}

/** A labelled box for one level, in sensor code values. */
@Composable
private fun LevelField(label: String, value: Float, modifier: Modifier, enabled: Boolean, onSet: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toInt().toString()) }
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Pro.Well).border(1.dp, Pro.Line, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(label, color = Pro.Dim, fontSize = 10.sp)
        androidx.compose.foundation.text.BasicTextField(
            text, { t -> text = t.filter { it.isDigit() }.take(5); text.toIntOrNull()?.let { onSet(it.toFloat()) } }, singleLine = true, enabled = enabled,
            textStyle = androidx.compose.ui.text.TextStyle(color = Pro.Text, fontSize = 14.sp, fontFamily = Pro.Mono),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun fmtFps(f: Double) = if (f % 1.0 == 0.0) "%.0f".format(f) else "%.3f".format(f).trimEnd('0')

/** One line on what the pending develop options will do. */
private fun developNote(o: DevelopOptions): String = listOfNotNull(
    "Draft: half size, quicker to edit".takeIf { o.half && o.cfa == 1 },
    "Only the vignette's colour shift is corrected".takeIf { o.vignette == DevelopOptions.VIGNETTE_COLOUR },
    "Clipped photosites rebuilt from their neighbours".takeIf { o.highlights },
    "ISO and shutter changes taken out".takeIf { o.exposure == DevelopOptions.NORMALISE },
    "Exposure changes eased over a second".takeIf { o.exposure == DevelopOptions.SMOOTH },
    "White balance follows the camera".takeIf { o.whiteBalance == DevelopOptions.FOLLOW },
    "White balance changes eased".takeIf { o.whiteBalance == DevelopOptions.SMOOTH },
    "Frames repeated or dropped to a standard rate, in real time".takeIf { o.frameRate == DevelopOptions.CFR_STANDARD },
    "Every frame kept, evenly spaced (sound may drift slightly)".takeIf { o.frameRate == DevelopOptions.CFR_MEDIAN },
    "Colour blocks binned to Bayer (smaller picture, less noise)".takeIf { o.cfa > 1 },
).joinToString(" · ").ifEmpty { "As MotionCam recorded it, with the lens's vignette corrected" }

@Composable
private fun Info(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Pro.Well).padding(horizontal = 8.dp, vertical = 7.dp)) {
        Text(value, color = Pro.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Pro.Mono, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Text(label, color = Pro.Dim, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Color(0xFF2E2418) else Pro.CardHi)
            .border(1.dp, if (on) Palette.Accent.copy(alpha = 0.6f) else Pro.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Palette.Accent else Pro.Text, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false) }
}

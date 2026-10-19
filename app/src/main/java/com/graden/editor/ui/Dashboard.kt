package com.graden.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.EditorViewModel
import com.graden.editor.ProjectMeta
import com.graden.editor.ProjectSummary
import com.graden.editor.timecode

/** A canvas shape on offer, with the places it is made for. */
private class CanvasPreset(val name: String, val use: String, val w: Int, val h: Int)

private val Presets = listOf(
    CanvasPreset("YouTube", "Widescreen", 16, 9),
    CanvasPreset("TikTok", "Reels · Shorts", 9, 16),
    CanvasPreset("Instagram", "Square post", 1, 1),
    CanvasPreset("Instagram", "Portrait post", 4, 5),
    CanvasPreset("Facebook", "Landscape post", 5, 4),
    CanvasPreset("Phone", "Full-screen", 20, 9),
    CanvasPreset("Phone", "Full-screen tall", 9, 20),
    CanvasPreset("Cinema", "Scope", 21, 9),
    CanvasPreset("Classic", "TV", 4, 3),
    CanvasPreset("Classic", "Portrait", 3, 4),
    CanvasPreset("Univisium", "Streaming", 2, 1),
    CanvasPreset("X / Twitter", "Header", 3, 1),
)
/** The last tile: any width and height, typed in. */
private val CustomPreset = CanvasPreset("Custom", "Any size", 0, 0)
private val Resolutions = listOf(720 to "720p", 1080 to "1080p", 1440 to "1440p", 2160 to "4K")
private val Rates = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
/** Colour spaces, grouped as in a colour-managed desktop project. The note under each says when it is the right one. */
private class Space(val name: String, val note: String)

/** What a project can be delivered as: every one of them can be written and correctly tagged in a video file. */
private val SpaceGroups: List<Pair<String, List<Space>>> = listOf(
    "SDR" to com.graden.editor.ColorSpaces.outputs.filter { !it.hdr }.map { Space(it.name, it.note) },
    "HDR" to com.graden.editor.ColorSpaces.outputs.filter { it.hdr }.map { Space(it.name, it.note) },
)
private val AllSpaces = SpaceGroups.flatMap { it.second }
private val WorkingSpaces = com.graden.editor.ColorSpaces.working.map { it.name }
private val MasterNits = listOf(400, 600, 1000, 2000, 4000)

/**
 * The first screen: projects, newest first, and a large button to start a new one. Tap a project
 * to open it; hold it to delete it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Dashboard(vm: EditorViewModel) {
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize().systemBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Column {
                    Text("Graden", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Your projects", color = Palette.TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    // The way in: a wide, warm button that is hard to miss.
                    Row(
                        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(18.dp))
                            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFFF0872B), Color(0xFFD9681C))))
                            .clickable { creating = true }.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0x33000000)), contentAlignment = Alignment.Center) {
                            Icon(Lucide.Plus, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("New project", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("Choose the canvas, frame rate and colour space", color = Color(0xE6FFFFFF), fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (vm.projectList.isNotEmpty()) Text("Recent", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    else Text("No projects yet. Start one above.", color = Palette.TextDim, fontSize = 13.sp)
                }
            }
            items(vm.projectList, key = { it.id }) { p ->
                Column(Modifier.combinedClickable(onClick = { vm.openProject(p.id) }, onLongClick = { deleting = p })) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF07080A))
                            .border(1.dp, Palette.Line, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The card shows the project's own canvas shape, with its first picture in it.
                        val a = p.meta.aspect
                        Box(Modifier.fillMaxHeight(0.82f).aspectRatio(a.coerceIn(0.3f, 3.5f), matchHeightConstraintsFirst = a < 1.6f).clip(RoundedCornerShape(4.dp)).background(Color.Black)) {
                            p.cover?.let { cover ->
                                vm.thumbs.version
                                vm.thumbs.get(cover, 0L)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                        }
                        Text(
                            p.meta.ratioLabel, color = Palette.Text, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000)).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                        Text(
                            timecode(p.durationUs, p.meta.fps), color = Palette.Text, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000)).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Text(p.meta.name, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(
                        "${ago(p.modified)} · ${p.clips} clip${if (p.clips == 1) "" else "s"} · ${p.meta.fpsLabel} fps",
                        color = Palette.TextDim, fontSize = 11.sp, maxLines = 1,
                    )
                }
            }
        }
        ProjectSheet(creating, null, onClose = { creating = false }, onDone = {
            creating = false
            vm.createProject(it)
        }, modifier = Modifier.align(Alignment.BottomCenter))
        deleting?.let { p ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                containerColor = Palette.Surface,
                title = { Text("Delete \"${p.meta.name}\"?", color = Palette.Text) },
                text = { Text("The project and its edit are removed. Your media files stay on the phone.", color = Palette.TextDim) },
                confirmButton = { TextButton({ vm.deleteProject(p.id); deleting = null }) { Text("Delete", color = Palette.Danger) } },
                dismissButton = { TextButton({ deleting = null }) { Text("Keep", color = Palette.Text) } },
            )
        }
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000L
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / 60 / 24} d ago"
    }
}

private val SampleRates = listOf(44_100 to "44.1 kHz", 48_000 to "48 kHz", 96_000 to "96 kHz")
private val Loudness = listOf<Pair<Float?, String>>(null to "Off", -14f to "−14 LUFS", -16f to "−16 LUFS", -23f to "−23 LUFS")
private val LoudnessNotes = listOf("The mix is left as it is", "YouTube, Spotify, TikTok, Instagram", "Apple Music, podcasts", "Broadcast TV (EBU R128)")

/**
 * Project settings, chosen before a project starts and changeable later from the editor (tap the project's name).
 * [initial] fills it from an existing project; null starts a new one. What everyone chooses (name, shape, size, frame
 * rate) comes first; colour and sound, which most people leave as they are (Rec.709, 48 kHz, −14 LUFS), are folded
 * under Advanced. Clips of another shape always fit the canvas whole; they can be placed and sized by hand on it.
 */
@Composable
fun ProjectSheet(open: Boolean, initial: ProjectMeta?, onClose: () -> Unit, onDone: (ProjectMeta) -> Unit, modifier: Modifier) {
    if (open) Box(Modifier.fillMaxSize().background(Color(0xA6000000)).pointerInput(Unit) { detectTapGestures { onClose() } })
    AnimatedVisibility(open, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val start = remember(open) { initial }
        val startPreset = remember(open) {
            if (start == null) Presets.first()
            else if (start.customW > 0) CustomPreset
            else Presets.firstOrNull { it.w == start.aspectW && it.h == start.aspectH } ?: CustomPreset
        }
        var name by remember(open) { mutableStateOf(start?.name ?: "") }
        var preset by remember(open) { mutableStateOf(startPreset) }
        var side by remember(open) { mutableStateOf(start?.shortSide ?: 1080) }
        var fps by remember(open) { mutableStateOf(start?.fps?.takeIf { it in Rates } ?: 30f) }
        // Older projects may carry names that are no longer offered; they map onto the nearest real space.
        var color by remember(open) { mutableStateOf(start?.colorSpace?.let { com.graden.editor.ColorSpaces.output(it).name } ?: AllSpaces.first().name) }
        var working by remember(open) { mutableStateOf(start?.workingSpace?.let { com.graden.editor.ColorSpaces.working(it).name } ?: WorkingSpaces.first()) }
        var nits by remember(open) { mutableStateOf(start?.masterNits ?: 1000) }
        var customW by remember(open) { mutableStateOf((start?.width ?: 1920).toString()) }
        var customH by remember(open) { mutableStateOf((start?.height ?: 1080).toString()) }
        var customFps by remember(open) { mutableStateOf(start?.fps?.takeIf { it !in Rates }?.toString() ?: "") }
        var bitDepth by remember(open) { mutableStateOf(start?.bitDepth ?: 8) }
        var sampleRate by remember(open) { mutableStateOf(start?.sampleRate ?: 48_000) }
        var loudness by remember(open) { mutableStateOf(if (start == null) -14f else start.loudness) }
        val placeholder = remember(open) { start?.name ?: ProjectMeta.defaultName() }
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        val isCustom = preset === CustomPreset
        // A typed size is kept within what phones can decode, and made even, as video encoders need.
        val cw = (customW.toIntOrNull() ?: 0).coerceIn(16, 8192) / 2 * 2
        val ch = (customH.toIntOrNull() ?: 0).coerceIn(16, 8192) / 2 * 2
        val typedFps = customFps.replace(',', '.').toFloatOrNull()?.takeIf { it in 1f..240f }
        val rate = typedFps ?: fps
        val base = ProjectMeta(
            name, start?.created ?: 0L, preset.name, preset.w.coerceAtLeast(1), preset.h.coerceAtLeast(1), side, rate, color,
            scaling = "Fit", bitDepth = bitDepth, sampleRate = sampleRate, loudness = loudness, startTcSec = 0, workingSpace = working, masterNits = nits,
        )
        val meta = if (isCustom) ProjectMeta.custom(base, cw, ch) else base
        val summary = "${meta.width} × ${meta.height}  ·  ${meta.ratioLabel}  ·  ${meta.fpsLabel} fps"
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.94f).clip(shape).background(Kit.Sheet).border(1.dp, Kit.Hair, shape)
                .pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding().imePadding()
        ) {
            KGrabber()
            KHeader(if (start == null) "New project" else "Project settings", summary, onClose)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                // The name, large, as the project's title.
                KGroup {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (name.isEmpty()) Text(placeholder, color = Kit.Faint, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            BasicTextField(
                                name, { name = it.take(ProjectMeta.MAX_NAME) }, singleLine = true,
                                textStyle = TextStyle(color = Kit.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium), cursorBrush = SolidColor(Kit.Accent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text("${name.length}/${ProjectMeta.MAX_NAME}", color = Kit.Faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                KSection("Canvas")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in Presets + CustomPreset) {
                        val on = p === preset
                        Column(
                            Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Kit.AccentSoft else Kit.Group)
                                .border(if (on) 1.5.dp else 1.dp, if (on) Kit.Accent else Color.Transparent, RoundedCornerShape(16.dp))
                                .clickable { preset = p }.padding(top = 12.dp, bottom = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // The shape itself, drawn to scale.
                            Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                                val a = if (p === CustomPreset) (cw.toFloat() / ch).coerceIn(0.2f, 5f) else p.w.toFloat() / p.h
                                val bw = if (a >= 1f) 48f else 48f * a
                                val bh = if (a >= 1f) 48f / a else 48f
                                Box(
                                    Modifier.size(bw.dp, bh.dp).clip(RoundedCornerShape(5.dp))
                                        .background(if (on) Kit.Accent.copy(alpha = 0.22f) else Color(0x10FFFFFF))
                                        .border(1.5.dp, if (on) Kit.Accent else Kit.Dim, RoundedCornerShape(5.dp))
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(if (p === CustomPreset) "Custom" else "${p.w}:${p.h}", color = if (on) Kit.Accent else Kit.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (p === CustomPreset) "Any size" else p.name, color = Kit.Dim, fontSize = 11.sp, maxLines = 1)
                            Text(p.use, color = Kit.Faint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }

                KSection("Resolution")
                if (isCustom) KGroup {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Size", color = Kit.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        NumberField(customW, "Width") { customW = it }
                        Text("×", color = Kit.Dim, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 10.dp))
                        NumberField(customH, "Height") { customH = it }
                    }
                } else KChips(Resolutions.map { it.second }, Resolutions.indexOfFirst { it.first == side }) { side = Resolutions[it].first }

                KSection("Frame rate")
                KChips(
                    Rates.map { if (it % 1f == 0f) "${it.toInt()}" else "%.3f".format(it).trimEnd('0') },
                    if (typedFps != null) -1 else Rates.indexOf(fps),
                ) {
                    fps = Rates[it]
                    customFps = ""
                }
                Row(Modifier.padding(start = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberField(customFps, "Other", decimals = true) { customFps = it }
                    val bad = customFps.isNotEmpty() && typedFps == null
                    Text(if (bad) "1 to 240" else "fps", color = if (bad) Kit.Bad else Kit.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                }

                KSection("Advanced")
                val loudIdx = Loudness.indexOfFirst { it.first == loudness }.coerceAtLeast(0)
                KFold(
                    "Colour and sound",
                    "${color.replace(" Gamma ", " γ")} · ${bitDepth}-bit · ${SampleRates.firstOrNull { it.first == sampleRate }?.second ?: ""} · ${Loudness[loudIdx].second}",
                ) {
                    FoldLabel("Output colour space")
                    for (sp in AllSpaces) KOption(sp.name, sp.note, sp.name == color, badge = {
                        val hdr = ProjectMeta.isHdr(sp.name)
                        KBadge(if (hdr) "HDR" else "SDR", if (hdr) Kit.Accent else Kit.Dim)
                    }) {
                        color = sp.name
                        // HDR needs more than 8 bits per channel, or smooth gradients band.
                        if (ProjectMeta.isHdr(color)) bitDepth = 10
                    }
                    if (ProjectMeta.isHdr(color)) {
                        FoldLabel("HDR mastering brightness")
                        KChips(MasterNits.map { "$it nits" }, MasterNits.indexOf(nits)) { nits = MasterNits[it] }
                        FoldNote("The brightest the picture is graded for. 1000 nits suits most HDR phones and streaming.")
                    }
                    FoldLabel("Timeline colour space")
                    for (w in WorkingSpaces) KOption(w, com.graden.editor.ColorSpaces.working(w).note, w == working) { working = w }
                    FoldNote(
                        "Where grading happens. A wider space keeps more colour from each camera; export converts to the output space." +
                            if (ProjectMeta.isHdr(color) && !com.graden.editor.ColorSpaces.working(working).log) " For HDR output, DaVinci Wide Gamut or ACEScct keeps the highlights." else "",
                    )
                    KDivider(0.dp)
                    KRow("Bit depth", if (bitDepth == 8 && ProjectMeta.isHdr(color)) "HDR at 8-bit will band; 10-bit is recommended" else null) {
                        KSegmented(listOf("8-bit", "10-bit"), if (bitDepth == 10) 1 else 0, Modifier.width(150.dp)) { bitDepth = if (it == 1) 10 else 8 }
                    }
                    KDivider()
                    KRow("Sample rate") {
                        KSegmented(SampleRates.map { it.second }, SampleRates.indexOfFirst { it.first == sampleRate }, Modifier.width(210.dp)) { sampleRate = SampleRates[it].first }
                    }
                    KDivider(0.dp)
                    FoldLabel("Loudness target")
                    for ((i, l) in Loudness.withIndex()) KOption(l.second, LoudnessNotes[i], l.first == loudness) { loudness = l.first }
                    FoldNote("Export brings the mix to this level, as the platforms measure it.")
                    Spacer(Modifier.height(12.dp))
                }
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                KPrimary(if (start == null) "Create project" else "Apply", sub = summary) { onDone(meta.copy(name = name.ifBlank { placeholder })) }
            }
        }
    }
}

@Composable
private fun FoldLabel(text: String) {
    Text(text.uppercase(), color = Kit.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun FoldNote(text: String) {
    Text(text, color = Kit.Faint, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp))
}

@Composable
private fun Note(text: String, mono: Boolean = false) {
    Text(
        text, color = if (text.startsWith("HDR at 8")) Palette.Accent else Palette.TextDim, fontSize = 11.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        modifier = Modifier.padding(start = 22.dp, end = 20.dp, top = 6.dp),
    )
}

/** A small box for typing a number; [decimals] allows a decimal point (for frame rates like 47.952). */
@Composable
private fun NumberField(value: String, hint: String, decimals: Boolean = false, onChange: (String) -> Unit) {
    Box(
        Modifier.width(92.dp).clip(RoundedCornerShape(12.dp)).background(Kit.Raised)
            .border(1.dp, if (value.isNotEmpty()) Kit.Accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = Palette.TextDim, fontSize = 14.sp)
        BasicTextField(
            value, { t -> onChange(t.filter { it.isDigit() || (decimals && (it == '.' || it == ',')) }.take(if (decimals) 7 else 4)) }, singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(Palette.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = Palette.TextDim, fontSize = 12.sp, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label, color = if (on) Color.Black else Palette.Text, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Palette.Accent else Palette.Surface)
                    .border(1.dp, if (on) Palette.Accent else Palette.Line, RoundedCornerShape(16.dp))
                    .clickable { onPick(i) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}


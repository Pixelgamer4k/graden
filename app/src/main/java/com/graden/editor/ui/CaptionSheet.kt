package com.graden.editor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.Caption
import com.graden.editor.AudioEngine
import com.graden.editor.CaptionModels
import com.graden.editor.CaptionRender
import com.graden.editor.Captioner
import com.graden.editor.EditorViewModel
import com.graden.editor.Whisper

// The sheet's palette: three greys for text, two surfaces, one accent.
private val CsBg = Kit.Sheet
private val CsGroup = Kit.Group
private val CsLine = Kit.Hair
private val CsText1 = Kit.Text
private val CsText2 = Kit.Dim
private val CsText3 = Kit.Faint
private val CsGood = Color(0xFF6FD08C)
private val CsBad = Color(0xFFE5857F)
private val CsMenu = darkColorScheme(surface = Color(0xFF1F2027), surfaceContainer = Color(0xFF1F2027))

/**
 * Auto captions: for the whole project (onto a new caption layer) or one clip (into the free space of the caption layer
 * above it). The models are not in the app: each is downloaded from its official source or added from storage here.
 */
@Composable
internal fun CaptionSheet(vm: EditorViewModel) {
    val target = vm.captionSheetFor ?: return
    val context = LocalContext.current
    vm.modelsTick
    val clipMode = target >= 0
    val prefs = remember { context.getSharedPreferences("captions", 0) }
    var lang by remember { mutableStateOf(prefs.getString("lang", "auto") ?: "auto") }
    val pickSpeech = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importModel(u, null) }
    val pickDiar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importModel(u, CaptionModels.DIARIZATION) }
    val pickCed = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importModel(u, CaptionModels.CED) }
    val engine = remember { AudioEngine.available(context) }
    val running = vm.captionRun != null
    BackHandler { if (!running) vm.captionSheetFor = null }
    val none = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(none, null, enabled = !running) { vm.captionSheetFor = null }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 720.dp)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(CsBg)
                .clickable(none, null) {}.navigationBarsPadding(),
        ) {
            KGrabber()
            // Title, and what it will cover.
            val (n, us) = remember(target, vm.tracks) { vm.captionSources(if (clipMode) target else null) }
            KHeader(
                if (clipMode) "Captions for this clip" else "Auto captions",
                (if (n == 0) "No sound to caption" else (if (clipMode) "" else "$n clip${if (n == 1) "" else "s"} with sound · ") + length(us)) +
                    if (clipMode) " · onto the text layer above" else " · onto a new text layer",
                onClose = if (running) null else ({ vm.captionSheetFor = null }),
            )
            Spacer(Modifier.height(16.dp))

            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                if (!Whisper.available) {
                    Text("Captions need a phone with an ARM processor.", color = CsBad, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
                    return@Column
                }
                // ---- language: the first thing to say
                Section("Spoken language") { LanguagePicker(lang) { code -> lang = code; prefs.edit().putString("lang", code).apply() } }
                // ---- speech
                val chosen = CaptionModels.chosen(context)
                Section("Speech recognition", "Add from storage" to { pickSpeech.launch(arrayOf("*/*")) }) {
                    val specs = buildList {
                        add(Triple(CaptionModels.WHISPER_FAST, "Whisper Small", "Quick · 99 languages"))
                        add(Triple(CaptionModels.WHISPER_BEST, "Whisper Large v3 Turbo", "Most accurate · 99 languages"))
                    }
                    for ((i, t) in specs.withIndex()) {
                        val (spec, name, line) = t
                        val has = CaptionModels.has(context, spec)
                        if (i > 0) RowDivider()
                        ModelRow(
                            lead = { Radio(has && chosen == spec.id, has) }, title = name, badge = if (spec === CaptionModels.WHISPER_FAST) "Recommended" else null,
                            line = line, size = mb(spec.size), has = has, progress = vm.modelDownloads[spec.id], error = vm.modelErrors[spec.id],
                            onClick = { if (has) vm.chooseWhisper(spec.id) }, onDownload = { vm.downloadModel(spec) }, onRemove = { vm.deleteModel(spec) },
                            update = CaptionModels.old(context, spec) != null,
                        )
                    }
                    CaptionModels.ownWhisper(context)?.let { own ->
                        RowDivider()
                        ModelRow({ Radio(chosen == "own", true) }, "Your model", null, "Added from storage", mb(own.length()), true, null, null,
                            onClick = { vm.chooseWhisper("own") }, onDownload = {}, onRemove = { vm.deleteModel(null) })
                    }
                }
                if (engine) {
                    // ---- the optional detectors: who speaks, and what is sung or only music
                    Section("Detection · optional") {
                        val d = CaptionModels.DIARIZATION
                        val hasD = CaptionModels.has(context, d)
                        ModelRow({ IconTile(Lucide.Users, hasD) }, "Speakers", null,
                            "A caption per person, up to 8; quick replies stacked", mb(d.size),
                            hasD, vm.modelDownloads[d.id], vm.modelErrors[d.id], onClick = {}, onDownload = { vm.downloadModel(d) }, onRemove = { vm.deleteModel(d) })
                        RowDivider()
                        val c = CaptionModels.CED
                        val hasC = CaptionModels.has(context, c)
                        ModelRow({ IconTile(Lucide.Music, hasC) }, "Music and lyrics", null, "Sung lines and music marked ${Captioner.CLEF}", mb(c.size),
                            hasC, vm.modelDownloads[c.id], vm.modelErrors[c.id], onClick = {}, onDownload = { vm.downloadModel(c) }, onRemove = { vm.deleteModel(c) })
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 0.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add speaker model from storage", color = Kit.Accent, fontSize = 12.5.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { pickDiar.launch(arrayOf("*/*")) }.padding(6.dp))
                        Text("Add music model", color = Kit.Accent, fontSize = 12.5.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { pickCed.launch(arrayOf("*/*")) }.padding(6.dp))
                    }
                }
                // ---- size
                Section("Size", if (vm.hasCaptions) "Apply to existing" to { vm.applyCaptionSizeToAll() } else null) {
                    SizePreview(vm.captionSize)
                    Segmented(Caption.SIZES.map { it.replace("Very small", "XS").replace("Very large", "XL").replace("Small", "S").replace("Normal", "M").replace("Large", "L") },
                        vm.captionSize) { vm.chooseCaptionSize(it) }
                    Text(Caption.SIZES[vm.captionSize] + if (vm.captionSize == 2) " · as YouTube and Netflix" else "", color = CsText2, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 16.dp, bottom = 14.dp))
                }
                Text("Runs entirely on this phone; nothing is uploaded. Models come from their official sources (Hugging Face: ggml-org and audio.cpp) and are checked against published checksums.",
                    color = CsText3, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(14.dp))
            }

            // ---- the action, or its progress
            val ready = CaptionModels.whisper(context) != null
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 16.dp)) {
                AnimatedContent(vm.captionRun, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "run") { run ->
                    if (run != null) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CsGroup).padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(run.title, color = CsText1, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                                    if (run.detail.isNotEmpty()) Text(run.detail, color = CsText2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${(run.progress * 100).toInt()}%", color = CsText1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(CsLine)) {
                                Box(Modifier.fillMaxWidth(run.progress.coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(Palette.Accent))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Cancel", color = Palette.Accent, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(8.dp)).clickable { vm.cancelCaptions() }.padding(horizontal = 8.dp, vertical = 6.dp))
                        }
                    } else {
                        Column {
                            KPrimary(if (clipMode) "Generate captions for this clip" else "Generate captions", enabled = ready,
                                sub = if (ready) "Runs on this phone · nothing is uploaded" else null) { vm.makeCaptions(if (clipMode) target else null, lang) }
                            if (!ready) Text("Get a speech recognition model, or add one from storage, to begin.", color = CsText3, fontSize = 11.5.sp,
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun mb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)
private fun length(us: Long): String { val s = (us / 1_000_000).toInt(); return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s" }

/** A titled group: a small caps label (with an optional text action) over a rounded block. */
@Composable
private fun Section(label: String, action: Pair<String, () -> Unit>? = null, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = CsText3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp, modifier = Modifier.weight(1f))
        action?.let { (t, f) ->
            Text(t, color = Palette.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = f).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CsGroup)) { content() }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun RowDivider() = Box(Modifier.padding(start = 56.dp).fillMaxWidth().height(1.dp).background(CsLine))

/** One model: a leading mark (a radio, or an icon), its name and line, and on the right one action over its size. */
@Composable
private fun ModelRow(
    lead: @Composable () -> Unit, title: String, badge: String?, line: String, size: String, has: Boolean, progress: Float?, error: String?,
    onClick: () -> Unit, onDownload: () -> Unit, onRemove: () -> Unit,
    /** An older, slower version of it is here: offer the current one. */
    update: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(enabled = has) { onClick() }.padding(start = 16.dp, end = 10.dp, top = 13.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(40.dp)) { lead() }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (has || progress != null) CsText1 else Color(0xFFC9CBD3), fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                badge?.let {
                    Spacer(Modifier.width(8.dp))
                    val accent = it == "Recommended"
                    Text(it, color = if (accent) Palette.Accent else CsText2, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (accent) Palette.Accent.copy(alpha = 0.13f) else CsLine).padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    progress != null -> "Downloading · ${(progress * 100).toInt()}%"
                    error != null -> error
                    update -> "About 3× faster version available · $size"
                    else -> line
                },
                color = if (error != null && progress == null) CsBad else CsText2, fontSize = 12.sp, lineHeight = 16.sp,
            )
            if (has && progress == null) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(CsGood))
                Spacer(Modifier.width(6.dp))
                Text(if (update) "On this phone · older version" else "On this phone · $size", color = CsText3, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                when {
                    progress != null -> Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDownload), contentAlignment = Alignment.Center) {
                        Ring(progress)
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(1.5.dp)).background(Palette.Accent))
                    }
                    has && !update -> Box(Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                        Icon(Lucide.MoreVertical, "More", tint = CsText2, modifier = Modifier.size(18.dp))
                    }
                    else -> Box(
                        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Accent.copy(alpha = if (error != null) 0.22f else 0.14f))
                            .clickable(onClick = onDownload).padding(horizontal = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Lucide.Download, null, tint = Palette.Accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (error != null) "Retry" else if (update) "Update" else "Get", color = Palette.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                MaterialTheme(colorScheme = CsMenu) {
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Remove from phone", color = CsBad, fontSize = 14.sp) }, { menu = false; onRemove() })
                    }
                }
            }
            if ((!has || update) && progress == null) Text(size, color = CsText3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Radio(on: Boolean, enabled: Boolean) {
    Box(Modifier.size(20.dp).clip(CircleShape).border(1.6.dp, if (on) Palette.Accent else if (enabled) Color(0xFF6A6D78) else Color(0xFF34363F), CircleShape),
        contentAlignment = Alignment.Center) { if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Accent)) }
}

@Composable
private fun IconTile(icon: ImageVector, on: Boolean) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Palette.Accent.copy(alpha = 0.14f) else CsLine), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) Palette.Accent else CsText2, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun Ring(p: Float) {
    Canvas(Modifier.size(26.dp)) {
        val w = 2.5.dp.toPx()
        drawArc(CsLine, 0f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
        drawArc(Palette.Accent, -90f, 360f * p.coerceIn(0.02f, 1f), false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Five steps as one control. */
@Composable
private fun Segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).height(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF111216)).padding(3.dp)) {
        for ((i, l) in labels.withIndex()) {
            val on = i == selected
            val bg by animateColorAsState(if (on) Color(0xFF2B2C34) else Color.Transparent, label = "seg")
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(bg).clickable { onPick(i) }, contentAlignment = Alignment.Center) {
                Text(l, color = if (on) CsText1 else CsText2, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

/** A frame with a sample caption at the chosen size, drawn as the export draws it. */
@Composable
private fun SizePreview(size: Int) {
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
        .background(Brush.verticalGradient(listOf(Color(0xFF2A3346), Color(0xFF3B3328), Color(0xFF17181C))))) {
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { c ->
                CaptionRender.draw(c.nativeCanvas, Caption("This is how your captions will look", size = size), android.graphics.RectF(0f, 0f, this.size.width, this.size.height))
            }
        }
    }
}

/** The language, as one row that opens the list. */
@Composable
private fun LanguagePicker(code: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = Captioner.LANGUAGES.firstOrNull { it.first == code }?.second ?: code
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp)) { IconTile(Lucide.Globe, true) }
            Column(Modifier.weight(1f)) {
                Text("Spoken language", color = CsText1, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(if (code == "auto") "Found from the speech" else "Written in $name", color = CsText2, fontSize = 12.sp)
            }
            Text(if (code == "auto") "Automatic" else name, color = CsText1, fontSize = 13.5.sp, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Icon(Lucide.ChevronDown, null, tint = CsText2, modifier = Modifier.size(16.dp))
        }
        MaterialTheme(colorScheme = CsMenu) {
            DropdownMenu(open, { open = false }, Modifier.heightIn(max = 380.dp)) {
                for ((c, n) in Captioner.LANGUAGES) DropdownMenuItem(
                    { Text(n, color = if (c == code) Palette.Accent else CsText1, fontSize = 14.sp, fontWeight = if (c == code) FontWeight.SemiBold else FontWeight.Normal) },
                    { open = false; onPick(c) },
                )
            }
        }
    }
}

/** Editing a caption's words. */
@Composable
internal fun CaptionEditDialog(vm: EditorViewModel) {
    val id = vm.captionEditFor ?: return
    val clip = vm.locate(id)?.clip ?: run { vm.captionEditFor = null; return }
    var text by remember(id) { mutableStateOf(clip.caption?.text ?: "") }
    BackHandler { vm.captionEditFor = null }
    val none = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(none, null) { vm.captionEditFor = null }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CsBg).clickable(none, null) {}.padding(20.dp)) {
            Text("Edit caption", color = CsText1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(12.dp)).background(CsGroup).padding(14.dp)) {
                BasicTextField(text, { text = it }, textStyle = TextStyle(color = CsText1, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(Palette.Accent), modifier = Modifier.fillMaxWidth())
            }
            Text("A line break starts the second line on screen.", color = CsText3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                Text("Cancel", color = CsText2, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.captionEditFor = null }.padding(horizontal = 14.dp, vertical = 10.dp))
                Text("Save", color = Color(0xFF1A1208), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Accent)
                        .clickable { vm.setCaptionText(id, text.trim()); vm.captionEditFor = null }.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
    }
}

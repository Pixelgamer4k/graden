package com.graden.editor.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.graden.editor.EditorViewModel
import com.graden.editor.export.Container
import com.graden.editor.export.EncoderInfo
import com.graden.editor.export.ExportJob
import com.graden.editor.export.ExportPresets
import com.graden.editor.export.ExportService
import com.graden.editor.export.ExportSettings
import com.graden.editor.export.QualityMode
import com.graden.editor.export.RateMode
import com.graden.editor.export.VideoCodec
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val CardBg = Kit.Group
private val CardLine = Kit.Hair
private val Field = Kit.Raised
private val Ink = Kit.Text
private val Sub = Kit.Dim
private val Faint = Kit.Faint
private val Good = Color(0xFF4CD787)
private val Blue = Color(0xFF6FA0FF)
private val Dark = Color(0xFF15161C)
private val MonoFont = FontFamily.Monospace

/**
 * The export screen, laid out like Resolve's render settings: presets, then inspector cards for the file, the video,
 * advanced encoder settings and the sound, with a bar along the bottom that always says exactly what will be written.
 * Every option comes from what this device's encoders report; a choice an encoder cannot do is shown as such, never
 * quietly lowered.
 */
@Composable
fun ExportScreen(vm: EditorViewModel) {
    val context = LocalContext.current
    val meta = vm.meta
    val encoders = remember { EncoderInfo.discover() }
    fun encoderFor(c: VideoCodec) = encoders.firstOrNull { it.codec == c && it.hardware }?.name ?: encoders.firstOrNull { it.codec == c }?.name ?: ""
    fun canWrite(e: EncoderInfo, o: com.graden.editor.ColorSpaces.Space) = !o.hdr || (e.tenBit && (o != com.graden.editor.ColorSpaces.PQ || e.hdr10 || e.hdrEditing))
    /** For an HDR output: the encoder built for HDR editing first (it writes the HDR10 metadata into the stream), then any that can. */
    fun hdrEncoderFor(c: VideoCodec, o: com.graden.editor.ColorSpaces.Space): String? =
        encoders.filter { it.codec == c && canWrite(it, o) }.sortedWith(compareBy({ !it.hdrEditing }, { !it.hardware }, { it.unlistedMain10 })).firstOrNull()?.name
    var s by remember {
        mutableStateOf(
            ExportPresets.builtIn[0].apply(
                ExportSettings(fileName = meta.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"), width = meta.width, height = meta.height), meta.width, meta.height,
            ).let { st ->
                val o = com.graden.editor.ColorSpaces.output(meta.colorSpace)
                st.copy(encoder = (if (o.hdr) hdrEncoderFor(st.codec, o) else null) ?: encoderFor(st.codec), loudnessTarget = meta.loudness ?: -14f, output = o.name)
            },
        )
    }
    var preset by remember { mutableStateOf(ExportPresets.builtIn[0].name) }
    var userPresets by remember { mutableStateOf(ExportPresets.loadUser(context)) }
    var naming by remember { mutableStateOf(false) }
    val enc = encoders.firstOrNull { it.name == s.encoder && it.codec == s.codec } ?: encoders.firstOrNull { it.codec == s.codec }
    // The footage's own bitrate (read once, off the main thread): "Best" never goes below it.
    val used = remember(vm.tracks) { vm.tracks.flatMap { t -> t.clips.mapNotNull { vm.assets[it.assetId] } }.distinctBy { it.id } }
    val floor by androidx.compose.runtime.produceState(0, used, s.width, s.height, s.codec, meta.fps) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.graden.editor.export.SourceRate.floor(context, used, s.width, s.height, meta.fps, s.codec)
        }
    }
    if (s.sourceFloor != floor) s = s.copy(sourceFloor = floor)
    // An HDR output this encoder cannot write: another encoder of the codec that can is taken; if none can, Rec.709,
    // and the screen says why (never silently).
    var hdrNote by remember { mutableStateOf<String?>(null) }
    run {
        val o = com.graden.editor.ColorSpaces.output(s.output)
        if (o.hdr && (enc == null || !canWrite(enc, o))) {
            val other = hdrEncoderFor(s.codec, o)
            if (other != null) s = s.copy(encoder = other, bitDepth = 10)
            else {
                hdrNote = if (encoders.none { it.codec == s.codec && it.tenBit })
                    "This phone's ${s.codec.label} encoder cannot write 10-bit, so ${o.name} is not possible here; the export is SDR Rec.709." +
                        (if (s.codec == VideoCodec.H264 && encoders.any { it.codec == VideoCodec.H265 && canWrite(it, o) }) " Choose H.265 for HDR." else "")
                else "This phone's encoders cannot write HDR10 metadata; the export is SDR Rec.709. Rec.2100 HLG works here."
                s = s.copy(output = com.graden.editor.ColorSpaces.REC709.name)
            }
        }
    }
    val notifyAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun change(update: (ExportSettings) -> ExportSettings) {
        s = update(s)
        preset = ""
    }

    BackHandler {
        if (ExportJob.running) return@BackHandler
        ExportJob.reset()
        vm.exportOpen = false
    }
    Box(Modifier.fillMaxSize().background(Kit.Bg).systemBarsPadding().pointerInput(Unit) { detectTapGestures { } }) {
        if (ExportJob.phase != ExportJob.Phase.IDLE) {
            ExportProgress(vm)
            return@Box
        }
        val problem = problem(s, enc)
        val br = s.videoBitrate(meta.fps, enc)
        val bytes = ((br.toDouble() + if (s.audio) s.audioBitrate else 0) * vm.totalUs / 1e6 / 8).toLong()
        val outSpace = com.graden.editor.ColorSpaces.output(s.output)
        Column(Modifier.fillMaxSize()) {
            KHeader("Export", "${meta.name} · ${vm.tc(vm.totalUs)}", onClose = { vm.exportOpen = false })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // What will be written, at a glance.
                KGroup {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                            val a = (s.width.toFloat() / s.height.coerceAtLeast(1)).coerceIn(0.3f, 3.5f)
                            val bw = if (a >= 1f) 58f else 58f * a
                            val bh = if (a >= 1f) 58f / a else 58f
                            Box(Modifier.size(bw.dp, bh.dp).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Color(0xFF3A2A1C), Color(0xFF1E1F26))))
                                .border(1.5.dp, Kit.Accent, RoundedCornerShape(6.dp)))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${shortRes(s.width, s.height)} · ${fpsText(meta.fps)} fps", color = Kit.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("${s.width} × ${s.height} · ${s.codec.label}" + (if (s.bitDepth == 10) " · 10-bit" else "") + (if (outSpace.hdr) " · HDR" else ""),
                                color = Kit.Dim, fontSize = 13.sp, maxLines = 1)
                            Text(if (s.quality == QualityMode.CONSTANT) "Size depends on the picture" else "About ${sizeText(bytes)} · ${s.container.ext.uppercase()}",
                                color = Kit.Faint, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }

                KSection("Presets")
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (p in ExportPresets.builtIn) {
                        val preview = p.apply(s, meta.width, meta.height)
                        PresetCard(p.name, preview, preset == p.name) {
                            s = preview.copy(encoder = encoderFor(preview.codec))
                            preset = p.name
                        }
                    }
                    for ((name, json) in userPresets) {
                        val st = ExportSettings.fromJson(json, s.fileName)
                        PresetCard(name, st, preset == name, saved = true, onLong = {
                            ExportPresets.deleteUser(context, name)
                            userPresets = ExportPresets.loadUser(context)
                        }) {
                            s = st.copy(loudnessTarget = s.loudnessTarget, encoder = encoders.firstOrNull { it.name == st.encoder }?.name ?: encoderFor(st.codec))
                            preset = name
                        }
                    }
                    AddPresetCard { naming = true }
                }

                KSection("Video")
                KGroup {
                    val sizes = resolutionChoices(meta.width, meta.height)
                    val sizeName = sizes.firstOrNull { it.second == (s.width to s.height) }?.first ?: "Custom"
                    Expandable("Resolution", { KValue("$sizeName · ${s.width}×${s.height}") }) {
                        BoxWithConstraints(Modifier.padding(horizontal = 16.dp)) {
                            val cols = 3
                            val gap = 8.dp
                            val w = (maxWidth - gap * (cols - 1)) / cols
                            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                for (row in sizes.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    for ((label, wh) in row) {
                                        val ok = enc?.supports(wh.first, wh.second, meta.fps) != false
                                        SizeTile(label, "${wh.first}×${wh.second}", s.width == wh.first && s.height == wh.second, ok, Modifier.width(w)) {
                                            change { it.copy(width = wh.first, height = wh.second) }
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Custom", color = Kit.Dim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                            NumberBox(s.width, Modifier.weight(1f)) { v -> change { it.copy(width = (v / 2 * 2).coerceIn(16, 16384)) } }
                            Text("×", color = Kit.Dim, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 10.dp))
                            NumberBox(s.height, Modifier.weight(1f)) { v -> change { it.copy(height = (v / 2 * 2).coerceIn(16, 16384)) } }
                        }
                    }
                    KDivider()
                    val modes = QualityMode.entries.filter { it != QualityMode.CONSTANT || enc?.supportsCq == true }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Quality", color = Kit.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            KSegmented(modes.map { if (it == QualityMode.AUTO_BEST) "Best" else if (it == QualityMode.RESTRICT) "Bitrate" else "Constant" },
                                modes.indexOf(s.quality).coerceAtLeast(0), Modifier.width(if (modes.size == 3) 230.dp else 160.dp)) { i ->
                                change { it.copy(quality = modes[i], bitrate = if (modes[i] == QualityMode.RESTRICT && it.quality != QualityMode.RESTRICT) it.videoBitrate(meta.fps, enc) else it.bitrate) }
                            }
                        }
                        when (s.quality) {
                            QualityMode.AUTO_BEST -> Hint(
                                (if (s.sourceFloor > ExportSettings.autoBest(s.codec, s.width, s.height, meta.fps))
                                    "%.0f Mbps · the footage's own bitrate, kept in full".format(br / 1e6)
                                else "%.0f Mbps · visually lossless for this size and frame rate".format(br / 1e6)) +
                                    if (enc != null && maxOf(ExportSettings.autoBest(s.codec, s.width, s.height, meta.fps), s.sourceFloor) > enc.maxBitrate) " (encoder maximum)" else "",
                            )
                            QualityMode.RESTRICT -> {
                                val max = ((enc?.maxBitrate ?: 200_000_000) / 1_000_000).coerceAtLeast(2)
                                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ValueSlider((s.bitrate / 1_000_000).coerceIn(1, max), 1, max, Modifier.weight(1f)) { v -> change { it.copy(bitrate = v * 1_000_000) } }
                                    Spacer(Modifier.width(12.dp))
                                    NumberBox(s.bitrate / 1_000_000, Modifier.width(72.dp)) { v -> change { it.copy(bitrate = v.coerceIn(1, max) * 1_000_000) } }
                                    Text(" Mbps", color = Kit.Dim, fontSize = 13.sp)
                                }
                                Hint("Up to $max Mbps on this encoder")
                            }
                            QualityMode.CONSTANT -> {
                                val lo = enc?.qualityRange?.first ?: 0
                                val hi = enc?.qualityRange?.last ?: 100
                                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ValueSlider(s.cq.coerceIn(lo, hi), lo, hi, Modifier.weight(1f)) { v -> change { it.copy(cq = v) } }
                                    Spacer(Modifier.width(12.dp))
                                    ValueText((if (enc?.soft == true) "CRF " else "") + "${s.cq}", mono = true)
                                }
                                Hint(if (enc?.soft == true) "Lower is better · 18 is visually lossless · the file grows as needed" else "Higher is better; the file grows as needed")
                            }
                        }
                    }
                    KDivider()
                    KRow("Codec") {
                        KSegmented(VideoCodec.entries.map { it.label }, VideoCodec.entries.indexOf(s.codec), Modifier.width((VideoCodec.entries.size * 70).dp)) { i ->
                            val c = VideoCodec.entries[i]
                            change { it.copy(codec = c, encoder = encoderFor(c), profile = 0) }
                        }
                    }
                    KDivider()
                    KRow("Frame rate", "Set by the project") {
                        Icon(Lucide.Lock, null, tint = Kit.Faint, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        KValue("${fpsText(meta.fps)} fps")
                    }
                }

                KSection("Audio")
                KGroup {
                    KRow("Include sound") { KToggle(s.audio) { v -> change { it.copy(audio = v) } } }
                    AnimatedVisibility(s.audio, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column {
                            KDivider()
                            val rates = listOf(128_000, 192_000, 256_000, 320_000)
                            KRow("Bitrate", "AAC · 48 kHz · stereo") {
                                KSegmented(rates.map { "${it / 1000}" }, rates.indexOf(s.audioBitrate).coerceAtLeast(0), Modifier.width(200.dp)) { i -> change { it.copy(audioBitrate = rates[i]) } }
                            }
                            KDivider()
                            KRow("Even out loudness", "To %.0f LUFS, as the platforms measure it".format(s.loudnessTarget)) { KToggle(s.normalizeLoudness) { v -> change { it.copy(normalizeLoudness = v) } } }
                        }
                    }
                }

                KSection("File")
                KGroup {
                    KRow("Name") {
                        BasicTextField(
                            s.fileName, { v -> change { it.copy(fileName = v.replace(Regex("[\\\\/:*?\"<>|]"), "_")) } }, singleLine = true,
                            textStyle = TextStyle(color = Kit.Text, fontSize = 15.sp, textAlign = TextAlign.End), cursorBrush = SolidColor(Kit.Accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    KDivider()
                    KRow("Format") {
                        KSegmented(Container.entries.map { if (it == Container.MOV) "MOV" else "MP4" }, Container.entries.indexOf(s.container), Modifier.width(140.dp)) { i ->
                            change { it.copy(container = Container.entries[i]) }
                        }
                    }
                    KDivider()
                    KRow("Saved to") { KValue("Movies / Graden") }
                }

                KSection("Advanced")
                KFold("Encoder and colour", "${enc?.label?.substringAfter("· ") ?: "No encoder"} · ${s.bitDepth}-bit · ${outSpace.name.replace(" Gamma ", " γ")}") {
                    val list = encoders.filter { it.codec == s.codec }
                    FoldTitle("Encoder")
                    if (list.isEmpty()) Text("This device has no ${s.codec.label} encoder.", color = Kit.Bad, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    for (e in list) EncoderOption(e, e.name == enc?.name, e.supports(s.width, s.height, meta.fps)) {
                        change {
                            it.copy(
                                encoder = e.name, profile = 0, bitDepth = if (e.tenBit) it.bitDepth else 8,
                                quality = if (it.quality == QualityMode.CONSTANT && !e.supportsCq) QualityMode.AUTO_BEST else it.quality,
                                cq = if (e.soft) (if (it.cq in 0..51) it.cq else 18) else (e.qualityRange?.let { r -> it.cq.coerceIn(r.first, r.last) } ?: it.cq),
                            )
                        }
                    }
                    if (enc?.tenBit == true) {
                        KDivider(0.dp)
                        KRow("Bit depth") {
                            KSegmented(listOf("8-bit", "10-bit"), if (s.bitDepth == 10) 1 else 0, Modifier.width(150.dp)) { i -> change { it.copy(bitDepth = if (i == 1) 10 else 8, profile = 0) } }
                        }
                    }
                    if (enc?.soft == true) {
                        KDivider(0.dp)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Speed", color = Kit.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                Text(s.speedPreset, color = Kit.Dim, fontSize = 14.sp, fontFamily = MonoFont)
                            }
                            val speeds = listOf("ultrafast", "veryfast", "fast", "medium", "slow", "veryslow")
                            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState())) {
                                Segments(listOf("Fastest", "Very fast", "Fast", "Medium", "Slow", "Best"), speeds.indexOf(s.speedPreset).coerceAtLeast(0)) { i -> change { it.copy(speedPreset = speeds[i]) } }
                            }
                            Hint("Slower presets make smaller files at the same quality; the phone works harder")
                        }
                    }
                    // Output colour space. HDR is offered only where this encoder can write 10-bit (and HDR10 metadata).
                    KDivider(0.dp)
                    FoldTitle("Colour")
                    val outs = com.graden.editor.ColorSpaces.outputs.filter { !it.hdr || hdrEncoderFor(s.codec, it) != null }
                    for (o in outs) KOption(o.name, o.note, o == outSpace, badge = { KBadge(if (o.hdr) "HDR" else "SDR", if (o.hdr) Kit.Accent else Kit.Dim) }) {
                        hdrNote = null
                        change { it.copy(output = o.name, bitDepth = if (o.hdr) 10 else it.bitDepth, profile = 0, encoder = (if (o.hdr) hdrEncoderFor(it.codec, o) else null) ?: it.encoder) }
                    }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        hdrNote?.let { Hint(it) }
                        if (hdrNote == null && com.graden.editor.ColorSpaces.outputs.any { it.hdr && it !in outs }) Hint(
                            if (encoders.none { it.codec == s.codec && it.tenBit }) "HDR needs 10-bit encoding, which this phone's ${s.codec.label} encoder does not have" +
                                (if (s.codec == VideoCodec.H264 && encoders.any { it.codec == VideoCodec.H265 && it.tenBit }) "; H.265 has it" else "")
                            else "HDR10 needs an encoder that writes HDR10 metadata; this phone has none for ${s.codec.label}",
                        )
                        if (outSpace.hdr && !com.graden.editor.ColorSpaces.working(meta.workingSpace).log) Hint(
                            "The project is graded in an SDR working space, so highlights are rolled off. For full HDR, set the working space to DaVinci Wide Gamut or ACEScct in project settings.",
                        )
                    }
                    KDivider(0.dp)
                    if (s.quality != QualityMode.CONSTANT) {
                        val rates = RateMode.entries.filter { (it == RateMode.VBR && enc?.supportsVbr != false) || (it == RateMode.CBR && enc?.supportsCbr != false) }
                        KRow("Rate control") { KSegmented(rates.map { if (it == RateMode.VBR) "VBR" else "CBR" }, rates.indexOf(s.rateMode).coerceAtLeast(0), Modifier.width(130.dp)) { i -> change { it.copy(rateMode = rates[i]) } } }
                        KDivider()
                    }
                    val profiles = enc?.profiles.orEmpty().filter { it.tenBit == (s.bitDepth == 10) }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Profile", color = Kit.Text, fontSize = 15.sp)
                        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                            Segments(listOf("Auto") + profiles.map { it.label }, if (s.profile == 0) 0 else profiles.indexOfFirst { it.value == s.profile } + 1) { i ->
                                change { it.copy(profile = if (i == 0) 0 else profiles[i - 1].value) }
                            }
                        }
                    }
                    KDivider()
                    KRow("Keyframes") {
                        if (s.keyframeEvery > 0) {
                            NumberBox(s.keyframeEvery, Modifier.width(64.dp)) { v -> change { it.copy(keyframeEvery = v.coerceIn(1, 1000)) } }
                            Text(" fr  ", color = Kit.Dim, fontSize = 13.sp)
                        }
                        KSegmented(listOf("Auto", "Every"), if (s.keyframeEvery == 0) 0 else 1, Modifier.width(130.dp)) { i -> change { it.copy(keyframeEvery = if (i == 0) 0 else meta.fps.roundToInt().coerceAtLeast(1)) } }
                    }
                    KDivider()
                    KRow("Working space", "Set in project settings") { KValue(com.graden.editor.ColorSpaces.working(meta.workingSpace).name) }
                }
                Spacer(Modifier.height(24.dp))
            }
            // The action, with what it will write, or why it can't.
            Column(Modifier.fillMaxWidth().background(Kit.Bg).padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (problem != null) Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Alert, null, tint = Kit.Bad, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(problem, color = Kit.Bad, fontSize = 12.sp, maxLines = 3, lineHeight = 15.sp)
                }
                val ok = problem == null && enc != null
                KPrimary(
                    "Export", enabled = ok,
                    sub = "${s.codec.label} · ${s.width}×${s.height}" + if (s.quality == QualityMode.CONSTANT) (if (enc?.soft == true) " · CRF ${s.cq}" else " · constant quality") else " · %.0f Mbps · ≈ %s".format(br / 1e6, sizeText(bytes)),
                ) {
                    if (Build.VERSION.SDK_INT >= 33) notifyAsk.launch(Manifest.permission.POST_NOTIFICATIONS)
                    vm.pause()
                    ExportJob.start(context, vm.exportProject(s.output), s, enc!!)
                }
            }
        }
        if (naming) NameDialog("Preset ${userPresets.size + 1}", onDismiss = { naming = false }) { name ->
            ExportPresets.saveUser(context, name, s)
            userPresets = ExportPresets.loadUser(context)
            preset = name
            naming = false
        }
    }
}

@Composable
private fun FoldTitle(t: String) {
    Text(t.uppercase(), color = Kit.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp))
}

/** Why the current settings cannot be rendered on this device, or null if they can. */
private fun problem(s: ExportSettings, enc: EncoderInfo?): String? = when {
    enc == null -> "No ${s.codec.label} encoder on this device"
    s.fileName.isBlank() -> "Give the file a name"
    s.width > enc.maxWidth || s.height > enc.maxHeight || !enc.supports(s.width, s.height, 1f) ->
        "${s.width}×${s.height} is beyond this encoder (up to ${enc.maxWidth}×${enc.maxHeight})"
    s.quality == QualityMode.RESTRICT && s.bitrate > enc.maxBitrate -> "This encoder goes up to ${enc.maxBitrate / 1_000_000} Mbps"
    else -> null
}

private fun resolutionChoices(w: Int, h: Int): List<Pair<String, Pair<Int, Int>>> {
    fun at(short: Int): Pair<Int, Int> {
        val k = short.toFloat() / minOf(w, h)
        return ((w * k).toInt() / 2 * 2) to ((h * k).toInt() / 2 * 2)
    }
    val out = arrayListOf("Timeline" to (w to h))
    for ((label, short) in listOf("720p" to 720, "1080p" to 1080, "1440p" to 1440, "4K" to 2160, "8K" to 4320)) {
        val wh = at(short)
        if (wh != (w to h)) out += label to wh
    }
    return out
}

private fun fpsText(f: Float) = if (f % 1f == 0f) "%.0f".format(f) else "%.3f".format(f).trimEnd('0')

private fun sizeText(b: Long) = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

private fun clock(sec: Long) = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

// ---- the render ----------------------------------------------------------------------------------------

@Composable
private fun ExportProgress(vm: EditorViewModel) {
    val context = LocalContext.current
    val phase = ExportJob.phase
    val s = ExportJob.settings
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var endedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ExportJob.running) {
        while (ExportJob.running) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
        endedAt = SystemClock.elapsedRealtime()
    }
    var cancelArmed by remember { mutableStateOf(false) }
    LaunchedEffect(cancelArmed) {
        if (cancelArmed) {
            delay(3000)
            cancelArmed = false
        }
    }
    val elapsed = ((if (ExportJob.running) now else endedAt) - ExportJob.startedAt).coerceAtLeast(0) / 1000
    val progress by animateFloatAsState(ExportJob.progress.coerceIn(0f, 1f), label = "progress")
    val aspect = ((s?.width ?: 16).toFloat() / (s?.height ?: 9)).coerceIn(0.3f, 3.5f)

    Column(Modifier.fillMaxSize()) {
        KHeader(
            when (phase) {
                ExportJob.Phase.DONE -> "Export complete"
                ExportJob.Phase.FAILED -> "Export failed"
                ExportJob.Phase.CANCELLED -> "Export cancelled"
                else -> "Exporting"
            },
            "${s?.fileName ?: ""}.${s?.container?.ext ?: ""}",
        )
        // Everything but the buttons scrolls if it must; the picture never takes more than its share of the height,
        // so a tall (9:16) export leaves the progress in view.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val maxH = maxHeight * 0.38f
            val maxW = maxWidth - 32.dp
            val boxW = if (maxW / aspect > maxH) maxH * aspect else maxW
            val boxH = boxW / aspect
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.padding(top = 4.dp).size(boxW, boxH).clip(RoundedCornerShape(18.dp)).background(Color.Black).border(1.dp, Kit.Hair, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    ExportJob.preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        ?: Text(if (phase == ExportJob.Phase.AUDIO) "Mixing sound…" else "", color = Kit.Dim, fontSize = 13.sp)
                    if (phase == ExportJob.Phase.VIDEO && ExportJob.framesDone > 0) Text(
                        vm.tc((ExportJob.framesDone / ExportJob.fps * 1e6).toLong()), color = Ink, fontSize = 12.sp, fontFamily = MonoFont,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                    if (phase == ExportJob.Phase.DONE) Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Color(0xCC15161C)), contentAlignment = Alignment.Center,
                    ) { Icon(Lucide.Check, null, tint = Good, modifier = Modifier.size(34.dp)) }
                }
                Spacer(Modifier.height(20.dp))
                Steps(phase)
                Spacer(Modifier.height(18.dp))
                when (phase) {
                    ExportJob.Phase.DONE -> {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Kit.Group).padding(16.dp)) {
                            Text(ExportJob.resultName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text("${sizeText(ExportJob.resultBytes)} · ${s?.codec?.label} · ${s?.width}×${s?.height} · ${fpsText(ExportJob.fps)} fps", color = Sub, fontSize = 13.sp)
                            Text("Movies / Graden · made in ${clock(elapsed)}", color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    ExportJob.Phase.FAILED, ExportJob.Phase.CANCELLED -> {
                        if (phase == ExportJob.Phase.FAILED) Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x22E5484D)).padding(14.dp), verticalAlignment = Alignment.Top,
                        ) {
                            Icon(Lucide.Alert, null, tint = Kit.Bad, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(ExportJob.error ?: "Something went wrong", color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        else Text("Nothing was saved.", color = Sub, fontSize = 13.sp)
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            Text("%d%%".format((progress * 100).toInt()), color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                            Spacer(Modifier.weight(1f))
                            if (phase == ExportJob.Phase.VIDEO && ExportJob.etaSec >= 0) Text("${clock(ExportJob.etaSec.toLong())} left", color = Sub, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Kit.Raised)) {
                            Box(Modifier.fillMaxWidth(progress).height(8.dp).clip(CircleShape).background(Kit.Primary))
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Kit.Group).padding(vertical = 12.dp)) {
                            Stat("Elapsed", clock(elapsed), Modifier.weight(1f))
                            Stat("Speed", if (ExportJob.speed > 0f) "%.1f fps".format(ExportJob.speed) else "–", Modifier.weight(1f))
                            Stat("Frames", if (ExportJob.framesTotal > 0) "${ExportJob.framesDone}/${ExportJob.framesTotal}" else "–", Modifier.weight(1f))
                        }
                        // Stabilisation: how long it took to prepare, and how fast the stabilised frames render.
                        if (ExportJob.stabClips > 0) {
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Kit.Group).padding(vertical = 12.dp)) {
                                Stat("Stabilise prep", if (ExportJob.stabPrepMs < 1000) "${ExportJob.stabPrepMs} ms" else "%.1f s".format(ExportJob.stabPrepMs / 1000f), Modifier.weight(1f))
                                Stat("Stabilised", if (ExportJob.stabFps > 0f) "%.1f fps".format(ExportJob.stabFps) else "–", Modifier.weight(1f))
                                Stat("Frames", "${ExportJob.stabFrames}", Modifier.weight(1f))
                            }
                        }
                        if (ExportJob.speed > 0f) Text(
                            "%.2f× real time · %s".format(ExportJob.speed / ExportJob.fps, s?.let { "${it.codec.label} · ${it.width}×${it.height}" } ?: ""),
                            color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        if (!ExportJob.running && phase == ExportJob.Phase.DONE) {
            // Finished: straight on to the next one, from the project setup.
            KSecondary("Start a new project", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), Lucide.Plus) {
                ExportJob.reset()
                vm.exportOpen = false
                vm.closeProject()
                vm.askNewProject = true
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ExportJob.running) {
                KSecondary(if (cancelArmed) "Tap again to cancel" else "Cancel", Modifier.weight(1f), danger = cancelArmed) {
                    if (cancelArmed) ExportJob.cancel() else cancelArmed = true
                }
            } else if (phase == ExportJob.Phase.DONE) {
                KSecondary("Play", Modifier.weight(1f), Lucide.Play) {
                    ExportJob.result?.let { u ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, ExportJob.settings?.container?.mime ?: "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }
                }
                KSecondary("Share", Modifier.weight(1f), Lucide.Share) {
                    ExportJob.result?.let { u ->
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(Intent(Intent.ACTION_SEND).setType(ExportJob.settings?.container?.mime ?: "video/*").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"),
                            )
                        }
                    }
                }
                KPrimary("Done", Modifier.weight(1f)) {
                    ExportJob.reset()
                    vm.exportOpen = false
                }
            } else {
                KPrimary("Back to settings", Modifier.weight(1f)) { ExportJob.reset() }
            }
        }
    }
}

/** Sound → Picture → Save, each marked done, under way or to come. */
@Composable
private fun Steps(phase: ExportJob.Phase) {
    val order = listOf(ExportJob.Phase.AUDIO to "Sound", ExportJob.Phase.VIDEO to "Picture", ExportJob.Phase.SAVING to "Save")
    val at = when (phase) {
        ExportJob.Phase.AUDIO -> 0
        ExportJob.Phase.VIDEO -> 1
        ExportJob.Phase.SAVING -> 2
        ExportJob.Phase.DONE -> 3
        else -> -1
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        order.forEachIndexed { i, (_, label) ->
            val done = at > i
            val active = at == i
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (done) Good else if (active) Palette.Accent else Field),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Lucide.Check, null, tint = Dark, modifier = Modifier.size(14.dp))
                else Text("${i + 1}", color = if (active) Dark else Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(label, color = if (done || active) Ink else Faint, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 8.dp))
            if (i < order.size - 1) Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(1.dp).background(if (done) Good.copy(alpha = 0.6f) else CardLine))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 14.dp), horizontalAlignment = Alignment.Start) {
        Text(label, color = Sub, fontSize = 11.5.sp, maxLines = 1)
        Text(value, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private enum class ButtonKind { Primary, Plain, Danger }

@Composable
private fun WideButton(text: String, kind: ButtonKind, modifier: Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val bg = when (kind) {
        ButtonKind.Primary -> Palette.Accent
        ButtonKind.Danger -> Palette.Danger
        ButtonKind.Plain -> Field
    }
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = if (kind == ButtonKind.Plain) Ink else Dark, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = if (kind == ButtonKind.Plain) Ink else if (kind == ButtonKind.Danger) Color.White else Dark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---- building blocks -------------------------------------------------------------------------------------

@Composable
private fun SectionTitle(t: String, modifier: Modifier = Modifier) {
    Text(t.uppercase(), color = Sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = modifier.padding(top = 18.dp, bottom = 8.dp))
}

/** A titled inspector card; a [collapsible] one starts folded. */
@Composable
private fun Card(title: String, collapsible: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(!collapsible) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(if (collapsible) Modifier.clickable { open = !open } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionTitle(title, Modifier.weight(1f))
        if (collapsible) Icon(Lucide.ChevronDown, null, tint = Sub, modifier = Modifier.padding(top = 10.dp).size(16.dp).rotate(turn))
    }
    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).border(1.dp, CardLine, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp),
            content = content,
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(CardLine))
}

/** Label on the left (with an optional note under it), its value or control on the right. */
@Composable
private fun RowItem(label: String, note: String? = null, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.padding(end = 12.dp)) {
            Text(label, color = Sub, fontSize = 14.sp)
            note?.let { Text(it, color = Faint, fontSize = 11.sp) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { value() }
    }
}

/** A row that opens in place to show its choices. */
@Composable
private fun Expandable(label: String, value: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "chev")
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable { open = !open }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 15.sp, modifier = Modifier.padding(end = 12.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { value() }
        Icon(Lucide.ChevronRight, null, tint = Faint, modifier = Modifier.padding(start = 8.dp).size(16.dp).rotate(turn))
    }
    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), content = content)
    }
}

@Composable
private fun ValueText(t: String, color: Color = Ink, mono: Boolean = false) {
    Text(t, color = color, fontSize = 15.sp, fontFamily = if (mono) MonoFont else FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Hint(t: String) {
    Text(t, color = Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Badge(t: String, color: Color) {
    Text(
        t, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Segments(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Field).padding(3.dp)) {
        items.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label, color = if (on) Dark else Ink, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Palette.Accent else Color.Transparent)
                    .clickable {
                        if (!on) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSelect(i)
                    }.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    val x by animateFloatAsState(if (on) 1f else 0f, label = "toggle")
    Box(
        Modifier.width(50.dp).height(30.dp).clip(RoundedCornerShape(50)).background(if (on) Palette.Accent else Field)
            .clickable { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onChange(!on) }.padding(3.dp),
    ) { Box(Modifier.offset(x = 20.dp * x).size(24.dp).clip(CircleShape).background(Color.White)) }
}

/** A slider sized for a finger, with a tick each step of about a twentieth of its range. */
@Composable
private fun ValueSlider(value: Int, min: Int, max: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val view = LocalView.current
    val frac = ((value - min).toFloat() / (max - min).coerceAtLeast(1)).coerceIn(0f, 1f)
    BoxWithConstraints(modifier.height(36.dp).pointerInput(min, max) {
        fun at(x: Float) = (min + (x / size.width).coerceIn(0f, 1f) * (max - min)).roundToInt()
        var last = value
        detectHorizontalDragGestures(onDragStart = { last = value }) { c, _ ->
            c.consume()
            val v = at(c.position.x)
            if (v != last && (v - min) % ((max - min) / 20).coerceAtLeast(1) == 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            last = v
            onChange(v)
        }
    }.pointerInput(min, max) {
        detectTapGestures { p -> onChange((min + (p.x / size.width).coerceIn(0f, 1f) * (max - min)).roundToInt()) }
    }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Field))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(50)).background(Palette.Accent))
        Box(Modifier.offset(x = (maxWidth - 22.dp) * frac).size(22.dp).clip(CircleShape).background(Color.White).border(3.dp, Palette.Accent, CircleShape))
    }
}

@Composable
private fun NumberBox(value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        text, { t -> text = t.filter { it.isDigit() }.take(6); text.toIntOrNull()?.let(onChange) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(color = Ink, fontSize = 15.sp, fontFamily = MonoFont, textAlign = TextAlign.Center), cursorBrush = SolidColor(Palette.Accent),
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Field).padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

@Composable
private fun SizeTile(label: String, size: String, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF2B2016) else Field)
            .border(1.dp, if (on) Palette.Accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = if (!enabled) Faint else if (on) Palette.Accent else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(if (enabled) size else "Too large", color = if (enabled) Sub else Faint, fontSize = 11.sp, fontFamily = MonoFont)
    }
}

@Composable
private fun ColourOption(name: String, note: String, on: Boolean, hdr: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF2B2016) else Field)
            .border(1.dp, if (on) Palette.Accent else Color.Transparent, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(if (hdr) "HDR" else "SDR", if (hdr) Palette.Accent else Sub)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = if (on) Palette.Accent else Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(note, color = Sub, fontSize = 11.sp)
        }
        if (on) Icon(Lucide.Check, null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EncoderOption(e: EncoderInfo, on: Boolean, fits: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF2B2016) else Field)
            .border(1.dp, if (on) Palette.Accent else Color.Transparent, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(if (e.hardware) "HW" else "SW", if (e.hardware) Good else Blue)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.label.substringAfter("· "), color = if (on) Palette.Accent else Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                (if (e.hardware) "Hardware" else "Software") + " · " + e.limits + if (!fits) " · not at this size" else "",
                color = if (fits) Sub else Palette.Danger, fontSize = 11.sp,
            )
        }
        if (on) Icon(Lucide.Check, null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetCard(name: String, st: ExportSettings, on: Boolean, saved: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val click = if (onLong != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLong) else Modifier.clickable(onClick = onClick)
    Column(
        Modifier.width(150.dp).height(92.dp).clip(RoundedCornerShape(14.dp))
            .background(if (on) Brush.verticalGradient(listOf(Color(0xFF33231A), Color(0xFF221A14))) else SolidColor(CardBg))
            .border(if (on) 1.5.dp else 1.dp, if (on) Palette.Accent else CardLine, RoundedCornerShape(14.dp)).then(click).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(st.codec.label, if (on) Palette.Accent else Sub)
            Spacer(Modifier.weight(1f))
            if (saved) Text("Saved", color = Faint, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(name, color = if (on) Palette.Accent else Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${shortRes(st.width, st.height)} · ${st.container.ext.uppercase()} · " + when (st.quality) {
                QualityMode.AUTO_BEST -> "Best"
                QualityMode.RESTRICT -> "${st.bitrate / 1_000_000} Mbps"
                QualityMode.CONSTANT -> "CQ"
            },
            color = Sub, fontSize = 11.sp, maxLines = 1,
        )
    }
}

private fun shortRes(w: Int, h: Int): String = when (minOf(w, h)) {
    2160 -> "4K"
    4320 -> "8K"
    else -> "${minOf(w, h)}p"
}

@Composable
private fun AddPresetCard(onClick: () -> Unit) {
    Column(
        Modifier.width(110.dp).height(92.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, CardLine, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.Plus, null, tint = Sub, modifier = Modifier.size(20.dp))
        Text("Save preset", color = Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).border(1.dp, CardLine, RoundedCornerShape(20.dp)).padding(20.dp)) {
            Text("Save preset", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Keeps these export settings for any project.", color = Sub, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            BasicTextField(
                name, { name = it.take(40) }, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 15.sp), cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Field).padding(horizontal = 12.dp, vertical = 12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WideButton("Cancel", ButtonKind.Plain, Modifier.weight(1f), onClick = onDismiss)
                WideButton("Save", ButtonKind.Primary, Modifier.weight(1f)) { if (name.isNotBlank()) onSave(name.trim()) }
            }
        }
    }
}

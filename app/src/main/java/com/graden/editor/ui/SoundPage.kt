package com.graden.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.Clip
import com.graden.editor.EditorViewModel
import com.graden.editor.MediaAsset
import com.graden.editor.Snd
import com.graden.editor.WaveformCache
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// The page's own palette: deep neutral surfaces, the sound's green for what is heard, the app's orange for what is touched.
private val Sheet = Color(0xFF111217)
private val Surface = Color(0xFF191A20)
private val Raised = Color(0xFF22232B)
private val Hair = Color(0xFF2A2C35)
private val Wave = Color(0xFF5FCF9A)
private val WaveDeep = Color(0xFF2E7A57)
private val Hot = Color(0xFFFF5A52)
// Each group has its own colour, so level, balance, fades, equaliser and pitch never look alike.
private val LevelInk = Color(0xFFF0872B)
private val PanInk = Color(0xFF6FA8FF)
private val FadeInk = Color(0xFFB48CFF)
private val EqInk = Color(0xFF5FCF9A)
private val PitchInk = Color(0xFFFF7EB6)

/** Equaliser starting points: bass, mid and treble in dB. */
private val EqPresets = listOf(
    "Flat" to Triple(0f, 0f, 0f), "Voice" to Triple(-4f, 4f, 2f), "Podcast" to Triple(-3f, 3f, 3f), "Warm" to Triple(4f, 1f, -3f),
    "Bright" to Triple(-2f, 0f, 6f), "Bass boost" to Triple(8f, 0f, 0f), "Telephone" to Triple(-12f, 6f, -10f),
)

private enum class SoundTab(val label: String) { MIX("Mix"), EQ("Equaliser"), PITCH("Pitch"), MORE("More") }

/**
 * The Sound page: one clip's sound, for a video's own sound, an audio file, or sound extracted from a video. At the top,
 * every lane that carries sound, so any other clip can be picked and worked on without leaving the page. Then the clip
 * itself, large, shaped by what is set: its fades are dragged at its corners and its level by the line across it. Below,
 * the mix, the equaliser as a curve with handles, and the pitch. Changes are heard straight away; the tick keeps
 * everything done on the page as one undo step, across every clip touched, and the cross puts it all back.
 */
/** The top bar while the Sound page is open: cancel, which clip, hear it, done. */
@Composable
fun SoundTopBar(vm: EditorViewModel) {
    val id = vm.soundClipId ?: return
    val l = vm.locate(id) ?: return
    val a = vm.assets[l.clip.assetId] ?: return
    Header(vm, l.clip, a, l.trackId)
}

/**
 * The Sound page's body, in the editor's own layout: the picture stays above it (on a phone) or beside it (on a tablet,
 * where it is the side panel and the editor's timeline stays below). [strip]: the lanes with sound, as a small timeline
 * of the whole project, for screens where the editor's timeline is out of view.
 */
@Composable
fun SoundBody(vm: EditorViewModel, soon: (String) -> Unit, modifier: Modifier, strip: Boolean = true) {
    val id = vm.soundClipId ?: return
    val l = vm.locate(id) ?: return
    val c = l.clip
    val a = vm.assets[c.assetId] ?: return
    val sn = c.snd
    var tab by remember { mutableStateOf(SoundTab.MIX) }
    BackHandler { vm.closeSound(keep = true) }
    // A clip picked on the editor's timeline while the page is open becomes the one being worked on.
    androidx.compose.runtime.LaunchedEffect(vm.selectedClipId) {
        val sel = vm.selectedClipId ?: return@LaunchedEffect
        if (sel != vm.soundClipId && vm.soundLanes.any { (_, cs) -> cs.any { it.id == sel } }) vm.switchSound(sel)
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(Sheet)) {
    // A big panel (a tablet): a tall waveform, then everything at once in two columns, no tabs.
    val waveH = (maxHeight * 0.3f).coerceIn(150.dp, 260.dp)
    if (maxWidth >= 540.dp && maxHeight >= 520.dp) Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        if (strip) SoundTimeline(vm, id)
        HeroWave(vm, c, a, sn, waveH)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = 12.dp)) { Twins(vm, c) }
        Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                MixTab(vm, c, sn)
                if (!sn.isDefault) Text(
                    "Reset this clip's sound", color = Hot, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp)).clickable { vm.setSnd { Snd() } }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                EqTab(vm, sn)
                PitchTab(vm, sn)
                MoreTab(soon)
            }
        }
        Spacer(Modifier.height(8.dp))
    } else Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        if (strip) SoundTimeline(vm, id)
        HeroWave(vm, c, a, sn)
        Tabs(tab) { tab = it }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Twins(vm, c)
            when (tab) {
                SoundTab.MIX -> MixTab(vm, c, sn)
                SoundTab.EQ -> EqTab(vm, sn)
                SoundTab.PITCH -> PitchTab(vm, sn)
                SoundTab.MORE -> MoreTab(soon)
            }
            if (!sn.isDefault) Text(
                "Reset this clip's sound", color = Hot, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp)).clickable { vm.setSnd { Snd() } }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
    }
}

@Composable
private fun Header(vm: EditorViewModel, c: Clip, a: MediaAsset, trackId: Int) {
    Row(Modifier.fillMaxWidth().height(52.dp).background(Sheet).padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.X, "Cancel", tint = Palette.TextDim, modifier = Modifier.size(42.dp).clip(CircleShape).clickable { vm.closeSound(keep = false) }.padding(11.dp))
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            Text("Sound", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vm.labelOf(trackId), color = Wave, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Wave.copy(alpha = 0.14f)).padding(horizontal = 5.dp, vertical = 1.dp))
                Text("  " + a.name, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Hearing the clip is a pill of its own, so it is never mistaken for the editor's play button.
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Raised).clickable { if (vm.isPlaying) vm.pause() else vm.previewSound() }
                .padding(start = 10.dp, end = 13.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (vm.isPlaying) Lucide.Pause else Lucide.Play, null, tint = Wave, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (vm.isPlaying) "Stop" else "Hear clip", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "Done", color = Color(0xFF15161C), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.Accent).clickable { vm.closeSound(keep = true) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * Every lane with sound, top lane first, laid over the project's whole length: each clip a block with its waveform,
 * the one being worked on lit. Tap another clip to work on its sound.
 */
@Composable
private fun SoundTimeline(vm: EditorViewModel, current: Long) {
    val lanes = vm.soundLanes
    if (lanes.isEmpty()) return
    val total = vm.totalUs.coerceAtLeast(1L).toFloat()
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).border(1.dp, Hair, RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sound timeline", color = Palette.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("Drag to scrub · tap a clip to edit it", color = Palette.TextDim.copy(alpha = 0.7f), fontSize = 10.sp)
        }
        Column(Modifier.heightInLanes(lanes.size).verticalScroll(rememberScrollState())) {
            for ((t, clips) in lanes) {
                val cur by rememberUpdatedState(current)
                val list by rememberUpdatedState(clips)
                Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, color = if (t.video) Color(0xFF8AA6FF) else Wave, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                    Canvas(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color(0xFF14151A))
                            .pointerInput(total) {
                                detectTapGestures { p ->
                                    val us = p.x / size.width * total
                                    list.firstOrNull { us >= it.startUs && us < it.endUs }?.let { vm.switchSound(it.id) }
                                }
                            }
                            // Dragging along it moves the playhead over the whole project, as a timeline does.
                            .pointerInput(total) {
                                detectHorizontalDragGestures { ch, _ -> ch.consume(); vm.seek((ch.position.x / size.width * total).toLong().coerceIn(0L, vm.totalUs)) }
                            }
                    ) {
                        vm.waves.version
                        val w = size.width; val h = size.height
                        for (cl in clips) {
                            val x0 = cl.startUs / total * w; val x1 = max(x0 + 2f, cl.endUs / total * w)
                            val on = cl.id == cur
                            val base = if (on) Palette.Accent else if (t.video) Color(0xFF3A4E8C) else WaveDeep
                            drawRoundRect(base.copy(alpha = if (on) 0.28f else 0.55f), Offset(x0 + 1f, 1f), Size(x1 - x0 - 2f, h - 2f), CornerRadius(4.dp.toPx()))
                            vm.assets[cl.assetId]?.let { asset -> miniWave(vm, cl, asset, x0, x1, h, if (on) Palette.Accent else Wave.copy(alpha = 0.85f)) }
                            if (on) drawRoundRect(Palette.Accent, Offset(x0 + 1f, 1f), Size(x1 - x0 - 2f, h - 2f), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        }
                        val ph = vm.playheadUs / total * w
                        drawLine(Color.White.copy(alpha = 0.75f), Offset(ph, 0f), Offset(ph, h), 1.dp.toPx())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 38.dp, end = 10.dp)) {
            Text("0:00", color = Palette.TextDim.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text(clock(vm.totalUs), color = Palette.TextDim.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** At most four lanes in view; more scroll. */
private fun Modifier.heightInLanes(n: Int): Modifier = this.height((min(n, 4) * 26).dp)

/** A clip's waveform, small, between [x0] and [x1]. */
private fun DrawScope.miniWave(vm: EditorViewModel, c: Clip, a: MediaAsset, x0: Float, x1: Float, h: Float, color: Color) {
    val peaks = vm.waves.get(a) ?: return
    val step = 2.5f
    var x = x0 + 2f
    val dur = c.durationUs.toFloat()
    while (x < x1 - 2f) {
        val t = (x - x0) / (x1 - x0) * dur
        val i = ((c.inUs + t * c.speed) * WaveformCache.PEAKS_PER_SEC / 1e6).toInt().coerceIn(0, peaks.size - 1)
        val v = (peaks[i].toInt() and 0xFF) / 255f * (if (c.snd.muted) 0f else c.snd.volume)
        val half = max(0.5f, min(1f, v) * (h / 2 - 3f))
        drawLine(color, Offset(x, h / 2 - half), Offset(x, h / 2 + half), 1.4f)
        x += step
    }
}

private enum class WaveGrab { NONE, FADE_IN, FADE_OUT, LEVEL }

/**
 * The clip, large: its waveform as it will be heard, shaped by level and fades (anything that would clip in red). The
 * fade handles sit at the top corners, dragged sideways; the level is the line across, dragged up or down. A dB scale
 * runs down the right.
 */
@Composable
private fun HeroWave(vm: EditorViewModel, c: Clip, a: MediaAsset, sn: Snd, height: androidx.compose.ui.unit.Dp = 118.dp) {
    val text = rememberTextMeasurer()
    val snd by rememberUpdatedState(sn)
    val dur = c.durationUs.toFloat().coerceAtLeast(1f)
    val maxFade = min(10_000_000L, c.durationUs / 2).toFloat().coerceAtLeast(1f)
    var grab by remember { mutableStateOf(WaveGrab.NONE) }
    Box(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1D24), Color(0xFF15161B)))).border(1.dp, Hair, RoundedCornerShape(16.dp))
    ) {
        Canvas(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 34.dp, top = 16.dp, bottom = 18.dp)
                .pointerInput(dur) {
                    detectDragGestures(
                        onDragStart = { p ->
                            val w = size.width.toFloat()
                            val fi = snd.fadeInUs / dur * w
                            val fo = w - snd.fadeOutUs / dur * w
                            val reach = 28.dp.toPx()
                            grab = when {
                                p.y < size.height * 0.4f && abs(p.x - fi) < reach -> WaveGrab.FADE_IN
                                p.y < size.height * 0.4f && abs(p.x - fo) < reach -> WaveGrab.FADE_OUT
                                else -> WaveGrab.LEVEL
                            }
                        },
                        onDragEnd = { grab = WaveGrab.NONE },
                        onDragCancel = { grab = WaveGrab.NONE },
                    ) { ch, d ->
                        ch.consume()
                        val w = size.width.toFloat()
                        when (grab) {
                            WaveGrab.FADE_IN -> vm.setSnd { it.copy(fadeInUs = (it.fadeInUs + d.x / w * dur).toLong().coerceIn(0L, maxFade.toLong())) }
                            WaveGrab.FADE_OUT -> vm.setSnd { it.copy(fadeOutUs = (it.fadeOutUs - d.x / w * dur).toLong().coerceIn(0L, maxFade.toLong())) }
                            WaveGrab.LEVEL -> vm.setSnd {
                                val db = (dbOf(it.volume) - d.y / (size.height / 2f) * SPAN_DB).coerceIn(TOP_DB - SPAN_DB, TOP_DB)
                                it.copy(volume = snap(gainOf(db), 1f, 0.03f).coerceIn(0f, 2f))
                            }
                            WaveGrab.NONE -> Unit
                        }
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { vm.setSnd { it.copy(volume = 1f) } }) }
        ) {
            vm.waves.version
            val peaks = vm.waves.get(a)
            val w = size.width; val h = size.height; val mid = h / 2
            // Centre line and the -6 dB guides.
            drawLine(Hair, Offset(0f, mid), Offset(w, mid), 1f)
            val norm = if (sn.normalize) vm.normalizeGain(c) else 1f
            val step = 3.dp.toPx()
            var x = 0f
            while (x < w && peaks != null) {
                val t = x / w * dur
                var env = 1f
                if (sn.fadeInUs > 0) env *= (t / sn.fadeInUs).coerceIn(0f, 1f)
                if (sn.fadeOutUs > 0) env *= ((dur - t) / sn.fadeOutUs).coerceIn(0f, 1f)
                val i = ((c.inUs + t * c.speed) * WaveformCache.PEAKS_PER_SEC / 1e6).toInt().coerceIn(0, peaks.size - 1)
                val v = (peaks[i].toInt() and 0xFF) / 255f * (if (sn.muted) 0f else sn.volume * env * norm)
                val half = max(0.8f, min(1f, v) * (mid - 2f))
                val hot = v > 1f
                drawRoundRect(
                    if (hot) Hot else Wave.copy(alpha = 0.55f + 0.45f * min(1f, v)), Offset(x, mid - half), Size(step * 0.62f, half * 2),
                    CornerRadius(step * 0.31f),
                )
                x += step
            }
            if (peaks == null) drawRect(Color.White.copy(alpha = 0.04f), Offset.Zero, Size(w, h))
            // Fades: the ramp drawn over the sound, and a round handle at each top corner.
            val fi = sn.fadeInUs / dur * w
            val fo = sn.fadeOutUs / dur * w
            if (fi > 0f) drawPath(Path().apply { moveTo(0f, 0f); lineTo(fi, 0f); lineTo(0f, h); close() }, Color.Black.copy(alpha = 0.35f))
            if (fo > 0f) drawPath(Path().apply { moveTo(w, 0f); lineTo(w - fo, 0f); lineTo(w, h); close() }, Color.Black.copy(alpha = 0.35f))
            drawLine(Color.White.copy(alpha = 0.85f), Offset(0f, h), Offset(fi, 0f), 1.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.85f), Offset(w, h), Offset(w - fo, 0f), 1.5.dp.toPx())
            for ((hx, on) in listOf(fi to (grab == WaveGrab.FADE_IN), (w - fo) to (grab == WaveGrab.FADE_OUT))) {
                drawCircle(Color.Black.copy(alpha = 0.4f), (if (on) 9 else 7).dp.toPx(), Offset(hx, 0f))
                drawCircle(Color.White, (if (on) 7 else 5).dp.toPx(), Offset(hx, 0f))
            }
            // The level line: where 100% would put the loudest peak, dashed in the app's orange.
            val ly = levelY(sn.volume, mid)
            drawLine(Palette.Accent.copy(alpha = if (grab == WaveGrab.LEVEL) 1f else 0.8f), Offset(0f, ly), Offset(w, ly), (if (grab == WaveGrab.LEVEL) 2.5f else 1.6f).dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
            val tag = text.measure(db(sn.volume), TextStyle(color = Color(0xFF15161C), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
            val tx = 6.dp.toPx(); val ty = (ly - tag.size.height - 4.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(Palette.Accent, Offset(tx - 4.dp.toPx(), ty - 1.dp.toPx()), Size(tag.size.width + 8.dp.toPx(), tag.size.height + 2.dp.toPx()), CornerRadius(5.dp.toPx()))
            drawText(tag, topLeft = Offset(tx, ty))
        }
        // dB scale down the right edge.
        Canvas(Modifier.align(Alignment.CenterEnd).width(30.dp).fillMaxHeight().padding(top = 16.dp, bottom = 18.dp)) {
            val h = size.height; val mid = h / 2
            for ((lab, v) in listOf("+6" to 2f, "0" to 1f, "-12" to 0.251f)) {
                val y = levelY(v, mid)
                val m = text.measure(lab, TextStyle(color = Palette.TextDim.copy(alpha = 0.8f), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                drawLine(Hair, Offset(0f, y), Offset(5.dp.toPx(), y), 1f)
                drawText(m, topLeft = Offset(7.dp.toPx(), y - m.size.height / 2))
            }
        }
        Text("Drag the line for level · the corners for fades", color = Palette.TextDim.copy(alpha = 0.7f), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 2.dp))
        Text(clock(c.durationUs), color = Palette.TextDim.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 2.dp))
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Tabs(tab: SoundTab, onTab: (SoundTab) -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Surface).border(1.dp, Hair, RoundedCornerShape(11.dp)).padding(3.dp),
    ) {
        for (t in SoundTab.entries) {
            val on = t == tab
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) Raised else Color.Transparent).clickable { onTab(t) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text(t.label, color = if (on) Palette.Text else Palette.TextDim, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

/** The same sound also playing elsewhere would drown out every change made here: say so, and offer to mute it. */
@Composable
private fun Twins(vm: EditorViewModel, c: Clip) {
    val twins = vm.sameSoundElsewhere(c.id)
    if (twins.isEmpty()) return
    Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A1F15))
            .border(1.dp, Palette.Accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("This sound also plays on ${twins.joinToString { vm.labelOf(it.trackId) }}, so changes here are hard to hear.",
            color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("Mute it", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Accent).clickable { vm.muteSameSoundElsewhere() }
                .padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

// ---- Mix --------------------------------------------------------------------------------------------------------

@Composable
private fun MixTab(vm: EditorViewModel, c: Clip, sn: Snd) {
    val maxFade = min(10_000_000L, c.durationUs / 2).toFloat().coerceAtLeast(1f)
    Group("Level", Lucide.Volume, LevelInk) {
        Setting("Level", db(sn.volume), LevelInk) {
            Fader(sn.volume, 0f..2f, default = 1f, color = LevelInk, ticks = listOf(0f to "-∞", 0.25f to "-12", 0.5f to "-6", 1f to "0", 2f to "+6")) { v ->
                vm.setSnd { it.copy(volume = snap(v, 1f, 0.03f)) }
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch("Mute", Lucide.VolumeX, sn.muted, Modifier.weight(1f)) { vm.setSnd { it.copy(muted = !it.muted) } }
            Switch("Normalize", Lucide.AudioWave, sn.normalize, Modifier.weight(1f)) { vm.setSnd { it.copy(normalize = !it.normalize) } }
        }
    }
    Group("Balance", Lucide.ArrowUpDown, PanInk) {
        Setting("Pan", pan(sn.pan), PanInk) {
            Fader(sn.pan, -1f..1f, default = 0f, bipolar = true, color = PanInk, ticks = listOf(-1f to "L", 0f to "C", 1f to "R")) { v -> vm.setSnd { it.copy(pan = snap(v, 0f, 0.05f)) } }
        }
    }
    Group("Fades", Lucide.AnimIn, FadeInk) {
        Setting("In", secs(sn.fadeInUs), FadeInk) { Fader(sn.fadeInUs.toFloat(), 0f..maxFade, default = 0f, color = FadeInk) { v -> vm.setSnd { it.copy(fadeInUs = v.toLong()) } } }
        Setting("Out", secs(sn.fadeOutUs), FadeInk) { Fader(sn.fadeOutUs.toFloat(), 0f..maxFade, default = 0f, color = FadeInk) { v -> vm.setSnd { it.copy(fadeOutUs = v.toLong()) } } }
    }
}

// ---- Equaliser ----------------------------------------------------------------------------------------------------

@Composable
private fun EqTab(vm: EditorViewModel, sn: Snd) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((name, v) in EqPresets) {
            val on = sn.bass == v.first && sn.mid == v.second && sn.treble == v.third
            Text(
                name, color = if (on) Color(0xFF15161C) else Palette.Text, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) EqInk else Raised)
                    .clickable { vm.setSnd { it.copy(bass = v.first, mid = v.second, treble = v.third) } }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    Group("Equaliser · drag a point, double tap to flatten", Lucide.AudioLines, EqInk) {
        EqCurve(vm, sn)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            for ((name, hz, v) in listOf(Triple("Low", "200 Hz", sn.bass), Triple("Mid", "1 kHz", sn.mid), Triple("High", "4 kHz", sn.treble))) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dbBand(v), color = if (abs(v) < 0.05f) Palette.TextDim else EqInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    Text("$name · $hz", color = Palette.TextDim, fontSize = 10.sp)
                }
            }
        }
    }
}

/** How a band lifts or cuts at frequency [f], for the drawing (shelves at 200 Hz and 4 kHz, a bell at 1 kHz). */
private fun response(f: Float, sn: Snd): Float {
    val lf = log2(f)
    fun sig(x: Float) = 1f / (1f + exp(-x))
    val low = sn.bass * sig((log2(200f) - lf) * 2.6f)
    val mid = sn.mid * exp(-((lf - log2(1000f)).pow(2)) / (2f * 0.75f * 0.75f))
    val high = sn.treble * sig((lf - log2(4000f)) * 2.6f)
    return low + mid + high
}

@Composable
private fun EqCurve(vm: EditorViewModel, sn: Snd) {
    val text = rememberTextMeasurer()
    var held by remember { mutableIntStateOf(-1) }
    val lo = log2(20f); val hi = log2(20000f)
    val bands = listOf(200f, 1000f, 4000f)
    fun xOf(f: Float, w: Float) = (log2(f) - lo) / (hi - lo) * w
    fun set(i: Int, g: Float) = vm.setSnd {
        val v = snap(g.coerceIn(-12f, 12f), 0f, 0.4f)
        when (i) { 0 -> it.copy(bass = v); 1 -> it.copy(mid = v); else -> it.copy(treble = v) }
    }
    Canvas(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF14151A))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p -> held = bands.indices.minByOrNull { abs(xOf(bands[it], size.width.toFloat()) - p.x) } ?: -1 },
                    onDragEnd = { held = -1 }, onDragCancel = { held = -1 },
                ) { ch, _ ->
                    ch.consume()
                    if (held >= 0) set(held, (0.5f - ch.position.y / size.height) * 30f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p ->
                    val i = bands.indices.minByOrNull { abs(xOf(bands[it], size.width.toFloat()) - p.x) } ?: return@detectTapGestures
                    set(i, 0f)
                })
            }
    ) {
        val w = size.width; val h = size.height
        fun yOf(db: Float) = h / 2 - db / 30f * h
        // Grid: decades and the usual marks across, every 6 dB down.
        for (f in listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)) {
            val x = xOf(f, w)
            drawLine(Hair.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, h), 1f)
            val m = text.measure(if (f >= 1000f) "${(f / 1000).roundToInt()}k" else "${f.roundToInt()}", TextStyle(color = Palette.TextDim.copy(alpha = 0.6f), fontSize = 9.sp))
            drawText(m, topLeft = Offset(x + 3f, h - m.size.height - 2f))
        }
        for (d in listOf(-12f, -6f, 0f, 6f, 12f)) {
            val y = yOf(d)
            drawLine(if (d == 0f) Color(0xFF3A3D47) else Hair.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), if (d == 0f) 1.5f else 1f)
            if (d != 0f) {
                val m = text.measure((if (d > 0) "+" else "") + d.roundToInt(), TextStyle(color = Palette.TextDim.copy(alpha = 0.55f), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                drawText(m, topLeft = Offset(4f, y - m.size.height / 2))
            }
        }
        // The curve, filled towards 0 dB.
        val curve = Path(); val fill = Path()
        val n = 120
        for (k in 0..n) {
            val x = k / n.toFloat() * w
            val f = 2f.pow(lo + (hi - lo) * k / n)
            val y = yOf(response(f, sn))
            if (k == 0) { curve.moveTo(x, y); fill.moveTo(x, h / 2); fill.lineTo(x, y) } else { curve.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(w, h / 2); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(EqInk.copy(alpha = 0.28f), EqInk.copy(alpha = 0.04f))))
        drawPath(curve, EqInk, style = Stroke(2.2.dp.toPx()))
        // The three points.
        for ((i, f) in bands.withIndex()) {
            val g = when (i) { 0 -> sn.bass; 1 -> sn.mid; else -> sn.treble }
            val c = Offset(xOf(f, w), yOf(response(f, sn)))
            drawCircle(Color.Black.copy(alpha = 0.45f), (if (held == i) 13 else 10).dp.toPx(), c)
            drawCircle(if (abs(g) < 0.05f) Color(0xFFDADBE2) else EqInk, (if (held == i) 10 else 8).dp.toPx(), c)
            val m = text.measure("${i + 1}", TextStyle(color = Color(0xFF15161C), fontSize = 10.sp, fontWeight = FontWeight.Bold))
            drawText(m, topLeft = Offset(c.x - m.size.width / 2, c.y - m.size.height / 2))
        }
    }
}

// ---- Pitch -------------------------------------------------------------------------------------------------------

@Composable
private fun PitchTab(vm: EditorViewModel, sn: Snd) {
    Group("Pitch · in semitones, the clip keeps its speed", Lucide.Music, PitchInk) {
        Text(if (abs(sn.pitch) < 0.05f) "0 st" else "%+.1f st".format(sn.pitch), color = PitchInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Step("−") { vm.setSnd { it.copy(pitch = (it.pitch - 0.5f).coerceIn(-12f, 12f)) } }
            Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Fader(sn.pitch, -12f..12f, default = 0f, bipolar = true, color = PitchInk, ticks = listOf(-12f to "-12", -6f to "-6", 0f to "0", 6f to "+6", 12f to "+12")) { v ->
                    vm.setSnd { it.copy(pitch = (v * 2).roundToInt() / 2f) }
                }
            }
            Step("+") { vm.setSnd { it.copy(pitch = (it.pitch + 0.5f).coerceIn(-12f, 12f)) } }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((name, st) in listOf("Octave down" to -12f, "Deeper" to -3f, "Natural" to 0f, "Brighter" to 3f, "Octave up" to 12f)) {
                val on = abs(sn.pitch - st) < 0.05f
                Text(name, color = if (on) Color(0xFF15161C) else Palette.Text, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) PitchInk else Raised)
                        .clickable { vm.setSnd { it.copy(pitch = st) } }.padding(vertical = 7.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Step(label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Raised).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

// ---- More --------------------------------------------------------------------------------------------------------

@Composable
private fun MoreTab(soon: (String) -> Unit) {
    val tools = listOf(
        "Noise reduction" to "Takes out hiss, hum and room noise", "Voice isolation" to "Keeps the voice, lowers everything else",
        "Compressor" to "Evens out loud and quiet", "Limiter" to "Stops peaks from clipping", "Auto-duck music" to "Lowers music under speech",
        "Remove silence" to "Cuts the pauses", "De-ess" to "Softens harsh S sounds", "Reverb" to "Adds a room", "Echo" to "Repeats the sound",
    )
    Group("Coming soon", Lucide.Sparkles, Color(0xFF8A8D99)) {
        for ((name, what) in tools) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { soon(name) }.padding(vertical = 9.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, color = Palette.Text, fontSize = 13.sp)
                Text(what, color = Palette.TextDim, fontSize = 11.sp)
            }
            Text("Soon", color = Palette.TextDim, fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Raised).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

// ---- Parts -------------------------------------------------------------------------------------------------------

/** A group of settings: a coloured mark and title, then its rows, on a card a shade lighter than the page. */
@Composable
private fun Group(title: String, icon: ImageVector, ink: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface).border(1.dp, Hair, RoundedCornerShape(14.dp))
    ) {
        Row(Modifier.fillMaxWidth().background(ink.copy(alpha = 0.09f)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(ink.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(title, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
        }
        Column(Modifier.padding(start = 12.dp, end = 10.dp, top = 4.dp, bottom = 8.dp), content = content)
    }
}

/** One setting on one row: its name and value on the left, its fader beside them. */
@Composable
private fun Setting(name: String, value: String, ink: Color, fader: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(78.dp)) {
            Text(name, color = Palette.TextDim, fontSize = 11.sp)
            Text(value, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        Box(Modifier.weight(1f)) { fader() }
    }
}

@Composable
private fun Switch(label: String, icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val k by animateFloatAsState(if (on) 1f else 0f, spring(0.8f, 600f), label = "switch")
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.lerp(Raised, Color(0xFF2A1F15), k))
            .border(1.dp, androidx.compose.ui.graphics.lerp(Hair, Palette.Accent.copy(alpha = 0.6f), k), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) Palette.Accent else Palette.TextDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        // A small switch, drawn.
        Box(Modifier.size(30.dp, 18.dp).clip(RoundedCornerShape(9.dp)).background(androidx.compose.ui.graphics.lerp(Color(0xFF34363F), Palette.Accent, k))) {
            Box(Modifier.padding(start = (2 + 12 * k).dp, top = 2.dp).size(14.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/**
 * A fader: a slim track with tick marks and labels under it, the part from the rest point lit (from the start, or from
 * the centre when [bipolar]), and a grip the width of a fingertip. Drag or tap to set; double tap puts [default] back.
 */
@Composable
private fun Fader(
    value: Float, range: ClosedFloatingPointRange<Float>, default: Float, bipolar: Boolean = false, color: Color = LevelInk,
    ticks: List<Pair<Float, String>> = emptyList(), onChange: (Float) -> Unit,
) {
    val v by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val text = rememberTextMeasurer()
    var held by remember { mutableStateOf(false) }
    val span = range.endInclusive - range.start
    fun at(x: Float, w: Float) = (range.start + (x / w).coerceIn(0f, 1f) * span)
    Canvas(
        Modifier.fillMaxWidth().height(if (ticks.isEmpty()) 30.dp else 38.dp)
            .pointerInput(range) {
                detectTapGestures(onDoubleTap = { change(default) }, onTap = { p -> change(at(p.x - 10.dp.toPx(), size.width - 20.dp.toPx())) })
            }
            .pointerInput(range) {
                detectHorizontalDragGestures(onDragStart = { held = true }, onDragEnd = { held = false }, onDragCancel = { held = false }) { ch, d ->
                    ch.consume()
                    val w = size.width - 20.dp.toPx()
                    change((v + d / w * span).coerceIn(range.start, range.endInclusive))
                }
            }
    ) {
        val pad = 10.dp.toPx()
        val w = size.width - pad * 2
        val y = 14.dp.toPx()
        fun xOf(t: Float) = pad + (t - range.start) / span * w
        val track = 5.dp.toPx()
        drawRoundRect(Color(0xFF2C2E37), Offset(pad, y - track / 2), Size(w, track), CornerRadius(track / 2))
        val from = if (bipolar) xOf(default.coerceIn(range.start, range.endInclusive)) else pad
        val to = xOf(value.coerceIn(range.start, range.endInclusive))
        drawRoundRect(color, Offset(min(from, to), y - track / 2), Size(abs(to - from), track), CornerRadius(track / 2))
        for ((t, lab) in ticks) {
            val x = xOf(t)
            drawLine(Color(0xFF4A4D58), Offset(x, y + 8.dp.toPx()), Offset(x, y + 12.dp.toPx()), 1.dp.toPx())
            val m = text.measure(lab, TextStyle(color = Palette.TextDim.copy(alpha = 0.75f), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
            drawText(m, topLeft = Offset((x - m.size.width / 2).coerceIn(0f, size.width - m.size.width), y + 12.dp.toPx()))
        }
        // The grip: a rounded pill with a shadow ring, larger while held.
        val gw = (if (held) 22 else 18).dp.toPx(); val gh = (if (held) 22 else 18).dp.toPx()
        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(to - gw / 2 - 2f, y - gh / 2 - 1f), Size(gw + 4f, gh + 4f), CornerRadius(gh / 2))
        drawRoundRect(Color(0xFFF4F4F7), Offset(to - gw / 2, y - gh / 2), Size(gw, gh), CornerRadius(gh / 2))
        drawRoundRect(color, Offset(to - 1.5.dp.toPx(), y - 4.dp.toPx()), Size(3.dp.toPx(), 8.dp.toPx()), CornerRadius(1.5.dp.toPx()))
    }
}

private fun snap(v: Float, to: Float, within: Float) = if (abs(v - to) < within) to else v

/** The level line and scale run in decibels, +6 at the top to -24 at the centre line (below that is silence). */
private const val TOP_DB = 6f
private const val SPAN_DB = 30f
private fun dbOf(v: Float) = if (v <= 0.0001f) -SPAN_DB + TOP_DB else 20f * log10(v)
private fun levelY(v: Float, mid: Float) = mid * ((TOP_DB - dbOf(v).coerceIn(TOP_DB - SPAN_DB, TOP_DB)) / SPAN_DB)
private fun gainOf(db: Float) = if (db <= TOP_DB - SPAN_DB + 0.5f) 0f else 10f.pow(db / 20f)

private fun db(v: Float) = if (v <= 0.0001f) "−∞ dB" else "%+.1f dB".format(20 * log10(v))

private fun dbBand(v: Float) = if (abs(v) < 0.05f) "0 dB" else "%+.1f dB".format(v)

private fun pan(v: Float) = when {
    abs(v) < 0.01f -> "Centre"
    v < 0 -> "L ${(-v * 100).roundToInt()}"
    else -> "R ${(v * 100).roundToInt()}"
}

private fun secs(us: Long) = if (us == 0L) "Off" else "%.1f s".format(us / 1e6)

private fun clock(us: Long): String {
    val s = (us / 1_000_000L).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

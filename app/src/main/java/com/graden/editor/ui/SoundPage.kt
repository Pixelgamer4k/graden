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
import com.graden.editor.SoundFxSet
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

private enum class SoundTab(val label: String) { MIX("Mix"), EQ("Equaliser"), PITCH("Pitch"), MORE("Effects") }

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
    // Kept in the editor, so turning the phone (a different layout of this page) never loses the tab.
    val tab = runCatching { SoundTab.valueOf(vm.soundTab) }.getOrDefault(SoundTab.MIX)
    BackHandler { vm.closeSound(keep = true) }
    // A clip picked on the editor's timeline while the page is open becomes the one being worked on.
    androidx.compose.runtime.LaunchedEffect(vm.selectedClipId) {
        val sel = vm.selectedClipId ?: return@LaunchedEffect
        if (sel != vm.soundClipId && vm.soundLanes.any { (_, cs) -> cs.any { it.id == sel } }) vm.switchSound(sel)
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(Sheet)) {
    // A big panel (a tablet): a tall waveform, then everything at once in two columns, no tabs.
    // Any wide panel (a tablet, an open foldable, a phone on its side): no tabs, everything in columns, each scrolling
    // on its own. Three columns from 960 units, two below; the waveform a share of the height.
    val waveH = (maxHeight * 0.22f).coerceIn(90.dp, 240.dp)
    val fitWave = (maxHeight * 0.2f).coerceIn(64.dp, 118.dp)
    val laneH = (maxHeight * 0.3f).coerceIn(110.dp, 220.dp)
    val three = maxWidth >= 960.dp
    val audible = a.hasAudio || a.isAudio
    if (!audible && maxHeight < 400.dp) { NoAudio(a); return@BoxWithConstraints }
    if (maxWidth >= 540.dp) Column(Modifier.fillMaxSize()) {
        // The waveform is in the picture's place (its Picture | Waveform switch), never a second one here.
        Spacer(Modifier.height(8.dp))
        if (strip) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { ColorTimeline(vm, waveH, sound = true) }
        HearSwitch(vm)
        Box(Modifier.padding(horizontal = 12.dp)) { Twins(vm, c) }
        val reset: @Composable ColumnScope.() -> Unit = {
            if (!sn.isDefault) Text(
                "Reset this clip's sound", color = Hot, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp)).clickable { vm.setSnd { Snd() } }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (three) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { MixTab(vm, c, sn); reset() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { EqTab(vm, sn) }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { PitchTab(vm, sn); EffectsTab(vm, c, sn, Modifier.fillMaxWidth().height(460.dp)) }
            } else {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { MixTab(vm, c, sn); PitchTab(vm, sn); reset() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { EqTab(vm, sn); EffectsTab(vm, c, sn, Modifier.fillMaxWidth().height(460.dp)) }
            }
        }
        Spacer(Modifier.height(8.dp))
    } else if (maxHeight >= 400.dp && !strip) Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        // A side panel beside the editor (a tablet, an open foldable): the editor's own timeline and transport are in
        // view already, so the panel is just the controls, fitted to its height.
        if (!(a.hasAudio || a.isAudio)) NoAudio(a)
        else {
            HearSwitch(vm)
            Tabs(tab, onReset = if (sn.isDefault) null else ({ vm.setSnd { Snd() } })) { vm.soundTab = it.name }
            Column(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                Twins(vm, c)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (tab) {
                        SoundTab.MIX -> MixFit(vm, c, sn)
                        SoundTab.EQ -> EqFit(vm, sn)
                        SoundTab.PITCH -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) { PitchTab(vm, sn) }
                        SoundTab.MORE -> EffectsTab(vm, c, sn)
                    }
                }
            }
        }
    } else if (maxHeight >= 400.dp) {
        // An upright phone, laid out as the Color page: every lane at the top (drag the bar under the transport to hide
        // it or to see the whole timeline), the sound controls below. A clip without sound says so instead.
        val audible = a.hasAudio || a.isAudio
        SplitPage(
            vm.soundControlsFraction, { vm.soundControlsFraction = it }, Modifier.fillMaxSize(),
            top = { h -> Box(Modifier.padding(horizontal = 8.dp).padding(top = 6.dp)) { if (h > 50.dp) ColorTimeline(vm, h - 10.dp, sound = true) } },
            controls = { m ->
                Column(m.background(Sheet).padding(top = 8.dp)) {
                    if (!audible) NoAudio(a)
                    else {
                        HearSwitch(vm)
                        Tabs(tab, onReset = if (sn.isDefault) null else ({ vm.setSnd { Snd() } })) { vm.soundTab = it.name }
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                            val content = @Composable {
                                Column(Modifier.fillMaxSize()) {
                                    Twins(vm, c)
                                    Box(Modifier.fillMaxWidth().weight(1f)) {
                                        when (tab) {
                                            SoundTab.MIX -> MixFit(vm, c, sn)
                                            SoundTab.EQ -> EqFit(vm, sn)
                                            SoundTab.PITCH -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) { PitchTab(vm, sn) }
                                            SoundTab.MORE -> EffectsTab(vm, c, sn)
                                        }
                                    }
                                }
                            }
                            // Fitted to the room; only when the timeline has been pulled very large do the controls scroll.
                            if (maxHeight >= 262.dp) content()
                            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { Box(Modifier.fillMaxWidth().height(262.dp)) { content() } }
                        }
                    }
                }
            },
            transport = { ColorTransport(vm) },
        )
    } else Column(Modifier.fillMaxSize()) {
        // A short window (a phone on its side): the same controls; the waveform is in the picture's place, and what is
        // heard is a small pill in the tab row, so the controls get the height.
        Spacer(Modifier.height(4.dp))
        Tabs(tab, onReset = if (sn.isDefault) null else ({ vm.setSnd { Snd() } }), extra = { HearPill(vm) }) { vm.soundTab = it.name }
        // Effects take exactly the room left (their grid and settings fit it); the other tabs scroll.
        if (tab == SoundTab.MORE) Box(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) { EffectsTab(vm, c, sn) }
        else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Twins(vm, c)
            when (tab) {
                SoundTab.MIX -> MixTab(vm, c, sn)
                SoundTab.EQ -> EqTab(vm, sn)
                SoundTab.PITCH -> PitchTab(vm, sn)
                SoundTab.MORE -> Unit
            }
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
        // "Hear clip": the clip alone, start to end. The transport below plays the timeline, as the switch says.
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Raised).clickable { if (vm.isPlaying) vm.pause() else vm.previewSound() }
                .padding(start = 10.dp, end = 13.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (vm.isPlaying && vm.hearingClip) Lucide.Pause else Lucide.Play, null, tint = Wave, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (vm.isPlaying && vm.hearingClip) "Stop" else "Hear clip", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
internal fun HeroWave(vm: EditorViewModel, c: Clip, a: MediaAsset, sn: Snd, height: androidx.compose.ui.unit.Dp = 118.dp) {
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

/** The hear switch as one small pill (short panels): "Alone" or "+ Background", a tap flips it. */
@Composable
private fun HearPill(vm: EditorViewModel) {
    val all = vm.soundHearAll
    val view = androidx.compose.ui.platform.LocalView.current
    Row(
        Modifier.padding(start = 6.dp).height(38.dp).clip(RoundedCornerShape(11.dp)).background(if (all) Surface else Color(0xFF173A2E))
            .border(1.dp, if (all) Hair else Wave.copy(alpha = 0.5f), RoundedCornerShape(11.dp))
            .clickable { vm.chooseSoundHearAll(!all); view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (all) Lucide.AudioLines else Lucide.Volume, null, tint = Wave, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(if (all) "+ Background" else "Alone", color = Palette.Text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/**
 * What the transport plays while this clip's sound is edited: "This sound alone" (the default) or "With background
 * sounds" (everything, as it will play). Editing is always of this clip only; the words say so.
 */
@Composable
private fun HearSwitch(vm: EditorViewModel) {
    val view = androidx.compose.ui.platform.LocalView.current
    Row(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Surface)
            .border(1.dp, Hair, RoundedCornerShape(11.dp)).padding(3.dp),
    ) {
        // Narrow panels (a phone on its side) get the short words, so neither is ever cut off.
        val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp > androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
        for ((all, label, icon) in listOf(
            Triple(false, if (narrow) "Alone" else "This sound alone", Lucide.Volume),
            Triple(true, if (narrow) "With background" else "With background sounds", Lucide.AudioLines),
        )) {
            val on = vm.soundHearAll == all
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF173A2E) else Color.Transparent)
                    .clickable { vm.chooseSoundHearAll(all); view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (on) Wave else Palette.TextDim, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = if (on) Palette.Text else Palette.TextDim, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** A clip with no sound picked on the timeline: nothing to set, said plainly. */
@Composable
private fun NoAudio(a: MediaAsset) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Lucide.VolumeX, null, tint = Palette.TextDim, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(10.dp))
        Text("No audio found", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(if (a.isImage) "A picture has no sound. Pick a clip with sound on the timeline." else "${a.name} has no sound track. Pick a clip with sound on the timeline.",
            color = Palette.TextDim, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The waveform of the clip being worked on, filling [modifier]: shown in the picture's place on the Sound page. */
@Composable
internal fun SoundWave(vm: EditorViewModel, modifier: Modifier) {
    val id = vm.soundClipId ?: return
    val l = vm.locate(id) ?: return
    val a = vm.assets[l.clip.assetId] ?: return
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(Sheet), contentAlignment = Alignment.Center) {
        HeroWave(vm, l.clip, a, l.clip.snd, (maxHeight - 12.dp).coerceAtLeast(60.dp))
    }
}

@Composable
private fun Tabs(tab: SoundTab, onReset: (() -> Unit)? = null, extra: (@Composable () -> Unit)? = null, onTab: (SoundTab) -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Row(
        Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(Surface).border(1.dp, Hair, RoundedCornerShape(11.dp)).padding(3.dp),
    ) {
        for (t in SoundTab.entries) {
            val on = t == tab
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) Raised else Color.Transparent).clickable { onTab(t) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text(t.label, color = if (on) Palette.Text else Palette.TextDim, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
    extra?.invoke()
    // The clip's sound back to as it was recorded, when anything is changed.
    if (onReset != null) Icon(
        Lucide.RotateCcw, "Reset this clip's sound", tint = Hot,
        modifier = Modifier.padding(start = 6.dp).size(38.dp).clip(CircleShape).background(Surface).clickable(onClick = onReset).padding(10.dp),
    )
    }
}

/** Mix, fitted to the height: five slim rows spread evenly, no cards, no scrolling. */
@Composable
private fun MixFit(vm: EditorViewModel, c: Clip, sn: Snd) {
    val maxFade = min(10_000_000L, c.durationUs / 2).toFloat().coerceAtLeast(1f)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        Setting("Level", db(sn.volume), LevelInk) {
            Fader(sn.volume, 0f..2f, default = 1f, color = LevelInk, ticks = listOf(0f to "-∞", 0.25f to "-12", 0.5f to "-6", 1f to "0", 2f to "+6")) { v ->
                vm.setSnd { it.copy(volume = snap(v, 1f, 0.03f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch("Mute", Lucide.VolumeX, sn.muted, Modifier.weight(1f)) { vm.setSnd { it.copy(muted = !it.muted) } }
            Switch("Normalize", Lucide.AudioWave, sn.normalize, Modifier.weight(1f)) { vm.setSnd { it.copy(normalize = !it.normalize) } }
        }
        Setting("Pan", pan(sn.pan), PanInk) {
            Fader(sn.pan, -1f..1f, default = 0f, bipolar = true, color = PanInk, ticks = listOf(-1f to "L", 0f to "C", 1f to "R")) { v -> vm.setSnd { it.copy(pan = snap(v, 0f, 0.05f)) } }
        }
        // The two fades side by side: one row.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((name, us, set) in listOf(
                Triple("Fade in", sn.fadeInUs) { v: Float -> vm.setSnd { it.copy(fadeInUs = v.toLong()) } },
                Triple("Fade out", sn.fadeOutUs) { v: Float -> vm.setSnd { it.copy(fadeOutUs = v.toLong()) } },
            )) Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(name, color = Palette.TextDim, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(secs(us), color = FadeInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                Fader(us.toFloat(), 0f..maxFade, default = 0f, color = FadeInk, onChange = set)
            }
        }
    }
}

/** The equaliser, fitted: presets, the curve taking all the height left, then the three bands' values. */
@Composable
private fun EqFit(vm: EditorViewModel, sn: Snd) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((name, v) in EqPresets) {
                val on = sn.bass == v.first && sn.mid == v.second && sn.treble == v.third
                Text(
                    name, color = if (on) Color(0xFF15161C) else Palette.Text, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) EqInk else Raised)
                        .clickable { vm.setSnd { it.copy(bass = v.first, mid = v.second, treble = v.third) } }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) { EqCurve(vm, sn, Modifier.fillMaxSize()) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            for ((name, hz, v) in listOf(Triple("Low", "200 Hz", sn.bass), Triple("Mid", "1 kHz", sn.mid), Triple("High", "4 kHz", sn.treble))) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dbBand(v), color = if (abs(v) < 0.05f) Palette.TextDim else EqInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    Text("$name · $hz", color = Palette.TextDim, fontSize = 10.sp)
                }
            }
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

/** How the equaliser lifts or cuts at frequency [f]: the filters' true response, as heard. */
private fun response(f: Float, sn: Snd): Float =
    com.graden.editor.Biquad2.eqResponseDb(f.toDouble(), sn.bass, sn.mid, sn.treble).toFloat()

@Composable
private fun EqCurve(vm: EditorViewModel, sn: Snd, modifier: Modifier = Modifier.fillMaxWidth().height(150.dp)) {
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
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF14151A))
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
        Column(Modifier.width(80.dp)) {
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
        Text(label, color = Palette.Text, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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

// ---- Effects ---------------------------------------------------------------------------------------------------

private val CleanInk = Color(0xFF4FD1C5)
private val DynInk = Color(0xFFF2A65A)
private val SpaceInk = Color(0xFFB794F6)

private enum class FxKind(val label: String, val short: String, val icon: ImageVector, val ink: Color, val about: String) {
    NR("Noise reduction", "Denoise", Lucide.Wand, CleanInk, "Takes out steady hiss, hum, fans and room noise, learned from the quiet moments"),
    VOICE("Voice isolation", "Voice", Lucide.Users, CleanInk, "Keeps the speaking voice; turns down what is outside the voice and the gaps between words"),
    SILENCE("Remove silence", "Silence", Lucide.Scissors, CleanInk, "Cuts the quiet pauses out of this clip; the clips after it close up"),
    COMP("Compressor", "Compress", Lucide.Gauge, DynInk, "Evens out loud and quiet: everything above the threshold is turned down by the ratio"),
    LIMIT("Limiter", "Limit", Lucide.Activity, DynInk, "Holds every peak under the ceiling, looking 5 ms ahead so nothing clips"),
    DEESS("De-ess", "De-ess", Lucide.Feather, DynInk, "Softens harsh S and T sounds: only the hissy band is turned down, only while it is loud"),
    DUCK("Auto-duck", "Duck", Lucide.ArrowUpDown, DynInk, "Turns this clip down while other clips have sound, such as music under speech"),
    REVERB("Reverb", "Reverb", Lucide.Circle, SpaceInk, "Puts the sound in a space, from a small room to a cathedral"),
    ECHO("Echo", "Echo", Lucide.AudioWave, SpaceInk, "Repeats the sound, each repeat a little quieter and darker"),
}

private fun FxKind.on(f: SoundFxSet) = when (this) {
    FxKind.NR -> f.nrOn; FxKind.VOICE -> f.voiceOn; FxKind.COMP -> f.compOn; FxKind.LIMIT -> f.limOn; FxKind.DEESS -> f.deessOn
    FxKind.DUCK -> f.duckOn; FxKind.REVERB -> f.revOn; FxKind.ECHO -> f.echoOn; FxKind.SILENCE -> false
}

private fun FxKind.toggle(f: SoundFxSet, v: Boolean) = when (this) {
    FxKind.NR -> f.copy(nrOn = v); FxKind.VOICE -> f.copy(voiceOn = v); FxKind.COMP -> f.copy(compOn = v); FxKind.LIMIT -> f.copy(limOn = v)
    FxKind.DEESS -> f.copy(deessOn = v); FxKind.DUCK -> f.copy(duckOn = v); FxKind.REVERB -> f.copy(revOn = v); FxKind.ECHO -> f.copy(echoOn = v)
    FxKind.SILENCE -> f
}

private fun FxKind.reset(f: SoundFxSet): SoundFxSet {
    val d = SoundFxSet()
    return when (this) {
        FxKind.NR -> f.copy(nrOn = d.nrOn, nrAmount = d.nrAmount, nrSens = d.nrSens)
        FxKind.VOICE -> f.copy(voiceOn = d.voiceOn, voiceAmount = d.voiceAmount)
        FxKind.COMP -> f.copy(compOn = d.compOn, compThreshold = d.compThreshold, compRatio = d.compRatio, compAttack = d.compAttack, compRelease = d.compRelease, compMakeup = d.compMakeup)
        FxKind.LIMIT -> f.copy(limOn = d.limOn, limCeiling = d.limCeiling, limRelease = d.limRelease)
        FxKind.DEESS -> f.copy(deessOn = d.deessOn, deessFreq = d.deessFreq, deessAmount = d.deessAmount)
        FxKind.DUCK -> f.copy(duckOn = d.duckOn, duckDb = d.duckDb, duckThreshold = d.duckThreshold, duckFadeMs = d.duckFadeMs)
        FxKind.REVERB -> f.copy(revOn = d.revOn, revSize = d.revSize, revDamp = d.revDamp, revMix = d.revMix)
        FxKind.ECHO -> f.copy(echoOn = d.echoOn, echoMs = d.echoMs, echoFeedback = d.echoFeedback, echoMix = d.echoMix, echoPing = d.echoPing)
        FxKind.SILENCE -> f
    }
}

/** What a tool is set to, in a few characters, for its tile. */
private fun FxKind.summary(f: SoundFxSet): String = if (this != FxKind.SILENCE && !on(f)) "Off" else when (this) {
    FxKind.NR -> "−%.0f dB".format(f.nrAmount)
    FxKind.VOICE -> "%d%%".format((f.voiceAmount * 100).toInt())
    FxKind.COMP -> "%.0f dB · %.1f:1".format(f.compThreshold, f.compRatio)
    FxKind.LIMIT -> "%.1f dB".format(f.limCeiling)
    FxKind.DEESS -> "−%.0f dB".format(f.deessAmount)
    FxKind.DUCK -> "%.0f dB".format(f.duckDb)
    FxKind.REVERB -> "%d%% wet".format((f.revMix * 100).toInt())
    FxKind.ECHO -> "%.0f ms".format(f.echoMs)
    FxKind.SILENCE -> "Cut pauses"
}

/**
 * The Effects tab, for any panel: in a narrow one (an upright phone) the nine tools as a grid filling the room, and a
 * tool's settings opening in its place; in a wide one (a phone on its side, a tablet, an open foldable) the tools down
 * the left and the chosen one's settings beside them.
 */
@Composable
private fun EffectsTab(vm: EditorViewModel, c: Clip, sn: Snd, modifier: Modifier = Modifier.fillMaxSize()) {
    // The open effect lives in the editor too: it stays open through a turn of the phone.
    val open = vm.soundFxOpen
    val view = androidx.compose.ui.platform.LocalView.current
    fun setFx(f: (SoundFxSet) -> SoundFxSet) = vm.setSnd { it.copy(fx = f(it.fx)) }
    fun quick(k: FxKind) {
        if (k == FxKind.SILENCE) { vm.soundFxOpen = k.name; return }
        setFx { k.toggle(it, !k.on(it)) }
        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val mw = maxWidth
        val mh = maxHeight
        val wide = mw >= 520.dp
        val chosen = open?.let { n -> FxKind.entries.firstOrNull { it.name == n } }
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val sel = chosen ?: FxKind.NR
            Column(Modifier.width((mw * 0.38f).coerceIn(190.dp, 300.dp)).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (k in FxKind.entries) FxRow(k, sn.fx, k == sel, onOpen = { vm.soundFxOpen = k.name }, onToggle = { quick(k) })
            }
            FxDetail(vm, c, sn, sel, back = null, Modifier.weight(1f).fillMaxHeight(), compact = mh < 300.dp)
        } else if (chosen != null) {
            FxDetail(vm, c, sn, chosen, back = { vm.soundFxOpen = null }, Modifier.fillMaxSize(), compact = mh < 300.dp)
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in FxKind.entries.chunked(3)) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in row) FxTile(k, sn.fx, Modifier.weight(1f).fillMaxHeight(), onOpen = { vm.soundFxOpen = k.name }, onToggle = { quick(k) })
            }
        }
    }
}

/**
 * A tool on the grid: its mark, name and setting; the switch turns it on or off without opening it. Tall tiles stack
 * mark over name; a short tile (a phone on its side) lays them in a row, so the grid always fits without scrolling.
 */
@Composable
private fun FxTile(k: FxKind, f: SoundFxSet, modifier: Modifier, onOpen: () -> Unit, onToggle: () -> Unit) {
    val on = k.on(f)
    val glow by animateFloatAsState(if (on) 1f else 0f, spring(0.85f, 500f), label = "tile")
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.lerp(Surface, solidOver(k.ink, 0.13f), glow))
            .border(1.dp, androidx.compose.ui.graphics.lerp(Hair, k.ink.copy(alpha = 0.55f), glow), RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen),
    ) {
        val short = maxHeight < 78.dp
        val narrow = maxWidth < 110.dp
        if (short) Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FxMark(k, 24.dp, glow)
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(if (narrow) k.short else k.label, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(k.summary(f), color = if (on) k.ink else Palette.TextDim, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (k != FxKind.SILENCE) Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) k.ink else Color(0xFF3A3D47)))
        } else Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.SpaceBetween) {
                FxMark(k, 28.dp, glow)
                Column {
                    Text(k.label, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 14.sp, overflow = TextOverflow.Ellipsis)
                    Text(k.summary(f), color = if (on) k.ink else Palette.TextDim, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (k != FxKind.SILENCE) MiniSwitch(on, k.ink, Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp), onToggle)
        }
    }
}

@Composable
private fun FxMark(k: FxKind, size: androidx.compose.ui.unit.Dp, glow: Float) {
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(k.ink.copy(alpha = 0.14f + 0.14f * glow)), contentAlignment = Alignment.Center) {
        Icon(k.icon, null, tint = k.ink, modifier = Modifier.size(size * 0.54f))
    }
}

/** [c] at [a] laid over the page's surface, as a solid colour (so tiles blend smoothly, without alpha stacking). */
private fun solidOver(c: Color, a: Float) = Color(
    red = c.red * a + Surface.red * (1 - a), green = c.green * a + Surface.green * (1 - a), blue = c.blue * a + Surface.blue * (1 - a),
)

/** A tool in the side list (wide panels). */
@Composable
private fun FxRow(k: FxKind, f: SoundFxSet, selected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val on = k.on(f)
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Raised else Surface)
            .border(1.dp, if (selected) k.ink.copy(alpha = 0.6f) else Hair, RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(k.ink.copy(alpha = if (on) 0.28f else 0.14f)), contentAlignment = Alignment.Center) {
            Icon(k.icon, null, tint = k.ink, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(k.label, color = Palette.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(k.summary(f), color = if (on) k.ink else Palette.TextDim, fontSize = 10.5.sp, maxLines = 1)
        }
        if (k != FxKind.SILENCE) MiniSwitch(on, k.ink, Modifier, onToggle)
    }
}

@Composable
private fun MiniSwitch(on: Boolean, ink: Color, modifier: Modifier, onClick: () -> Unit) {
    val t by animateFloatAsState(if (on) 1f else 0f, spring(0.8f, 600f), label = "mini")
    Box(modifier.size(40.dp, 28.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp, 16.dp).clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.lerp(Color(0xFF34363F), ink, t))) {
            Box(Modifier.padding(start = (2 + 12 * t).dp, top = 2.dp).size(12.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/** A tool's presets: a tap sets them and turns the tool on. */
private fun presetsOf(k: FxKind): List<Pair<String, (SoundFxSet) -> SoundFxSet>> = when (k) {
    FxKind.NR -> listOf("Light" to { x -> x.copy(nrOn = true, nrAmount = 6f, nrSens = 1.5f) }, "Medium" to { x -> x.copy(nrOn = true, nrAmount = 12f, nrSens = 2f) }, "Strong" to { x -> x.copy(nrOn = true, nrAmount = 20f, nrSens = 3f) })
    FxKind.VOICE -> listOf("Natural" to { x -> x.copy(voiceOn = true, voiceAmount = 0.4f) }, "Focused" to { x -> x.copy(voiceOn = true, voiceAmount = 0.7f) }, "Isolated" to { x -> x.copy(voiceOn = true, voiceAmount = 1f) })
    FxKind.COMP -> listOf(
        "Gentle" to { x -> x.copy(compOn = true, compThreshold = -18f, compRatio = 2f, compAttack = 20f, compRelease = 200f, compMakeup = 2f) },
        "Voice" to { x -> x.copy(compOn = true, compThreshold = -20f, compRatio = 3f, compAttack = 8f, compRelease = 120f, compMakeup = 4f) },
        "Punchy" to { x -> x.copy(compOn = true, compThreshold = -14f, compRatio = 4f, compAttack = 5f, compRelease = 80f, compMakeup = 3f) },
        "Broadcast" to { x -> x.copy(compOn = true, compThreshold = -24f, compRatio = 6f, compAttack = 3f, compRelease = 150f, compMakeup = 8f) })
    FxKind.LIMIT -> listOf("Streaming −1" to { x -> x.copy(limOn = true, limCeiling = -1f) }, "Safe −2" to { x -> x.copy(limOn = true, limCeiling = -2f) }, "Max −0.3" to { x -> x.copy(limOn = true, limCeiling = -0.3f) })
    FxKind.DEESS -> listOf("Soft" to { x -> x.copy(deessOn = true, deessAmount = 4f) }, "Medium" to { x -> x.copy(deessOn = true, deessAmount = 6f) }, "Strong" to { x -> x.copy(deessOn = true, deessAmount = 10f) })
    FxKind.DUCK -> listOf("Light" to { x -> x.copy(duckOn = true, duckDb = -6f) }, "Normal" to { x -> x.copy(duckOn = true, duckDb = -12f) }, "Deep" to { x -> x.copy(duckOn = true, duckDb = -20f) })
    FxKind.REVERB -> listOf(
        "Room" to { x -> x.copy(revOn = true, revSize = 0.3f, revDamp = 0.5f, revMix = 0.18f) }, "Hall" to { x -> x.copy(revOn = true, revSize = 0.75f, revDamp = 0.4f, revMix = 0.28f) },
        "Plate" to { x -> x.copy(revOn = true, revSize = 0.55f, revDamp = 0.15f, revMix = 0.25f) }, "Cathedral" to { x -> x.copy(revOn = true, revSize = 0.95f, revDamp = 0.3f, revMix = 0.35f) })
    FxKind.ECHO -> listOf(
        "Slap" to { x -> x.copy(echoOn = true, echoMs = 110f, echoFeedback = 0.15f, echoMix = 0.3f, echoPing = false) },
        "Quarter" to { x -> x.copy(echoOn = true, echoMs = 300f, echoFeedback = 0.35f, echoMix = 0.3f, echoPing = false) },
        "Long" to { x -> x.copy(echoOn = true, echoMs = 600f, echoFeedback = 0.5f, echoMix = 0.35f, echoPing = false) },
        "Ping-pong" to { x -> x.copy(echoOn = true, echoMs = 380f, echoFeedback = 0.45f, echoMix = 0.35f, echoPing = true) })
    FxKind.SILENCE -> emptyList()
}

/**
 * One tool's settings, filling [modifier]: a header (back, name, on, reset), a picture of what it does where one helps
 * (the compressor's and limiter's response, what Remove silence will cut), presets, then its controls. The controls dim
 * while the tool is off; moving one turns it on. Times and frequencies move on a log scale, as on any mixing desk.
 */
@Composable
private fun FxDetail(vm: EditorViewModel, c: Clip, sn: Snd, k: FxKind, back: (() -> Unit)?, modifier: Modifier, compact: Boolean = false) {
    val f = sn.fx
    val view = androidx.compose.ui.platform.LocalView.current
    fun setFx(u: (SoundFxSet) -> SoundFxSet) = vm.setSnd { it.copy(fx = u(it.fx)) }
    val on = k.on(f)
    val changed = k.reset(f) != f
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Surface).border(1.dp, Hair, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().height(44.dp).background(k.ink.copy(alpha = 0.09f)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) Icon(Lucide.ChevronsLeft, "Back to effects", tint = Palette.Text, modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = back).padding(10.dp))
            else Spacer(Modifier.width(8.dp))
            FxMark(k, 22.dp, if (on) 1f else 0f)
            Spacer(Modifier.width(8.dp))
            Text(k.label, color = Palette.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (k != FxKind.SILENCE) {
                Icon(Lucide.RotateCcw, "Reset ${k.label}", tint = if (changed) Palette.Text else Palette.TextDim.copy(alpha = 0.35f),
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable(enabled = changed) { setFx { k.reset(it) } }.padding(10.dp))
                MiniSwitch(on, k.ink, Modifier) { setFx { k.toggle(it, !on) }; view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Short panels leave the description out: the controls get the room.
            if (!compact) Text(k.about, color = Palette.TextDim, fontSize = 11.sp, lineHeight = 14.sp)
            when (k) {
                FxKind.COMP -> DynamicsGraph(f.compThreshold, f.compRatio, f.compMakeup, ceiling = null, ink = if (on) k.ink else Palette.TextDim)
                FxKind.LIMIT -> DynamicsGraph(f.limCeiling, 100f, 0f, ceiling = f.limCeiling, ink = if (on) k.ink else Palette.TextDim)
                else -> Unit
            }
            val presets = presetsOf(k)
            if (presets.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((name, p) in presets) {
                    val now = on && p(f) == f
                    Text(name, color = if (now) Color(0xFF15161C) else Palette.Text, fontSize = 12.sp, fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (now) k.ink else Raised).border(1.dp, if (now) Color.Transparent else Hair, RoundedCornerShape(16.dp))
                            .clickable { setFx(p); view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }.padding(horizontal = 13.dp, vertical = 7.dp))
                }
            }
            val ink = if (on || k == FxKind.SILENCE) k.ink else Palette.TextDim
            // A control: linear, or on a log scale for times and frequencies.
            @Composable
            fun row(name: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, def: Float, log: Boolean = false, set: (SoundFxSet, Float) -> SoundFxSet) {
                fun toPos(x: Float) = if (log) (kotlin.math.ln(x / range.start) / kotlin.math.ln(range.endInclusive / range.start)).toFloat() else (x - range.start) / (range.endInclusive - range.start)
                fun fromPos(p: Float) = if (log) range.start * (range.endInclusive / range.start).pow(p) else range.start + p * (range.endInclusive - range.start)
                Setting(name, value, ink) {
                    Fader(toPos(v.coerceIn(range.start, range.endInclusive)), 0f..1f, default = toPos(def), color = ink) { p -> setFx { s -> k.toggle(set(s, fromPos(p)), true) } }
                }
            }
            when (k) {
                FxKind.NR -> {
                    row("Reduction", "%.0f dB".format(f.nrAmount), f.nrAmount, 0f..30f, 12f) { s, x -> s.copy(nrAmount = x) }
                    row("Sensitivity", "%.1f×".format(f.nrSens), f.nrSens, 1f..4f, 2f) { s, x -> s.copy(nrSens = x) }
                    Note("Learns the noise from the quiet moments in about a quarter second. Steady tones (hum, whine) count as noise.")
                }
                FxKind.VOICE -> {
                    row("Amount", "%d%%".format((f.voiceAmount * 100).toInt()), f.voiceAmount, 0f..1f, 0.7f) { s, x -> s.copy(voiceAmount = x) }
                    Note("Keeps 100 Hz to 7.5 kHz and lowers what lies between words. Pair it with Noise reduction for noisy rooms.")
                }
                FxKind.COMP -> {
                    row("Threshold", "%.0f dB".format(f.compThreshold), f.compThreshold, -60f..0f, -18f) { s, x -> s.copy(compThreshold = x) }
                    row("Ratio", "%.1f:1".format(f.compRatio), f.compRatio, 1f..20f, 3f, log = true) { s, x -> s.copy(compRatio = x) }
                    row("Attack", if (f.compAttack < 10f) "%.1f ms".format(f.compAttack) else "%.0f ms".format(f.compAttack), f.compAttack, 0.5f..100f, 10f, log = true) { s, x -> s.copy(compAttack = x) }
                    row("Release", "%.0f ms".format(f.compRelease), f.compRelease, 10f..1000f, 120f, log = true) { s, x -> s.copy(compRelease = x) }
                    row("Makeup", "%+.1f dB".format(f.compMakeup), f.compMakeup, 0f..24f, 0f) { s, x -> s.copy(compMakeup = x) }
                }
                FxKind.LIMIT -> {
                    row("Ceiling", "%.1f dB".format(f.limCeiling), f.limCeiling, -12f..0f, -1f) { s, x -> s.copy(limCeiling = x) }
                    row("Release", "%.0f ms".format(f.limRelease), f.limRelease, 10f..500f, 60f, log = true) { s, x -> s.copy(limRelease = x) }
                }
                FxKind.DEESS -> {
                    row("Frequency", "%.1f kHz".format(f.deessFreq / 1000), f.deessFreq, 3000f..11000f, 6500f, log = true) { s, x -> s.copy(deessFreq = x) }
                    row("Reduction", "%.0f dB".format(f.deessAmount), f.deessAmount, 0f..18f, 6f) { s, x -> s.copy(deessAmount = x) }
                }
                FxKind.DUCK -> {
                    row("Turn down", "%.0f dB".format(f.duckDb), f.duckDb, -30f..-3f, -12f) { s, x -> s.copy(duckDb = x) }
                    row("Listens above", "%.0f dB".format(f.duckThreshold), f.duckThreshold, -60f..-12f, -36f) { s, x -> s.copy(duckThreshold = x) }
                    row("Fade", "%.0f ms".format(f.duckFadeMs), f.duckFadeMs, 50f..1500f, 250f, log = true) { s, x -> s.copy(duckFadeMs = x) }
                    Note("Listens to every other clip with sound. Hear the result with \"With background sounds\".")
                }
                FxKind.REVERB -> {
                    row("Room size", "%d%%".format((f.revSize * 100).toInt()), f.revSize, 0f..1f, 0.5f) { s, x -> s.copy(revSize = x) }
                    row("Damping", "%d%%".format((f.revDamp * 100).toInt()), f.revDamp, 0f..1f, 0.5f) { s, x -> s.copy(revDamp = x) }
                    row("Mix", "%d%%".format((f.revMix * 100).toInt()), f.revMix, 0f..1f, 0.25f) { s, x -> s.copy(revMix = x) }
                }
                FxKind.ECHO -> {
                    row("Time", "%.0f ms".format(f.echoMs), f.echoMs, 20f..1200f, 300f, log = true) { s, x -> s.copy(echoMs = x) }
                    row("Feedback", "%d%%".format((f.echoFeedback * 100).toInt()), f.echoFeedback, 0f..0.9f, 0.35f) { s, x -> s.copy(echoFeedback = x) }
                    row("Mix", "%d%%".format((f.echoMix * 100).toInt()), f.echoMix, 0f..1f, 0.3f) { s, x -> s.copy(echoMix = x) }
                    Switch("Ping-pong", Lucide.ArrowUpDown, f.echoPing, Modifier.fillMaxWidth()) { setFx { s -> s.copy(echoOn = true, echoPing = !s.echoPing) } }
                }
                FxKind.SILENCE -> SilenceTool(vm, c)
            }
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, color = Palette.TextDim.copy(alpha = 0.8f), fontSize = 10.5.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 2.dp))

/**
 * The response of a compressor or limiter: input level across, output level up, both −60 to 0 dB; the line bends at the
 * threshold by the ratio (softened over the 6 dB knee), lifted by the makeup; a limiter is a flat ceiling.
 */
@Composable
private fun DynamicsGraph(threshold: Float, ratio: Float, makeup: Float, ceiling: Float?, ink: Color) {
    val text = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF14151A))) {
        val lo = -60f; val w = size.width; val h = size.height
        fun x(db: Float) = (db - lo) / -lo * w
        fun y(db: Float) = h - (db.coerceIn(lo, 6f) - lo) / -lo * h
        for (d in listOf(-48f, -36f, -24f, -12f)) {
            drawLine(Hair.copy(alpha = 0.6f), Offset(x(d), 0f), Offset(x(d), h), 1f)
            drawLine(Hair.copy(alpha = 0.6f), Offset(0f, y(d)), Offset(w, y(d)), 1f)
        }
        drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, h), Offset(w, 0f), 1.dp.toPx())
        val path = Path()
        val knee = 6f; val r = ratio.coerceAtLeast(1f)
        for (i in 0..120) {
            val xi = lo + i / 120f * -lo
            val d = xi - threshold
            var yo = when {
                ceiling != null -> minOf(xi, ceiling)
                2 * d < -knee -> xi
                2 * kotlin.math.abs(d) <= knee -> xi + (1 / r - 1) * (d + knee / 2) * (d + knee / 2) / (2 * knee)
                else -> threshold + d / r
            }
            yo += makeup
            if (i == 0) path.moveTo(x(xi), y(yo)) else path.lineTo(x(xi), y(yo))
        }
        drawPath(path, ink, style = Stroke(2.2.dp.toPx()))
        drawLine(ink.copy(alpha = 0.35f), Offset(x(threshold), 0f), Offset(x(threshold), h), 1.dp.toPx())
        val m = text.measure(if (ceiling != null) "ceiling %.1f dB".format(ceiling) else "%.0f dB · %.1f:1".format(threshold, ratio),
            TextStyle(color = ink, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
        drawText(m, topLeft = Offset(6.dp.toPx(), 4.dp.toPx()))
    }
}

/**
 * Remove silence: the clip's waveform with every pause it would cut shaded, updated as the settings move; then the cut,
 * as one undo step. Its settings live in the editor (kept through a turn of the phone).
 */
@Composable
private fun SilenceTool(vm: EditorViewModel, c: Clip) {
    var done by remember(c.id) { mutableStateOf<Int?>(null) }
    val version = vm.waves.version
    val th = vm.silenceDb; val minMs = vm.silenceMinMs; val pad = vm.silencePadMs
    // Found once per change of what it depends on, not at every redraw.
    val found = remember(c, th, minMs, pad, version) { vm.silences(c.id, th, minMs, pad) }
    val saves = found.sumOf { it.last - it.first } / c.speed / 1e6
    val peaks = vm.assets[c.assetId]?.let { vm.waves.get(it) }
    Canvas(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF14151A))) {
        val w = size.width; val h = size.height; val mid = h / 2
        val dur = (c.outUs - c.inUs).toFloat().coerceAtLeast(1f)
        fun xOf(src: Long) = (src - c.inUs) / dur * w
        for (r in found) drawRect(Color(0xFFEF6A6A).copy(alpha = 0.22f), Offset(xOf(r.first), 0f), Size(xOf(r.last) - xOf(r.first), h))
        if (peaks != null) {
            var x = 0f
            val rate = WaveformCache.PEAKS_PER_SEC
            while (x < w) {
                val src = c.inUs + (x / w * dur).toLong()
                val i = (src * rate / 1_000_000L).toInt().coerceIn(0, peaks.size - 1)
                val v = (peaks[i].toInt() and 0xFF) / 255f
                val cut = found.any { src in it }
                drawLine(if (cut) Color(0xFFEF6A6A).copy(alpha = 0.6f) else CleanInk, Offset(x, mid - v * (mid - 3f)), Offset(x, mid + v * (mid - 3f)), 1.6f)
                x += 2.5f
            }
            // The threshold, as a level either side of the middle.
            val t = 10f.pow(th / 20f) * (mid - 3f)
            drawLine(Color.White.copy(alpha = 0.45f), Offset(0f, mid - t), Offset(w, mid - t), 1f)
            drawLine(Color.White.copy(alpha = 0.45f), Offset(0f, mid + t), Offset(w, mid + t), 1f)
        }
    }
    Setting("Quieter than", "%.0f dB".format(th), CleanInk) { Fader(th, -60f..-20f, default = -40f, color = CleanInk) { vm.silenceDb = it; done = null } }
    Setting("Longer than", "%.1f s".format(minMs / 1000), CleanInk) { Fader(minMs, 200f..2000f, default = 500f, color = CleanInk) { vm.silenceMinMs = it; done = null } }
    Setting("Keep", "%.2f s".format(pad / 1000), CleanInk) { Fader(pad, 0f..400f, default = 120f, color = CleanInk) { vm.silencePadMs = it; done = null } }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            done?.let { "Removed $it pause${if (it == 1) "" else "s"} · undo to bring them back" } ?: when {
                peaks == null -> "Reading the sound…"
                found.isEmpty() -> "No pauses this quiet and long"
                else -> "${found.size} pause${if (found.size == 1) "" else "s"} · %.1f s shorter".format(saves)
            },
            color = if (done != null) CleanInk else Palette.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f),
        )
        val ok = found.isNotEmpty()
        Text(
            "Remove", color = if (ok) Color(0xFF15161C) else Palette.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (ok) CleanInk else Raised)
                .clickable(enabled = ok) { done = vm.removeSilences(c.id, th, minMs, pad) }.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

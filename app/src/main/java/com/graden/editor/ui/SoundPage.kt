package com.graden.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.EditorViewModel
import com.graden.editor.Snd
import com.graden.editor.WaveformCache
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val Card = Color(0xFF22232A)

/** Equaliser starting points, as bass, mid and treble in dB, named in [EqNames]. */
private val EqPresets = listOf(
    Triple(0f, 0f, 0f), Triple(-4f, 4f, 2f), Triple(8f, 0f, 0f), Triple(-2f, 0f, 6f),
    Triple(4f, 1f, -3f), Triple(-12f, 6f, -10f), Triple(-3f, 3f, 3f),
)
private val EqNames = listOf("Flat", "Voice", "Bass boost", "Bright", "Warm", "Telephone", "Podcast")

/**
 * The Sound page: everything about one clip's sound, for a video's own sound, an audio file, or sound
 * extracted from a video. Changes are heard straight away (press the play button to hear the clip);
 * the tick keeps them as one undo step, the cross puts everything back.
 */
@Composable
fun SoundPage(vm: EditorViewModel, soon: (String) -> Unit) {
    val id = vm.soundClipId ?: return
    val l = vm.locate(id) ?: return
    val c = l.clip
    val a = vm.assets[c.assetId] ?: return
    val sn = c.snd
    BackHandler { vm.closeSound(keep = true) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures { vm.closeSound(keep = true) } })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.68f).clip(SheetShape).background(Color(0xFF16171D))
                .border(1.dp, Palette.Line, SheetShape).pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.X, "Cancel", tint = Palette.Text, modifier = Modifier.size(42.dp).clip(CircleShape).clickable { vm.closeSound(keep = false) }.padding(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sound", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        a.name + if (!a.isAudio && vm.tracks.firstOrNull { it.id == l.trackId }?.video == false) "  ·  extracted" else "",
                        color = Palette.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (vm.isPlaying) Lucide.Pause else Lucide.Play, "Hear the clip", tint = Palette.Accent,
                    modifier = Modifier.size(42.dp).clip(CircleShape).clickable { if (vm.isPlaying) vm.pause() else vm.previewSound() }.padding(11.dp),
                )
                Icon(Lucide.Check, "Done", tint = Palette.Accent, modifier = Modifier.size(42.dp).clip(CircleShape).clickable { vm.closeSound(keep = true) }.padding(10.dp))
            }

            // The clip's sound as it will be heard: waveform shaped by volume and fades.
            Canvas(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(Card)) {
                vm.waves.version
                val peaks = vm.waves.get(a)
                val w = size.width
                val h = size.height
                val dur = c.durationUs.toFloat()
                val norm = if (sn.normalize) vm.normalizeGain(c) else 1f
                val step = 2.dp.toPx()
                var x = 0f
                while (x < w && peaks != null) {
                    val t = x / w * dur
                    var env = 1f
                    if (sn.fadeInUs > 0) env *= (t / sn.fadeInUs).coerceIn(0f, 1f)
                    if (sn.fadeOutUs > 0) env *= ((dur - t) / sn.fadeOutUs).coerceIn(0f, 1f)
                    val i = ((c.inUs + t * c.speed) * WaveformCache.PEAKS_PER_SEC / 1e6).toInt().coerceIn(0, peaks.size - 1)
                    val v = (peaks[i].toInt() and 0xFF) / 255f * (if (sn.muted) 0f else sn.volume * env * norm)
                    val half = max(0.6f, min(1f, v) * (h / 2 - 4.dp.toPx()))
                    drawLine(if (v > 1f) Palette.Danger else Palette.AudioLabel, Offset(x, h / 2 - half), Offset(x, h / 2 + half), step * 0.6f)
                    x += step
                }
                // Fade ramps
                val fi = sn.fadeInUs / dur * w
                val fo = sn.fadeOutUs / dur * w
                if (fi > 0f) drawPath(Path().apply { moveTo(0f, 0f); lineTo(fi, 0f); lineTo(0f, h); close() }, Color.Black.copy(alpha = 0.35f))
                if (fo > 0f) drawPath(Path().apply { moveTo(w, 0f); lineTo(w - fo, 0f); lineTo(w, h); close() }, Color.Black.copy(alpha = 0.35f))
                if (fi > 0f) drawLine(Color.White.copy(alpha = 0.8f), Offset(0f, h), Offset(fi, 0f), 1.5.dp.toPx())
                if (fo > 0f) drawLine(Color.White.copy(alpha = 0.8f), Offset(w, h), Offset(w - fo, 0f), 1.5.dp.toPx())
                if (peaks == null) drawRect(Color.White.copy(alpha = 0.05f), Offset.Zero, Size(w, h))
            }

            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                // The same sound also playing elsewhere (an extracted copy, or the video it came from) would drown out
                // every change made here, so say so, and offer to mute the other copy.
                val twins = vm.sameSoundElsewhere(c.id)
                if (twins.isNotEmpty()) Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2A1F15)).border(1.dp, Palette.Accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "This sound also plays on ${twins.joinToString { vm.labelOf(it.trackId) }}, so changes here are hard to hear.",
                        color = Palette.Text, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Mute it", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Accent)
                            .clickable { vm.muteSameSoundElsewhere() }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Section("Volume")
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Toggle("Mute", sn.muted) { vm.setSnd { it.copy(muted = !it.muted) } }
                    Toggle("Normalize", sn.normalize) { vm.setSnd { it.copy(normalize = !it.normalize) } }
                }
                Value(
                    "Level", "${(sn.volume * 100).roundToInt()}%  ·  ${db(sn.volume)}", sn.volume, 0f..2f,
                    marks = listOf(1f),
                ) { v -> vm.setSnd { it.copy(volume = snap(v, 1f, 0.04f)) } }

                Section("Fades")
                val maxFade = min(10_000_000L, c.durationUs / 2).toFloat().coerceAtLeast(1f)
                Value("Fade in", secs(sn.fadeInUs), sn.fadeInUs.toFloat(), 0f..maxFade) { v -> vm.setSnd { it.copy(fadeInUs = v.toLong()) } }
                Value("Fade out", secs(sn.fadeOutUs), sn.fadeOutUs.toFloat(), 0f..maxFade) { v -> vm.setSnd { it.copy(fadeOutUs = v.toLong()) } }

                Section("Balance")
                Value("Pan", pan(sn.pan), sn.pan, -1f..1f, marks = listOf(0f)) { v -> vm.setSnd { it.copy(pan = snap(v, 0f, 0.06f)) } }

                Section("Equaliser")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EqPresets.forEachIndexed { i, (b, m, t) ->
                        val on = sn.bass == b && sn.mid == m && sn.treble == t
                        Toggle(EqNames[i], on) { vm.setSnd { it.copy(bass = b, mid = m, treble = t) } }
                    }
                }
                Value("Bass · 200 Hz", dbBand(sn.bass), sn.bass, -12f..12f, marks = listOf(0f)) { v -> vm.setSnd { it.copy(bass = snap(v, 0f, 0.5f)) } }
                Value("Mid · 1 kHz", dbBand(sn.mid), sn.mid, -12f..12f, marks = listOf(0f)) { v -> vm.setSnd { it.copy(mid = snap(v, 0f, 0.5f)) } }
                Value("Treble · 4 kHz", dbBand(sn.treble), sn.treble, -12f..12f, marks = listOf(0f)) { v -> vm.setSnd { it.copy(treble = snap(v, 0f, 0.5f)) } }

                Section("Pitch")
                Value(
                    "Shift", if (abs(sn.pitch) < 0.05f) "0 st" else "%+.1f st".format(sn.pitch), sn.pitch, -12f..12f, marks = listOf(0f),
                ) { v -> vm.setSnd { it.copy(pitch = (v * 2).roundToInt() / 2f) } }

                Section("Coming soon")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (name in listOf("Noise reduction", "Compressor", "Limiter", "Echo", "Reverb", "Remove silence", "Auto-duck music", "Voice isolation", "De-ess")) {
                        Text(
                            name, color = Palette.TextDim, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Card).clickable { soon(name) }.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "Reset all", color = Palette.Danger, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp)).clickable { vm.setSnd { Snd() } }.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = Palette.TextDim, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (on) Color.Black else Palette.Text, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Palette.Accent else Card)
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

/** A labelled slider with its value; [marks] are values it gently snaps to (100%, centre, 0 dB). */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun Value(label: String, shown: String, value: Float, range: ClosedFloatingPointRange<Float>, marks: List<Float> = emptyList(), onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(shown, color = Palette.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Line),
            modifier = Modifier.height(30.dp),
        )
    }
}

private fun snap(v: Float, to: Float, within: Float) = if (abs(v - to) < within) to else v

private fun db(v: Float) = if (v <= 0.0001f) "−∞ dB" else "%+.1f dB".format(20 * log10(v))

private fun dbBand(v: Float) = if (abs(v) < 0.05f) "0 dB" else "%+.1f dB".format(v)

private fun pan(v: Float) = when {
    abs(v) < 0.01f -> "Centre"
    v < 0 -> "L ${(-v * 100).roundToInt()}"
    else -> "R ${(v * 100).roundToInt()}"
}

private fun secs(us: Long) = if (us == 0L) "Off" else "%.1fs".format(us / 1e6)

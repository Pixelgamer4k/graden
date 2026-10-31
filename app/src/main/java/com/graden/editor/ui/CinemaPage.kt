package com.graden.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import android.os.SystemClock
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.delay
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.Cinema
import com.graden.editor.CinemaKey
import com.graden.editor.CinemaRun
import com.graden.editor.Clip
import com.graden.editor.cinema.FocusTrack
import com.graden.editor.CinemaModels
import com.graden.editor.EditorViewModel
import com.graden.editor.cinema.FocusEdits
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val Gold = Color(0xFFF5C451)
private val Warn = Color(0xFFFFAA55)
private val Good = Color(0xFF7BE0A3)
private val Well = Color(0xFF15161B)
private val Lift = Color(0xFF2A2B33)
private val Hair = Color(0xFF24252C)

@Composable
fun CinemaTopBar(vm: EditorViewModel) {
    BackHandler { vm.closeCinema() }
    val l = vm.cinemaTarget
    val npu = vm.cinemaEngine == "NPU"
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon2(Lucide.X, "Close") { vm.closeCinema() }
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            Text("Cinema DOF", color = Pro.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (npu) Good else Warn))
                Spacer(Modifier.width(5.dp))
                Text(vm.cinemaEngine, color = if (npu) Good else Warn, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                Text("   " + (l?.let { vm.assets[it.clip.assetId]?.name } ?: ""), color = Pro.Dim, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon2(Lucide.Undo, "Undo", enabled = vm.canUndo) { vm.undo() }
        Icon2(Lucide.Redo, "Redo", enabled = vm.canRedo) { vm.redo() }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.clip(RoundedCornerShape(50)).background(Palette.Accent).clickable { vm.closeCinema() }.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Done", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Icon2(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (enabled) Pro.Text else Pro.Faint, modifier = Modifier.size(18.dp))
    }
}

/** A choice of a few, in one calm track. */
@Composable
private fun Seg(options: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(11.dp)).background(Well).border(1.dp, Hair, RoundedCornerShape(11.dp)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val bg by animateColorAsState(if (on) Lift else Color.Transparent, tween(140), label = "seg")
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(bg).clickable { onPick(i) }, contentAlignment = Alignment.Center) {
                Text(o, color = if (on) Pro.Text else Pro.Dim, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** A lens character, set in one go. Blur amount stays the user's. */
private class Look(val name: String, val shape: Int, val rotation: Float, val rim: Float, val highlight: Float, val breathing: Float) {
    fun put(c: Cinema) = c.copy(shape = shape, rotation = rotation, rim = rim, highlight = highlight, breathing = breathing)
    fun on(c: Cinema) = put(c) == c
}

private val Looks = listOf(Look("Clean", 0, 0f, 0f, 0f, 0f), Look("Vintage", 2, .2f, .35f, .25f, .5f), Look("Anamorphic", 3, 0f, .2f, .5f, .2f), Look("Dreamy", 0, 0f, 0f, .7f, .3f))

@Composable
private fun Head(text: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 7.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Pro.Dim, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        trailing?.let { t -> Text(t, color = if (onTrailing != null) Pro.Dim else Pro.Faint, fontSize = 11.sp, modifier = if (onTrailing != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onTrailing).padding(horizontal = 6.dp, vertical = 3.dp) else Modifier) }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, on: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Gold.copy(alpha = .14f) else Well, tween(140), label = "act")
    Row(modifier.height(40.dp).clip(RoundedCornerShape(11.dp)).background(bg).border(1.dp, if (on) Gold.copy(alpha = .45f) else Hair, RoundedCornerShape(11.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = if (on) Gold else Pro.Text, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = if (on) Gold else Pro.Text, fontSize = 12.5.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Slide(vm: EditorViewModel, label: String, value: Float, min: Float, max: Float, neutral: Float, format: String, set: (Cinema, Float) -> Cinema) {
    ProSliderRaw(label, value, min, max, neutral, format, onStart = { vm.gradeStart() }, onChange = { v -> vm.setCinema { set(it, v) } }, onEnd = { vm.gradeEnd() },
        onReset = { vm.setCinemaOnce { set(it, neutral) } }, onType = null)
}

@Composable
fun CinemaBody(vm: EditorViewModel, modifier: Modifier) {
    val gt = vm.gradeTarget?.clip?.id
    LaunchedEffect(gt) { vm.cinemaFollow() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val clip = vm.cinemaTarget?.clip
    val ci = clip?.cinema ?: Cinema()
    val run = vm.cinemaRun
    Column(modifier.fillMaxWidth().background(Palette.Bg)) {
        Box(Modifier.padding(horizontal = 8.dp).padding(top = 6.dp)) { ColorTimeline(vm, 78.dp) }
        if (run != null && clip != null) FocusLane(vm, ci, run, clip)
        ColorTransport(vm)
        val busy = vm.cinemaProgress
        if (busy != null) Working(vm, busy)
        vm.cinemaError?.let { Text(it, color = Color(0xFFEF6A6A), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
        if (run == null) {
            if (busy == null) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) { AnalyseTab(vm, false) }
        } else {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { Seg(listOf("Focus", "Lens", "Analysis"), tab) { tab = it } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 16.dp)) {
                when (tab) {
                    0 -> FocusTab(vm, ci)
                    1 -> LensTab(vm, ci)
                    else -> AnalyseTab(vm, true)
                }
            }
        }
    }
}

/**
 * The clip's focus over time: each change a soft gold run, ending in a dot where focus lands. Drag a dot to move the change,
 * drag a run's start to lengthen it, hold a dot to remove it; anywhere else scrubs.
 */
@Composable
private fun FocusLane(vm: EditorViewModel, ci: Cinema, run: CinemaRun, c: Clip) {
    val now by rememberUpdatedState(vm)
    val keys by rememberUpdatedState(ci.keys)
    val first = c.inUs; val span = (c.outUs - c.inUs).coerceAtLeast(1L)
    val play = ((vm.cinemaUs(c) - first).toFloat() / span).coerceIn(0f, 1f)
    var grabbed by remember { mutableStateOf<Long?>(null) }
    fun landing(k: CinemaKey) = k.us + (k.seconds * 1e6f).toLong()
    Canvas(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 14.dp).pointerInput(c.id, span) {
        val reach = 20.dp.toPx()
        fun dot(x: Float) = keys.minByOrNull { abs((landing(it) - first).toFloat() / span * size.width - x) }?.takeIf { abs((landing(it) - first).toFloat() / span * size.width - x) < reach }
        fun usAt(x: Float) = first + ((x / size.width).coerceIn(0f, 1f) * span).toLong()
        detectTapGestures(
            onTap = { p -> now.cinemaSeek(dot(p.x)?.let(::landing) ?: usAt(p.x)) },
            onLongPress = { p -> dot(p.x)?.let { now.cinemaDropKey(it.us) } },
        )
    }.pointerInput(c.id, span) {
        val reach = 20.dp.toPx()
        var us = -1L; var end = 0L; var start = false
        fun xOf(t: Long) = (t - first).toFloat() / span * size.width
        fun usAt(x: Float) = first + ((x / size.width).coerceIn(0f, 1f) * span).toLong()
        fun finish() { if (us >= 0) now.gradeEnd(); us = -1L; grabbed = null }
        detectHorizontalDragGestures(
            onDragStart = { p ->
                val atEnd = keys.minByOrNull { abs(xOf(landing(it)) - p.x) }?.takeIf { abs(xOf(landing(it)) - p.x) < reach }
                val atStart = keys.filter { it.seconds > .02f }.minByOrNull { abs(xOf(it.us) - p.x) }?.takeIf { abs(xOf(it.us) - p.x) < reach }
                val k = atEnd ?: atStart
                if (k != null) { start = atEnd == null; us = k.us; end = landing(k); grabbed = us; now.gradeStart() }
            },
            onDragEnd = { finish() },
            onDragCancel = { finish() },
            onHorizontalDrag = { ch, _ ->
                ch.consume()
                val t = usAt(ch.position.x)
                if (us < 0) now.cinemaSeek(t)
                else {
                    val k = keys.firstOrNull { it.us == us }
                    if (k != null) {
                        us = if (start) now.cinemaMoveKey(us, t.coerceAtMost(end), (end - t).coerceAtLeast(0L) / 1e6f) else now.cinemaMoveKey(us, t - (k.seconds * 1e6f).toLong(), k.seconds)
                        grabbed = us
                        keys.firstOrNull { it.us == us }?.let { if (!start) end = landing(it) }
                        now.cinemaSeek(end)
                    }
                }
            },
        )
    }) {
        val y = size.height / 2; val w = size.width; val h = 4.dp.toPx()
        drawRoundRect(Color(0xFF23242B), Offset(0f, y - h / 2), Size(w, h), CornerRadius(h / 2))
        fun x(us: Long) = ((us - first).toFloat() / span).coerceIn(0f, 1f) * w
        for (k in ci.keys) {
            val a = x(k.us); val b = x(landing(k)); val big = k.us == grabbed
            if (b - a > 1f) {
                drawRoundRect(Gold.copy(alpha = .4f), Offset(a, y - h / 2), Size(b - a, h), CornerRadius(h / 2))
                drawCircle(Gold.copy(alpha = .7f), 2.5.dp.toPx(), Offset(a, y))
            }
            drawCircle(Color.Black.copy(alpha = .5f), (if (big) 8.dp else 6.dp).toPx(), Offset(b, y))
            drawCircle(Gold, (if (big) 6.5.dp else 4.5.dp).toPx(), Offset(b, y))
        }
        val p = play * w
        drawRoundRect(Color.White, Offset(p - 1.dp.toPx(), y - 9.dp.toPx()), Size(2.dp.toPx(), 18.dp.toPx()), CornerRadius(1.dp.toPx()))
    }
}

@Composable
private fun Working(vm: EditorViewModel, busy: Float) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(vm.cinemaBusy.ifEmpty { "Starting" }, color = Pro.Text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${(busy * 100).toInt()}%", color = Palette.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Icon2(Lucide.X, "Stop") { vm.cancelCinema() }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Hair)) {
            Box(Modifier.fillMaxWidth(busy.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
        }
    }
}

@Composable
private fun AnalyseTab(vm: EditorViewModel, done: Boolean) {
    Head("Analysis size")
    val sizes = listOf(960, 1280, 1920)
    Seg(listOf("Fast", "Balanced", "Fine"), sizes.indexOf(vm.cinemaEdge)) { vm.cinemaEdge = sizes[it] }
    Text("${vm.cinemaEdge}px  ·  smaller is faster, with slightly softer hair edges", color = Pro.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp, start = 2.dp))
    Text(if (CinemaModels.hexagon == null) "This phone has no supported NPU, so analysis runs on the CPU and takes longer" else "Runs once per file on this phone's NPU", color = Pro.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp, start = 2.dp))
    if (!vm.cinemaInstalled) Text("First use downloads the engine, about ${CinemaModels.downloadMb} MB", color = Pro.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp, start = 2.dp))
    Spacer(Modifier.height(14.dp))
    if (vm.cinemaProgress == null) Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(13.dp)).background(Palette.Accent).clickable { vm.analyseCinema() }, contentAlignment = Alignment.Center) {
        Text(if (done) "Analyse again" else "Analyse clip", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    if (done) {
        Spacer(Modifier.height(10.dp))
        Action(Lucide.RotateCcw, "Remove Cinema DOF from this clip", modifier = Modifier.fillMaxWidth()) { vm.removeCinema() }
    }
}

@Composable
private fun FocusTab(vm: EditorViewModel, ci: Cinema) {
    val c = vm.cinemaTarget?.clip ?: return
    val run = vm.cinemaRun ?: return
    val i = run.index(vm.cinemaUs(c))
    Spacer(Modifier.height(10.dp))
    Action(Lucide.Sparkles, "Auto from here", modifier = Modifier.fillMaxWidth()) { vm.cinemaAutoHere() }
    Spacer(Modifier.height(6.dp))
    ProSliderRaw("Focus depth", run.track.focus[i], 0f, 1f, .5f, "%.2f", onStart = { vm.gradeStart(); vm.showCinemaView(2) }, onChange = { vm.cinemaFixHere(it) }, onEnd = { vm.gradeEnd(); vm.showCinemaView(0) }, onReset = {}, onType = null)
    Head("Focus change speed")
    val secs = listOf(0f, .15f, .8f, 2.5f)
    Seg(listOf("Instant", "Whip", "Standard", "Slow"), secs.indexOfFirst { abs(it - ci.pull) < .01f }) { p -> vm.setCinemaOnce { it.copy(pull = secs[p]) } }
    Head("Focus points", if (ci.keys.isEmpty()) null else "Clear all", if (ci.keys.isEmpty()) null else ({ vm.cinemaDropKey(null) }))
    if (ci.keys.isEmpty()) Text("Focus follows the main subject on its own. Tap a subject to focus on it, double tap to hold on to it, or drag the gold mark across to pull focus by hand.", color = Pro.Faint, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 2.dp))
    val now = run.cache.times[i]
    val current = ci.keys.lastOrNull { it.us <= now }
    ci.keys.forEach { k -> KeyRow(vm, k, k === current) }
}

@Composable
private fun LensTab(vm: EditorViewModel, ci: Cinema) {
    Head("Look")
    Seg(Looks.map { it.name }, Looks.indexOfFirst { it.on(ci) }) { p -> vm.setCinemaOnce(Looks[p]::put) }
    Head("Blur")
    Slide(vm, "Amount", ci.strength, 0f, 1f, .45f, "%.2f") { c, v -> c.copy(strength = v) }
    Head("Aperture")
    Seg(listOf("Round", "Hex", "Octa", "Oval"), ci.shape) { p -> vm.setCinemaOnce { it.copy(shape = p) } }
    if (ci.shape != 0) Slide(vm, "Blade rotation", ci.rotation, 0f, 1.05f, 0f, "%.2f") { c, v -> c.copy(rotation = v) }
    Head("Character")
    Slide(vm, "Bright rim", ci.rim, 0f, 1f, 0f, "%.2f") { c, v -> c.copy(rim = v) }
    Slide(vm, "Highlights", ci.highlight, 0f, 1f, 0f, "%.2f") { c, v -> c.copy(highlight = v) }
    Slide(vm, "Focus breathing", ci.breathing, 0f, 1f, 0f, "%.2f") { c, v -> c.copy(breathing = v) }
    Head("Render quality")
    Seg(listOf("Fine", "Fast"), if (ci.samples == 48) 1 else 0) { p -> vm.setCinemaOnce { it.copy(samples = if (p == 0) 96 else 48) } }
}

@Composable
private fun KeyRow(vm: EditorViewModel, k: CinemaKey, current: Boolean) {
    val what = when (k.mode) { FocusEdits.AUTO -> "Auto"; FocusEdits.FIXED -> "Depth"; else -> if (k.strong) "Subject, held" else "Subject" }
    val landing = k.us + (k.seconds * 1e6f).toLong()
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(if (current) Well else Color.Transparent)
        .border(1.dp, if (current) Hair else Color.Transparent, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().height(40.dp).clickable { vm.cinemaSeek(landing) }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (current) Gold else Pro.Faint))
            Spacer(Modifier.width(10.dp))
            Text("%.2fs".format(landing / 1e6f), color = Pro.Text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            Text(what, color = Pro.Dim, fontSize = 12.5.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { vm.cinemaDropKey(k.us) }, contentAlignment = Alignment.Center) {
                Icon(Lucide.X, "Remove", tint = Pro.Dim, modifier = Modifier.size(14.dp))
            }
        }
        if (current) Column(Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp)) {
            ProSliderRaw("Transition", k.seconds, 0f, 5f, .8f, "%.2f s", onStart = { vm.gradeStart() }, onChange = { v -> vm.cinemaKey(k.us) { it.copy(seconds = v) } }, onEnd = { vm.gradeEnd() }, onReset = {}, onType = null)
            val curves = listOf(0f to 1f, .33f to .67f, 0f to .6f, .4f to 1f)
            val names = listOf("Smooth", "Linear", "Quick", "Late")
            val pick = curves.indexOfFirst { abs(it.first - k.outHandle) < .01f && abs(it.second - k.inHandle) < .01f }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                curves.forEachIndexed { n, h -> CurveChip(names[n], h.first, h.second, n == pick, Modifier.weight(1f)) { vm.gradeStart(); vm.cinemaKey(k.us) { it.copy(outHandle = h.first, inHandle = h.second) }; vm.gradeEnd() } }
            }
        }
    }
}

/** A transition's shape, drawn. */
@Composable
private fun CurveChip(name: String, a: Float, b: Float, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = if (on) Gold else Pro.Dim
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Gold.copy(alpha = .1f) else Color.Transparent).border(1.dp, if (on) Gold.copy(alpha = .4f) else Hair, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(34.dp).height(20.dp)) {
            val p = Path()
            for (i in 0..24) {
                val t = i / 24f
                val v = com.graden.editor.cinema.FocusTrack.curve(t, a, b)
                val x = t * size.width; val y = size.height * (1 - v)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, ink, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.height(4.dp))
        Text(name, color = ink, fontSize = 10.5.sp, maxLines = 1, softWrap = false)
    }
}

/**
 * Over the picture: the quietest outline on each subject found, and round corners on the one in focus. Marks size themselves
 * to the subject, within limits, and the corners glide from one subject to the next.
 */
@Composable
fun CinemaOverlay(vm: EditorViewModel) {
    val c = vm.cinemaTarget?.clip ?: return
    val run = vm.cinemaRun ?: return
    if (c.cinema?.on != true) return
    val a = vm.assets[c.assetId] ?: return
    if (vm.isPlaying || vm.playheadUs < c.startUs || vm.playheadUs >= c.endUs || vm.cinemaView != 0) return
    val now by rememberUpdatedState(vm)
    val i = run.index(vm.cinemaUs(c))
    val subjects = run.track.candidates[i].filter { it.score > 1 }
    val fx = run.track.x[i]; val fy = run.track.y[i]; val conf = run.track.confidence[i]
    val aspect = if (a.width > 0 && a.height > 0) a.width.toFloat() / a.height else 16f / 9f
    val canvasAspect = vm.meta.width.toFloat() / vm.meta.height.coerceAtLeast(1)
    val fill = vm.meta.scaling == "Fill"
    val tf = c.tf
    val pending = vm.cinemaPending
    val fitted = vm.centreScaleOf(c) * tf.scale
    val place: (Float, Float) -> FloatArray = { w, h -> placed(w, h, canvasAspect, aspect, fill, fitted, tf.x, tf.y) }
    val pic: (Offset, Float, Float) -> Offset? = { o, w, h ->
        val b = place(w, h)
        val x = (o.x - b[0]) / b[2]; val y = (o.y - b[1]) / b[3]
        if (x in 0f..1f && y in 0f..1f) Offset(x, y) else null
    }
    // Dragging the gold mark: the finger is the focus puller, and how long it takes is how long the pull lasts.
    var drag by remember { mutableStateOf<Offset?>(null) }
    var dragFrom by remember { mutableLongStateOf(0L) }
    var dragSeconds by remember { mutableFloatStateOf(0f) }
    val dragging = drag != null
    LaunchedEffect(dragging) { while (dragging) { dragSeconds = (SystemClock.uptimeMillis() - dragFrom) / 1000f; delay(80) } }
    val text = rememberTextMeasurer()
    val time = run.cache.times[i]
    val key = c.cinema?.keys?.lastOrNull { it.us <= time }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dpx = LocalDensity.current.density
        val box = place(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val small = min(box[2], box[3])
        val minSide = 30 * dpx; val maxSide = max(minSide, small * .3f)
        var chosen: FocusTrack.Subject? = null
        for (s in subjects) if (fx >= s.left && fx <= s.right && fy >= s.top && fy <= s.bottom && (chosen == null || abs(s.x - fx) + abs(s.y - fy) < abs(chosen.x - fx) + abs(chosen.y - fy))) chosen = s
        fun mark(s: FocusTrack.Subject): Triple<Float, Float, Float> {
            val w = (s.right - s.left) * box[2]; val h = (s.bottom - s.top) * box[3]
            val side = (min(w, h) * .5f).coerceIn(minSide, maxSide)
            // On a person or an animal the mark sits at the head end of the box, where the eye goes.
            val cy = if (s.kind == 0 || s.kind in 14..23) s.top * box[3] + max(side * .55f, min(h * .14f, side)) else s.y * box[3]
            return Triple(box[0] + s.x * box[2], box[1] + cy, side)
        }
        val hover = drag?.let { d -> pic(d, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()) }?.let { o -> subjects.filter { o.x in it.left..it.right && o.y in it.top..it.bottom }.minByOrNull { (it.right - it.left) * (it.bottom - it.top) } }
        val goal = hover?.let(::mark) ?: drag?.let { Triple(it.x, it.y, minSide * 1.25f) } ?: pending?.let { Triple(box[0] + it.x * box[2], box[1] + it.y * box[3], minSide * 1.25f) } ?: chosen?.let(::mark) ?: Triple(box[0] + fx * box[2], box[1] + fy * box[3], minSide * 1.25f)
        val glide = spring<Float>(dampingRatio = .82f, stiffness = 340f)
        val ox by animateFloatAsState(goal.first, glide, label = "fx")
        val oy by animateFloatAsState(goal.second, glide, label = "fy")
        val side by animateFloatAsState(goal.third * (if (pending != null) 1.14f else 1f), glide, label = "fs")
        val ink by animateColorAsState((if (conf < .5f && pending == null) Warn else Gold), tween(180), label = "fc")
        val spot by rememberUpdatedState(Offset(ox, oy))
        val grab by rememberUpdatedState(side / 2 + 28 * dpx)
        val tag = when {
            drag != null -> "%.1f s pull".format(dragSeconds.coerceIn(.15f, 5f))
            pending != null -> "Focusing"
            key?.mode == FocusEdits.FIXED -> "Depth"
            else -> (if (key == null || key.mode == FocusEdits.AUTO) "Auto · " else "") + kindName(chosen?.kind ?: -1) + (if (key?.strong == true && key.mode == FocusEdits.TRACK) " · Held" else "")
        }
        Canvas(Modifier.fillMaxSize().pointerInput(aspect, canvasAspect, fill, tf, fitted) {
            fun at(o: Offset) = pic(o, size.width.toFloat(), size.height.toFloat())
            detectTapGestures(
                onTap = { p -> at(p)?.let { now.cinemaTap(it.x, it.y, false) } },
                onDoubleTap = { p -> at(p)?.let { now.cinemaTap(it.x, it.y, true) } },
            )
        }.pointerInput(aspect, canvasAspect, fill, tf, fitted) {
            detectDragGestures(
                onDragStart = { p -> if ((p - spot).getDistance() < grab) { drag = p; dragFrom = SystemClock.uptimeMillis(); dragSeconds = 0f } },
                onDragEnd = {
                    val d = drag; drag = null
                    d?.let { pic(it, size.width.toFloat(), size.height.toFloat()) }?.let { now.cinemaTap(it.x, it.y, false, ((SystemClock.uptimeMillis() - dragFrom) / 1000f).coerceIn(.15f, 5f)) }
                },
                onDragCancel = { drag = null },
                onDrag = { ch, _ -> if (drag != null) { ch.consume(); drag = ch.position } },
            )
        }) {
            for (s in subjects) {
                if (s === chosen) continue
                val (x, y, sd) = mark(s)
                val tl = Offset(x - sd / 2, y - sd / 2); val cr = CornerRadius(sd * .16f)
                drawRoundRect(Color.Black.copy(alpha = .16f), tl, Size(sd, sd), cr, style = Stroke(2.2.dp.toPx()))
                drawRoundRect(Color.White.copy(alpha = .38f), tl, Size(sd, sd), cr, style = Stroke(1.dp.toPx()))
            }
            // Four round corners, each one curve, over a soft shadow so they read on any picture.
            val r = side / 2; val bend = side * .17f; val arm = side * .3f
            val p = Path()
            for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
                val cx = ox + sx * r; val cy = oy + sy * r
                p.moveTo(cx - sx * arm, cy)
                p.lineTo(cx - sx * bend, cy)
                p.quadraticBezierTo(cx, cy, cx, cy - sy * bend)
                p.lineTo(cx, cy - sy * arm)
            }
            drawPath(p, Color.Black.copy(alpha = .28f), style = Stroke(3.2.dp.toPx(), cap = StrokeCap.Round))
            drawPath(p, ink.copy(alpha = .92f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            // What the mark is on, and why, in a small tag: above the corners, or under them near the top of the picture.
            val label = text.measure(tag, TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium))
            val pad = 6.dp.toPx(); val tw = label.size.width + pad * 2; val th = label.size.height + 3.dp.toPx()
            val tx = (ox - tw / 2).coerceIn(4.dp.toPx(), max(4.dp.toPx(), size.width - tw - 4.dp.toPx()))
            val above = oy - r - th - 5.dp.toPx()
            val ty = if (above > 4.dp.toPx()) above else oy + r + 5.dp.toPx()
            drawRoundRect(Color.Black.copy(alpha = .5f), Offset(tx, ty), Size(tw, th), CornerRadius(th / 2))
            drawText(label, topLeft = Offset(tx + pad, ty + 1.5.dp.toPx()))
        }
    }
}

/** Hold the eye for the depth map, the aperture for what's in focus; let go and the picture is back. */
@Composable
fun CinemaPeek(vm: EditorViewModel) {
    if (vm.cinemaRun == null || vm.cinemaTarget?.clip?.cinema?.on != true) return
    Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PeekButton(vm, Lucide.Eye, "Hold to see depth", 1)
        PeekButton(vm, Lucide.Aperture, "Hold to see what's in focus", 2)
    }
}

@Composable
private fun PeekButton(vm: EditorViewModel, icon: ImageVector, label: String, view: Int) {
    val held = vm.cinemaView == view
    val now by rememberUpdatedState(vm)
    val bg by animateColorAsState(if (held) Gold else Color.Black.copy(alpha = .42f), tween(120), label = "peek")
    Box(Modifier.size(36.dp).clip(CircleShape).background(bg).pointerInput(Unit) {
        awaitEachGesture {
            // The picture behind has drag gestures of its own, and a finger never holds perfectly still: this press keeps
            // every event it sees until the finger lifts, so a small wobble can't end the hold.
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
            now.showCinemaView(view)
            try {
                do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    e.changes.forEach { it.consume() }
                } while (e.changes.any { it.pressed })
            } finally { now.showCinemaView(0) }
        }
    }, contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (held) Color.Black else Color.White, modifier = Modifier.size(17.dp))
    }
}

private fun kindName(k: Int) = when (k) { 0 -> "Person"; in 14..23 -> "Animal"; in 1..8 -> "Vehicle"; -1 -> "Subject"; else -> "Object" }

/** Where a clip lands in the preview area: the canvas fits the area, the clip fits (or fills) the canvas, then its own placing. */
private fun placed(w: Float, h: Float, canvasAspect: Float, aspect: Float, fill: Boolean, scale: Float, tx: Float, ty: Float): FloatArray {
    val cv = fit(w, h, canvasAspect)
    val inner = if (fill) { val a = cv[2] / cv[3]; if (aspect > a) floatArrayOf(0f, 0f, cv[3] * aspect, cv[3]) else floatArrayOf(0f, 0f, cv[2], cv[2] / aspect) } else fit(cv[2], cv[3], aspect)
    val pw = inner[2] * scale; val ph = inner[3] * scale
    return floatArrayOf(cv[0] + cv[2] / 2 - pw / 2 + tx * cv[2], cv[1] + cv[3] / 2 - ph / 2 + ty * cv[3], pw, ph)
}

/** Where a picture of [aspect] sits in a [w] × [h] area: left, top, width, height. */
private fun fit(w: Float, h: Float, aspect: Float): FloatArray {
    val a = w / h
    return if (aspect > a) { val ph = w / aspect; floatArrayOf(0f, (h - ph) / 2, w, ph) } else { val pw = h * aspect; floatArrayOf((w - pw) / 2, 0f, pw, h) }
}

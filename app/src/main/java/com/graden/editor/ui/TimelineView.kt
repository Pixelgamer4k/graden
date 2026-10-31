package com.graden.editor.ui

import androidx.compose.runtime.getValue

import androidx.compose.runtime.setValue

import androidx.compose.ui.platform.LocalView

import androidx.compose.ui.draw.shadow

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.EditorViewModel
import com.graden.editor.EditorViewModel.Companion.NEW_AUDIO
import com.graden.editor.EditorViewModel.Companion.NEW_VIDEO
import com.graden.editor.EditorViewModel.Companion.V0
import com.graden.editor.Clip
import com.graden.editor.Fx
import com.graden.editor.MediaAsset
import com.graden.editor.Track
import com.graden.editor.WaveformCache
import com.graden.editor.timecode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One row of the timeline. [key] is a track id, or NEW_VIDEO / NEW_AUDIO for the lane a drop would create. */
class Lane(val key: Int, val video: Boolean, val top: Float, val height: Float) {
    val bottom: Float get() = top + height
}

/**
 * Lanes top to bottom: the stack in [tracks] from its top down to its bottom.
 * Only lanes that exist are laid out, plus [ghost], the one a drag in progress would create:
 * a new video layer opens above the top one, a new audio lane below the last. Lanes keep their
 * height ([scale]d by a vertical pinch); when there are more than fit, they scroll. Positions here are unscrolled.
 */
fun layoutLanes(heightPx: Float, d: Density, tracks: List<Track>, ghost: Int, scale: Float = 1f): List<Lane> = with(d) {
    val rows = ArrayList<Pair<Int, Boolean>>()
    if (ghost == NEW_VIDEO) rows += NEW_VIDEO to true
    tracks.asReversed().forEach { rows += it.id to it.video }
    if (ghost == NEW_AUDIO) rows += NEW_AUDIO to false
    val gap = 1.dp.toPx()
    var y = 40.dp.toPx()
    rows.map { (key, video) ->
        val h = (if (video) 54.dp.toPx() else 46.dp.toPx()) * scale
        Lane(key, video, y, h).also { y += h + gap }
    }
}

/**
 * What a dragged clip is over at height [y]: a lane's track id, NEW_VIDEO for the top edge of the
 * highest video layer and above (a new layer), or NEW_AUDIO below the last lane. [lanes] must be
 * laid out without a ghost lane, so the answer cannot chase the lane it opens.
 */
fun laneKeyAt(y: Float, lanes: List<Lane>, slack: Float): Int {
    val top = lanes.first()
    if (y < top.top + top.height * 0.3f) return NEW_VIDEO
    return lanes.firstOrNull { y >= it.top - slack && y < it.bottom + slack }?.key ?: if (y < top.top) NEW_VIDEO else NEW_AUDIO
}

private class Geo(
    val dp: Float,
    val rulerH: Float,
    val headerW: Float,
    val handleW: Float,
    val corner: Float,
    val minTick: Float,
    val minPps: Float,
    val maxPps: Float,
    val basePps: Float,
)

private class LaneAnim(val lane: Lane, val top: Animatable<Float, AnimationVector1D>, val height: Animatable<Float, AnimationVector1D>)

private class ClipIcons(val film: VectorPainter, val music: VectorPainter, val image: VectorPainter)

private class Paints(private val density: Density) {
    private fun sp(v: Int) = with(density) { v.sp.toPx() }
    val ruler = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color(0xFFA3A7B4).toArgb(); textSize = sp(10); typeface = Typeface.MONOSPACE }
    val rulerMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color(0xFF6C707D).toArgb(); textSize = sp(9); typeface = Typeface.MONOSPACE }
    val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color(0xFFF3F3F5).toArgb(); textSize = sp(10); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL); letterSpacing = 0.02f
    }
    val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.toArgb(); textSize = sp(12) }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.02f
    }
    val fx = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.White.toArgb(); textSize = sp(9); typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.White.toArgb(); textSize = sp(12); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val duration = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color(0xFFAEB4C4).toArgb(); textSize = sp(9); typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT
    }
}

/**
 * Edge auto-scroll while something is dragged: [x] inside a band [zone] wide at either end of
 * [left]..[right] gives a signed pixels-per-second speed that starts slow and ramps up towards the edge.
 */
fun edgeScrollSpeed(x: Float, left: Float, right: Float, zone: Float, dpPx: Float): Float {
    val f = when {
        x < left + zone -> -((left + zone - x) / zone).coerceIn(0f, 1f)
        x > right - zone -> ((x - (right - zone)) / zone).coerceIn(0f, 1f)
        else -> return 0f
    }
    return (if (f < 0) -1f else 1f) * (50f + 1500f * f * f) * dpPx
}

private val RULER_STEPS = floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f, 1800f, 3600f)
private enum class Mode { NONE, SCROLL, LANES, ZOOM, TRIM, MOVE, REORDER }

/**
 * After a touch lands on something that can be picked up: null if the finger held still long
 * enough (pick it up), 1 if it lifted (a tap), 2 if it moved or a second finger came down first.
 */
private suspend fun AwaitPointerEventScope.holdOrMove(down: Offset): Int? =
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var result = 0
        while (result == 0) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.first()
            if (ev.changes.none { it.pressed }) result = 1
            else if (ev.changes.size > 1 || (ch.position - down).getDistance() > viewConfiguration.touchSlop) result = 2
        }
        result
    }

private val VideoFill = Color(0xFF263A63)
private val VideoEdge = Color(0xFF41609C)
private val VideoAudioHalf = Color(0xFF1A2947)
private val VideoWave = Color(0xFF86AEFF)
private val AudioFill = Color(0xFF1E4638)
private val AudioEdge = Color(0xFF2F7358)
private val AudioWave = Color(0xFF6FD9A6)
private val AudioAltFill = Color(0xFF363F22)
private val AudioAltEdge = Color(0xFF5F6E38)
private val PictureFill = Color(0xFF4A3F22)
private val PictureEdge = Color(0xFF8C7636)
private val PictureCap = Color(0xFFE0BC55)
// Label colours: a light tint of each clip kind's own colour.
private val VideoInk = Color(0xFFB4C8F5)
private val AudioInk = Color(0xFFA9DCC3)
private val AudioAltInk = Color(0xFFCBD79F)
private val PictureInk = Color(0xFFE9D69A)
// Where a picked-up clip came from: a grey footprint that stays until the clip is dropped.
private val Footprint = Color(0xFF30323B)
private val FootprintEdge = Color(0xFF4A4D58)

/**
 * The timeline: the playhead is fixed at the centre and the tracks slide under it. Everything
 * scrolling is one Canvas that reads state only in the draw phase, so scrolling, zooming and
 * playback never recompose. Lanes exist only for tracks that have clips and spring open or
 * closed as tracks come and go.
 */
@Composable
fun TimelineView(vm: EditorViewModel, modifier: Modifier = Modifier) {
    com.graden.editor.RecomposeProbe.hit("TimelineView")
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val g = remember(density) {
        with(density) {
            Geo(
                dp = 1.dp.toPx(), rulerH = 34.dp.toPx(), headerW = 46.dp.toPx(), handleW = 18.dp.toPx(), corner = 6.dp.toPx(),
                minTick = 44.dp.toPx(), minPps = 0.02.dp.toPx(), maxPps = 320.dp.toPx(), basePps = 48.dp.toPx(),
            )
        }
    }
    if (vm.pxPerSec <= 0f) vm.pxPerSec = g.basePps
    vm.snapPx = 10 * g.dp
    val decay = remember(density) { splineBasedDecay<Float>(density) }
    val fling = remember { arrayOfNulls<Job>(1) }
    val path = remember { Path() }
    val clipNodes = remember { ClipNodes() }
    val ordered = remember { LaneOrder() }
    val thumbsDone = remember { HashSet<Long>() }
    // Filmstrip pictures land many at a time; clips waiting for them are recorded again at most three times a second.
    val thumbTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var seen = -1
        while (true) {
            delay(330)
            val v = vm.thumbs.version
            if (v != seen) {
                seen = v
                thumbTick.intValue++
            }
        }
    }
    val paints = remember(density) { Paints(density) }
    val icons = ClipIcons(rememberVectorPainter(Lucide.Film), rememberVectorPainter(Lucide.Music), rememberVectorPainter(Lucide.Image))
    // Edge auto-scroll while a clip is being moved or trimmed: speed, finger travel and how far we have scrolled.
    val edgeSpeed = remember { mutableFloatStateOf(0f) }
    val dragMode = remember { arrayOf(Mode.NONE) }
    val travel = remember { longArrayOf(0L) }
    val scrolled = remember { longArrayOf(0L) }
    // Where the finger is while a clip is picked up (Offset.Unspecified otherwise), and the lane under it.
    val pick = remember { mutableStateOf(Offset.Unspecified) }
    val pickLane = remember { arrayOfNulls<Int>(1) }
    val scrolling = edgeSpeed.floatValue != 0f
    LaunchedEffect(scrolling) {
        if (!scrolling) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) {
                val us = (edgeSpeed.floatValue * (now - last) / 1e9f / vm.pxPerSec * 1e6f).toLong()
                if (vm.panBy(us)) {
                    // The clip stays under the finger while the timeline moves beneath it.
                    scrolled[0] += us
                    if (dragMode[0] == Mode.MOVE) {
                        if (pick.value != Offset.Unspecified) vm.moveDrag(travel[0] + scrolled[0], pickLane[0])
                    } else vm.trimDrag(travel[0] + scrolled[0])
                }
            }
            last = now
        }
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        com.graden.editor.RecomposeProbe.hit("TimelineBox")
        val ghost = vm.drop?.trackId?.takeIf { it < 0 } ?: 0
        val lanes = layoutLanes(constraints.maxHeight.toFloat(), density, vm.tracks, ghost, vm.laneScale)
        val target = remember { arrayOf(lanes) }
        target[0] = lanes
        // More lanes than fit: they scroll up and down under the ruler.
        vm.laneScrollMax = max(0f, lanes.last().bottom + 10 * g.dp - constraints.maxHeight)
        if (vm.laneScroll > vm.laneScrollMax) vm.laneScroll = vm.laneScrollMax
        val previousGhost = remember { intArrayOf(0) }
        val anims = lanes.map { lane ->
            key(lane.key) {
                // A lane that replaces the ghost lane of the drop that created it is already open.
                val open = lane.key == V0 || (lane.key > 0 && previousGhost[0] == (if (lane.video) NEW_VIDEO else NEW_AUDIO))
                val top = remember { Animatable(lane.top) }
                val height = remember { Animatable(if (open) lane.height else 0f) }
                LaunchedEffect(lane.top) { top.animateTo(lane.top, spring(0.8f, 380f)) }
                LaunchedEffect(lane.height) { height.animateTo(lane.height, spring(0.68f, 380f)) }
                LaneAnim(lane, top, height)
            }
        }
        SideEffect { previousGhost[0] = ghost }

        // Making room on V0, launcher-style: hold a dragged clip over a cut for a moment and the clips after it
        // slide aside; move away and they close up again before anything opens elsewhere.
        val liveDrop = vm.drop
        val slotKey = liveDrop?.takeIf { it.trackId == V0 }?.index
        val slot = remember { mutableStateOf<EditorViewModel.DropTarget?>(null) }
        val room = remember { Animatable(0f) }
        // On V1 and up: a clip dropped into a gap too small for it pushes the clips after the gap along; they slide
        // there while it is held, so it is clear where it will land.
        val freePush = liveDrop?.takeIf { it.trackId != V0 && it.pushUs > 0L }
        val pushLane = remember { mutableStateOf(Int.MIN_VALUE) }
        val pushFrom = remember { mutableStateOf(0L) }
        if (freePush != null) {
            pushLane.value = freePush.trackId
            pushFrom.value = freePush.pushFromUs
        }
        val pushUs = androidx.compose.animation.core.animateFloatAsState(freePush?.pushUs?.toFloat() ?: 0f, spring(0.8f, 420f), label = "push").value
        LaunchedEffect(slotKey) {
            room.animateTo(0f, tween(110))
            slot.value = liveDrop?.takeIf { it.trackId == V0 }
            if (slotKey != null) {
                delay(180)
                room.animateTo(1f, spring(0.72f, 320f))
            }
        }

        // Held in remember so its identity never changes: a new lambda would restart pointer input and
        // cut off a drag in progress every time the timeline recomposes (which a drag itself causes).
        val gesture = remember<suspend PointerInputScope.() -> Unit>(vm) {
            {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (fling[0]?.isActive == true) {
                        fling[0]?.cancel()
                        vm.scrubEnd()
                    }
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val cx = size.width / 2f
                    // Lanes are laid out unscrolled, so the touch is shifted into their space.
                    val downInLanes = down.position.copy(y = down.position.y + vm.laneScroll)
                    val onLanes = down.position.y > g.rulerH
                    val handle = if (onLanes) hitHandle(vm, g, target[0], cx, downInLanes) else 0
                    val overHeader = onLanes && down.position.x < g.headerW
                    // A tap on a cut's "+" or on its transition bar opens the transition picker.
                    val junction = if (onLanes && !overHeader) hitJunction(vm, g, target[0], cx, downInLanes) else null
                    val hit = if (onLanes && !overHeader) hitClip(vm, target[0], cx, downInLanes) else null
                    // A lane (other than the base) is picked up by holding its header or any empty part of it. The
                    // header alone is not enough: it lies along the screen edge, where the system's back gesture can take the touch.
                    val headerLane = if (onLanes && (overHeader || hit == null)) {
                        target[0].firstOrNull { downInLanes.y >= it.top && downInLanes.y <= it.bottom }?.key?.takeIf { it > 0 }
                    } else null
                    var mode = Mode.NONE
                    var zoomed = false
                    scrolled[0] = 0L
                    travel[0] = 0L
                    // Trimming a V0 clip's left edge already shifts the view itself, so it does not auto-scroll.
                    val canAutoScroll = !(handle < 0 && vm.selectedClipId?.let(vm::locate)?.trackId == V0)
                    if (handle != 0) {
                        mode = Mode.TRIM
                        vm.trimStart(left = handle < 0)
                    } else if (headerLane != null || hit != null) {
                        // Hold still on a lane's header or on a clip to pick it up; move first and it is a scroll.
                        when (holdOrMove(down.position)) {
                            null -> if (headerLane != null) {
                                if (vm.reorderStart(headerLane)) {
                                    mode = Mode.REORDER
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            } else if (hit != null && vm.moveStart(hit)) {
                                mode = Mode.MOVE
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            1 -> {
                                if (junction != null) vm.openFx(junction, EditorViewModel.FxSlot.TRANSITION)
                                else if (headerLane == null) vm.tapClip(hit)
                                return@awaitEachGesture
                            }
                            else -> {
                                val d = currentEvent.changes.first().position - down.position
                                if (abs(d.y) > abs(d.x) && vm.laneScrollMax > 0f) mode = Mode.LANES
                                else {
                                    mode = Mode.SCROLL
                                    vm.scrubStart()
                                }
                            }
                        }
                    }
                    var total = 0f
                    var totalY = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2 && mode != Mode.TRIM && mode != Mode.MOVE && mode != Mode.REORDER) {
                            if (mode == Mode.NONE) vm.scrubStart()
                            mode = Mode.ZOOM
                            zoomed = true
                            // Fingers side by side pinch time (clips get longer or shorter); fingers one above the
                            // other pinch the lanes (they get taller or thinner).
                            val two = ev.changes.filter { it.pressed }
                            val between = two[0].position - two[1].position
                            if (abs(between.x) >= abs(between.y)) {
                                vm.zoomBy(ev.calculateZoom(), g.minPps, g.maxPps)
                                vm.scrubBy((-ev.calculatePan().x / vm.pxPerSec * 1e6f).toLong())
                            } else {
                                vm.laneScale = (vm.laneScale * ev.calculateZoom()).coerceIn(0.5f, 2.2f)
                                vm.scrollLanesBy(-ev.calculatePan().y)
                            }
                            ev.changes.forEach { it.consume() }
                        } else {
                            val ch = ev.changes.first { it.pressed }
                            val dx = ch.positionChange().x
                            val dy = ch.positionChange().y
                            total += dx
                            totalY += dy
                            val travelUs = ((ch.position.x - down.position.x) / vm.pxPerSec * 1e6f).toLong()
                            if (mode == Mode.TRIM || mode == Mode.MOVE) {
                                travel[0] = travelUs
                                dragMode[0] = mode
                                edgeSpeed.floatValue =
                                    if (canAutoScroll) edgeScrollSpeed(ch.position.x, g.headerW, size.width.toFloat(), 64 * g.dp, g.dp) else 0f
                            }
                            when (mode) {
                                Mode.TRIM -> vm.trimDrag(travelUs + scrolled[0])
                                Mode.MOVE -> {
                                    // The lane under the finger, measured on the layout without the preview lane so it cannot chase itself.
                                    val rows = layoutLanes(size.height.toFloat(), this, vm.tracks, 0, vm.laneScale)
                                    pickLane[0] = laneKeyAt(ch.position.y + vm.laneScroll, rows, 3 * g.dp)
                                    pick.value = ch.position
                                    vm.moveDrag(travelUs + scrolled[0], pickLane[0])
                                }
                                // Whichever way the finger goes first decides: sideways moves through time, up and down scrolls the lanes.
                                Mode.NONE -> if (abs(totalY) > viewConfiguration.touchSlop && abs(totalY) > abs(total) && vm.laneScrollMax > 0f) {
                                    mode = Mode.LANES
                                } else if (abs(total) > viewConfiguration.touchSlop) {
                                    mode = Mode.SCROLL
                                    vm.scrubStart()
                                }
                                Mode.LANES -> vm.scrollLanesBy(-dy)
                                Mode.REORDER -> {
                                    // Swap once the finger is well inside another lane, so lanes of different heights do not flip back and forth.
                                    val rows = layoutLanes(size.height.toFloat(), this, vm.tracks, 0, vm.laneScale)
                                    val y = ch.position.y + vm.laneScroll
                                    rows.firstOrNull { y >= it.top + it.height * 0.2f && y <= it.bottom - it.height * 0.2f }?.let { vm.reorderOver(it.key) }
                                }
                                else -> {
                                    vm.scrubBy((-dx / vm.pxPerSec * 1e6f).toLong())
                                    tracker.addPosition(ch.uptimeMillis, ch.position)
                                }
                            }
                            if (mode != Mode.NONE) ch.consume()
                        }
                    }
                    edgeSpeed.floatValue = 0f
                    pick.value = Offset.Unspecified
                    when (mode) {
                        Mode.TRIM -> vm.trimEnd()
                        Mode.MOVE -> vm.moveEnd()
                        Mode.NONE -> if (junction != null) vm.openFx(junction, EditorViewModel.FxSlot.TRANSITION) else vm.tapClip(hit)
                        Mode.LANES -> Unit
                        Mode.REORDER -> vm.reorderEnd()
                        else -> {
                            val v = tracker.calculateVelocity().x
                            if (!zoomed && abs(v) > 400f) {
                                fling[0] = scope.launch {
                                    var last = 0f
                                    AnimationState(0f, -v).animateDecay(decay) {
                                        val d = value - last
                                        last = value
                                        if (!vm.scrubBy((d / vm.pxPerSec * 1e6f).toLong()) && abs(d) > 1f) cancelAnimation()
                                    }
                                    vm.scrubEnd()
                                }
                            } else vm.scrubEnd()
                        }
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxSize().pointerInput(Unit, gesture)) {
            val drawStart = System.nanoTime()
            val pps = vm.pxPerSec
            if (pps <= 0f) return@Canvas
            val assets = vm.assets
            val tracks = vm.tracks
            val ph = vm.playheadUs
            val moving = vm.movingClipId
            vm.waves.version // redraw when a waveform arrives
            val thumbVersion = thumbTick.intValue // and, at most three times a second, when filmstrip pictures do
            val cx = size.width / 2f
            val usPerPx = 1e6 / pps
            val tL = ph - (cx * usPerPx).toLong()
            val tR = ph + (cx * usPerPx).toLong()
            fun xOf(us: Long) = cx + ((us - ph) / usPerPx).toFloat()

            val sc = vm.laneScroll
            val hair = 1f // one physical pixel

            // Ruler: a label at every step, small ticks dividing each step, and hairlines down through the lanes.
            // Whole seconds read as minutes:seconds; steps finer than a second read as frames ("15f"), as editors do.
            val stepS = RULER_STEPS.firstOrNull { it * pps >= g.minTick } ?: RULER_STEPS.last()
            val stepUs = (stepS * 1e6f).toLong()
            val parts = if (stepS == 2f || stepS == 120f) 4 else 5
            val fpsInt = vm.fps.roundToInt().coerceAtLeast(1)
            var t = max(0L, tL) / stepUs * stepUs
            drawIntoCanvas { c ->
                while (t <= tR + stepUs) {
                    val x = xOf(t)
                    drawLine(Palette.Grid, Offset(x, g.rulerH), Offset(x, size.height), hair)
                    drawLine(Palette.Tick, Offset(x, g.rulerH - 9 * g.dp), Offset(x, g.rulerH), hair)
                    for (k in 1 until parts) {
                        val xm = xOf(t + stepUs * k / parts)
                        val middle = parts % 2 == 0 && k == parts / 2
                        drawLine(Palette.Tick.copy(alpha = 0.7f), Offset(xm, g.rulerH - (if (middle) 6 else 4) * g.dp), Offset(xm, g.rulerH), hair)
                    }
                    val xHalf = xOf(t + stepUs / 2)
                    drawLine(Palette.Grid.copy(alpha = 0.55f), Offset(xHalf, g.rulerH), Offset(xHalf, size.height), hair)
                    val whole = t % 1_000_000L == 0L
                    c.nativeCanvas.drawText(
                        if (whole) rulerLabel(t) else "${((t % 1_000_000L) * fpsInt / 1_000_000.0).roundToInt()}f",
                        x + 4 * g.dp, g.rulerH - 13 * g.dp, if (whole) paints.ruler else paints.rulerMinor,
                    )
                    t += stepUs
                }
            }
            drawLine(Palette.Line, Offset(0f, g.rulerH), Offset(size.width, g.rulerH), hair)
            val tRuler = System.nanoTime()
            var tLanes = tRuler

            // The space that has opened on V0 for a clip held over one of its cuts.
            val held = slot.value
            val pickedUp = moving?.let(vm::locate)
            val ownSlot = pickedUp != null && pickedUp.trackId == V0 && held != null && held.startUs == pickedUp.startUs
            val gapAt = held?.startUs ?: 0L
            val gapPx = if (held != null && !ownSlot) min((held.durationUs / usPerPx).toFloat(), 150 * g.dp) * room.value else 0f

            clipRect(top = g.rulerH) {
            // Lanes and their clips
            for (la in anims) {
                val top = (la.top.value - sc)
                val h = la.height.value
                if (h < 2f) continue
                drawRect(Palette.Lane, Offset(0f, top), Size(size.width, h))
                drawLine(Palette.Line, Offset(0f, top + h), Offset(size.width, top + h), hair)
                val track = tracks.firstOrNull { it.id == la.lane.key } ?: continue
                val audioIndex = if (track.video) 0 else tracks.filter { !it.video }.indexOf(track)
                // Only the clips in view, found by halving the lane's clips in time order rather than walking all of them.
                val laneClips = ordered.of(track)
                val pushAll = (if (track.id == V0) gapPx else 0f) + (if (track.id == pushLane.value) (pushUs / usPerPx).toFloat() else 0f)
                val margin = (pushAll * usPerPx).toLong() + 1
                for (ci in ordered.firstEndingAfter(laneClips, tL - margin) until laneClips.size) {
                    val clip = laneClips[ci]
                    if (clip.startUs > tR) break
                    val push = if (track.id == V0 && clip.startUs >= gapAt) gapPx
                    else if (track.id == pushLane.value && clip.id != moving && clip.startUs >= pushFrom.value) (pushUs / usPerPx).toFloat()
                    else 0f
                    // A text: its words on a coloured bar (caption, lyrics and music, or a text added by hand).
                    val cap = clip.caption
                    if (cap != null) {
                        drawCaptionBlock(g, cap, xOf(clip.startUs) + push, xOf(clip.endUs) - 2.5f * g.dp + push, top + 3 * g.dp, h - 6 * g.dp, clip.id == moving)
                        continue
                    }
                    val a = assets[clip.assetId]
                    val peaks = a?.let(vm.waves::get)
                    val alt = audioIndex % 2 == 1
                    val norm = if (clip.snd.normalize) vm.normalizeGain(clip) else 1f
                    val bx0 = xOf(clip.startUs) + push
                    val bx1 = xOf(clip.endUs) - 2.5f * g.dp + push
                    val by = top + 3 * g.dp
                    val bh = h - 6 * g.dp
                    val lifted = clip.id == moving
                    val rev = vm.reversing[clip.id]
                    // A clip looks the same from frame to frame while the timeline scrolls; it is recorded once and
                    // only moved, which keeps scrolling and playback light. Anything changing (a drag, a reversal in
                    // progress) is drawn as it is.
                    // Filmstrip pictures from the clip's scrub proxy (tiny, every frame a keyframe, quick to read); a clip
                    // whose pictures have not all arrived yet is recorded again as they land, then stays as it is.
                    var missing = false
                    val thumb: ((Long) -> androidx.compose.ui.graphics.ImageBitmap?)? = a?.takeIf { !it.isAudio }?.let { asset ->
                        val px = vm.proxies[asset.id]
                        if (asset.isImage || px != null) { us -> vm.thumbs.get(asset, us, px).also { if (it == null) missing = true } } else null
                    }
                    val key = ClipDrawKey(
                        clip.copy(startUs = 0L, transition = null, animIn = null, animOut = null, tf = com.graden.editor.Tf(), grade = com.graden.editor.Grade()),
                        a, peaks, track.video, alt, ((bx1 - bx0) * 2).roundToInt(), (bh * 2).roundToInt(), norm, usPerPx,
                        if (clip.id in thumbsDone) -1 else thumbVersion,
                    )
                    val cached = !lifted && rev == null && clipNodes.draw(this, clip.id, key, bx0, by, bx1 - bx0, bh) {
                        missing = false
                        drawClipBlock(g, paints, path, icons, a, peaks, track.video, alt, clip.inUs, clip.durationUs, 0f, bx1 - bx0, 0f, bh, usPerPx, false, clip.snd, norm, clip.speed, null, thumb)
                        if (missing || thumb == null) thumbsDone.remove(clip.id) else thumbsDone.add(clip.id)
                    }
                    if (!cached) drawClipBlock(
                        g, paints, path, icons, a, peaks, track.video, alt, clip.inUs, clip.durationUs,
                        bx0, bx1, by, bh, usPerPx, lifted, clip.snd, norm, clip.speed, rev, thumb,
                    )
                }
                // On every cut of a video lane: a "+" to add a transition, or the transition that is there.
                if (track.pictures && moving == null) for (ci in ordered.firstEndingAfter(laneClips, tL) until laneClips.size) {
                    val clip = laneClips[ci]
                    if (clip.startUs > tR + 5_000_000L) break
                    drawAnimMarks(g, clip, xOf(clip.startUs), xOf(clip.endUs) - 2.5f * g.dp, top + 3 * g.dp, h - 6 * g.dp, usPerPx)
                    val prev = laneClips.getOrNull(ci - 1)?.takeIf { it.endUs == clip.startUs } ?: continue
                    val box = junctionBox(g, clip, prev, top, xOf(clip.startUs), usPerPx) ?: continue
                    drawJunction(g, paints, box, clip.transition)
                }
            }

            tLanes = System.nanoTime()
            // The lane being moved up or down the stack is lit.
            anims.firstOrNull { it.lane.key == vm.reorderingTrack }?.let { la ->
                drawRect(Color.White.copy(alpha = 0.07f), Offset(0f, (la.top.value - sc)), Size(size.width, la.height.value))
                drawRect(Palette.Accent, Offset(0f, (la.top.value - sc)), Size(size.width, la.height.value), style = Stroke(1.5f * g.dp))
            }

            // Clips slide under the track headers rather than being chopped off by them: a shadow falls from
            // the header's edge onto the lane and fades out, so a clip passing beneath visibly continues.
            val shade = 22 * g.dp
            for (la in anims) {
                val h = la.height.value
                if (h < 2f) continue
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), g.headerW, g.headerW + shade),
                    Offset(g.headerW, (la.top.value - sc)), Size(shade, h),
                )
            }

            // Where the dragged clip would land. On V0: a thin bar at the cut, which becomes an open slot once
            // the clips have moved aside. On other lanes: an outlined slot where it will sit.
            val slotEdge = Color.White.copy(alpha = 0.7f)
            if (gapPx > 2 * g.dp) anims.firstOrNull { it.lane.key == V0 }?.let { la ->
                val x0 = xOf(gapAt)
                drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(x0, (la.top.value - sc)), Size(gapPx - g.dp, la.height.value), CornerRadius(g.corner))
                drawRoundRect(slotEdge, Offset(x0, (la.top.value - sc)), Size(gapPx - g.dp, la.height.value), CornerRadius(g.corner), style = Stroke(1.5f * g.dp))
            }
            vm.drop?.let { d ->
                val la = anims.firstOrNull { it.lane.key == d.trackId } ?: return@let
                val top = (la.top.value - sc)
                val h = la.height.value
                if (h <= 2f) return@let
                if (d.trackId == V0) {
                    val own = pickedUp != null && pickedUp.trackId == V0 && d.startUs == pickedUp.startUs
                    if (gapPx <= 2 * g.dp && !own) {
                        val x = xOf(d.startUs)
                        drawRoundRect(Color.White, Offset(x - 2 * g.dp, top - 4 * g.dp), Size(4 * g.dp, h + 8 * g.dp), CornerRadius(2 * g.dp))
                    }
                } else {
                    val x0 = xOf(d.startUs)
                    val w = max(8 * g.dp, (d.durationUs / usPerPx).toFloat())
                    drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(x0, top), Size(w, h), CornerRadius(g.corner))
                    drawRoundRect(slotEdge, Offset(x0, top), Size(w, h), CornerRadius(g.corner), style = Stroke(1.5f * g.dp))
                }
            }

            // Selection frame and trim handles
            val sel = vm.selectedClipId?.let(vm::locate)
            val selLane = sel?.let { s -> anims.firstOrNull { it.lane.key == s.trackId } }
            // Hidden during a drag: clips are sliding about then, and the frame would mark the wrong place.
            if (sel != null && selLane != null && sel.clip.id != moving && vm.drop == null) {
                val x0 = xOf(sel.startUs)
                val x1 = xOf(sel.startUs + sel.clip.durationUs) - 2.5f * g.dp
                val top = (selLane.top.value - sc) + 3 * g.dp
                val h = selLane.height.value - 6 * g.dp
                val side = vm.trimSide
                drawRect(Color.White, Offset(x0, top), Size(x1 - x0, h), style = Stroke(2.5f * g.dp))
                drawTrimHandle(g, path, true, x0, top, h, side < 0)
                drawTrimHandle(g, path, false, x1, top, h, side > 0)
                if (side != 0) {
                    // While trimming, the clip's length rides above the edge being pulled.
                    val label = "%.1fs".format(sel.clip.durationUs / 1e6)
                    val lw = paints.tip.measureText(label)
                    val bx = ((if (side < 0) x0 else x1) - lw / 2).coerceIn(g.headerW + 6 * g.dp, size.width - lw - 12 * g.dp)
                    val by = top + h + 9 * g.dp // below the lane, clear of the playhead's timecode
                    drawRoundRect(Color(0xF2202229), Offset(bx - 8 * g.dp, by), Size(lw + 16 * g.dp, 22 * g.dp), CornerRadius(7 * g.dp))
                    drawIntoCanvas { it.nativeCanvas.drawText(label, bx, by + 15.5f * g.dp, paints.tip) }
                }
            }

            }

            // The picked-up clip rides centred under the fingertip as a small tag.
            val finger = pick.value
            val picked = moving?.let(vm::locate)
            if (finger != Offset.Unspecified && picked != null) {
                val name = assets[picked.clip.assetId]?.name ?: ""
                val shown = if (name.length > 18) name.take(17) + "…" else name
                val tw = paints.name.measureText(shown)
                val bw = tw + 20 * g.dp
                val bx = (finger.x - bw / 2).coerceIn(g.headerW, size.width - bw)
                val by = (finger.y - 15 * g.dp).coerceAtLeast(2 * g.dp)
                val audio = tracks.firstOrNull { it.id == picked.trackId }?.video == false
                drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(bx, by + 3 * g.dp), Size(bw, 30 * g.dp), CornerRadius(9 * g.dp))
                drawRoundRect(if (audio) AudioFill else VideoFill, Offset(bx, by), Size(bw, 30 * g.dp), CornerRadius(9 * g.dp))
                drawRoundRect(Color.White, Offset(bx, by), Size(bw, 30 * g.dp), CornerRadius(9 * g.dp), style = Stroke(1.5f * g.dp))
                drawIntoCanvas { it.nativeCanvas.drawText(shown, bx + 10 * g.dp, by + 20 * g.dp, paints.name) }
            }

            // Playhead with its timecode
            // Playhead. The time sits in a quiet dark capsule; a small soft-cornered pointer hangs from it like the
            // tail of a tooltip, and a fine line with a faint glow runs down from the pointer's tip through the lanes.
            val capH = 17 * g.dp
            val capTop = 3 * g.dp
            val tipY = capTop + capH + 6.5f * g.dp
            drawLine(Palette.Accent.copy(alpha = 0.16f), Offset(cx, g.rulerH), Offset(cx, size.height), 5 * g.dp)
            drawLine(Palette.Accent, Offset(cx, tipY - g.dp), Offset(cx, size.height), 1.3f * g.dp)
            path.rewind()
            path.moveTo(cx - 4 * g.dp, capTop + capH + 1.5f * g.dp)
            path.lineTo(cx + 4 * g.dp, capTop + capH + 1.5f * g.dp)
            path.lineTo(cx, tipY - 1.5f * g.dp)
            path.close()
            drawPath(path, Palette.Accent)
            drawPath(path, Palette.Accent, style = Stroke(2.4f * g.dp, join = StrokeJoin.Round))
            val tc = vm.tc(ph)
            val tw = paints.bubble.measureText(tc)
            val capW = tw + 16 * g.dp
            drawRoundRect(Color(0xF21A1B21), Offset(cx - capW / 2, capTop), Size(capW, capH), CornerRadius(capH / 2))
            drawRoundRect(Palette.Accent.copy(alpha = 0.55f), Offset(cx - capW / 2, capTop), Size(capW, capH), CornerRadius(capH / 2), style = Stroke(g.dp))
            drawIntoCanvas { it.nativeCanvas.drawText(tc, cx - tw / 2, capTop + 12.2f * g.dp, paints.bubble) }
            clipNodes.endFrame()
            val drawMs = (System.nanoTime() - drawStart) / 1e6
            if (drawMs > 2.0) android.util.Log.d("PlayProbe", "SLOW timeline draw %.1fms ruler %.1f lanes %.1f rest %.1f".format(drawMs, (tRuler - drawStart) / 1e6, (tLanes - tRuler) / 1e6, (System.nanoTime() - tLanes) / 1e6))
        }

        // Track headers
        // The headers run down the left edge of the screen, where the system's back gesture would
        // otherwise take touches away; this strip is kept for the app so a lane can be held and dragged.
        Box(Modifier.padding(top = 34.dp).width(46.dp).fillMaxHeight().systemGestureExclusion())
        Box(Modifier.fillMaxSize().padding(top = 34.dp).clipToBounds()) {
        for (la in anims) key(la.lane.key) {
            val id = la.lane.key
            val h = la.height.value
            if (h >= 2f) Column(
                Modifier.offset { IntOffset(0, (la.top.value - vm.laneScroll - g.rulerH).roundToInt()) }.width(46.dp).height(with(density) { h.toDp() })
                    .background(if (id == vm.reorderingTrack) Color(0xFF3A2A18) else Palette.Header).alpha(if (id < 0) 0.5f else 1f)
                    .drawBehind { drawLine(Palette.Line, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    when (id) {
                        NEW_VIDEO -> vm.nextName(true)
                        NEW_AUDIO -> vm.nextName(false)
                        else -> vm.labelOf(id)
                    },
                    color = if (la.lane.video) Palette.VideoLabel else Palette.AudioLabel,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                )
                if (id >= 0 && h > 30 * g.dp) {
                    // One easy target per lane: a pill showing what is set (locked, hidden, muted); tapped, it opens
                    // large switches for all three beside the header. The lane stays the size it is.
                    LaneControls(vm, id, la.lane.video, if (la.lane.video) vm.laneHasSound(id) else true)
                }
            }
        }
        }
    }
}

/** A lane's state at a glance, and the way to its switches. */
@Composable
private fun LaneControls(vm: EditorViewModel, id: Int, visual: Boolean, sound: Boolean) {
    val locked = id in vm.lockedTracks
    val hidden = id in vm.hiddenTracks
    val muted = id in vm.mutedTracks
    var open by remember { mutableStateOf(false) }
    val any = locked || (visual && hidden) || (sound && muted)
    val shape = RoundedCornerShape(9.dp)
    Box {
        Row(
            Modifier.padding(top = 3.dp).width(38.dp).height(22.dp).clip(shape)
                .background(if (open) Palette.Accent.copy(alpha = 0.3f) else if (any) Palette.Accent.copy(alpha = 0.16f) else Color(0xFF26272E))
                .border(1.dp, if (any || open) Palette.Accent.copy(alpha = 0.7f) else Color(0x24FFFFFF), shape)
                .clickable { open = true },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!any) Icon(Lucide.Settings, "Track settings", tint = Palette.TextDim, modifier = Modifier.size(12.dp))
            else {
                if (locked) Icon(Lucide.Lock, "Locked", tint = Palette.Accent, modifier = Modifier.size(11.dp))
                if (visual && hidden) Icon(Lucide.EyeOff, "Hidden", tint = Palette.Accent, modifier = Modifier.size(11.dp))
                if (sound && muted) Icon(Lucide.VolumeX, "Muted", tint = Palette.Accent, modifier = Modifier.size(11.dp))
            }
        }
        if (open) androidx.compose.ui.window.Popup(
            alignment = Alignment.TopStart, offset = IntOffset(with(LocalDensity.current) { 44.dp.roundToPx() }, with(LocalDensity.current) { (-18).dp.roundToPx() }),
            onDismissRequest = { open = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        ) {
            val card = RoundedCornerShape(16.dp)
            Row(
                Modifier.shadow(16.dp, card, ambientColor = Color.Black, spotColor = Color.Black).clip(card)
                    .background(Brush.verticalGradient(listOf(Color(0xFF2A2B33), Color(0xFF1E1F25)))).border(1.dp, Color(0x29FFFFFF), card).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LaneSwitch(if (locked) Lucide.Lock else Lucide.LockOpen, if (locked) "Locked" else "Lock", locked) { vm.toggleLock(id) }
                if (visual) LaneSwitch(if (hidden) Lucide.EyeOff else Lucide.Eye, if (hidden) "Hidden" else "Hide", hidden) { vm.toggleHidden(id) }
                if (sound) LaneSwitch(if (muted) Lucide.VolumeX else Lucide.Volume, if (muted) "Muted" else "Mute", muted) { vm.toggleMute(id) }
            }
        }
    }
}

/** One big switch in a lane's panel: icon and word, lit when on. */
@Composable
private fun LaneSwitch(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(66.dp).height(58.dp).clip(shape)
            .background(if (on) Palette.Accent.copy(alpha = 0.22f) else Color(0xFF34353E)).border(1.dp, if (on) Palette.Accent else Color(0x1AFFFFFF), shape)
            .clickable { view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onClick() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = if (on) Palette.Accent else Palette.Text, modifier = Modifier.size(20.dp))
        Text(label, color = if (on) Palette.Accent else Palette.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * One clip as a block. A video clip that has sound shows its picture half on top (icon, name,
 * length) and its own audio waveform underneath; an audio clip is all waveform.
 */
/** Each lane's clips in time order, kept until the lane changes, for finding what is in view by halving. */
private class LaneOrder {
    private val map = HashMap<Int, Pair<List<Clip>, List<Clip>>>()

    fun of(t: Track): List<Clip> {
        map[t.id]?.let { if (it.first === t.clips) return it.second }
        return t.clips.sortedBy { it.startUs }.also { map[t.id] = t.clips to it }
    }

    /** Index of the first clip that ends after [us]. */
    fun firstEndingAfter(clips: List<Clip>, us: Long): Int {
        var lo = 0
        var hi = clips.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (clips[mid].endUs <= us) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

private val secondsLabels = HashMap<Long, String>()

/** "0.6s" and the like, made once per length instead of formatted every frame. */
private fun secondsLabel(us: Long): String = secondsLabels.getOrPut(us / 100_000L) { "%.1fs".format(us / 1e6) }

/** Everything a clip block's look depends on, apart from where it is. */
private data class ClipDrawKey(
    val clip: Clip, val asset: MediaAsset?, val peaks: ByteArray?, val video: Boolean, val alternate: Boolean,
    val halfW: Int, val halfH: Int, val norm: Float, val usPerPx: Double,
    /** -1 once all its filmstrip pictures are in; until then the thumbnail cache's version, so it is redrawn as they land. */
    val thumbs: Int,
)

/**
 * Clip blocks recorded as GPU display lists. Each is recorded when its look changes and otherwise only drawn at its
 * new place, a single call; lists not used for a couple of seconds are let go.
 */
private class ClipNodes {
    private class Entry(var key: Any, val node: android.graphics.RenderNode, var usedAt: Long)
    private val map = HashMap<Long, Entry>()
    private var frame = 0L
    private val recorder = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()

    /**
     * Draws [id] at ([x], [y]); false when it cannot be cached here (then draw it directly). A wide block (a long clip,
     * or any clip zoomed in) is recorded in tiles of at most [TILE_W] pixels, and only the tiles in view are drawn, so
     * every recording stays a size the GPU renderer handles well.
     */
    fun draw(scope: DrawScope, id: Long, key: Any, x: Float, y: Float, w: Float, h: Float, record: DrawScope.() -> Unit): Boolean {
        val canvas = scope.drawContext.canvas.nativeCanvas
        if (!canvas.isHardwareAccelerated || w < 1f || h < 1f || w > MAX_W) return false
        val tiles = kotlin.math.ceil(w / TILE_W).toInt().coerceAtLeast(1)
        val viewW = scope.size.width
        for (i in 0 until tiles) {
            val tx = i * TILE_W
            val tw = min(TILE_W, w - tx)
            if (x + tx > viewW || x + tx + tw < 0f) continue
            val slot = id * 4096L + i
            var e = map[slot]
            // The GPU side may throw a recording's content away (for one, when it went undrawn during a fast fling);
            // drawing it then shows nothing, so it is recorded again whenever its content is gone.
            if (e == null || e.key != key || !e.node.hasDisplayList()) {
                val node = e?.node ?: android.graphics.RenderNode("clip")
                node.setPosition(0, 0, kotlin.math.ceil(tw).toInt() + 1, kotlin.math.ceil(h).toInt() + 1)
                val rc = node.beginRecording()
                try {
                    rc.translate(-tx, 0f)
                    recorder.draw(scope, scope.layoutDirection, androidx.compose.ui.graphics.Canvas(rc), Size(w, h), record)
                } finally {
                    node.endRecording()
                }
                if (e == null) {
                    e = Entry(key, node, frame)
                    map[slot] = e
                } else e.key = key
            }
            e.usedAt = frame
            canvas.save()
            canvas.translate(x + tx, y)
            canvas.drawRenderNode(e.node)
            canvas.restore()
        }
        return true
    }

    private companion object {
        /** No recording is wider than this; wider blocks are split into tiles. */
        const val TILE_W = 1500f
        /** Beyond this (an hour-long clip zoomed right in) the block is simply drawn each frame. */
        const val MAX_W = 200_000f
    }

    fun endFrame() {
        frame++
        if (frame % 120L == 0L) map.entries.removeAll { (_, e) -> (frame - e.usedAt > 240).also { if (it) e.node.discardDisplayList() } }
    }
}

private fun DrawScope.drawClipBlock(
    g: Geo, paints: Paints, path: Path, icons: ClipIcons, a: MediaAsset?, peaks: ByteArray?, video: Boolean, alternate: Boolean, inUs: Long, durationUs: Long,
    x0: Float, x1: Float, laneTop: Float, laneH: Float, usPerPx: Double, lifted: Boolean,
    snd: com.graden.editor.Snd = com.graden.editor.Snd(), norm: Float = 1f, speed: Float = 1f,
    /** How far its reversed copy has got, while one is being made. */
    reversing: Float? = null,
    /** The clip's picture at a source time, for the filmstrip; null draws a plain block. */
    thumb: ((Long) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
) {
    // The waveform is drawn as it will be heard: scaled by volume, shaped by the fades, flat when muted.
    val level = if (snd.muted) 0f else snd.volume * norm
    val env: (Double) -> Float = { us ->
        // [us] is source time; fades are measured on the timeline, where the source runs at the clip's speed.
        val local = (us - inUs) / speed
        var e = level
        if (snd.fadeInUs > 0) e *= (local / snd.fadeInUs).toFloat().coerceIn(0f, 1f)
        if (snd.fadeOutUs > 0) e *= ((durationUs - local) / snd.fadeOutUs).toFloat().coerceIn(0f, 1f)
        e
    }
    val top = laneTop
    val h = laneH
    val w = x1 - x0
    val image = a?.isImage == true
    val fill = if (image) PictureFill else if (video) VideoFill else if (alternate) AudioAltFill else AudioFill
    if (w < 3 * g.dp) {
        drawRect(fill, Offset(x0, top), Size(max(w, g.dp), h))
        return
    }
    val vx0 = max(x0, -g.corner)
    val vx1 = min(x1, size.width + g.corner)
    val r = CornerRadius(min(g.corner, w / 2))
    val split = if (video && !image) top + h * 0.56f else top + h
    path.rewind()
    path.addRoundRect(RoundRect(Rect(x0, top, x1, top + h), r))
    clipPath(path) {
        drawRect(if (lifted) Footprint else fill, Offset(vx0, top), Size(vx1 - vx0, h))
        // Filmstrip: the clip's own pictures side by side along its picture part, in the file's shape.
        if (!lifted && thumb != null && a != null && a.width > 0 && a.height > 0) {
            val stripH = split - top
            val tw = (stripH * a.width / a.height.toFloat()).coerceAtLeast(8 * g.dp)
            var i = ((vx0 - x0) / tw).toInt().coerceAtLeast(0)
            while (true) {
                val tx = x0 + i * tw
                if (tx > vx1) break
                // Quarter-second steps, so neighbouring clips and zoom levels share pictures.
                val us = (inUs + ((i * tw + tw / 2) * usPerPx * speed).toLong()) / 250_000L * 250_000L
                thumb(us)?.let { img ->
                    drawImage(
                        img, IntOffset.Zero, IntSize(img.width, img.height),
                        IntOffset(tx.roundToInt(), top.roundToInt()), IntSize(kotlin.math.ceil(tw).toInt() + 1, stripH.roundToInt()),
                    )
                }
                i++
            }
            // A soft shade along the top keeps the name readable over the pictures; a hairline marks the sound part.
            val shade = min(20 * g.dp, stripH)
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), top, top + shade), Offset(vx0, top), Size(vx1 - vx0, shade))
        }
        if (lifted) {
            // Picked up: only a faint footprint stays behind until the clip is dropped.
        } else if (video && split < top + h) {
            drawRect(VideoAudioHalf, Offset(vx0, split), Size(vx1 - vx0, top + h - split))
            val mid = (split + top + h) / 2
            if (peaks != null) drawWave(peaks, inUs, x0, vx0, vx1, mid, (top + h - split) / 2 - 2 * g.dp, usPerPx * speed, 2 * g.dp, VideoWave, env)
            // No waveform yet, or a file with no sound: a flat line, so the sound half never just vanishes.
            else drawLine(VideoWave.copy(alpha = if (a?.hasAudio == true) 0.5f else 0.28f), Offset(vx0, mid), Offset(vx1, mid), g.dp)
        } else if (!video && peaks != null) {
            drawWave(peaks, inUs, x0, vx0, vx1, top + h / 2, h / 2 - 3 * g.dp, usPerPx * speed, 2 * g.dp, AudioWave.copy(alpha = 0.55f), env)
        }
        // Fade ramps, as lines across the sound from silence up to full.
        if (!lifted && (snd.fadeInUs > 0 || snd.fadeOutUs > 0)) {
            val soundTop = if (video) split else top
            val ink = Color.White.copy(alpha = 0.75f)
            if (snd.fadeInUs > 0) drawLine(ink, Offset(x0, top + h), Offset(x0 + (snd.fadeInUs / usPerPx).toFloat(), soundTop), 1.3f * g.dp)
            if (snd.fadeOutUs > 0) drawLine(ink, Offset(x1, top + h), Offset(x1 - (snd.fadeOutUs / usPerPx).toFloat(), soundTop), 1.3f * g.dp)
        }
        // A bright cap on the leading edge: this is where the clip really begins.
        if (!lifted) drawRect(if (image) PictureCap else if (video) VideoWave else AudioWave, Offset(x0, top), Size(3.5f * g.dp, h))
        if (w > 44 * g.dp && h > 24 * g.dp && a != null && !lifted) drawIntoCanvas { c ->
            // The name sits at the clip's start and stays there, small and in a tint of the clip's own colour,
            // so it scrolls away with the clip instead of hanging on screen. The length sits at the clip's end.
            val ink = if (image) PictureInk else if (video) VideoInk else if (alternate) AudioAltInk else AudioInk
            val tx = x0 + 9 * g.dp
            val baseline = top + 14 * g.dp
            val glyph = if (image) icons.image else if (video) icons.film else icons.music
            translate(tx, baseline - 9 * g.dp) {
                with(glyph) { draw(Size(10.5f * g.dp, 10.5f * g.dp), colorFilter = ColorFilter.tint(ink)) }
            }
            paints.label.color = ink.toArgb()
            // A changed speed, and playing backwards, show as badges before the name.
            var nameX = tx + 15 * g.dp
            fun badge(tag: String) {
                val tw = paints.label.measureText(tag)
                drawRoundRect(Palette.Accent, Offset(nameX - 2 * g.dp, baseline - 9.5f * g.dp), Size(tw + 8 * g.dp, 12.5f * g.dp), CornerRadius(4 * g.dp))
                paints.label.color = Color.Black.toArgb()
                c.nativeCanvas.drawText(tag, nameX + 2 * g.dp, baseline, paints.label)
                paints.label.color = ink.toArgb()
                nameX += tw + 12 * g.dp
            }
            if (a.isReversed) badge("REV")
            if (speed != 1f) badge(if (speed >= 1f) "%.1f×".format(speed) else "%.2f×".format(speed))
            c.nativeCanvas.drawText(a.name, nameX, baseline, paints.label)
            if (w > 110 * g.dp && h > 38 * g.dp) {
                paints.duration.color = ink.copy(alpha = 0.7f).toArgb()
                c.nativeCanvas.drawText("%.1fs".format(durationUs / 1e6), x1 - 7 * g.dp, (if (video) split else top + h) - 5 * g.dp, paints.duration)
            }
        }
    }
    // While the reversed copy is being made: the clip dims, and a bar fills along its bottom.
    if (reversing != null && !lifted) {
        clipPath(path) {
            drawRect(Color.Black.copy(alpha = 0.45f), Offset(vx0, top), Size(vx1 - vx0, h))
            drawRect(Color.White.copy(alpha = 0.18f), Offset(x0, top + h - 3 * g.dp), Size(w, 3 * g.dp))
            drawRect(Palette.Accent, Offset(x0, top + h - 3 * g.dp), Size(w * reversing.coerceIn(0f, 1f), 3 * g.dp))
        }
        if (w > 70 * g.dp) drawIntoCanvas { c ->
            paints.label.color = Color.White.toArgb()
            val text = "Reversing ${(reversing * 100).toInt()}%"
            val tw = paints.label.measureText(text)
            val lo = x0 + tw / 2 + 6 * g.dp
            val hi = x1 - tw / 2 - 6 * g.dp
            val cx = if (lo <= hi) ((max(x0, 0f) + min(x1, size.width)) / 2).coerceIn(lo, hi) else (x0 + x1) / 2
            if (lo <= hi) c.nativeCanvas.drawText(text, cx - tw / 2, top + h / 2 + 4 * g.dp, paints.label)
        }
    }
    drawRoundRect(if (lifted) FootprintEdge else if (image) PictureEdge else if (video) VideoEdge else if (alternate) AudioAltEdge else AudioEdge, Offset(x0, top), Size(w, h), r, style = Stroke(g.dp))
}

/**
 * A trim handle: a white tab on the outside of the clip edge with rounded outer corners and a
 * chevron pointing the way it pulls. It is taller than the lane, casts a soft shadow, and turns
 * orange and widens while it is being dragged.
 */
private val captionText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }

/** A caption on its layer: a rounded bar with its words, cut off at its end. */
private fun DrawScope.drawCaptionBlock(g: Geo, cap: com.graden.editor.Caption, x0: Float, x1: Float, top: Float, h: Float, lifted: Boolean) {
    if (x1 - x0 < 1f) return
    // Captions from the sound violet (lyrics and music blue); texts added by hand teal, marked T.
    val base = when (cap.kind) {
        com.graden.editor.Caption.TEXT -> Color(0xFF1F6F6A)
        com.graden.editor.Cue.SPEECH -> Color(0xFF5A4797)
        else -> Color(0xFF2F5E86)
    }
    drawRoundRect(if (lifted) base.copy(alpha = 0.7f) else base, Offset(x0, top), Size(x1 - x0, h), CornerRadius(5 * g.dp))
    drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(x0, top), Size(x1 - x0, h), CornerRadius(5 * g.dp), style = Stroke(1 * g.dp))
    if (x1 - x0 < 14 * g.dp) return
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        captionText.textSize = 11.5f * g.dp
        nc.save()
        nc.clipRect(x0 + 5 * g.dp, top, x1 - 4 * g.dp, top + h)
        nc.drawText((if (cap.kind == com.graden.editor.Caption.TEXT) "T  " else "") + cap.text.replace('\n', ' '), x0 + 6 * g.dp, top + h / 2 + 4 * g.dp, captionText)
        nc.restore()
    }
}

private fun DrawScope.drawTrimHandle(g: Geo, path: Path, left: Boolean, edgeX: Float, top: Float, h: Float, active: Boolean) {
    val w = g.handleW * if (active) 1.25f else 1f
    val out = 4 * g.dp
    val r = CornerRadius(9 * g.dp)
    val zero = CornerRadius.Zero
    val rect = if (left) Rect(edgeX - w, top - out, edgeX, top + h + out) else Rect(edgeX, top - out, edgeX + w, top + h + out)
    fun shape(rc: Rect) {
        path.rewind()
        path.addRoundRect(
            if (left) RoundRect(rc, topLeft = r, bottomLeft = r, topRight = zero, bottomRight = zero)
            else RoundRect(rc, topLeft = zero, bottomLeft = zero, topRight = r, bottomRight = r)
        )
    }
    shape(rect.translate(0f, 2 * g.dp).inflate(g.dp))
    drawPath(path, Color.Black.copy(alpha = 0.35f))
    shape(rect)
    drawPath(path, if (active) Palette.Accent else Color.White)
    val cx = rect.center.x
    val cy = rect.center.y
    val a = 3 * g.dp * if (left) 1f else -1f
    val b = min(7 * g.dp, h / 5)
    val ink = Color(0xFF15161C)
    drawLine(ink, Offset(cx + a, cy - b), Offset(cx - a, cy), 2.4f * g.dp, cap = StrokeCap.Round)
    drawLine(ink, Offset(cx - a, cy), Offset(cx + a, cy + b), 2.4f * g.dp, cap = StrokeCap.Round)
}

/**
 * Where the transition marker at the cut before [c] is drawn (and tapped), in lane space. With a
 * transition set it is a bar spanning the transition, half on each clip, so a longer transition
 * makes a longer bar. Without one it is a small "+" sitting on the top of the seam; it shrinks
 * with the clips either side and is not shown at all until they are big enough to aim at.
 */
private fun junctionBox(g: Geo, c: Clip, prev: Clip, laneTop: Float, cutX: Float, usPerPx: Double): Rect? {
    val blockTop = laneTop + 3 * g.dp
    val tr = c.transition
    if (tr != null) {
        val half = max(10 * g.dp, (tr.durationUs / 2 / usPerPx).toFloat())
        return Rect(cutX - half, blockTop - 2 * g.dp, cutX + half, blockTop + 15 * g.dp)
    }
    val s = min(19 * g.dp, (min(prev.durationUs, c.durationUs) / usPerPx).toFloat() * 0.3f)
    if (s < 9 * g.dp) return null
    return Rect(cutX - s / 2, blockTop - s * 0.3f, cutX + s / 2, blockTop + s * 0.7f)
}

private fun DrawScope.drawJunction(g: Geo, paints: Paints, box: Rect, tr: Fx?) {
    val r = CornerRadius(min(5 * g.dp, box.height / 3))
    if (tr == null) {
        // A see-through box with a plus.
        drawRoundRect(Color(0x66101116), box.topLeft, box.size, r)
        drawRoundRect(Color.White.copy(alpha = 0.16f), box.topLeft, box.size, r)
        drawRoundRect(Color.White.copy(alpha = 0.55f), box.topLeft, box.size, r, style = Stroke(g.dp))
        val arm = box.width * 0.24f
        val c = box.center
        drawLine(Color.White.copy(alpha = 0.9f), Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), 1.5f * g.dp, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.9f), Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), 1.5f * g.dp, cap = StrokeCap.Round)
        return
    }
    // The transition itself: a grey bar across the cut, with two overlapping frames as its sign and its length.
    drawRoundRect(Color(0xF26C707C), box.topLeft, box.size, r)
    drawRoundRect(Color.White.copy(alpha = 0.35f), box.topLeft, box.size, r, style = Stroke(g.dp))
    val showText = box.width > 52 * g.dp
    val ix = if (showText) box.left + 6 * g.dp else box.center.x - 5.5f * g.dp
    val iy = box.center.y - 4 * g.dp
    drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(ix, iy), Size(7 * g.dp, 6 * g.dp), CornerRadius(1.5f * g.dp), style = Stroke(1.2f * g.dp))
    drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(ix + 4 * g.dp, iy + 2.5f * g.dp), Size(7 * g.dp, 6 * g.dp), CornerRadius(1.5f * g.dp), style = Stroke(1.2f * g.dp))
    if (showText) drawIntoCanvas {
        it.nativeCanvas.drawText(secondsLabel(tr.durationUs), box.center.x + 8 * g.dp, box.center.y + 3.2f * g.dp, paints.fx)
    }
}

/** A thin light bar along the bottom of a clip for as long as its opening or closing animation runs. */
private fun DrawScope.drawAnimMarks(g: Geo, c: Clip, x0: Float, x1: Float, top: Float, h: Float, usPerPx: Double) {
    val y = top + h - 5 * g.dp
    // Cinema's focus points, as small diamonds where each starts.
    c.cinema?.takeIf { it.on }?.keys?.forEach { k ->
        val kx = x0 + (((k.us - c.inUs) / c.speed) / usPerPx).toFloat()
        if (kx in x0..x1) {
            val r = 3.5f * g.dp; val ky = top + 6 * g.dp
            val p = Path().apply { moveTo(kx, ky - r); lineTo(kx + r, ky); lineTo(kx, ky + r); lineTo(kx - r, ky); close() }
            drawPath(p, Color(0xFFF2C14E))
        }
    }
    c.animIn?.let { drawRoundRect(Color.White.copy(alpha = 0.65f), Offset(x0 + 2 * g.dp, y), Size(max(6 * g.dp, (it.durationUs / usPerPx).toFloat()), 3 * g.dp), CornerRadius(1.5f * g.dp)) }
    c.animOut?.let {
        val w = max(6 * g.dp, (it.durationUs / usPerPx).toFloat())
        drawRoundRect(Color.White.copy(alpha = 0.65f), Offset(x1 - 2 * g.dp - w, y), Size(w, 3 * g.dp), CornerRadius(1.5f * g.dp))
    }
}

/** Id of the clip whose cut marker is under [p] (in lane space), ignoring locked tracks. */
private fun hitJunction(vm: EditorViewModel, g: Geo, lanes: List<Lane>, cx: Float, p: Offset): Long? {
    val usPerPx = 1e6 / vm.pxPerSec
    val slack = 7 * g.dp
    for (lane in lanes) {
        if (!lane.video || lane.key in vm.lockedTracks || p.y < lane.top - slack || p.y > lane.bottom) continue
        val track = vm.tracks.firstOrNull { it.id == lane.key } ?: continue
        for (c in track.clips) {
            val prev = track.clips.firstOrNull { it.endUs == c.startUs } ?: continue
            val box = junctionBox(g, c, prev, lane.top, cx + ((c.startUs - vm.playheadUs) / usPerPx).toFloat(), usPerPx) ?: continue
            if (box.inflate(slack).contains(p)) return c.id
        }
    }
    return null
}

/** Mirrored bars around [yMid], one every [step] pixels, each the loudest peak in the time it covers. */
private fun DrawScope.drawWave(
    peaks: ByteArray, inUs: Long, x0: Float, fromX: Float, toX: Float, yMid: Float, halfH: Float, usPerPx: Double, step: Float, color: Color, env: (Double) -> Float = { 1f },
) {
    if (halfH < 2f) return
    val perUs = WaveformCache.PEAKS_PER_SEC / 1e6
    var x = max(fromX, 0f)
    val end = min(toX, size.width)
    // All bars go into one array and one draw call: hundreds of separate lines every frame were the
    // single largest cost of drawing the timeline while it scrolls.
    val needed = (((end - x) / step).toInt() + 2) * 4
    if (waveBuf.size < needed) waveBuf = FloatArray(needed * 2)
    val buf = waveBuf
    var n = 0
    while (x < end) {
        val us = inUs + (x - x0) * usPerPx
        val i0 = (us * perUs).toInt()
        if (i0 >= peaks.size) break
        val i1 = min(peaks.size, max(i0 + 1, ((us + step * usPerPx) * perUs).toInt()))
        val stride = max(1, (i1 - i0) / 16)
        var m = 0
        var i = max(0, i0)
        while (i < i1) {
            val v = peaks[i].toInt() and 0xFF
            if (v > m) m = v
            i += stride
        }
        val e = env(us)
        val half = max(0.6f, halfH * (m / 255f * e).coerceAtMost(1f))
        if (n + 4 > buf.size) break
        buf[n] = x; buf[n + 1] = yMid - half; buf[n + 2] = x; buf[n + 3] = yMid + half
        n += 4
        x += step
    }
    if (n == 0) return
    wavePaint.color = color.toArgb()
    wavePaint.strokeWidth = step * 0.55f
    drawIntoCanvas { it.nativeCanvas.drawLines(buf, 0, n, wavePaint) }
}

private var waveBuf = FloatArray(4096)
private val wavePaint = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.BUTT }

private fun rulerLabel(us: Long): String {
    val s = us / 1_000_000L
    val h = s / 3600
    val m = (s / 60) % 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** Id of the clip under [p], ignoring locked tracks. */
private fun hitClip(vm: EditorViewModel, lanes: List<Lane>, cx: Float, p: Offset): Long? {
    val lane = lanes.firstOrNull { p.y >= it.top && p.y <= it.bottom } ?: return null
    if (lane.key in vm.lockedTracks) return null
    val us = vm.playheadUs + ((p.x - cx) / vm.pxPerSec * 1e6f).toLong()
    return vm.tracks.firstOrNull { it.id == lane.key }?.clipAt(us)?.id
}

/** -1 for the left trim handle of the selected clip, 1 for the right, 0 for neither. */
private fun hitHandle(vm: EditorViewModel, g: Geo, lanes: List<Lane>, cx: Float, p: Offset): Int {
    val sel = vm.selectedClipId?.let(vm::locate) ?: return 0
    val lane = lanes.firstOrNull { it.key == sel.trackId } ?: return 0
    if (p.y < lane.top - 10 * g.dp || p.y > lane.bottom + 10 * g.dp) return 0
    val usPerPx = 1e6 / vm.pxPerSec
    val x0 = cx + ((sel.startUs - vm.playheadUs) / usPerPx).toFloat()
    val x1 = cx + ((sel.startUs + sel.clip.durationUs - vm.playheadUs) / usPerPx).toFloat()
    val slack = 14 * g.dp
    val inner = min(slack, (x1 - x0) / 3)
    if (p.x >= x0 - g.handleW - slack && p.x <= x0 + inner) return -1
    if (p.x >= x1 - inner && p.x <= x1 + g.handleW + slack) return 1
    return 0
}

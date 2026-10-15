package com.graden.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import com.graden.editor.Clip
import com.graden.editor.EditorViewModel
import com.graden.editor.Tf
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Geometry shared by the preview and the canvas gestures. A clip's picture first sits in its base
 * rectangle (fitted to, or covering, the canvas, centred), then is cropped, then scaled and turned
 * about the canvas centre and moved by its [Tf].
 */
object CanvasGeo {
    fun base(w: Float, h: Float, pictureAspect: Float, fill: Boolean): Rect {
        val ca = w / h
        val wide = pictureAspect > ca
        val bw = if (wide != fill) w else h * pictureAspect
        val bh = bw / pictureAspect
        return Rect((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
    }

    fun crop(base: Rect, tf: Tf) = Rect(
        base.left + tf.cropL * base.width, base.top + tf.cropT * base.height,
        base.right - tf.cropR * base.width, base.bottom - tf.cropB * base.height,
    )

    /** A point of the untransformed canvas to where it is shown. */
    fun toScreen(q: Offset, w: Float, h: Float, tf: Tf, scale: Float): Offset {
        val c = Offset(w / 2f, h / 2f)
        val r = Math.toRadians(tf.rot.toDouble())
        val d = (q - c) * scale
        return c + Offset((d.x * cos(r) - d.y * sin(r)).toFloat(), (d.x * sin(r) + d.y * cos(r)).toFloat()) + Offset(tf.x * w, tf.y * h)
    }

    /** The inverse of [toScreen]. */
    fun toLocal(p: Offset, w: Float, h: Float, tf: Tf, scale: Float): Offset {
        val c = Offset(w / 2f, h / 2f)
        val r = Math.toRadians(-tf.rot.toDouble())
        val d = p - c - Offset(tf.x * w, tf.y * h)
        return c + Offset((d.x * cos(r) - d.y * sin(r)).toFloat(), (d.x * sin(r) + d.y * cos(r)).toFloat()) / scale
    }
}

/** Clips a lane's view to the clip's crop. */
class CropShape(private val pictureAspect: Float, private val fill: Boolean, private val tf: Tf) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(CanvasGeo.crop(CanvasGeo.base(size.width, size.height, pictureAspect, fill), tf))
}

/**
 * Touch layer over the canvas. Tap a picture to select it (the highest layer under the finger wins);
 * drag to move it, pinch to resize, twist to turn. A selected picture shows its frame, and buttons to
 * fit it back or fill the canvas. Moving snaps the centre to the canvas centre, and turning snaps to
 * square, with a light tick. Cropping is on its own page, from the Edit tools.
 */
@Composable
fun BoxScope.CanvasOverlay(vm: EditorViewModel) {
    com.graden.editor.RecomposeProbe.hit("CanvasOverlay")
    val view = LocalView.current
    // Where the snap guides are drawn (canvas pixels), or NaN when not snapped.
    var guideX by remember { mutableStateOf(Float.NaN) }
    var guideY by remember { mutableStateOf(Float.NaN) }
    val fill = vm.meta.scaling == "Fill"
    fun aspectOf(c: Clip) = vm.assets[c.assetId]?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 9f)
    fun scaleOf(c: Clip) = c.tf.scale * vm.centreScaleOf(c)

    Box(
        Modifier.matchParentSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                fun contains(c: Clip, p: Offset): Boolean {
                    val local = CanvasGeo.toLocal(p, w, h, c.tf, scaleOf(c))
                    return CanvasGeo.crop(CanvasGeo.base(w, h, aspectOf(c), fill), c.tf).contains(local)
                }
                val under = vm.canvasStack().firstOrNull { contains(it.second, down.position) }?.second
                var target = vm.canvasClip()?.clip
                if (target == null || !contains(target, down.position)) target = under
                var started = false
                var snappedX = false
                var snappedY = false
                var snappedRot = false
                // The unsnapped values follow the fingers, so a snap can be pulled out of.
                var rawX = target?.tf?.x ?: 0f
                var rawY = target?.tf?.y ?: 0f
                var rawRot = target?.tf?.rot ?: 0f
                // A tap that one of the buttons on the canvas has taken is not a tap on the picture.
                var takenByButton = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.count { it.pressed }
                    if (pressed == 0) {
                        takenByButton = ev.changes.any { it.isConsumed }
                        break
                    }
                    val t = target ?: continue
                    if (!started) {
                        val moved = ev.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }
                        if (!moved && pressed < 2) continue
                        started = true
                        vm.selectOnCanvas(t.id)
                        vm.canvasEditStart()
                    }
                    val cur = vm.locate(t.id)?.clip ?: break
                    val pan = ev.calculatePan()
                    rawX += pan.x / w
                    rawY += pan.y / h
                    rawRot += if (pressed >= 2) ev.calculateRotation() else 0f
                    val zoom = if (pressed >= 2) ev.calculateZoom() else 1f
                    val square = (rawRot / 90f).roundToInt() * 90f
                    val snapR = abs(rawRot - square) < 4f
                    val rot = if (snapR) square else rawRot
                    val scale = (cur.tf.scale * zoom).coerceIn(0.05f, 20f)
                    // Snapping goes by what is visible: the cropped part, which may not be the middle of the picture.
                    val ss = scale * vm.centreScaleOf(cur)
                    val base = CanvasGeo.base(w, h, aspectOf(cur), fill)
                    val kept = CanvasGeo.crop(base, cur.tf)
                    val d = kept.center - base.center
                    val r = Math.toRadians(rot.toDouble())
                    val offX = ((d.x * cos(r) - d.y * sin(r)) * ss).toFloat()
                    val offY = ((d.x * sin(r) + d.y * cos(r)) * ss).toFloat()
                    // Half the visible size on screen, for snapping edges to the canvas edges when square to it.
                    val sideways = ((rot / 90f).roundToInt() % 2 + 2) % 2 == 1
                    val upright = snapR
                    val halfW = (if (sideways) kept.height else kept.width) * ss / 2f
                    val halfH = (if (sideways) kept.width else kept.height) * ss / 2f
                    val reach = 8.dp.toPx()
                    // Candidate positions of the visible centre, in canvas pixels from the canvas centre.
                    val cx = rawX * w + offX
                    val cy = rawY * h + offY
                    var gx = Float.NaN
                    var gy = Float.NaN
                    var nx = cx
                    var ny = cy
                    val xTargets = buildList {
                        add(0f to w / 2)
                        if (upright) {
                            add((-w / 2 + halfW) to 0f)
                            add((w / 2 - halfW) to w)
                        }
                    }
                    val yTargets = buildList {
                        add(0f to h / 2)
                        if (upright) {
                            add((-h / 2 + halfH) to 0f)
                            add((h / 2 - halfH) to h)
                        }
                    }
                    xTargets.minByOrNull { abs(cx - it.first) }?.let { (pos, line) -> if (abs(cx - pos) < reach) { nx = pos; gx = line } }
                    yTargets.minByOrNull { abs(cy - it.first) }?.let { (pos, line) -> if (abs(cy - pos) < reach) { ny = pos; gy = line } }
                    val snapX = !gx.isNaN()
                    val snapY = !gy.isNaN()
                    if ((snapX && !snappedX) || (snapY && !snappedY) || (snapR && !snappedRot)) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    snappedX = snapX
                    snappedY = snapY
                    snappedRot = snapR
                    guideX = gx
                    guideY = gy
                    vm.setTf(cur.id, cur.tf.copy(x = (nx - offX) / w, y = (ny - offY) / h, scale = scale, rot = rot))
                    ev.changes.forEach { it.consume() }
                }
                guideX = Float.NaN
                guideY = Float.NaN
                if (started) vm.canvasEditEnd()
                else if (!takenByButton) vm.selectOnCanvas(under?.id)
            }
        }
    ) {
        // Derived: the overlay redraws when the clip under the playhead changes, not on every frame of a scrub.
        val sel by remember { derivedStateOf { if (vm.isPlaying) null else vm.canvasClip()?.clip } }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            val gx = guideX.coerceIn(1f, w - 1f)
            val gy = guideY.coerceIn(1f, h - 1f)
            if (!guideX.isNaN()) drawLine(Palette.Accent, Offset(gx, 0f), Offset(gx, h), 1.5.dp.toPx(), pathEffect = dash)
            if (!guideY.isNaN()) drawLine(Palette.Accent, Offset(0f, gy), Offset(w, gy), 1.5.dp.toPx(), pathEffect = dash)
            val c = sel ?: return@Canvas
            val s = scaleOf(c)
            val r = CanvasGeo.crop(CanvasGeo.base(w, h, aspectOf(c), fill), c.tf)
            withTransform({
                translate(c.tf.x * w, c.tf.y * h)
                rotate(c.tf.rot, Offset(w / 2, h / 2))
                scale(s, s, Offset(w / 2, h / 2))
            }) {
                val line = 1.5.dp.toPx() / s
                drawRect(Color.Black.copy(alpha = 0.4f), r.topLeft, r.size, style = Stroke(line * 2.2f))
                drawRect(Color.White, r.topLeft, r.size, style = Stroke(line))
                val dot = 4.5.dp.toPx() / s
                for (p in listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)) {
                    drawCircle(Color.White, dot, p)
                    drawCircle(Palette.Accent, dot * 0.55f, p)
                }
            }
        }
        // Down the right edge, below the playback meter and out of the way of the picture.
        if (sel != null) Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End,
        ) {
            Pill("Fit") { vm.fitCanvas() }
            Pill("Fill") { vm.fillCanvas() }
        }
    }
}

@Composable
private fun Pill(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = if (accent) Color.Black else Palette.Text, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (accent) Palette.Accent else Color(0xE616171D))
            .border(1.dp, if (accent) Palette.Accent else Palette.Line, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

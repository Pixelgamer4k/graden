package com.graden.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.EditorViewModel
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private class Ratio(val label: String, val w: Float, val h: Float)

private enum class Hold { NONE, MOVE, L, T, R, B, TL, TR, BL, BR }

/**
 * Cropping a picture on a page of its own. The kept part is framed over the full picture with the
 * rest dimmed. Drag a corner or an edge, or drag inside to move the frame. A ratio keeps the frame
 * to that shape (corners only then). Turning and mirroring apply to the clip as well. The tools for
 * keying, background removal and masks have their places here, for when they are built.
 */
@Composable
fun CropPage(vm: EditorViewModel, soon: (String) -> Unit) {
    val session = vm.cropSession ?: return
    val a = session.asset
    BackHandler { vm.closeCrop() }
    val pictureAspect = a.width.toFloat() / a.height.coerceAtLeast(1)
    val ratios = remember(vm.meta.aspect, pictureAspect) {
        listOf(
            Ratio("Free", 0f, 0f), Ratio("Original", pictureAspect, 1f), Ratio("Canvas", vm.meta.aspect, 1f),
            Ratio("1:1", 1f, 1f), Ratio("16:9", 16f, 9f), Ratio("9:16", 9f, 16f), Ratio("4:5", 4f, 5f),
            Ratio("4:3", 4f, 3f), Ratio("3:4", 3f, 4f), Ratio("21:9", 21f, 9f), Ratio("2:1", 2f, 1f),
        )
    }
    var ratio by remember { mutableStateOf(ratios.first()) }
    val tf = vm.cropTf
    val quarter = ((tf.rot / 90f).roundToInt() % 4 + 4) % 4
    val sideways = quarter % 2 == 1

    /** The largest frame of [r] (as seen on screen) that fits the picture, centred. */
    fun applyRatio(r: Ratio) {
        ratio = r
        if (r.w == 0f) return
        // A quarter turn swaps what "wide" means for the source.
        val seen = r.w / r.h
        val target = if (sideways) 1f / seen else seen
        val wFrac: Float
        val hFrac: Float
        if (target > pictureAspect) {
            wFrac = 1f
            hFrac = pictureAspect / target
        } else {
            hFrac = 1f
            wFrac = target / pictureAspect
        }
        vm.cropTf = tf.copy(cropL = (1f - wFrac) / 2, cropR = (1f - wFrac) / 2, cropT = (1f - hFrac) / 2, cropB = (1f - hFrac) / 2)
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) {}.systemBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.X, "Cancel", tint = Palette.Text, modifier = Modifier.size(44.dp).clip(CircleShape).clickable { vm.closeCrop() }.padding(11.dp))
            Text("Crop", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Icon(Lucide.Check, "Apply", tint = Palette.Accent, modifier = Modifier.size(44.dp).clip(CircleShape).clickable { vm.applyCrop() }.padding(10.dp))
        }

        // The picture, turned and mirrored as it will be, with the crop frame over it.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
            val shownAspect = if (sideways) 1f / pictureAspect else pictureAspect
            val boxW = min(maxWidth.value, maxHeight.value * shownAspect)
            val boxH = boxW / shownAspect
            // The unturned picture's size on screen.
            val pw = if (sideways) boxH else boxW
            val ph = if (sideways) boxW else boxH
            Box(
                Modifier.size(pw.dp, ph.dp).graphicsLayer {
                    rotationZ = quarter * 90f
                    scaleX = if (tf.flipH) -1f else 1f
                    scaleY = if (tf.flipV) -1f else 1f
                }
            ) {
                val frame = vm.cropFrame
                if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                else Box(Modifier.fillMaxSize().background(Color(0xFF15161B)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = Palette.Accent, strokeWidth = 2.dp)
                }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(quarter, tf.flipH, tf.flipV, ratio) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val t0 = vm.cropTf
                            val reach = 26.dp.toPx()
                            // Touches arrive in this box's own, turned and mirrored, coordinates already.
                            val p = down.position
                            val l = t0.cropL * w
                            val tp = t0.cropT * h
                            val r = w - t0.cropR * w
                            val b = h - t0.cropB * h
                            val nl = abs(p.x - l) < reach
                            val nr = abs(p.x - r) < reach
                            val nt = abs(p.y - tp) < reach
                            val nb = abs(p.y - b) < reach
                            val locked = ratio.w != 0f
                            var hold = when {
                                nl && nt -> Hold.TL
                                nr && nt -> Hold.TR
                                nl && nb -> Hold.BL
                                nr && nb -> Hold.BR
                                !locked && nl -> Hold.L
                                !locked && nr -> Hold.R
                                !locked && nt -> Hold.T
                                !locked && nb -> Hold.B
                                p.x in l..r && p.y in tp..b -> Hold.MOVE
                                else -> Hold.NONE
                            }
                            if (hold == Hold.NONE) return@awaitEachGesture
                            var L = l
                            var T = tp
                            var R = r
                            var Bm = b
                            val min = 36.dp.toPx()
                            val aspectPx = if (locked) (R - L) / (Bm - T) else 0f
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed } ?: break
                                val d = ch.position - ch.previousPosition
                                ch.consume()
                                when (hold) {
                                    Hold.MOVE -> {
                                        val dx = d.x.coerceIn(-L, w - R)
                                        val dy = d.y.coerceIn(-T, h - Bm)
                                        L += dx; R += dx; T += dy; Bm += dy
                                    }
                                    else -> {
                                        if (hold in setOf(Hold.L, Hold.TL, Hold.BL)) L = (L + d.x).coerceIn(0f, R - min)
                                        if (hold in setOf(Hold.R, Hold.TR, Hold.BR)) R = (R + d.x).coerceIn(L + min, w)
                                        if (hold in setOf(Hold.T, Hold.TL, Hold.TR)) T = (T + d.y).coerceIn(0f, Bm - min)
                                        if (hold in setOf(Hold.B, Hold.BL, Hold.BR)) Bm = (Bm + d.y).coerceIn(T + min, h)
                                        if (locked) {
                                            // Keep the shape: the height follows the width, from the corner opposite the finger.
                                            var nh = (R - L) / aspectPx
                                            val top = hold == Hold.TL || hold == Hold.TR
                                            val room = if (top) Bm else h - T
                                            if (nh > room) {
                                                nh = room
                                                val nw = nh * aspectPx
                                                if (hold == Hold.TL || hold == Hold.BL) L = R - nw else R = L + nw
                                            }
                                            if (top) T = Bm - nh else Bm = T + nh
                                        }
                                    }
                                }
                                vm.cropTf = vm.cropTf.copy(cropL = L / w, cropT = T / h, cropR = 1f - R / w, cropB = 1f - Bm / h)
                            }
                            hold = Hold.NONE
                        }
                    }
                ) {
                    val w = size.width
                    val h = size.height
                    val k = vm.cropTf
                    val rect = Rect(k.cropL * w, k.cropT * h, w - k.cropR * w, h - k.cropB * h)
                    val outside = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, w, h))
                        addRect(rect)
                    }
                    drawPath(outside, Color.Black.copy(alpha = 0.6f))
                    for (i in 1..2) {
                        val gx = rect.left + rect.width * i / 3
                        val gy = rect.top + rect.height * i / 3
                        drawLine(Color.White.copy(alpha = 0.32f), Offset(gx, rect.top), Offset(gx, rect.bottom), 1.dp.toPx())
                        drawLine(Color.White.copy(alpha = 0.32f), Offset(rect.left, gy), Offset(rect.right, gy), 1.dp.toPx())
                    }
                    drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(1.5.dp.toPx()))
                    val arm = min(20.dp.toPx(), min(rect.width, rect.height) / 3)
                    val thick = 3.5.dp.toPx()
                    val o = thick / 2
                    for ((cx, cy, dx, dy) in listOf(
                        listOf(rect.left - o, rect.top - o, 1f, 1f), listOf(rect.right + o, rect.top - o, -1f, 1f),
                        listOf(rect.left - o, rect.bottom + o, 1f, -1f), listOf(rect.right + o, rect.bottom + o, -1f, -1f),
                    )) {
                        drawLine(Color.White, Offset(cx, cy), Offset(cx + arm * dx, cy), thick)
                        drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + arm * dy), thick)
                    }
                    if (ratio.w == 0f) {
                        val mid = 14.dp.toPx()
                        for ((m0, m1) in listOf(
                            Offset(rect.center.x - mid, rect.top - o) to Offset(rect.center.x + mid, rect.top - o),
                            Offset(rect.center.x - mid, rect.bottom + o) to Offset(rect.center.x + mid, rect.bottom + o),
                            Offset(rect.left - o, rect.center.y - mid) to Offset(rect.left - o, rect.center.y + mid),
                            Offset(rect.right + o, rect.center.y - mid) to Offset(rect.right + o, rect.center.y + mid),
                        )) drawLine(Color.White, m0, m1, thick)
                    }
                }
            }
            // Size of what is kept, in the source's pixels.
            val keptW = (a.width * (1f - tf.cropL - tf.cropR)).roundToInt()
            val keptH = (a.height * (1f - tf.cropT - tf.cropB)).roundToInt()
            Text(
                if (sideways) "$keptH × $keptW" else "$keptW × $keptH", color = Palette.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomCenter).padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        Column(Modifier.fillMaxWidth().background(Color(0xFF1E1F25)).padding(vertical = 12.dp)) {
            // Shapes
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in ratios) {
                    val on = r === ratio
                    Column(
                        Modifier.width(58.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFF2A1F15) else Color(0xFF2C2D34))
                            .border(1.dp, if (on) Palette.Accent else Color.Transparent, RoundedCornerShape(10.dp)).clickable { applyRatio(r) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            if (r.w == 0f) Box(Modifier.size(20.dp, 15.dp).border(1.5.dp, if (on) Palette.Accent else Palette.TextDim, RoundedCornerShape(2.dp)))
                            else {
                                val ar = r.w / r.h
                                val bw = if (ar >= 1f) 22f else 22f * ar
                                val bh = if (ar >= 1f) 22f / ar else 22f
                                Box(Modifier.size(bw.dp, bh.dp).border(1.5.dp, if (on) Palette.Accent else Palette.TextDim, RoundedCornerShape(2.dp)))
                            }
                        }
                        Text(r.label, color = if (on) Palette.Accent else Palette.Text, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Turning, mirroring, starting over
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(Lucide.RotateCcw, "Rotate", Modifier.weight(1f)) {
                    vm.cropTf = tf.copy(rot = (quarter + 1) % 4 * 90f)
                    if (ratio.w != 0f) applyRatio(ratio)
                }
                Action(Lucide.Flip, "Mirror", Modifier.weight(1f), on = tf.flipH) { vm.cropTf = tf.copy(flipH = !tf.flipH) }
                Action(Lucide.FlipV, "Flip", Modifier.weight(1f), on = tf.flipV) { vm.cropTf = tf.copy(flipV = !tf.flipV) }
                Action(Lucide.Undo, "Reset", Modifier.weight(1f)) {
                    ratio = ratios.first()
                    vm.cropTf = tf.copy(cropL = 0f, cropT = 0f, cropR = 0f, cropB = 0f, rot = 0f, flipH = false, flipV = false)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Coming soon", color = Palette.TextDim, fontSize = 11.sp, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((icon, label) in listOf(
                    Lucide.Droplet to "Chroma key", Lucide.UserCut to "Remove background", Lucide.Circle to "Mask",
                    Lucide.Sparkles to "Smart crop", Lucide.Feather to "Soft edges",
                )) Row(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF2C2D34)).clickable { soon(label) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = Palette.TextDim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = Palette.TextDim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, modifier: Modifier, on: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFF2A1F15) else Color(0xFF2C2D34)).clickable(onClick = onClick).padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (on) Palette.Accent else Palette.Text, modifier = Modifier.size(20.dp))
        Text(label, color = if (on) Palette.Accent else Palette.Text, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

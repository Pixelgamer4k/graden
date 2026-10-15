package com.graden.editor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.EditorViewModel
import com.graden.editor.MediaAsset
import com.graden.editor.timecode
import kotlin.math.min

private val Ok = Color(0xFF4CC38A)
private val WindowShape = RoundedCornerShape(24.dp)

/**
 * The media pool, as a window that springs out of the folder button in the top bar. Imports
 * land here, not on the timeline, and each card shows where its file is in the import pipeline.
 * Tap a card to add it, or hold and drag it: the window gets out of the way so the clip can be
 * dropped on a timeline lane.
 */
@Composable
fun MediaWindow(
    vm: EditorViewModel,
    dragging: Boolean,
    onImport: () -> Unit,
    onDragStart: (MediaAsset, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    com.graden.editor.RecomposeProbe.hit("MediaWindow")
    val shown = vm.mediaBoxOpen && !dragging
    // Slightly under-damped so it overshoots and settles, like a window opening from an app icon.
    val p by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.74f, stiffness = 420f), label = "media window")
    // Stay composed during a drag even though invisible: the drag gesture lives in a card of this window.
    if (p < 0.004f && !shown && !dragging) return
    // Reversed copies belong to the clips that use them, not to the pool.
    val assets = vm.assets.values.filter { !it.isReversed }.sortedBy { it.id }
    Box(Modifier.fillMaxSize()) {
        if (shown) Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f * p.coerceIn(0f, 1f)))
                .pointerInput(Unit) { detectTapGestures { vm.mediaBoxOpen = false } }
        )
        Column(
            Modifier.systemBarsPadding().padding(top = 50.dp, start = 10.dp, end = 10.dp).fillMaxWidth().height(452.dp)
                .graphicsLayer {
                    // Never scale during a drag: the gesture is measured in this window's own coordinates,
                    // so shrinking it would magnify every finger movement. It only fades out then.
                    val s = if (dragging) 1f else 0.12f + 0.88f * p
                    scaleX = s
                    scaleY = s
                    alpha = (p * 1.6f).coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0.95f, 0f)
                    shadowElevation = 24.dp.toPx()
                    shape = WindowShape
                    clip = true
                }
                .background(Color(0xFF1B1C23)).border(1.dp, Palette.Line, WindowShape)
                .pointerInput(Unit) { detectTapGestures { } } // taps inside must not fall through to the scrim
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Media", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    val busy = vm.importQueue.count { !it.failed }
                    val optimizing = assets.count { !vm.statusOf(it).ready }
                    Text(
                        when {
                            busy > 0 -> "Analysing $busy · ${assets.size} in pool"
                            optimizing > 0 -> "Optimising $optimizing · ${assets.size} in pool"
                            assets.isEmpty() -> "Import video, pictures or audio to begin"
                            else -> "${assets.size} ready · tap to add, hold to drag"
                        },
                        color = if (busy + optimizing > 0) Palette.Accent else Palette.TextDim, fontSize = 11.sp,
                    )
                }
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.Accent).clickable(onClick = onImport)
                        .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Plus, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Icon(
                    Lucide.X, "Close media window", tint = Palette.Text,
                    modifier = Modifier.padding(start = 4.dp).size(40.dp).clip(CircleShape).clickable { vm.mediaBoxOpen = false }.padding(9.dp),
                )
            }
            LazyVerticalGrid(
                GridCells.Fixed(2), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vm.importQueue, key = { "q" + it.key }) { q ->
                    Column {
                        Box(Thumb.clickable(enabled = q.failed) { vm.dismissFailed(q.key) }, contentAlignment = Alignment.Center) {
                            if (q.failed) Icon(Lucide.Ban, null, tint = Palette.Danger)
                            else CircularProgressIndicator(Modifier.size(22.dp), color = Palette.Accent, strokeWidth = 2.dp)
                        }
                        Label(q.name)
                        Text(
                            if (q.failed) "Can't read this file · tap to dismiss" else "Analysing…",
                            color = if (q.failed) Palette.Danger else Palette.Accent, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                items(assets, key = { it.id }) { a -> AssetCard(vm, a, onDragStart, onDrag, onDragEnd) }
            }
        }
    }
}

private val Thumb = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)

@Composable
private fun Label(text: String) {
    Text(text, color = Palette.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
}

@Composable
private fun AssetCard(
    vm: EditorViewModel,
    a: MediaAsset,
    onDragStart: (MediaAsset, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val haptics = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val status = vm.statusOf(a)
    Column(
        Modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(a.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        start(a, origin + o)
                    },
                    onDrag = { change, d ->
                        change.consume()
                        drag(d)
                    },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                )
            }
            .clickable { vm.addAsset(a) }
    ) {
        Box(if (a.isAudio) Thumb.background(Color(0xFF16332A)) else Thumb) {
            if (a.isAudio) Icon(Lucide.Music, null, tint = Palette.AudioLabel, modifier = Modifier.align(Alignment.Center).size(30.dp))
            else {
                vm.thumbs.version // recompose when the thumbnail arrives
                vm.thumbs.get(a, 0L, vm.proxies[a.id])?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Text(
                if (a.isImage) "STILL" else timecode(a.durationUs, a.fps), color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000)).padding(horizontal = 4.dp),
            )
            if (status.percent != null) Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(status.percent / 100f).height(3.dp).background(Palette.Accent)
            )
        }
        Label(a.name)
        Text(specs(a), color = Palette.TextDim, fontSize = 10.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
        Text(status.text, color = if (status.ready) Ok else Palette.Accent, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun specs(a: MediaAsset): String {
    if (a.isAudio) return "Audio · " + a.mime.substringAfter('/').uppercase()
    if (a.isImage) return "Picture · ${a.width}×${a.height}"
    val short = min(a.width, a.height)
    val res = if (short >= 2160) "4K" else "${short}p"
    val codec = when (a.mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        else -> a.mime.substringAfter('/').uppercase()
    }
    return "$res · ${a.fps.toInt()}fps · $codec" + if (a.hdr) " · HDR" else ""
}

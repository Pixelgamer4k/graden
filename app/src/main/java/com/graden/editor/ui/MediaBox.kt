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
 * Files go on the timeline only by dragging: hold a card and drag it, and the window gets out of the
 * way so the clip can be dropped on a timeline lane.
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
                            else -> "${assets.size} in pool"
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
            if (assets.isNotEmpty()) Row(
                Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF23252E))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⠿", color = Palette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("Hold a file and drag it onto the timeline", color = Palette.Text, fontSize = 12.sp)
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
    var confirmDelete by remember { mutableStateOf(false) }
    // A tap only reminds how files get onto the timeline.
    var nudge by remember { mutableStateOf(0) }
    val shake by animateFloatAsState(if (nudge % 2 == 1) 1f else 0f, spring(dampingRatio = 0.3f, stiffness = 900f), label = "nudge") {
        if (nudge % 2 == 1) nudge++
    }
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
            .clickable { if (nudge % 2 == 0) nudge++ }
            .graphicsLayer { translationX = shake * 6.dp.toPx() }
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
            // Drag handle: the sign that this card is dragged onto the timeline.
            Row(
                Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000)).padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⠿", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(3.dp))
                Text(if (nudge % 2 == 1 || shake > 0.01f) "Hold & drag" else "Drag", color = Color.White, fontSize = 10.sp)
            }
            if (!vm.isBuiltIn(a)) Icon(
                Lucide.Trash, "Delete ${a.name}", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(Color(0xB3000000))
                    .clickable { confirmDelete = true }.padding(6.dp),
            )
        }
        Label(a.name)
        Text(specs(a), color = Palette.TextDim, fontSize = 10.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
        Text(status.text, color = if (status.ready) Ok else Palette.Accent, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (confirmDelete) {
        val used = vm.clipsUsing(a)
        Confirm(
            title = "Delete ${a.name}?",
            text = if (used > 0) "It is used by $used clip${if (used == 1) "" else "s"} on the timeline. They will be removed too, and this can't be undone."
            else "It is removed from this project. The file on your phone is not touched.",
            yes = "Delete", no = "Keep",
            onYes = { confirmDelete = false; vm.removeAsset(a) }, onNo = { confirmDelete = false },
        )
    }
}

@Composable
private fun Confirm(title: String, text: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNo,
        containerColor = Color(0xFF1B1C23),
        title = { Text(title, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(text, color = Palette.TextDim, fontSize = 14.sp) },
        confirmButton = {
            Text(yes, color = Palette.Danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onYes).padding(horizontal = 14.dp, vertical = 10.dp))
        },
        dismissButton = {
            Text(no, color = Palette.Text, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onNo).padding(horizontal = 14.dp, vertical = 10.dp))
        },
    )
}

/**
 * The import window, like VN's: the clips picked in one go are prepared one after another. It shows the clip being
 * prepared, large, with its progress, and a strip of all of them. It closes by itself when every clip is ready, and
 * the media box opens to drag them onto the timeline. Closing it early cancels the clips not yet ready, after a warning.
 */
@Composable
fun ImportWindow(vm: EditorViewModel) {
    val open = vm.importOpen
    val p by animateFloatAsState(if (open) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 380f), label = "import window")
    if (!open && p < 0.004f) return
    var confirmClose by remember { mutableStateOf(false) }
    val finished = vm.importFinished
    androidx.compose.runtime.LaunchedEffect(finished, open) {
        if (finished && open) {
            kotlinx.coroutines.delay(700)
            vm.finishImport()
        }
    }
    if (open) androidx.activity.compose.BackHandler { confirmClose = true }
    val clips = vm.importSession.mapNotNull { vm.assets[it] }
    val reading = vm.importQueue.filter { !it.failed }
    val unreadable = vm.importQueue.count { it.failed }
    val total = clips.size + reading.size
    val progress = clips.map { vm.importProgress(it) }
    val current = clips.indices.firstOrNull { progress[it] < 1f }
    val doneCount = progress.count { it >= 1f }
    // A RAW recording is developed before it joins the clips: that counts as its first half.
    val developing = vm.rawProgress
    val overall by animateFloatAsState(if (total == 0) 0f else (progress.sum() + developing.values.sum() * 0.5f) / total, spring(stiffness = 120f), label = "overall")
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = p.coerceIn(0f, 1f) }.background(Color(0xD9000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp).fillMaxWidth()
                .graphicsLayer { val s = 0.9f + 0.1f * p; scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(26.dp)).background(Color(0xFF17181E)).border(1.dp, Palette.Line, RoundedCornerShape(26.dp))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (finished) "All set" else "Importing", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            finished -> "$doneCount clip${if (doneCount == 1) "" else "s"} ready to edit"
                            current != null -> "Clip ${current + 1} of $total"
                            developing.isNotEmpty() -> "Developing RAW  ${(developing.values.first() * 100).toInt()}%"
                            else -> "Getting your clips…"
                        },
                        color = Palette.TextDim, fontSize = 13.sp,
                    )
                }
                Icon(
                    Lucide.X, "Cancel import", tint = Palette.TextDim,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF23252E)).clickable { confirmClose = true }.padding(10.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            // The clip being prepared now.
            val hero = current?.let { clips[it] } ?: clips.lastOrNull()
            androidx.compose.runtime.key(hero?.id) {
            val heroP by animateFloatAsState(current?.let { progress[it] } ?: if (finished) 1f else 0f, spring(stiffness = 140f), label = "clip")
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                if (hero != null && !hero.isAudio) {
                    vm.thumbs.version
                    vm.thumbs.get(hero, 0L, vm.proxies[hero.id])?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                } else if (hero != null) Icon(Lucide.Music, null, tint = Palette.AudioLabel, modifier = Modifier.size(40.dp))
                Box(Modifier.fillMaxSize().background(Color(0x73000000)))
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { heroP }, modifier = Modifier.size(84.dp), color = if (finished) Ok else Palette.Accent,
                        trackColor = Color(0x33FFFFFF), strokeWidth = 5.dp,
                    )
                    if (finished) Icon(Lucide.Check, null, tint = Ok, modifier = Modifier.size(34.dp))
                    else Text("${(heroP * 100).toInt()}%", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                if (hero != null) Text(
                    hero.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
                )
            }
            }
            // Every clip of this import, in order.
            if (total > 1) {
                Spacer(Modifier.height(14.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(clips.size, key = { clips[it].id }) { i ->
                        val a = clips[i]
                        val done = progress[i] >= 1f
                        val now = i == current
                        Box(
                            Modifier.width(64.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)
                                .then(if (now) Modifier.border(2.dp, Palette.Accent, RoundedCornerShape(8.dp)) else Modifier)
                                .graphicsLayer { alpha = if (done || now) 1f else 0.4f },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!a.isAudio) {
                                vm.thumbs.version
                                vm.thumbs.get(a, 0L, vm.proxies[a.id])?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            } else Icon(Lucide.Music, null, tint = Palette.AudioLabel, modifier = Modifier.size(16.dp))
                            if (done) Box(
                                Modifier.align(Alignment.TopEnd).padding(3.dp).size(15.dp).clip(CircleShape).background(Ok),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Lucide.Check, null, tint = Color.Black, modifier = Modifier.size(10.dp)) }
                        }
                    }
                    items(reading.size) {
                        Box(
                            Modifier.width(64.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF23252E)),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(Modifier.size(14.dp), color = Palette.TextDim, strokeWidth = 1.5.dp) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2A2C35))) {
                Box(Modifier.fillMaxWidth(if (finished) 1f else overall).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (finished) Ok else Palette.Accent))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (unreadable > 0) "$unreadable file${if (unreadable == 1) "" else "s"} couldn't be opened and ${if (unreadable == 1) "was" else "were"} skipped."
                else "Keep Graden open while your clips get ready.",
                color = if (unreadable > 0) Palette.Danger else Palette.TextDim, fontSize = 12.sp,
            )
        }
    }
    if (confirmClose) Confirm(
        title = "Cancel import?",
        text = "Clips that aren't ready yet won't be imported. Clips that are ready stay in your media.",
        yes = "Cancel import", no = "Keep importing",
        onYes = { confirmClose = false; vm.cancelImport() }, onNo = { confirmClose = false },
    )
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

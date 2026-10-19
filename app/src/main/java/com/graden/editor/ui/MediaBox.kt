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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
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

/**
 * The media pool, a sheet that rises over the editor from the folder button. Imports land here, not on the timeline.
 * Tabs sort it into all, video, text, images, GIFs, audio and (once there is a RAW recording) MCRAW, in an order chosen
 * at the top (newest first unless changed); below this project's files are the other projects' files, to use here
 * too. A file or a text goes on the timeline by holding it and dragging it there (the sheet gets out of the way); a
 * text can also be tapped to add it at the playhead, and a file tapped for what else can be done with it.
 */
@Composable
fun MediaWindow(
    vm: EditorViewModel,
    dragging: Boolean,
    onImport: () -> Unit,
    onDragStart: (MediaAsset, Offset) -> Unit,
    onTextDragStart: (com.graden.editor.Caption, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    com.graden.editor.RecomposeProbe.hit("MediaWindow")
    val shown = vm.mediaBoxOpen && !dragging
    val p by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.86f, stiffness = 420f), label = "media sheet")
    androidx.compose.runtime.LaunchedEffect(vm.mediaBoxOpen) { if (vm.mediaBoxOpen) vm.loadOtherMedia() }
    // Stay composed during a drag even though invisible: the drag gesture lives in a card of this sheet.
    if (p < 0.004f && !shown && !dragging) return
    // Reversed copies belong to the clips that use them, not to the pool.
    val pool = vm.assets.values.filter { !it.isReversed }
    val tabs = buildList {
        addAll(listOf("All", "Video", "Text", "Images", "GIFs", "Audio"))
        if (pool.any { it.isRaw } || vm.otherMedia.any { it.second.isRaw }) add("MCRAW")
    }
    val tab = vm.mediaTab.takeIf { it in tabs } ?: "All"
    val assets = vm.sortMedia(pool.filter { vm.inMediaTab(it, tab) }) { it }
    val others = vm.sortMedia(vm.otherMedia.filter { vm.inMediaTab(it.second, tab) }) { it.second }
    Box(Modifier.fillMaxSize()) {
        if (shown) Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f * p.coerceIn(0f, 1f)))
                .pointerInput(Unit) { detectTapGestures { vm.mediaBoxOpen = false } }
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.86f)
                .graphicsLayer {
                    // Never moved during a drag: the gesture is measured in this sheet's own coordinates. It only fades then.
                    translationY = if (dragging) 0f else (1f - p) * size.height
                    alpha = if (dragging) 0f else 1f
                    shape = SheetShape
                    clip = true
                }
                .background(Kit.Sheet).border(1.dp, Kit.Hair, SheetShape)
                .pointerInput(Unit) { detectTapGestures { } } // taps inside must not fall through to the scrim
                .navigationBarsPadding()
        ) {
            KGrabber()
            val busy = vm.importQueue.count { !it.failed }
            val optimizing = pool.count { !vm.statusOf(it).ready }
            KHeader(
                "Media",
                when {
                    busy > 0 -> "Analysing $busy…"
                    optimizing > 0 -> "Optimising $optimizing for smooth editing…"
                    pool.isEmpty() -> "Import videos, pictures and sound"
                    else -> "Hold and drag onto the timeline"
                },
                onClose = { vm.mediaBoxOpen = false },
                trailing = {
                    Row(
                        Modifier.padding(end = 8.dp).height(40.dp).clip(CircleShape).background(Kit.Accent).clickable(onClick = onImport).padding(start = 12.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Lucide.Plus, null, tint = Kit.Ink, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import", color = Kit.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                },
            )
            KTabs(tabs, tabs.indexOf(tab)) { vm.mediaTab = tabs[it] }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Kit.Hair))
            if (tab == "Text") TextSection(vm, onTextDragStart, onDrag, onDragEnd)
            else LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        val n = assets.size
                        Text(if (n == 1) "1 file" else "$n files", color = Kit.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        SortMenu(vm)
                    }
                }
                if (tab == "All") items(vm.importQueue, key = { "q" + it.key }) { q ->
                    Column {
                        Box(Tile.clickable(enabled = q.failed) { vm.dismissFailed(q.key) }, contentAlignment = Alignment.Center) {
                            if (q.failed) Icon(Lucide.Ban, null, tint = Kit.Bad)
                            else CircularProgressIndicator(Modifier.size(22.dp), color = Kit.Accent, strokeWidth = 2.dp)
                        }
                        Label(q.name)
                        Text(if (q.failed) "Can't open · tap to dismiss" else "Analysing…", color = if (q.failed) Kit.Bad else Kit.Accent,
                            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (assets.isEmpty() && (tab != "All" || vm.importQueue.isEmpty())) item(span = { GridItemSpan(maxLineSpan) }) {
                    Empty(if (pool.isEmpty()) "Nothing here yet" else "No ${tab.lowercase()} in this project", if (pool.isEmpty()) onImport else null)
                }
                items(assets, key = { it.id }) { a -> AssetCard(vm, a, null, onDragStart, onDrag, onDragEnd) }
                if (others.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(top = 18.dp)) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Kit.Hair))
                            Text("From other projects", color = Kit.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                            Text("Drag one in to use it here too", color = Kit.Dim, fontSize = 12.sp)
                        }
                    }
                    items(others, key = { "o" + it.first + it.second.uri }) { (from, a) -> AssetCard(vm, a, from, onDragStart, onDrag, onDragEnd) }
                }
            }
        }
    }
}

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val Tile = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0B0B0E))

@Composable
private fun Empty(text: String, onImport: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Kit.Group), contentAlignment = Alignment.Center) {
            Icon(Lucide.Folder, null, tint = Kit.Dim, modifier = Modifier.size(26.dp))
        }
        Text(text, color = Kit.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp))
        if (onImport != null) Text("Import", color = Kit.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp).clip(CircleShape).clickable(onClick = onImport).padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun SortMenu(vm: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape).clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.ArrowUpDown, null, tint = Kit.Dim, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(EditorViewModel.MEDIA_SORTS[vm.mediaSort], color = Kit.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(surface = Kit.Raised, surfaceContainer = Kit.Raised)) {
            androidx.compose.material3.DropdownMenu(open, { open = false }) {
                Text("Sort by", color = Kit.Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                for ((i, n) in EditorViewModel.MEDIA_SORTS.withIndex()) androidx.compose.material3.DropdownMenuItem(
                    { Text(n, color = if (i == vm.mediaSort) Kit.Accent else Kit.Text, fontSize = 15.sp, fontWeight = if (i == vm.mediaSort) FontWeight.SemiBold else FontWeight.Normal) },
                    { open = false; vm.chooseMediaSort(i) },
                    trailingIcon = { if (i == vm.mediaSort) Icon(Lucide.Check, null, tint = Kit.Accent, modifier = Modifier.size(16.dp)) },
                )
            }
        }
    }
}

/**
 * Texts to add: a heading, subheading and body to start from, then groups of ready styles. Tap one to add it at the
 * playhead, or hold and drag it onto the timeline.
 */
@Composable
private fun TextSection(vm: EditorViewModel, onDragStart: (com.graden.editor.Caption, Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
        item {
            Text("Tap to add at the playhead, or hold and drag", color = Kit.Dim, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp))
            KGroup {
                for ((i, pr) in com.graden.editor.TextPresets.QUICK.withIndex()) {
                    if (i > 0) KDivider()
                    val (size, weight) = when (i) { 0 -> 26.sp to FontWeight.ExtraBold; 1 -> 19.sp to FontWeight.SemiBold; else -> 15.sp to FontWeight.Normal }
                    Draggable(pr.cap, onDragStart, onDrag, onDragEnd, onTap = { vm.addText(pr.cap) }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pr.name, color = Kit.Text, fontSize = size, fontWeight = weight, maxLines = 1, modifier = Modifier.weight(1f))
                            Icon(Lucide.Plus, null, tint = Kit.Dim, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        for (g in com.graden.editor.TextPresets.GROUPS) {
            item(key = g.name) {
                Column {
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                        Text(g.name, color = Kit.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("  ${g.presets.size}", color = Kit.Faint, fontSize = 13.sp)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (pr in g.presets) Draggable(pr.cap, onDragStart, onDrag, onDragEnd, onTap = { vm.addText(pr.cap) }) {
                            Column {
                                TextTile(pr.cap, Modifier.width(164.dp).height(96.dp))
                                Text(pr.name, color = Kit.Dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Something that is tapped, or held and dragged onto the timeline. */
@Composable
private fun Draggable(
    cap: com.graden.editor.Caption, onDragStart: (com.graden.editor.Caption, Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit,
    onTap: () -> Unit, content: @Composable () -> Unit,
) {
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val tap by rememberUpdatedState(onTap)
    val haptics = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(cap) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); start(cap, origin + o) },
                    onDrag = { change, d -> change.consume(); drag(d) },
                    onDragEnd = { end() }, onDragCancel = { end() },
                )
            }
            .clickable { tap() }
    ) { content() }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Kit.Text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
}

@Composable
private fun AssetCard(
    vm: EditorViewModel,
    a: MediaAsset,
    /** The other project it is from; null: this one's. */
    from: String?,
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
    var menu by remember { mutableStateOf(false) }
    val status = if (from == null) vm.statusOf(a) else null
    Column(
        Modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(a.id, from) {
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
            .clickable { menu = true }
    ) {
        Box(Tile) {
            if (a.isAudio) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1C3D32), Color(0xFF11211C)))), contentAlignment = Alignment.Center) {
                Icon(Lucide.Music, null, tint = Palette.AudioLabel, modifier = Modifier.size(28.dp))
            } else {
                vm.thumbs.version // recompose when the thumbnail arrives
                vm.thumbs.get(a, 0L, if (from == null) vm.proxies[a.id] else null)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            // What it is, where that isn't plain video, and how long.
            val kind = when { a.isRaw -> "RAW"; a.isGif -> "GIF"; a.hdr -> "HDR"; else -> null }
            kind?.let { Tag(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
            if (!a.isImage) Tag(timecode(a.durationUs, a.fps).removePrefix("00:"), Modifier.align(Alignment.BottomEnd).padding(6.dp), mono = true)
            if (status?.percent != null) Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(status.percent / 100f).height(3.dp).background(Kit.Accent)
            )
            if (status != null && !status.ready && status.percent == null) Box(
                Modifier.align(Alignment.TopEnd).padding(7.dp).size(8.dp).clip(CircleShape).background(Kit.Accent)
            )
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(surface = Kit.Raised, surfaceContainer = Kit.Raised)) {
                androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                    Text(a.name, color = Kit.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(200.dp))
                    Text("Hold and drag it onto the timeline", color = Kit.Faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp))
                    if (from != null) androidx.compose.material3.DropdownMenuItem(
                        { Text("Add to this project", color = Kit.Text, fontSize = 15.sp) }, { menu = false; vm.adopt(a) },
                        leadingIcon = { Icon(Lucide.Plus, null, tint = Kit.Text, modifier = Modifier.size(16.dp)) },
                    )
                    else if (!vm.isBuiltIn(a)) androidx.compose.material3.DropdownMenuItem(
                        { Text("Remove from project", color = Kit.Bad, fontSize = 15.sp) }, { menu = false; confirmDelete = true },
                        leadingIcon = { Icon(Lucide.Trash, null, tint = Kit.Bad, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        Label(a.name)
        Text(
            if (from != null) "From $from" else if (status != null && !status.ready) status.text else specs(a),
            color = if (from != null) Palette.VideoLabel else if (status != null && !status.ready) Kit.Accent else Kit.Dim,
            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    if (confirmDelete) {
        val used = vm.clipsUsing(a)
        Confirm(
            title = "Remove ${a.name}?",
            text = if (used > 0) "It is used by $used clip${if (used == 1) "" else "s"} on the timeline. They will be removed too, and this can't be undone."
            else "It is removed from this project. The file on your phone is not touched.",
            yes = "Remove", no = "Keep",
            onYes = { confirmDelete = false; vm.removeAsset(a) }, onNo = { confirmDelete = false },
        )
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier, mono: Boolean = false) {
    Text(
        text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xA6000000)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun Confirm(title: String, text: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNo,
        containerColor = Kit.Raised,
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
                .clip(RoundedCornerShape(28.dp)).background(Kit.Sheet).border(1.dp, Kit.Hair, RoundedCornerShape(28.dp))
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (finished) "All set" else "Importing", color = Kit.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                    Text(
                        when {
                            finished -> "$doneCount clip${if (doneCount == 1) "" else "s"} ready to edit"
                            current != null -> "Clip ${current + 1} of $total"
                            developing.isNotEmpty() -> "Developing RAW  ${(developing.values.first() * 100).toInt()}%"
                            else -> "Getting your clips…"
                        },
                        color = Kit.Dim, fontSize = 13.sp,
                    )
                }
                Icon(
                    Lucide.X, "Cancel import", tint = Palette.TextDim,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(Kit.Raised).clickable { confirmClose = true }.padding(10.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            // The clip being prepared now.
            val hero = current?.let { clips[it] } ?: clips.lastOrNull()
            androidx.compose.runtime.key(hero?.id) {
            val heroP by animateFloatAsState(current?.let { progress[it] } ?: if (finished) 1f else 0f, spring(stiffness = 140f), label = "clip")
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
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
                            Modifier.width(64.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Kit.Raised),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(Modifier.size(14.dp), color = Palette.TextDim, strokeWidth = 1.5.dp) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Kit.Raised)) {
                Box(Modifier.fillMaxWidth(if (finished) 1f else overall).height(8.dp).clip(CircleShape).background(if (finished) SolidColor(Ok) else Kit.Primary))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (unreadable > 0) "$unreadable file${if (unreadable == 1) "" else "s"} couldn't be opened and ${if (unreadable == 1) "was" else "were"} skipped."
                else "Keep Graden open while your clips get ready.",
                color = if (unreadable > 0) Kit.Bad else Kit.Dim, fontSize = 12.sp,
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
    if (a.isGif) return "GIF · ${a.width}×${a.height}"
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
    return "$res · ${a.fps.roundToIntSafe()} fps · $codec"
}

private fun Float.roundToIntSafe() = kotlin.math.round(this).toInt()

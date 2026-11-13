package com.graden.editor.ui

import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.composed
import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.ui.graphics.drawscope.rotate

import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.foundation.gestures.calculateRotation

import androidx.compose.foundation.gestures.calculateZoom

import androidx.compose.foundation.gestures.calculatePan

import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.awaitEachGesture

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import androidx.compose.ui.platform.LocalView
import com.graden.editor.R
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.derivedStateOf
import com.graden.editor.GradedVideoView
import com.graden.editor.GradeGl
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import com.graden.editor.MediaAsset
import kotlin.math.roundToInt
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.graden.editor.DeviceProfiler
import com.graden.editor.EditorViewModel
import com.graden.editor.EditorViewModel.Selection
import com.graden.editor.timecode
import kotlin.math.min
import android.app.Activity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableFloatStateOf
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInVertically
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import com.graden.editor.Look
import com.graden.editor.ProjectMeta
import kotlin.math.hypot
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext

object Palette {
    val Bg = Color(0xFF0E0F13)
    val Surface = Color(0xFF191A20)
    val Line = Color(0xFF2A2C35)
    val Text = Color(0xFFF2F2F4)
    val TextDim = Color(0xFF8A8D99)
    val Accent = Color(0xFFF0872B)
    val Danger = Color(0xFFE5484D)
    val Clip = Color(0xFF26344F)
    val Audio = Color(0xFF1F4A3A)
    val Lane = Color(0xFF15161C)
    val Header = Color(0xFF191A20)
    val Grid = Color(0xFF22242C)
    val Tick = Color(0xFF4A4D58)
    val VideoLabel = Color(0xFF7FA8FF)
    val AudioLabel = Color(0xFF5FCF9A)
}

@Composable
fun EditorScreen(vm: EditorViewModel) {
    com.graden.editor.RecomposeProbe.hit("EditorScreen")
    // The dashboard comes first; the editor is shown once a project is open.
    if (vm.projectId == null) {
        Box(Modifier.fillMaxSize()) {
            Dashboard(vm)
            androidx.compose.animation.AnimatedVisibility(!vm.startupReady, enter = androidx.compose.animation.EnterTransition.None,
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(380))) { Splash(vm) }
        }
        return
    }
    BackHandler { vm.closeProject() }
    // HDR preview: the window becomes an HDR window and the pictures draw Rec.2100 PQ.
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val hdr = vm.hdrPreview
    androidx.compose.runtime.DisposableEffect(hdr) {
        com.graden.editor.GradeGl.setHdr(hdr)
        if (android.os.Build.VERSION.SDK_INT >= 34) activity?.window?.colorMode =
            if (hdr) android.content.pm.ActivityInfo.COLOR_MODE_HDR else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        onDispose {
            com.graden.editor.GradeGl.setHdr(false)
            if (android.os.Build.VERSION.SDK_INT >= 34) activity?.window?.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }
    val import = { picker.launch(arrayOf("video/*", "audio/*", "image/*", "application/octet-stream")) }
    val density = LocalDensity.current
    val context = LocalContext.current
    // Controls that are in the design but have nothing behind them yet say so instead of doing nothing.
    val soon: (String) -> Unit = { what -> Toast.makeText(context, "$what isn't built yet", Toast.LENGTH_SHORT).show() }
    LaunchedEffect(vm.notice) { vm.notice?.let { Toast.makeText(context, it.second, Toast.LENGTH_SHORT).show() } }

    LaunchedEffect(vm.isPlaying) {
        while (vm.isPlaying) withFrameNanos { vm.onFrame(it) }
    }

    var dragAsset by remember { mutableStateOf<MediaAsset?>(null) }
    // A text being dragged from the media box's Text section.
    var dragText by remember { mutableStateOf<com.graden.editor.Caption?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var timelineBounds by remember { mutableStateOf(Rect.Zero) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Where the editor sits in the window (to place a fold), and how much of a tablet's height the timeline takes.
    var originY by remember { mutableFloatStateOf(0f) }
    var timelineShare by androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(0.4f) }
    var sideShare by androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(0.42f) }
    // Tapping the Edit tab opens its tools above the tab bar; tapping it again folds them away.

    // A light tick when a dragged clip clicks into a slot, a firmer one when it is dropped.
    val view = LocalView.current
    LaunchedEffect(vm.hapticTick) { if (vm.hapticTick > 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    LaunchedEffect(vm.confirmTick) {
        if (vm.confirmTick > 0) view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }

    /** Tells the timeline which lane and time the dragged media item is over. */
    fun hover() {
        val a = dragAsset
        if (timelineBounds.contains(dragPos) && vm.pxPerSec > 0f) {
            val lanes = layoutLanes(timelineBounds.height, density, vm.tracks, 0, vm.laneScale)
            val lane = laneKeyAt(dragPos.y - timelineBounds.top + vm.laneScroll, lanes, with(density) { 3.dp.toPx() })
            val at = vm.playheadUs + ((dragPos.x - timelineBounds.center.x) / vm.pxPerSec * 1e6f).toLong()
            if (a != null) vm.hoverDrop(a, lane, at) else if (dragText != null) vm.hoverText(lane, at)
        } else if (a != null) vm.hoverDrop(a, null, 0L) else vm.hoverText(null, 0L)
    }

    // Holding a dragged item near the left or right end of the timeline scrolls it: slowly at first, fast at the very edge.
    val draggingMedia = dragAsset != null || dragText != null
    LaunchedEffect(draggingMedia) {
        if (!draggingMedia) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if ((dragAsset != null || dragText != null) && last != 0L && timelineBounds.contains(dragPos) && vm.pxPerSec > 0f) {
                val dp = density.density
                val speed = edgeScrollSpeed(dragPos.x, timelineBounds.left + 46 * dp, timelineBounds.right, 64 * dp, dp)
                if (speed != 0f && vm.panBy((speed * (now - last) / 1e9f / vm.pxPerSec * 1e6f).toLong())) hover()
                // Near the top or bottom of the lanes, they scroll too, so a lane out of view can be reached.
                val before = vm.laneScroll
                vm.scrollLanesBy(edgeScrollSpeed(dragPos.y, timelineBounds.top + 34 * dp, timelineBounds.bottom, 36 * dp, dp) * 0.35f * (now - last) / 1e9f)
                if (vm.laneScroll != before) hover()
            }
            last = now
        }
    }

    val media: @Composable (Boolean) -> Unit = { docked ->
            MediaWindow(
                vm, dragging = dragAsset != null || dragText != null, onImport = import,
                onDragStart = { a, pos ->
                    dragAsset = a
                    dragPos = pos
                    vm.mediaBoxOpen = false
                },
                onTextDragStart = { cap, pos ->
                    dragText = cap
                    dragPos = pos
                    vm.mediaBoxOpen = false
                },
                onDrag = { d ->
                    dragPos += d
                    hover()
                },
                onDragEnd = {
                    // A file from another project joins this one as it is dropped.
                    dragAsset?.let { a -> if (vm.drop != null) vm.dropAsset(if (vm.assets[a.id]?.uri == a.uri) a else vm.adopt(a)) }
                    dragText?.let { if (vm.drop != null) vm.dropText(it) }
                    vm.clearDrop()
                    vm.settle()
                    dragAsset = null
                    dragText = null
                },
                docked = docked,
            )
    }
    var mediaDocked by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        val topBar: @Composable () -> Unit = {
            when {
                vm.colorOpen -> ColorTopBar(vm)
                vm.stabOpen -> StabTopBar(vm)
                vm.cinemaOpen -> CinemaTopBar(vm)
                vm.soundClipId != null -> SoundTopBar(vm)
                else -> HostBar(vm, soon, onClose = { vm.closeProject() }, onSettings = { settingsOpen = true })
            }
        }
        val body: @Composable ColumnScope.() -> Unit = {
            when {
                vm.colorOpen -> ColorBody(vm, Modifier.weight(1f))
                vm.stabOpen -> StabBody(vm, Modifier.weight(1f))
                vm.cinemaOpen -> CinemaBody(vm, Modifier.weight(1f))
                vm.soundClipId != null -> SoundBody(vm, soon, Modifier.fillMaxWidth().weight(1f))
                else -> EditBody(vm, soon) { timelineBounds = it }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().displayCutoutPadding().onGloballyPositioned { originY = it.positionInWindow().y }) {
            // The layout follows the window, never the device: rotating, folding, split screen and resizing all just
            // measure again. Every size is a share of the window with limits, so screens a little bigger or smaller,
            // or a fold a little off centre, still come out right.
            val w = maxWidth
            val h = maxHeight
            val fold = vm.fold
            val modes = vm.colorOpen || vm.stabOpen || vm.cinemaOpen || vm.soundClipId != null
            val shape = when {
                // Half open with the fold across: the picture above it, the controls below (laptop or flex mode).
                fold != null && fold.halfOpen && fold.horizontal && h >= 400.dp -> EditorLayout.TABLETOP
                // A flip phone's cover screen or a tiny window: watching only.
                w < 480.dp && h < 480.dp -> EditorLayout.MINI
                // Not tall enough to stack: a phone on its side, or a short split-screen window.
                h < 480.dp -> EditorLayout.SIDE
                w < 600.dp -> EditorLayout.PHONE
                // Open foldables (nearly square) and tablets on their side.
                w / h >= 0.8f -> EditorLayout.STUDIO
                // Tablets upright.
                else -> EditorLayout.TALL
            }
            // Stabilize and Cinema bring their own page; Color opens in the tablet layout's side panel instead.
            val pages = vm.stabOpen || vm.cinemaOpen
            SideEffect { mediaDocked = (shape == EditorLayout.STUDIO && !pages) || (shape == EditorLayout.SIDE && !modes) }
            LaunchedEffect(shape, w, h) { android.util.Log.i("GradenLayout", "$shape ${w.value.toInt()}x${h.value.toInt()} dp") }
            val line = Color(0xFF1C1E24)
            when (shape) {
                EditorLayout.PHONE -> Column(Modifier.fillMaxSize()) {
                    topBar()
                    Preview(vm)
                    body()
                }
                EditorLayout.TALL -> Column(Modifier.fillMaxSize()) {
                    topBar()
                    // Upright tablet: the picture as tall as its shape needs at the full width, but never more than half
                    // the screen (a third while a page like Color needs the room), so the timeline and tools keep theirs.
                    val need = w / vm.meta.aspect.coerceAtLeast(0.4f) + 8.dp
                    Preview(vm, Modifier.fillMaxWidth().height(need.coerceIn(h * 0.28f, h * (if (modes) 0.38f else 0.5f))))
                    body()
                }
                EditorLayout.SIDE -> {
                    // A phone on its side, or any short window, as rounded panes on a darker ground so each part reads on
                    // its own. Left: the picture, then the playback bar and the timeline. Right: the media (under half)
                    // and the clip's tools as a grid; an open page (Color, Sound, Stabilize, Cinema) takes the whole right.
                    val panelW = (w * 0.42f).coerceIn(300.dp, 440.dp)
                    val hPx = with(LocalDensity.current) { h.toPx() }
                    Row(Modifier.fillMaxSize().background(Ground).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Column(Modifier.fillMaxWidth().weight(if (pages) 1f else 1f - sideShare).pane()) {
                                topBar()
                                Preview(vm, Modifier.weight(1f).fillMaxWidth())
                            }
                            if (!pages) Column(Modifier.fillMaxWidth().weight(sideShare).pane()) {
                                Toolbar(vm, soon, tools = false, compact = true)
                                Box(
                                    Modifier.fillMaxWidth().height(10.dp)
                                        .resizeBar(hPx, sideShare, 0.28f, 0.62f) { sideShare = it },
                                    contentAlignment = Alignment.Center,
                                ) { Box(Modifier.size(32.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3D47))) }
                                Column(Modifier.fillMaxWidth().weight(1f)) { EditBody(vm, soon, tools = false, transport = false) { timelineBounds = it } }
                            }
                        }
                        Column(Modifier.width(panelW).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            when {
                                // An open page takes the whole right side, media included; closing it brings them back.
                                vm.colorOpen -> ColorSidePanel(vm, Modifier.fillMaxSize().pane())
                                vm.soundClipId != null -> SoundBody(vm, soon, Modifier.fillMaxSize().pane(), strip = false)
                                pages -> Column(Modifier.fillMaxSize().pane()) { body() }
                                else -> {
                                    Box(Modifier.fillMaxWidth().weight(0.45f).pane()) { media(true) }
                                    ToolPanel(vm, soon, Modifier.fillMaxWidth().weight(0.55f).pane())
                                }
                            }
                        }
                    }
                }
                EditorLayout.MINI -> Column(Modifier.fillMaxSize()) {
                    Preview(vm, Modifier.weight(1f).fillMaxWidth())
                    Transport(vm)
                    Text("Open the phone or make the window bigger to edit", color = Palette.TextDim, fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(6.dp))
                }
                EditorLayout.TABLETOP -> {
                    val d = LocalDensity.current
                    val top = with(d) { (fold!!.top - originY).toDp() }.coerceIn(h * 0.3f, h * 0.7f)
                    val gap = with(d) { (fold!!.bottom - fold.top).toDp() }.coerceIn(0.dp, 48.dp)
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxWidth().height(top)) {
                            topBar()
                            Preview(vm, Modifier.weight(1f).fillMaxWidth())
                        }
                        Spacer(Modifier.height(gap))
                        Column(Modifier.fillMaxWidth().weight(1f)) { body() }
                    }
                }
                // Nearly square (an open foldable): side by side would leave the picture small, so Color, Stabilize and
                // Cinema stack instead, the picture the full width on top.
                EditorLayout.STUDIO -> if (pages && w / h < 1.3f) Column(Modifier.fillMaxSize()) {
                    topBar()
                    Preview(vm, Modifier.fillMaxWidth().height(h * 0.42f))
                    body()
                } else if (pages) Row(Modifier.fillMaxSize()) {
                    // Color, Stabilize and Cinema bring their own timeline: picture left, the page beside it.
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        topBar()
                        Preview(vm, Modifier.weight(1f).fillMaxWidth())
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                    Column(Modifier.width((w * 0.42f).coerceIn(340.dp, 560.dp)).fillMaxHeight()) { body() }
                } else {
                    Studio(vm, soon, w, h, timelineShare, { timelineShare = it }, topBar, { media(true) }) { timelineBounds = it }
                }
            }
        }
        if (vm.colorOpen) ColorOverlays(vm)
        // While a resize bar is held: a line where it will go. The layout itself changes once, when it is let go, so the
        // picture's video surface is never resized frame by frame (that was what made dragging stutter).
        Box(Modifier.fillMaxSize().drawBehind {
            val y = ResizeGhost.y ?: return@drawBehind
            drawRect(Color.Black.copy(alpha = 0.18f))
            drawLine(Palette.Accent, Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
            drawRoundRect(Palette.Accent, Offset(size.width / 2 - 26.dp.toPx(), y - 3.dp.toPx()), androidx.compose.ui.geometry.Size(52.dp.toPx(), 6.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        })
        Panel { Box(Modifier.fillMaxSize()) { TransitionSheet(vm, Modifier.align(Alignment.BottomCenter)) } }
        SavedNote(vm, Modifier.align(Alignment.TopCenter))
        if (vm.trimSession != null) TrimPage(vm)
        if (vm.cropSession != null) CropPage(vm, soon)
        if (vm.exportOpen) Panel { ExportScreen(vm) }
        Panel { CaptionSheet(vm) }
        CaptionEditDialog(vm)
        Panel { TextPanel(vm) }
        Panel { Box(Modifier.fillMaxSize()) {
            ProjectSheet(settingsOpen, vm.meta, onClose = { settingsOpen = false }, onDone = {
                settingsOpen = false
                vm.updateMeta(it)
            }, modifier = Modifier.align(Alignment.BottomCenter))
        } }
        // On a tablet the media sits docked in the layout; the sliding window is the phone's.
        if (!mediaDocked) Panel { media(false) }
        Panel { ImportWindow(vm) }
        // The item being dragged out of the media window stays centred under the fingertip, above
        // everything else, and shrinks once it is over a place it can be dropped.
        dragText?.let { cap ->
            val lift = remember { Animatable(0.6f) }
            LaunchedEffect(Unit) { lift.animateTo(1f, spring(0.55f, 500f)) }
            val over by animateFloatAsState(if (vm.drop != null) 0.62f else 1f, spring(0.7f, 500f), label = "drag text")
            Box(
                Modifier.offset { IntOffset((dragPos.x - 74.dp.toPx()).roundToInt(), (dragPos.y - 42.dp.toPx()).roundToInt()) }.size(148.dp, 84.dp)
                    .graphicsLayer { scaleX = lift.value * over; scaleY = lift.value * over; alpha = 0.94f; shadowElevation = 16.dp.toPx(); shape = RoundedCornerShape(10.dp); clip = true }
                    .border(1.5.dp, Color.White, RoundedCornerShape(10.dp))
            ) { TextTile(cap, Modifier.fillMaxSize()) }
        }
        dragAsset?.let { a ->
            val w = 120.dp
            val h = 68.dp
            val lift = remember { Animatable(0.6f) }
            LaunchedEffect(Unit) { lift.animateTo(1f, spring(0.55f, 500f)) }
            val over by animateFloatAsState(if (vm.drop != null) 0.62f else 1f, spring(0.7f, 500f), label = "drag")
            Box(
                Modifier
                    .offset { IntOffset((dragPos.x - w.toPx() / 2).roundToInt(), (dragPos.y - h.toPx() / 2).roundToInt()) }
                    .size(w, h)
                    .graphicsLayer {
                        scaleX = lift.value * over
                        scaleY = lift.value * over
                        alpha = 0.92f
                        shadowElevation = 16.dp.toPx()
                        shape = RoundedCornerShape(10.dp)
                        clip = true
                    }
                    .background(if (a.isAudio) Palette.Audio else Palette.Clip)
                    .border(1.5.dp, Color.White, RoundedCornerShape(10.dp))
            ) {
                if (!a.isAudio) vm.thumbs.get(a, 0L, vm.proxies[a.id])?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
    }
}

/**
 * Where a page that slides up (Sound, Media, Export, text, transitions, project settings) sits. On a phone: the whole
 * width, as designed. On a wide window (tablet, open foldable): a panel down the right edge, at a width a hand can work,
 * with the picture still in view; on an upright tablet: centred, no wider than a large phone.
 */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        when {
            w < 720.dp -> content()
            w / h >= 0.8f -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                Box(Modifier.fillMaxHeight().width((w * 0.4f).coerceIn(380.dp, 520.dp))) { content() }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxHeight().widthIn(max = 640.dp)) { content() }
            }
        }
    }
}

/** The darker ground the panes sit on, and a pane: a rounded card a shade lighter, with a hairline edge. */
private val Ground = Color(0xFF07080A)
private val PaneShape = RoundedCornerShape(14.dp)
private fun Modifier.pane(): Modifier = this.clip(PaneShape).background(Color(0xFF131419)).border(1.dp, Color(0xFF26282F), PaneShape)

/** Where the resize line is drawn while a bar is held (window y), or null. */
private object ResizeGhost { var y by mutableStateOf<Float?>(null) }

/** A bar that sets, live while dragged, how much of [hPx] the part under it takes ([share], within [lo]..[hi]). */
private fun Modifier.resizeBar(hPx: Float, share: Float, lo: Float, hi: Float, onShare: (Float) -> Unit): Modifier = composed {
    val now by rememberUpdatedState(share)
    val set by rememberUpdatedState(onShare)
    val view = LocalView.current
    pointerInput(hPx) {
        var dy = 0f
        var start = 0f
        detectDragGestures(
            onDragStart = { dy = 0f; start = now; view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) },
        ) { ch, d ->
            ch.consume()
            // Live: the parts resize under the finger as it moves.
            dy += d.y
            set((start - dy / hPx).coerceIn(lo, hi))
        }
    }
}

/** How the editor is arranged for the window it has (see EditorScreen). */
private enum class EditorLayout { PHONE, TALL, SIDE, MINI, TABLETOP, STUDIO }

/** The edit page under the preview: transport, timeline, and the tools for what is selected. */
@Composable
private fun ColumnScope.EditBody(vm: EditorViewModel, soon: (String) -> Unit, tools: Boolean = true, transport: Boolean = true, onTimelineBounds: (Rect) -> Unit) {
    com.graden.editor.RecomposeProbe.hit("EditBody")
    if (transport) Transport(vm)
    Box(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { onTimelineBounds(it.boundsInRoot()) }) {
        TimelineView(vm, Modifier.fillMaxSize())
        if (vm.totalUs == 0L && vm.drop == null) Text(
            "Open Media (folder icon) and drag a clip here",
            color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center),
        )
    }
    // One panel only, and only while something is selected: its tools are the ones that make sense for it.
    AnimatedVisibility(tools && vm.selection != Selection.NONE, enter = expandVertically(spring(0.8f, 500f)) + fadeIn(), exit = shrinkVertically(spring(0.9f, 600f)) + fadeOut()) {
        Tools(vm, soon)
    }
}

/** After the save button: a short note of what was saved and when, dropping in under the top bar. */
@Composable
private fun SavedNote(vm: EditorViewModel, modifier: Modifier) {
    com.graden.editor.RecomposeProbe.hit("SavedNote")
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(vm.manualSaveTick) {
        if (vm.manualSaveTick == 0) return@LaunchedEffect
        shown = true
        delay(2200)
        shown = false
    }
    AnimatedVisibility(
        shown, modifier.systemBarsPadding().padding(top = 58.dp),
        enter = slideInVertically { -it / 2 } + fadeIn(), exit = fadeOut(),
    ) {
        val time = remember(vm.lastSavedAt) { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(vm.lastSavedAt)) }
        val n = vm.clipCount
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xF21B1C22)).border(1.dp, SavedGreen.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Check, null, tint = SavedGreen, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Project saved", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("  ·  $n clip${if (n == 1) "" else "s"}  ·  $time", color = Palette.TextDim, fontSize = 12.sp, fontFamily = Mono)
        }
    }
}

/** How a clip of another shape sits on the canvas, from the project's setting. "Centre" is handled by scaling the view. */
@OptIn(UnstableApi::class)
/** How small a hidden picture is drawn: a few pixels, so it costs nearly nothing but keeps taking frames. */
private const val HIDDEN_SCALE = 0.01f

/** Hands a decoder's output surface to [p] when the view is ready, and takes it back just before it goes. */
private fun playerInput(p: androidx.media3.common.Player): (Surface, Boolean) -> Unit = { s, attached ->
    if (attached) p.setVideoSurface(s) else p.clearVideoSurface(s)
}

/**
 * Shows the still pictures [p] plays in [v]. The player hands pictures over as bitmaps (to an image output), not into
 * the video surface; each is copied (at most 4K on its long side, so the GPU can take it) and drawn into the view.
 */
@OptIn(UnstableApi::class)
private fun pictureOutput(p: androidx.media3.exoplayer.ExoPlayer, v: com.graden.editor.GradedVideoView) {
    p.setImageOutput(object : androidx.media3.exoplayer.image.ImageOutput {
        override fun onImageAvailable(presentationTimeUs: Long, bitmap: android.graphics.Bitmap) {
            val k = (3840f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
            val copy = runCatching {
                if (k < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * k).toInt().coerceAtLeast(1), (bitmap.height * k).toInt().coerceAtLeast(1), true)
                else bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            }.getOrNull() ?: return
            v.drawPicture(copy)
        }
        override fun onDisabled() {}
    })
}

private fun resizeFor(scaling: String) =
    if (scaling == "Fill") androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

/** The part of a picture a wipe or circle transition lets through. */
private class LookShape(private val look: Look) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (look.circle >= 0f) {
            val r = look.circle * hypot(size.width, size.height) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            return Outline.Rounded(RoundRect(Rect(c.x - r, c.y - r, c.x + r, c.y + r), CornerRadius(r)))
        }
        return Outline.Rectangle(Rect(look.left * size.width, look.top * size.height, look.right * size.width, look.bottom * size.height))
    }
}

private val Mono = FontFamily.Monospace
private val Soft = Color(0xFFC9CBD3)

/** Top row: back to the dashboard and the project's name (tap to rename) on the left; snapping, media, save and export on the right. */
@Composable
private fun HostBar(vm: EditorViewModel, soon: (String) -> Unit, onClose: () -> Unit, onSettings: () -> Unit) {
    com.graden.editor.RecomposeProbe.hit("HostBar")
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BarIcon(Lucide.X, "Back to projects", onClick = onClose)
        Spacer(Modifier.width(2.dp))
        // The name opens the project's settings (name included).
        Text(
            vm.meta.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp)).clickable { onSettings() }.padding(horizontal = 6.dp, vertical = 4.dp),
        )
        // Takes no room from the name; it only pushes the buttons to the right when the name is short.
        Spacer(Modifier.weight(0.001f))
        BarIcon(Lucide.Magnet, if (vm.snapEnabled) "Snapping on" else "Snapping off", tint = if (vm.snapEnabled) Palette.Accent else Palette.TextDim) {
            vm.snapEnabled = !vm.snapEnabled
        }
        Box {
            BarIcon(Lucide.Folder, if (vm.mediaBoxOpen) "Close media" else "Open media", tint = if (vm.mediaBoxOpen) Palette.Accent else Palette.Text) {
                vm.mediaBoxOpen = !vm.mediaBoxOpen
            }
            // Imports still being analysed or optimised while the window is closed.
            val busy = vm.importQueue.any { !it.failed } || vm.assets.values.any { !vm.statusOf(it).ready }
            if (busy) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(6.dp).clip(CircleShape).background(Palette.Accent))
        }
        BarIcon(Lucide.Captions, "Auto captions", tint = if (vm.captionRun != null) Palette.Accent else Palette.Text) { vm.pause(); vm.openCaptions(null) }
        BarIcon(Lucide.Info, "Clip details", tint = if (vm.infoOpen) Palette.Accent else Palette.Text) { vm.infoOpen = !vm.infoOpen }
        SaveButton(vm)
        ExportMenu(vm)
    }
}

/**
 * Save. Everything is saved automatically, so this is reassurance as much as a command: the small
 * dot shows whether the last change has reached storage (green) or is on its way (orange), and a
 * press writes at once, then answers with a tick, a little bounce, a firm vibration and a note
 * saying what was saved and when.
 */
@Composable
private fun SaveButton(vm: EditorViewModel) {
    com.graden.editor.RecomposeProbe.hit("SaveButton")
    val view = LocalView.current
    val bounce = remember { Animatable(1f) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(vm.manualSaveTick) {
        if (vm.manualSaveTick == 0) return@LaunchedEffect
        done = true
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        bounce.snapTo(0.7f)
        bounce.animateTo(1f, spring(0.38f, 600f))
        delay(1800)
        done = false
    }
    Box(Modifier.size(38.dp).clip(CircleShape).clickable { vm.saveManually() }, contentAlignment = Alignment.Center) {
        Crossfade(done, label = "save") { saved ->
            Icon(
                if (saved) Lucide.Check else Lucide.Save, if (saved) "Saved" else "Save",
                tint = if (saved) SavedGreen else Palette.Text,
                modifier = Modifier.size(19.dp).graphicsLayer { scaleX = bounce.value; scaleY = bounce.value },
            )
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(6.dp).clip(CircleShape)
                .background(if (vm.allSaved) SavedGreen else Palette.Accent)
        )
    }
}

private val SavedGreen = Color(0xFF4CC38A)

@Composable
private fun BarIcon(icon: ImageVector, label: String, tint: Color = Palette.Text, size: Int = 19, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size.dp))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Preview(vm: EditorViewModel, wide: Modifier? = null) {
    com.graden.editor.RecomposeProbe.hit("Preview")
    if (wide != null) {
        // The wide layout gives the picture a fixed area; it takes that area's shape.
        BoxWithConstraints(wide) {
            PreviewArea(vm, Modifier.fillMaxSize(), maxWidth.value / maxHeight.value.coerceAtLeast(1f))
        }
        return
    }
    // The preview area is the full width and at most square. A wide canvas (16:9, 21:9…) fills the width and the
    // area is just as tall as it needs, so wider shapes take less height. A square or tall canvas (1:1, 4:5, 9:16…)
    // gets the square area and fits inside it, with the space beside it the editor's, not the picture's.
    val canvasAspect = vm.meta.aspect
    // A square area, the canvas centred in it at its own shape: wide canvases with bars above and below, tall ones (reels)
    // with bars at the sides, so a tall canvas gets the whole height. While grading the scopes and wheels need the room,
    // so the area is wide there.
    val areaAspect = if (vm.colorOpen || vm.stabOpen || vm.cinemaOpen || vm.soundClipId != null) maxOf(canvasAspect, 1.78f) else 1f
    PreviewArea(vm, Modifier.fillMaxWidth().aspectRatio(areaAspect), areaAspect)
}

@Composable
private fun PreviewArea(vm: EditorViewModel, area: Modifier, areaAspect: Float) {
    val canvasAspect = vm.meta.aspect
    Box(area.background(Color(0xFF060709))) {
    Box(
        Modifier.align(Alignment.Center).padding(if (canvasAspect >= areaAspect - 0.01f) 0.dp else 4.dp)
            .aspectRatio(canvasAspect, matchHeightConstraintsFirst = canvasAspect < areaAspect)
            .background(Color.Black).border(1.dp, Color(0xFF26282F))
            // Whatever is moved past the edge of the canvas is outside the frame, so it is not shown.
            .clipToBounds(),
    ) {
        // Layers are composited bottom-up: V0 first, each higher layer over it, so the highest layer with a
        // clip is what shows. Each lane has two players that leapfrog across its cuts. Normally only the
        // active one is visible; during a transition both are, each shaped by its half of the transition.
        // Text lanes in their place among the pictures. Those above the top picture's lane are drawn after its
        // full-quality frame (below), so they stay over it.
        val topLane by remember { derivedStateOf { vm.topClip?.let { c -> vm.tracks.indexOfFirst { t -> t.clips.any { it.id == c.id } } } ?: -1 } }
        for ((i, t) in vm.tracks.withIndex()) {
            if (t.pictures) key(t.id) { LaneLayers(vm, t) }
            else if (t.captions && i < topLane) key(t.id) { TextLayer(vm, t) }
        }
        // At rest, the original file's frame covers the proxy the lanes are showing.
        val top by remember { derivedStateOf { vm.shownTopClip } }
        val topAsset = top?.let { vm.assets[it.assetId] }
        val topAspect = topAsset?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 9f)
        AndroidView(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val on = vm.stillVisible
                alpha = if (on) 1f else 0.004f
                if (!on) {
                    scaleX = HIDDEN_SCALE
                    scaleY = HIDDEN_SCALE
                }
            },
            factory = { ctx -> GradedVideoView(ctx, "still").apply { onInput = playerInput(vm.still); pictureOutput(vm.still, this) } },
            update = {
                it.setLook(vm.gradeFor(top), topAspect, vm.meta.scaling == "Fill", vm.cmFor(top, native = true), vm.layersFor(top))
                it.setStab(vm.stabFor(top)) { _ -> vm.stabStillUs(top) }
                it.cinema = vm.cinemaFor(top)
            },
        )
        // Proxy scrub frame, drawn over the players only while scrubbing or trimming.
        val aspect = vm.overlayAspect
        AndroidView(
            modifier = Modifier.align(Alignment.Center)
                .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < vm.meta.aspect)
                .graphicsLayer {
                    // The scrub frame stands in for the clip on top, so it sits where that clip has been placed.
                    val tf = vm.scrubTf
                    alpha = if (vm.showProxy) 1f else 0f
                    scaleX = tf.scale * (if (tf.flipH) -1f else 1f)
                    scaleY = tf.scale * (if (tf.flipV) -1f else 1f)
                    rotationZ = tf.rot
                    val parentW = size.width.coerceAtLeast(1f)
                    translationX = tf.x * parentW * (if (aspect < vm.meta.aspect) vm.meta.aspect / aspect else 1f)
                    translationY = tf.y * size.height * (if (aspect >= vm.meta.aspect) aspect / vm.meta.aspect else 1f)
                    if (tf.cropped) {
                        clip = true
                        shape = CropShape(aspect, false, tf)
                    }
                },
            factory = { ctx ->
                GradedVideoView(ctx, "scrub").apply { onInput = { s, attached -> vm.scrub.setSurface(if (attached) s else null) } }
            },
            update = {
                it.setLook(vm.gradeFor(top), 0f, false, vm.cmFor(top, native = false, scrub = true), vm.layersFor(top))
                // The scrub decoder stamps each frame with its own time.
                it.setStab(vm.stabFor(top)) { ns -> ns / 1000L }
                it.cinema = vm.cinemaFor(top)
            },
        )
        // Texts over the top picture, drawn as the export draws them (same size against the frame).
        for ((i, t) in vm.tracks.withIndex()) if (t.captions && i > topLane) key(t.id) { TextLayer(vm, t) }
        // The picture being graded feeds the scopes.
        val scopeKey by remember { derivedStateOf { vm.scopeKey() } }
        SideEffect { GradeGl.scopeTag = scopeKey }
        // Touching the canvas: select, move, resize, turn and crop pictures. Not while grading.
        if (!vm.colorOpen && !vm.stabOpen && !vm.cinemaOpen) CanvasOverlay(vm)
    }
        // Details of the picture on screen: opened from the "i" in the top bar; nothing sits on the canvas otherwise.
        val infoOpen = vm.infoOpen
        Column(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalAlignment = Alignment.End) {
            AnimatedVisibility(
                infoOpen,
                enter = scaleIn(spring(0.75f, 500f), initialScale = 0.5f, transformOrigin = TransformOrigin(1f, 0f)) + fadeIn(),
                exit = scaleOut(targetScale = 0.6f, transformOrigin = TransformOrigin(1f, 0f)) + fadeOut(),
            ) {
                Column(
                    Modifier.padding(top = 6.dp).width(214.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xF216171D))
                        .border(1.dp, Palette.Line, RoundedCornerShape(12.dp)).clickable { vm.infoOpen = false }.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val a = vm.currentAsset
                    if (a == null) Text("Nothing under the playhead", color = Palette.TextDim, fontSize = 12.sp)
                    else {
                        Text(a.name, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        val short = min(a.width, a.height)
                        InfoRow("Size", "${a.width} × ${a.height}" + if (short >= 2160) "  ·  4K" else "  ·  ${short}p")
                        if (a.isImage) InfoRow("Kind", "Picture")
                        else {
                            InfoRow("Frame rate", "${a.fps.toInt()} fps")
                            InfoRow("Codec", codecName(a.mime) + if (a.hdr) "  ·  HDR" else "")
                            InfoRow("Length", timecode(a.durationUs, a.fps))
                            InfoRow("Sound", if (a.hasAudio) "Yes" else "None")
                            InfoRow(
                                "Showing",
                                if (a.id in vm.playProxies && !vm.stillVisible) "${DeviceProfiler.proxyShortSide(a.tier)}p proxy" else "Original",
                            )
                        }
                    }
                    InfoRow("Canvas", "${vm.meta.ratioLabel}  ·  ${vm.meta.width}×${vm.meta.height}")
                    InfoRow("Project", "${vm.meta.fpsLabel} fps  ·  ${vm.meta.bitDepth}-bit")
                    InfoRow("Output", vm.meta.colorSpace + if (ProjectMeta.isHdr(vm.meta.colorSpace)) "  ·  ${vm.meta.masterNits} nits" else "")
                    InfoRow("Timeline", vm.meta.workingSpace)
                }
            }
        }
        if (vm.colorOpen && vm.wipe >= 0f) WipeLine(vm)
        if (vm.colorOpen && vm.secondaryOverlay) SecondaryOverlay(vm)
        if (vm.cinemaOpen) { CinemaOverlay(vm); CinemaPeek(vm) }
    }
}

/**
 * Over the picture while the Secondaries palette is open: the chosen secondary's window, drawn with its soft edge,
 * moved by dragging, sized by pinching and turned by twisting; or, while picking, a tap that picks the colour there.
 */
@Composable
private fun SecondaryOverlay(vm: EditorViewModel) {
    val g = vm.gradeTarget?.clip?.grade ?: return
    val i = vm.secondaryEdit
    val s = g.secondaries.getOrNull(i) ?: return
    val w = s.window
    val vmNow by rememberUpdatedState(vm)
    if (vm.pickingColour) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { p -> vmNow.pickColourAt(p.x / size.width, p.y / size.height) }
        }) {
            Text(
                "Tap the colour to select", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF2C14E)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        return
    }
    if (w.type == com.graden.editor.Window.NONE) return
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxSize().pointerInput(i) {
            awaitEachGesture {
                awaitFirstDown()
                val m = vmNow
                m.gradeStart()
                do {
                    val e = awaitPointerEvent()
                    val pan = e.calculatePan()
                    val zoom = e.calculateZoom()
                    val rot = e.calculateRotation()
                    if (pan != Offset.Zero || zoom != 1f || rot != 0f) {
                        val sw = size.width.toFloat(); val sh = size.height.toFloat()
                        m.setGrade { gr ->
                            gr.copy(secondaries = gr.secondaries.mapIndexed { k, x ->
                                if (k != i) x else x.copy(window = x.window.copy(
                                    cx = (x.window.cx + pan.x / sw).coerceIn(-0.5f, 1.5f), cy = (x.window.cy + pan.y / sh).coerceIn(-0.5f, 1.5f),
                                    w = (x.window.w * zoom).coerceIn(0.02f, 2f), h = (x.window.h * zoom).coerceIn(0.02f, 2f),
                                    angle = ((x.window.angle + rot + 540f) % 360f) - 180f,
                                ))
                            })
                        }
                    }
                    e.changes.forEach { it.consume() }
                } while (e.changes.any { it.pressed })
                m.gradeEnd()
            }
        },
    ) {
        val c = Offset(w.cx * size.width, w.cy * size.height)
        val stroke = 1.5.dp.toPx()
        val softPx = w.softness * size.height
        rotate(w.angle, c) {
            when (w.type) {
                com.graden.editor.Window.CIRCLE -> {
                    val rx = w.w * size.height; val ry = w.h * size.height
                    drawOval(Color.White, Offset(c.x - rx, c.y - ry), Size(2 * rx, 2 * ry), style = Stroke(stroke))
                    drawOval(Color.White.copy(alpha = 0.35f), Offset(c.x - rx - softPx, c.y - ry - softPx), Size(2 * (rx + softPx), 2 * (ry + softPx)),
                        style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
                }
                com.graden.editor.Window.RECT -> {
                    val rx = w.w * size.height; val ry = w.h * size.height
                    drawRect(Color.White, Offset(c.x - rx, c.y - ry), Size(2 * rx, 2 * ry), style = Stroke(stroke))
                    drawRect(Color.White.copy(alpha = 0.35f), Offset(c.x - rx - softPx, c.y - ry - softPx), Size(2 * (rx + softPx), 2 * (ry + softPx)),
                        style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
                }
                else -> {
                    // The edge of a linear window, across the frame; the selected side is above it.
                    val l = size.width * 2f
                    drawLine(Color.White, Offset(c.x - l, c.y), Offset(c.x + l, c.y), stroke)
                    for (o in listOf(-softPx, softPx)) drawLine(Color.White.copy(alpha = 0.35f), Offset(c.x - l, c.y + o), Offset(c.x + l, c.y + o), 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
                    drawLine(Color.White, c, Offset(c.x, c.y - 22.dp.toPx()), stroke)
                }
            }
            drawCircle(Color.Black.copy(alpha = 0.5f), 7.dp.toPx(), c)
            drawCircle(Color.White, 5.dp.toPx(), c)
        }
    }
}

/** The before/after split: graded on the left, as shot on the right; drag the line to move it. */
@Composable
private fun WipeLine(vm: EditorViewModel) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
        detectDragGestures { change, _ ->
            change.consume()
            vm.wipe = (change.position.x / size.width).coerceIn(0.02f, 0.98f)
        }
    }) {
        val x = maxWidth * vm.wipe
        Box(Modifier.offset(x = x - 1.dp).width(2.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.85f)))
        Box(
            Modifier.offset(x = x - 14.dp).align(Alignment.CenterStart).size(28.dp).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Text("⇆", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Text("Graded", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 1.dp))
        Text("Original", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/**
 * One picture lane of the preview: its two players (on screen and parked), shaped by any transition, and its native
 * frame at rest. A composable of its own, so what changes on one lane (a cut, a transition) recomposes only that lane.
 */
@OptIn(UnstableApi::class)
/** The captions showing at the playhead, over the canvas. */
/** Lane [t]'s text at the playhead, with its animation, drawn as the export draws it. */
@Composable
private fun TextLayer(vm: EditorViewModel, t: com.graden.editor.Track) {
    val hidden by remember(t.id) { derivedStateOf { t.id in vm.hiddenTracks } }
    if (hidden) return
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        // Read while drawing: playback redraws this layer, without recomposing the preview.
        val us = vm.playheadUs
        val lane = vm.tracks.firstOrNull { it.id == t.id } ?: return@Canvas
        val clip = lane.clipAt(us) ?: return@Canvas
        val cap = clip.caption?.takeIf { it.text.isNotBlank() } ?: return@Canvas
        val area = com.graden.editor.CaptionRender.videoRect(vm.tracks, vm.assets, vm.hiddenTracks, us, size.width, size.height, vm.meta.scaling == "Fill")
        // An animation being tried out in the text panel plays here, at its own time.
        val demo = vm.textDemo?.takeIf { it.first == clip.id }?.second
        drawIntoCanvas { c -> com.graden.editor.CaptionRender.draw(c.nativeCanvas, cap, area, demo ?: (us - clip.startUs), clip.durationUs) }
    }
}

@Composable
private fun LaneLayers(vm: EditorViewModel, t: com.graden.editor.Track) {
        val lp = vm.layer(t.id)
        // Derived per lane: a change on another lane (a cut, a clip starting) does not recompose this one.
        val shown by remember(t.id) { derivedStateOf { vm.trackVisible[t.id] == true && t.id !in vm.hiddenTracks } }
        // Read through derived states: the preview then recomposes when the clip under the playhead (or a
        // transition) changes, not on every frame of playback.
        val fx by remember(t, shown) { derivedStateOf { if (shown) vm.laneFxShape(t) else null } }
        val centre by remember(t) { derivedStateOf { vm.centreScale(t) } }
        // At the very end of a clip the held last frame keeps that clip's shape (it was drawn 16:9, stretched).
        val here by remember(t) { derivedStateOf { t.shownAt(vm.playheadUs) } }
        if (lp != null) Box(Modifier.fillMaxSize()) {
            fx?.backdrop?.let { Box(Modifier.fillMaxSize().background(it)) }
            for (k in 0..1) {
                val looking = fx?.looking?.get(k) == true
                val noFx = fx == null
                // The clip this player shows, for its place on the canvas.
                val shownClip = fx?.clips?.get(k) ?: here
                val tf = shownClip?.tf ?: com.graden.editor.Tf()
                val asset = shownClip?.let { vm.assets[it.assetId] }
                val pictureAspect = asset?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 9f)
                val cropShown = tf.cropped
                AndroidView(
                    modifier = Modifier.fillMaxSize().zIndex(fx?.z?.get(k) ?: 0f).graphicsLayer {
                        // The transition's progress is read here, in the layer, so it moves every frame without
                        // recomposing the preview.
                        val look = if (looking) vm.laneFx(t)?.looks?.get(k) else null
                        // Hidden players keep a trace of opacity: a fully transparent view is not drawn at all, and a
                        // video surface that is not drawn stops taking frames, which stalls its decoder.
                        // Placed by hand (tf), then any transition or animation on top of that.
                        val s = centre * tf.scale
                        val mx = if (tf.flipH) -1f else 1f
                        val my = if (tf.flipV) -1f else 1f
                        scaleX = s * mx
                        scaleY = s * my
                        translationX = tf.x * size.width
                        translationY = tf.y * size.height
                        rotationZ = tf.rot
                        if (cropShown) {
                            clip = true
                            shape = CropShape(pictureAspect, vm.meta.scaling == "Fill", tf)
                        }
                        if (look == null) {
                            // Which player is on screen is read here, in the layer: a cut moves layers, it does not recompose.
                            val activeHere = vm.layerActive[t.id] == k
                            val visible = (shown && noFx && activeHere) || (looking && activeHere)
                            alpha = if (visible) 1f else 0.004f
                            // A hidden player's picture is shrunk to a few pixels as well: it keeps taking frames (so
                            // it is ready the instant a cut shows it), but the GPU no longer composites a full-size
                            // invisible video layer for it every frame.
                            if (!visible) {
                                scaleX = HIDDEN_SCALE
                                scaleY = HIDDEN_SCALE
                            }
                        }
                        else {
                            alpha = look.alpha.coerceIn(0.004f, 1f)
                            translationX += look.tx * size.width
                            translationY += look.ty * size.height
                            scaleX = look.sx * s * mx
                            scaleY = look.sy * s * my
                            rotationZ = tf.rot + look.rot
                            if (look.clipped) {
                                clip = true
                                shape = LookShape(look)
                            }
                        }
                    },
                    // Drawn through the grade on the GPU, so a change to the grade shows at once, playing or paused.
                    factory = { ctx ->
                        GradedVideoView(ctx, "L${t.id}-$k").apply { onInput = playerInput(lp.players[k]); pictureOutput(lp.players[k], this) }
                    },
                    update = {
                        it.setLook(vm.gradeFor(shownClip), pictureAspect, vm.meta.scaling == "Fill", vm.cmFor(shownClip, native = false), vm.layersFor(shownClip))
                        // Playing: the exact frame on screen, from the player's record of what it released with that stamp.
                        val clipNow = shownClip
                        it.setStab(vm.stabFor(clipNow)) { ns -> lp.frameForStamp(k, ns) ?: vm.stabStillUs(clipNow) }
                        it.cinema = vm.cinemaFor(clipNow)
                    },
                )
            }
            // At rest: the original file's own frame, placed and graded exactly like the clip, over the proxy picture.
            // Shown once it has arrived; gone the moment anything moves.
            val restOn = vm.restReady[t.id] != null && fx == null && here != null
            val restTf = here?.tf ?: com.graden.editor.Tf()
            val restAsset = here?.let { vm.assets[it.assetId] }
            val restAspect = restAsset?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 9f)
            AndroidView(
                modifier = Modifier.fillMaxSize().zIndex(2f).graphicsLayer {
                    val s = centre * restTf.scale
                    scaleX = s * (if (restTf.flipH) -1f else 1f)
                    scaleY = s * (if (restTf.flipV) -1f else 1f)
                    translationX = restTf.x * size.width
                    translationY = restTf.y * size.height
                    rotationZ = restTf.rot
                    if (restTf.cropped) {
                        clip = true
                        shape = CropShape(restAspect, vm.meta.scaling == "Fill", restTf)
                    }
                    alpha = if (restOn) 1f else 0.004f
                    if (!restOn) {
                        scaleX = HIDDEN_SCALE
                        scaleY = HIDDEN_SCALE
                    }
                },
                factory = { ctx -> GradedVideoView(ctx, "R${t.id}") },
                update = {
                    it.setLook(vm.gradeFor(here), restAspect, vm.meta.scaling == "Fill", vm.cmFor(here, native = true), vm.layersFor(here))
                    it.setStab(vm.stabFor(here)) { _ -> vm.stabStillUs(here) }
                    it.cinema = vm.cinemaFor(here)
                },
            )
        }
}

/**
 * Playback meter under the "i": frames actually shown per second by the picture on top, and frames
 * the decoder had to drop per second. Read twice a second from the player's own counters while
 * playing; when paused it keeps the last reading, dimmed.
 */
/**
 * Frames really shown per second by the picture on top, and frames the decoder had to drop per second, read twice a
 * second from the player's own counters while playing.
 */
@OptIn(UnstableApi::class)
@Composable
private fun rememberFps(vm: EditorViewModel): Pair<Float, Float> {
    var fps by remember { mutableFloatStateOf(0f) }
    var dropped by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(vm.isPlaying) {
        if (!vm.isPlaying) return@LaunchedEffect
        var player: ExoPlayer? = null
        var rendered = 0
        var drops = 0
        var at = System.nanoTime()
        while (true) {
            delay(500)
            val p = vm.topPlayer()
            val c = p?.videoDecoderCounters?.also { it.ensureUpdated() }
            val now = System.nanoTime()
            if (p == null || c == null) {
                player = null
                continue
            }
            // The players take turns at each cut; start counting afresh when the one on screen changes.
            if (p === player) {
                val seconds = (now - at) / 1e9f
                fps = (c.renderedOutputBufferCount - rendered) / seconds
                dropped = (c.droppedBufferCount - drops) / seconds
            }
            player = p
            rendered = c.renderedOutputBufferCount
            drops = c.droppedBufferCount
            at = now
        }
    }
    return fps to dropped
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.width(74.dp))
        Text(value, color = Soft, fontSize = 11.sp, fontFamily = Mono)
    }
}

private fun codecName(mime: String) = when (mime) {
    "video/avc" -> "H.264"
    "video/hevc" -> "H.265"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/av01" -> "AV1"
    else -> mime.substringAfter('/').uppercase()
}

/**
 * Timecode, then the transport and history buttons. The timecode has a fixed width, so the buttons
 * hold still while the digits change, and everything fits without scrolling.
 */
@Composable
private fun Transport(vm: EditorViewModel) {
    TransportRow(vm)
}





/**
 * Sizes for the transport deck, as fractions of the screen width rather than fixed dp: the deck keeps the same proportions
 * on every phone, smaller on narrow screens and larger on wide ones, and ignores the system font size.
 */
private class DeckUnit(val u: androidx.compose.ui.unit.Dp, private val density: Density) {
    fun dp(v: Float) = u * v
    fun sp(v: Float) = with(density) { (u * v).toPx().toSp() / fontScale }
}

/**
 * The transport, as two decks so nothing ever crowds or moves:
 *  - an info line: the playhead's timecode and the project's length on the left; on the right what the preview is showing
 *    (native, edit proxy, scrub proxy) and, while playing, the live frame rate;
 *  - the deck: the playback controls truly centred, undo and redo at the right edge.
 */
@Composable
private fun TransportRow(vm: EditorViewModel, deck: Boolean = true) {
    com.graden.editor.RecomposeProbe.hit("TransportRow")
    BoxWithConstraints(Modifier.fillMaxWidth().background(Color(0xFF111217))) {
        // Capped, so on a tablet or open foldable the deck stays phone-sized instead of growing with the width.
        val k = DeckUnit(maxWidth.coerceAtMost(440.dp) / 400f, LocalDensity.current)
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(k.dp(26f)).padding(horizontal = k.dp(14f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayheadText(vm, Palette.Accent, k)
                Text(
                    "  /  " + vm.tc(vm.totalUs), color = Palette.TextDim.copy(alpha = 0.75f), fontSize = k.sp(11f), fontFamily = Mono,
                    maxLines = 1, softWrap = false,
                )
                Spacer(Modifier.weight(1f))
                HdrToggle(vm, k.sp(10.5f), k.dp(8f), k.dp(3f))
                Spacer(Modifier.width(k.dp(6f)))
                SourceTag(vm, k)
            }
            if (deck) Box(Modifier.fillMaxWidth().height(k.dp(56f)).padding(horizontal = k.dp(10f))) {
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(k.dp(10f)), verticalAlignment = Alignment.CenterVertically) {
                    DeckButton(Lucide.PrevCut, "Previous cut", k) { vm.jumpClip(-1) }
                    DeckButton(Lucide.FrameBack, "Back one frame", k) { vm.stepFrames(-1) }
                    // Play is the one button used constantly, so it is the one that stands out.
                    Box(
                        Modifier.size(k.dp(46f)).shadow(k.dp(6f), CircleShape, ambientColor = Palette.Accent, spotColor = Palette.Accent).clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(Color(0xFFF59A45), Palette.Accent))).clickable { vm.togglePlay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (vm.isPlaying) Lucide.Pause else Lucide.Play, if (vm.isPlaying) "Pause" else "Play", tint = Color(0xFF15161C),
                            modifier = Modifier.padding(start = if (vm.isPlaying) 0.dp else k.dp(2f)).size(k.dp(20f)),
                        )
                    }
                    DeckButton(Lucide.FrameForward, "Forward one frame", k) { vm.stepFrames(1) }
                    DeckButton(Lucide.NextCut, "Next cut", k) { vm.jumpClip(1) }
                }
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                    DeckButton(Lucide.Undo, "Undo", k, enabled = vm.canUndo, filled = false) { vm.undo() }
                    DeckButton(Lucide.Redo, "Redo", k, enabled = vm.canRedo, filled = false) { vm.redo() }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        }
    }
}

/** Where the player sits in the tablet layout; the Layout button moves it. Kept for next time. */
private enum class PlayerSpot { CENTER, LEFT, RIGHT, FULL }

/**
 * The tablet and open-foldable editor, as desktop editors and CapCut Pad have it: the media docked on one side, the
 * player, and the selected clip's tools beside it; under them one toolbar (clip tools, playback, undo) and the timeline
 * the full width at the bottom, where the hands are, its height dragged by the bar above it. The Layout button puts the
 * player in the middle, on the left, on the right, or across the whole top. A tall canvas (a reel) gets a player the
 * full height of the screen at one side instead. Every width is a share of the window with limits, so any screen fits.
 */
@Composable
private fun Studio(
    vm: EditorViewModel, soon: (String) -> Unit, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp,
    share: Float, onShare: (Float) -> Unit, topBar: @Composable () -> Unit, media: @Composable () -> Unit, onTimelineBounds: (Rect) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("layout", android.content.Context.MODE_PRIVATE) }
    var spot by remember { mutableStateOf(runCatching { PlayerSpot.valueOf(prefs.getString("player", "CENTER")!!) }.getOrDefault(PlayerSpot.CENTER)) }
    fun place(p: PlayerSpot) { spot = p; prefs.edit().putString("player", p.name).apply() }
    // The media and the tools can change sides.
    var swapped by remember { mutableStateOf(prefs.getBoolean("swapped", false)) }
    val tall = vm.meta.aspect < 0.8f
    val line = Color(0xFF1C1E24)
    // Narrower screens (open foldables) leave out the side panel; its tools move into the toolbar.
    val roomy = w >= 1000.dp
    val mediaW = (w * 0.25f).coerceIn(230.dp, 360.dp)
    val sideW = (w * 0.2f).coerceIn(220.dp, 300.dp)
    // Color open: its panel takes the side (on a narrower screen, the media's place), wide enough for the wheels.
    val grading = vm.colorOpen
    // Sound opens in the same place as Color: the panel, with the picture and the editor's timeline still in view.
    val sounding = !grading && vm.soundClipId != null
    val page: @Composable (Modifier) -> Unit = { m -> if (grading) ColorSidePanel(vm, m) else SoundBody(vm, soon, m, strip = false) }
    val colorW = (w * (if (roomy) 0.28f else 0.44f)).coerceIn(330.dp, 440.dp)
    val hPx = with(LocalDensity.current) { h.toPx() }
    var menu by remember { mutableStateOf(false) }
    // While grading the wheels need the height: the timeline keeps a smaller share unless dragged bigger then.
    var gradeShare by remember { mutableFloatStateOf(0.42f) }
    val share = if (grading || sounding) gradeShare else share
    val onShare: (Float) -> Unit = if (grading || sounding) ({ gradeShare = it }) else onShare
    // Panes stand apart by the gaps between them now, not by lines.
    val vline: @Composable () -> Unit = {}
    val inspector: @Composable () -> Unit = {
        if (grading || sounding) page(Modifier.width(colorW).fillMaxHeight().pane()) else ToolPanel(vm, soon, Modifier.width(sideW).fillMaxHeight().pane())
    }
    // On a narrower screen the media column makes way for Color while grading.
    val side: @Composable () -> Unit = {
        if ((grading || sounding) && !roomy) page(Modifier.width(colorW).fillMaxHeight().pane()) else Box(Modifier.width(mediaW).fillMaxHeight().pane()) { media() }
    }
    val player: @Composable (Modifier) -> Unit = { m -> Preview(vm, m.pane()) }
    val bottom: @Composable ColumnScope.() -> Unit = {
        Toolbar(vm, soon, tools = !roomy)
        Box(
            Modifier.fillMaxWidth().height(14.dp).background(Color(0xFF0E0F13))
                .resizeBar(hPx, share, 0.25f, 0.62f, onShare),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3D47))) }
        Column(Modifier.fillMaxWidth().weight(1f)) { EditBody(vm, soon, tools = false, transport = false, onTimelineBounds = onTimelineBounds) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { topBar() }
            Box {
                Row(
                    Modifier.padding(end = 10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1D24)).clickable { menu = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.SplitView, null, tint = Palette.Text, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Layout", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = Color(0xFF1C1D24)) {
                    val options = if (tall) listOf(PlayerSpot.RIGHT to "Player on the right", PlayerSpot.LEFT to "Player on the left")
                    else listOf(PlayerSpot.CENTER to "Player in the middle", PlayerSpot.LEFT to "Player on the left",
                        PlayerSpot.RIGHT to "Player on the right", PlayerSpot.FULL to "Player across the top")
                    for ((p, label) in options) androidx.compose.material3.DropdownMenuItem(
                        { Text(label, color = if (p == spot) Palette.Accent else Palette.Text, fontSize = 14.sp) },
                        { place(p); menu = false },
                        trailingIcon = { if (p == spot) Icon(Lucide.Check, null, tint = Palette.Accent, modifier = Modifier.size(16.dp)) },
                    )
                    if (roomy) androidx.compose.material3.DropdownMenuItem(
                        { Text(if (swapped) "Media left, tools right" else "Tools left, media right", color = Palette.Text, fontSize = 14.sp) },
                        { swapped = !swapped; prefs.edit().putBoolean("swapped", swapped).apply(); menu = false },
                        leadingIcon = { Icon(Lucide.ArrowUpDown, null, tint = Palette.TextDim, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        if (tall) {
            // A reel: the player the full height at one side, at its own shape; everything else beside it.
            val pw = (h * vm.meta.aspect.coerceAtLeast(0.3f) + 8.dp).coerceAtMost(w * 0.42f)
            val rest: @Composable RowScope.() -> Unit = {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth().weight(1f - share), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (roomy && swapped) { inspector(); vline() }
                        if ((grading || sounding) && !roomy) page(Modifier.weight(1f).fillMaxHeight().pane())
                        else Box(Modifier.weight(1f).fillMaxHeight().pane()) { media() }
                        if (roomy && !swapped) { vline(); inspector() }
                    }
                    Column(Modifier.fillMaxWidth().weight(share).pane()) { bottom() }
                }
            }
            Row(Modifier.fillMaxSize().background(Ground).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (spot == PlayerSpot.LEFT) { player(Modifier.width(pw).fillMaxHeight()); vline(); rest() }
                else { rest(); vline(); player(Modifier.width(pw).fillMaxHeight()) }
            }
        } else if (grading || sounding) {
            // Color or Sound open: the page takes a whole side, top to bottom, so the wheels, scopes and waveform get the
            // height; the picture and the timeline share the other side, the timeline only under the picture.
            val pageW = (w * 0.42f).coerceIn(380.dp, 640.dp)
            val pageSide: @Composable () -> Unit = { page(Modifier.width(pageW).fillMaxHeight().pane()) }
            Row(Modifier.fillMaxSize().background(Ground).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (swapped) pageSide()
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    player(Modifier.fillMaxWidth().weight(1f - share))
                    Column(Modifier.fillMaxWidth().weight(share).pane()) { bottom() }
                }
                if (!swapped) pageSide()
            }
        } else Column(Modifier.fillMaxSize().background(Ground).padding(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f - share), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                // The two panels in their chosen order (media first, or tools first when swapped).
                val first: @Composable () -> Unit = if (swapped && roomy) inspector else side
                val second: (@Composable () -> Unit)? = if (!roomy) null else if (swapped) side else inspector
                when (spot) {
                    PlayerSpot.CENTER -> {
                        first(); vline()
                        player(Modifier.weight(1f).fillMaxHeight())
                        second?.let { vline(); it() }
                    }
                    PlayerSpot.LEFT -> {
                        player(Modifier.weight(1f).fillMaxHeight()); vline()
                        first()
                        second?.let { vline(); it() }
                    }
                    PlayerSpot.RIGHT -> {
                        first()
                        second?.let { vline(); it() }
                        vline(); player(Modifier.weight(1f).fillMaxHeight())
                    }
                    PlayerSpot.FULL -> {
                        player(Modifier.weight(1f).fillMaxHeight())
                        if (grading || sounding) { vline(); page(Modifier.width(colorW).fillMaxHeight().pane()) }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().weight(share).pane()) { bottom() }
        }
    }
}

/**
 * The tools panel: the clip's menu (Color, Effects, Crop, Stabilize, Cinema DOF, Sound, Captions, Speed and the rest),
 * always in view. Color opens right here in its place, with the timeline still below; the others open their pages.
 * The project's details are behind the info button in the top bar.
 */
@Composable
private fun ToolPanel(vm: EditorViewModel, soon: (String) -> Unit, modifier: Modifier) {
    Column(modifier.background(Color(0xFF131419)).verticalScroll(rememberScrollState())) {
        Text("Tools", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 9.dp))
        if (vm.selection != Selection.NONE) Tools(vm, soon, panel = true)
        else {
            Text("Tap a clip on the timeline for its tools.", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            Box(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A1F15))
                    .clickable { vm.selectAtPlayhead() }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Select the clip at the playhead", color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * The bar over the timeline: the timecode, playback in the middle, undo and redo at the right; with [tools], the
 * selected clip's tools in a strip above it (when there is no side panel for them).
 */
@Composable
private fun Toolbar(vm: EditorViewModel, soon: (String) -> Unit, tools: Boolean, compact: Boolean = false) {
    val k = DeckUnit(1.dp, LocalDensity.current)
    Column(Modifier.fillMaxWidth().background(Color(0xFF111217))) {
        // No side panel: the clip's tools get their own strip, the full width, as on a phone.
        if (tools && vm.selection != Selection.NONE) Tools(vm, soon)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        Row(Modifier.fillMaxWidth().height(if (compact) 44.dp else 58.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                PlayheadText(vm, Palette.Accent, k)
                Text("  /  " + vm.tc(vm.totalUs), color = Palette.TextDim.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = Mono, maxLines = 1, softWrap = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!compact) DeckButton(Lucide.PrevCut, "Previous cut", k) { vm.jumpClip(-1) }
                DeckButton(Lucide.FrameBack, "Back one frame", k) { vm.stepFrames(-1) }
                Box(
                    Modifier.size(if (compact) 36.dp else 46.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFF59A45), Palette.Accent))).clickable { vm.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (vm.isPlaying) Lucide.Pause else Lucide.Play, if (vm.isPlaying) "Pause" else "Play", tint = Color(0xFF15161C),
                        modifier = Modifier.padding(start = if (vm.isPlaying) 0.dp else 2.dp).size(20.dp))
                }
                DeckButton(Lucide.FrameForward, "Forward one frame", k) { vm.stepFrames(1) }
                if (!compact) DeckButton(Lucide.NextCut, "Next cut", k) { vm.jumpClip(1) }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!compact) {
                    HdrToggle(vm)
                    Spacer(Modifier.width(6.dp))
                    SourceTag(vm, k)
                    Spacer(Modifier.width(6.dp))
                }
                DeckButton(Lucide.Undo, "Undo", k, enabled = vm.canUndo, filled = false) { vm.undo() }
                DeckButton(Lucide.Redo, "Redo", k, enabled = vm.canRedo, filled = false) { vm.redo() }
            }
        }
    }
}

/** A round transport button: a quiet circle behind a solid icon, dimmed when it can do nothing. */
@Composable
private fun DeckButton(icon: ImageVector, label: String, k: DeckUnit, enabled: Boolean = true, filled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(k.dp(if (filled) 38f else 34f)).clip(CircleShape).background(if (filled) Color(0xFF1C1D24) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) Color(0xFFE4E5EA) else Soft.copy(alpha = 0.28f), modifier = Modifier.size(k.dp(if (filled) 17f else 19f)))
    }
}

/**
 * The playhead's timecode, drawn rather than laid out: the playhead is read only while drawing, so the digits changing
 * every frame cost one small redraw, with no recomposition, measuring or layout.
 */
@Composable
private fun PlayheadText(vm: EditorViewModel, color: Color, k: DeckUnit) {
    val density = LocalDensity.current
    val px = with(density) { k.dp(15f).toPx() }
    val paint = remember(px, color) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            textSize = px
            this.color = color.toArgb()
        }
    }
    val width = with(density) { (paint.measureText("00:00:00:00") + 2f).toDp() }
    Box(Modifier.width(width).height(k.dp(20f)).drawBehind {
        val text = vm.tc(vm.playheadUs)
        val y = size.height / 2 - (paint.ascent() + paint.descent()) / 2
        drawContext.canvas.nativeCanvas.drawText(text, 0f, y, paint)
    })
}

/**
 * What the pictures in the preview come from right now: the scrub proxy while dragging, the edit proxy while playing (or
 * until the originals' frames arrive), the originals' own frames at rest, or a proxy still being made; with the live frame
 * rate while playing. A small pill on the right of the info line.
 */
/** HDR / SDR switch for the preview, on phones whose screen shows HDR. */
@Composable
internal fun HdrToggle(
    vm: EditorViewModel, size: androidx.compose.ui.unit.TextUnit = 10.5.sp,
    padH: androidx.compose.ui.unit.Dp = 8.dp, padV: androidx.compose.ui.unit.Dp = 3.dp,
) {
    if (!com.graden.editor.GradeGl.hdrSupported) return
    val on = vm.hdrPreview
    Text(
        if (on) "HDR" else "SDR", color = if (on) Color.Black else Palette.Text.copy(alpha = 0.8f), fontSize = size, fontWeight = FontWeight.Bold,
        maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(0xFFF2C14E) else Color(0xFF1A1B21))
            .clickable { vm.hdrChoice = !on }.padding(horizontal = padH, vertical = padV),
    )
}

@Composable
private fun SourceTag(vm: EditorViewModel, k: DeckUnit) {
    val source by remember { derivedStateOf { vm.previewSource } }
    val (kind, text) = source
    val dot = when (kind) {
        EditorViewModel.Source.NATIVE -> Color(0xFF4CD787)
        EditorViewModel.Source.EDIT -> Color(0xFF6FA0FF)
        EditorViewModel.Source.SCRUB, EditorViewModel.Source.OPTIMIZING -> Color(0xFFF0A23B)
    }
    val (fps, drops) = rememberFps(vm)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1A1B21)).padding(horizontal = k.dp(8f), vertical = k.dp(3f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(k.dp(6f)).clip(CircleShape).background(dot))
        Spacer(Modifier.width(k.dp(5f)))
        Text(text, color = Palette.Text.copy(alpha = 0.8f), fontSize = k.sp(10.5f), maxLines = 1, softWrap = false)
        if (vm.isPlaying && fps > 0f) {
            Text(
                "  %.0f fps".format(fps) + if (drops >= 0.5f) " · %.0f drop".format(drops) else "",
                color = if (drops >= 0.5f) Palette.Danger else Palette.TextDim, fontSize = k.sp(10.5f), fontFamily = Mono, maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
private fun TransportIcon(icon: ImageVector, label: String, tint: Color = Soft, size: Int = 20, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size.dp))
    }
}

/**
 * Clip tools, in a row that scrolls sideways. Only tools that can do something to what is selected
 * are offered: sound has no picture to crop, flip or animate; a still picture has nothing to
 * reverse; with nothing selected there is nothing to delete or copy. The tinted tiles are the
 * effect-type tools, as in the design.
 */
@Composable
private fun Tools(vm: EditorViewModel, soon: (String) -> Unit, panel: Boolean = false) {
    com.graden.editor.RecomposeProbe.hit("Tools")
    Column(Modifier.fillMaxWidth().background(Color(0xFF131419))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        // On a phone the tools are one strip that scrolls; in a tablet's side panel they wrap into rows.
        if (panel) Wrap(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), 4.dp, 6.dp) { ToolItems(vm, soon) }
        else Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) { ToolItems(vm, soon) }
    }
}

/** Lays its items out left to right, starting a new row when one is full. */
@Composable
private fun Wrap(modifier: Modifier, gapX: androidx.compose.ui.unit.Dp, gapY: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { items, c ->
        val gx = gapX.roundToPx(); val gy = gapY.roundToPx()
        val placed = items.map { it.measure(c.copy(minWidth = 0, minHeight = 0)) }
        val spots = ArrayList<IntOffset>()
        var x = 0; var y = 0; var rowH = 0
        for (p in placed) {
            if (x > 0 && x + p.width > c.maxWidth) { x = 0; y += rowH + gy; rowH = 0 }
            spots += IntOffset(x, y)
            x += p.width + gx
            rowH = maxOf(rowH, p.height)
        }
        layout(c.maxWidth, (y + rowH).coerceAtLeast(c.minHeight)) { placed.forEachIndexed { i, p -> p.place(spots[i]) } }
    }
}

@Composable
private fun ToolItems(vm: EditorViewModel, soon: (String) -> Unit) {
    val kind = vm.selection
    val video = kind == Selection.VIDEO || kind == Selection.SILENT_VIDEO
    val picture = video || kind == Selection.PICTURE
    val sound = kind == Selection.AUDIO || kind == Selection.VIDEO
    if (kind == Selection.CAPTION) {
        // A text (caption or title): its words, font, look and animation; split, delete and copy; its length by
        // its handles on the timeline, its place on the picture by dragging it there.
        Tool(Lucide.TypeT, "Edit", tinted = true) { vm.selectedClipId?.let { vm.openText(it, 0) } }
        Tool(Lucide.FontAa, "Font", tinted = true) { vm.selectedClipId?.let { vm.openText(it, 1) } }
        Tool(Lucide.Palette, "Style", tinted = true) { vm.selectedClipId?.let { vm.openText(it, 2) } }
        Tool(Lucide.AnimIn, "Animate", tinted = true) { vm.selectedClipId?.let { vm.openText(it, 3) } }
        val canSplit by remember { derivedStateOf { vm.canSplit } }
        Tool(Lucide.Split, "Split", enabled = canSplit) { vm.split() }
        Tool(Lucide.Trash, "Delete", color = Palette.Danger) { vm.deleteSelected() }
        Tool(Lucide.Copy, "Duplicate") { vm.duplicateSelected() }
        return
    }
    // Trim opens the trim page; a picture has no length of its own to trim, so it just shows its handles.
    Tool(Lucide.Scissors, "Trim") { if (!vm.openTrim()) vm.selectAtPlayhead() }
    val canSplit by remember { derivedStateOf { vm.canSplit } }
    Tool(Lucide.Split, "Split", enabled = canSplit) { vm.split() }
    Tool(Lucide.Trash, "Delete", color = Palette.Danger) { vm.deleteSelected() }
    if (picture) {
        Tool(Lucide.Palette, "Color", tinted = true) { vm.openColor() }
        Tool(Lucide.Sparkles, "Effects", tinted = true) { soon("Effects") }
        Tool(Lucide.Crop, "Crop") { vm.openCrop() }
        if (video) Tool(Lucide.Stabilize, "Stabilize", tinted = true) { vm.openStab() }
        if (video) Tool(Lucide.Aperture, "Cinema DOF", tinted = true, width = 70.dp) { vm.openCinema() }
        Tool(Lucide.AnimIn, "In") { vm.selectedClipId?.let { vm.openFx(it, EditorViewModel.FxSlot.IN) } }
        Tool(Lucide.AnimOut, "Out") { vm.selectedClipId?.let { vm.openFx(it, EditorViewModel.FxSlot.OUT) } }
    }
    if (sound) Tool(Lucide.AudioLines, "Sound", tinted = true) { vm.openSound() }
    if (sound) Tool(Lucide.Captions, "Captions", tinted = true) { vm.pause(); vm.openCaptions(vm.selectedClipId) }
    // A video's sound copied out onto its own lane; the video keeps its sound until muted on its Sound page.
    if (kind == Selection.VIDEO) Tool(Lucide.Volume, "Extract") { vm.extractSound() }
    if (kind != Selection.PICTURE) Tool(Lucide.Gauge, "Speed") { vm.openTrim(bySpeed = true) }
    Tool(Lucide.Copy, "Duplicate") { vm.duplicateSelected() }
    // Lit while the clip plays backwards; while its reversed copy is being made, tapping again stops it.
    if (kind != Selection.PICTURE) {
        val busy = vm.selectedClipId in vm.reversing
        Tool(Lucide.RotateCcw, if (busy) "Stop" else "Reverse", color = if (vm.selectedReversed || busy) Palette.Accent else Soft) { vm.reverseSelected() }
    }

}

@Composable
private fun Tool(icon: ImageVector, label: String, enabled: Boolean = true, tinted: Boolean = false, color: Color = Soft, width: androidx.compose.ui.unit.Dp = 54.dp, onClick: () -> Unit) {
    val ink = if (tinted) Palette.Accent else color
    Column(
        Modifier.width(width).clip(RoundedCornerShape(10.dp)).background(if (tinted) Color(0xFF2A1F15) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.35f).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = ink, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

/**
 * Pages. Tapping the page you are on opens or folds its tools. Pages that have nothing to offer the
 * selected clip are left out: no Color or Effects for sound, no Audio for a picture or a silent video.
 * Only Edit exists so far; the others say so when tapped.
 */
@Composable
private fun BottomNav(kind: Selection, onEdit: () -> Unit, soon: (String) -> Unit) {
    val hasPicture = kind != Selection.AUDIO
    val hasSound = kind != Selection.PICTURE && kind != Selection.SILENT_VIDEO
    Column(Modifier.fillMaxWidth().background(Color(0xFF111217))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        Row(Modifier.fillMaxWidth()) {
            NavItem(Lucide.Scissors, "Edit", active = true, onClick = onEdit)
            if (hasPicture) NavItem(Lucide.Palette, "Color") { soon("The Color page") }
            if (hasPicture) NavItem(Lucide.Sparkles, "Effects") { soon("The Effects page") }
            if (hasSound) NavItem(Lucide.AudioLines, "Audio") { soon("The Audio page") }
            // Export lives in the top bar; this place is kept for the AI tools to come.
            NavItem(Lucide.Bot, "AI") { soon("AI tools") }
        }
    }
}

@Composable
private fun RowScope.NavItem(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val ink = if (active) Palette.Accent else Soft
    Column(Modifier.weight(1f).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(26.dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(if (active) Palette.Accent else Color.Transparent))
        Spacer(Modifier.height(6.dp))
        Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (active) Palette.Accent else Palette.TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(5.dp))
    }
}

/** The export button: a small menu drops from it, to export the video or the whole project as a .graden file. */
@Composable
private fun ExportMenu(vm: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    val saver = rememberBundleSaver(vm)
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    shown.targetState = open
    BundleStatus(vm, Modifier)
    Box {
        BarIcon(Lucide.Upload, "Export") { vm.pause(); open = !open }
        if (shown.currentState || shown.targetState) androidx.compose.ui.window.Popup(
            alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.roundToPx() }),
            onDismissRequest = { open = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        ) {
            androidx.compose.animation.AnimatedVisibility(
                shown,
                enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn() +
                    androidx.compose.animation.scaleIn(initialScale = 0.92f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)),
                exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut(),
            ) {
                val shape = RoundedCornerShape(18.dp)
                Column(
                    Modifier.padding(end = 8.dp).width(230.dp).shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape)
                        .background(Kit.RaisedFill).border(1.dp, Kit.Rim, shape).padding(6.dp),
                ) {
                    ExportChoice(Lucide.Film, "Export media", "The finished video") { open = false; vm.exportOpen = true }
                    ExportChoice(Lucide.Folder, "Export project", "A .graden file with all its media") { open = false; saver(null, vm.meta.name) }
                }
            }
        }
    }
}

@Composable
private fun ExportChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, note: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Kit.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Kit.Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Kit.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(note, color = Kit.Dim, fontSize = 12.sp)
        }
    }
}

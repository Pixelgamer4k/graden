package com.graden.editor.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Graden's building blocks for its sheets and pages (project setup, export, media, captions, text): one quiet set of
 * surfaces, rows and controls, so every page reads the same way. Settings sit in rounded groups of rows, as on a
 * phone's own settings: a label on the left, its value or control on the right; the rarely needed ones folded away
 * under "Advanced".
 */
object Kit {
    val Bg = Color(0xFF0E0F13)
    val Sheet = Color(0xFF131419)
    val Group = Color(0xFF1B1C22)
    val Raised = Color(0xFF24262E)
    val Hair = Color(0x14FFFFFF)
    val Text = Color(0xFFF2F2F4)
    val Dim = Color(0xFF9A9DA8)
    val Faint = Color(0xFF5F626D)
    val Accent = Palette.Accent
    val AccentSoft = Color(0x24F0872B)
    val Good = Color(0xFF4CD787)
    val Bad = Color(0xFFE5484D)
    val Ink = Color(0xFF15161C)
    val Primary = Brush.verticalGradient(listOf(Color(0xFFF9AE62), Color(0xFFEE8526), Color(0xFFDD6F14)))
    /** A raised surface: lit from above, a little darker at the foot. */
    val RaisedFill = Brush.verticalGradient(listOf(Color(0xFF2C2E37), Color(0xFF22242B)))
    val GroupFill = Brush.verticalGradient(listOf(Color(0xFF1E1F26), Color(0xFF191A20)))
    /** The light catching a raised edge: brighter at the top. */
    val Rim = Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x08FFFFFF)))
    val PrimaryRim = Brush.verticalGradient(listOf(Color(0x80FFE3C2), Color(0x14FFFFFF)))
}

/** The small handle at the top of a sheet. */
@Composable
fun KGrabber() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(Color(0x33FFFFFF)))
    }
}

/** A page or sheet's title, with what it is about under it, a close button and room for an action. */
@Composable
fun KHeader(title: String, subtitle: String? = null, onClose: (() -> Unit)? = null, closeIcon: ImageVector = Lucide.X, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Kit.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = Kit.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp)) }
        }
        trailing?.invoke(this)
        if (onClose != null) KIconButton(closeIcon, "Close", onClose)
    }
}

/** A round icon button. [filled]: the accent colour, for the one main action of a page. */
@Composable
fun KIconButton(icon: ImageVector, label: String, onClick: () -> Unit, filled: Boolean = false, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).shadow(if (filled) 8.dp else 3.dp, CircleShape, ambientColor = if (filled) Kit.Accent else Color.Black, spotColor = if (filled) Kit.Accent else Color.Black)
            .clip(CircleShape).background(if (filled) Kit.Primary else Kit.RaisedFill).border(1.dp, if (filled) Kit.PrimaryRim else Kit.Rim, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (filled) Kit.Ink else Kit.Text, modifier = Modifier.size(size * 0.45f)) }
}

/** A group's name, above it. */
@Composable
fun KSection(title: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = Kit.Dim, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** A rounded group of rows. */
@Composable
fun KGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).background(Kit.GroupFill).border(1.dp, Kit.Hair, shape), content = content)
}

/** The thin line between rows of a group, in from the left like a list's. */
@Composable
fun KDivider(inset: Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(Kit.Hair))
}

/**
 * A row: [label] (and [note] under it) on the left, its value or control on the right. Tappable with [onClick], with a
 * chevron when it opens something.
 */
@Composable
fun KRow(
    label: String, note: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null, chevron: Boolean = false,
    value: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Kit.Raised), contentAlignment = Alignment.Center) {
                Icon(it, null, tint = Kit.Text, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f, fill = value == null).padding(end = 10.dp)) {
            Text(label, color = Kit.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, color = Kit.Dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 1.dp)) }
        }
        if (value != null) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = value)
        if (chevron) Icon(Lucide.ChevronRight, null, tint = Kit.Faint, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

/** A value shown on the right of a row. */
@Composable
fun KValue(text: String, color: Color = Kit.Dim) {
    Text(text, color = color, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A segmented control whose selection slides between its parts. */
@Composable
fun KSegmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val view = LocalView.current
    BoxWithConstraints(modifier.height(36.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF101115)).padding(3.dp)) {
        val w = maxWidth / options.size.coerceAtLeast(1)
        val x by animateDpAsState(w * selected.coerceAtLeast(0), spring(0.8f, 600f), label = "seg")
        if (selected >= 0) Box(Modifier.offset(x = x).width(w).fillMaxHeight().shadow(3.dp, RoundedCornerShape(9.dp)).clip(RoundedCornerShape(9.dp)).background(Kit.RaisedFill).border(1.dp, Kit.Rim, RoundedCornerShape(9.dp)))
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            for ((i, o) in options.withIndex()) Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp)).clickable {
                    if (i != selected) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onPick(i)
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(o, color = if (i == selected) Kit.Text else Kit.Dim, fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Choices as pills in a row that scrolls sideways. */
@Composable
fun KChips(options: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val view = LocalView.current
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((i, o) in options.withIndex()) {
            val on = i == selected
            Text(
                o, color = if (on) Kit.Ink else Kit.Text, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                modifier = Modifier.clip(CircleShape).background(if (on) Kit.Text else Kit.Raised).clickable {
                    if (!on) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onPick(i)
                }.padding(horizontal = 15.dp, vertical = 8.dp),
            )
        }
    }
}

/** An on/off switch. */
@Composable
fun KToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    val x by animateFloatAsState(if (on) 1f else 0f, spring(0.75f, 700f), label = "toggle")
    Box(
        Modifier.width(48.dp).height(28.dp).clip(CircleShape).background(if (on) Kit.Accent else Color(0xFF3A3C46))
            .clickable { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onChange(!on) }.padding(2.dp),
    ) { Box(Modifier.offset(x = 20.dp * x).size(24.dp).clip(CircleShape).background(Color.White)) }
}

/** The page's main action, across the bottom: [text], with what it will do under it in [sub]. */
@Composable
fun KPrimary(text: String, modifier: Modifier = Modifier, sub: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().heightIn(min = 54.dp)
            .shadow(if (enabled) 14.dp else 0.dp, shape, ambientColor = Kit.Accent, spotColor = Kit.Accent)
            .clip(shape).background(if (enabled) Kit.Primary else SolidColor(Kit.Raised))
            .border(1.dp, if (enabled) Kit.PrimaryRim else Kit.Rim, shape).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = if (enabled) Kit.Ink else Kit.Faint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        sub?.let { Text(it, color = if (enabled) Kit.Ink.copy(alpha = 0.72f) else Kit.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** A second action beside the main one. */
@Composable
fun KSecondary(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.height(54.dp).shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape)
            .background(if (danger) SolidColor(Kit.Bad) else Kit.RaisedFill).border(1.dp, Kit.Rim, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = Kit.Text, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = if (danger) Color.White else Kit.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * A group that folds away: [title] with what is in it in [summary]; closed at first. For what most people never need
 * to change.
 */
@Composable
fun KFold(title: String, summary: String, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "fold")
    KGroup {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Kit.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(summary, color = Kit.Dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Lucide.ChevronRight, null, tint = Kit.Dim, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth()) { KDivider(0.dp); content() }
        }
    }
}

/** Text tabs with a line under the one shown. */
@Composable
fun KTabs(tabs: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val view = LocalView.current
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        for ((i, t) in tabs.withIndex()) {
            val on = i == selected
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { if (!on) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onPick(i) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t, color = if (on) Kit.Text else Kit.Dim, fontSize = 14.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(if (on) 18.dp else 0.dp).height(3.dp).clip(CircleShape).background(Kit.Accent))
            }
        }
    }
}

/** A small label in a pill: a format, a state. */
@Composable
fun KBadge(text: String, color: Color = Kit.Dim) {
    Text(
        text, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A choice in a list of choices: a tick when it is the one chosen. */
@Composable
fun KOption(title: String, note: String? = null, on: Boolean, badge: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badge?.let { it(); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (on) Kit.Accent else Kit.Text, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            note?.let { Text(it, color = Kit.Dim, fontSize = 12.sp, lineHeight = 16.sp) }
        }
        if (on) Icon(Lucide.Check, null, tint = Kit.Accent, modifier = Modifier.size(18.dp))
    }
}

/** A sentence of help under a group. */
@Composable
fun KNote(text: String, color: Color = Kit.Dim) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp))
}

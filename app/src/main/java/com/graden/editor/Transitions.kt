package com.graden.editor

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** A transition or animation placed on a clip: which one, and how long it lasts. */
data class Fx(
    val type: String,
    val durationUs: Long,
    /** For a transition: whether the sound crossfades across it too, or cuts at the edit. */
    val crossfade: Boolean = true,
)

/**
 * How one picture is shown at an instant of a transition. Offsets are fractions of the frame
 * (1 = one full frame width or height); the clip rectangle is in fractions too. [circle], when
 * not negative, clips to a centred circle instead: 1 just covers the whole frame.
 */
class Look {
    var alpha = 1f
    var tx = 0f
    var ty = 0f
    var sx = 1f
    var sy = 1f
    var rot = 0f
    var left = 0f
    var top = 0f
    var right = 1f
    var bottom = 1f
    var circle = -1f
    var z = 0f

    var scale: Float
        get() = sx
        set(v) {
            sx = v
            sy = v
        }

    val clipped: Boolean get() = circle >= 0f || left > 0f || top > 0f || right < 1f || bottom < 1f
}

/**
 * One transition. [out] shapes the picture that is leaving and [into] the one arriving, for
 * progress 0..1 across the transition. The same two halves serve as a clip's closing and opening
 * animation. [backdrop] is a colour shown behind both (for dips); [outOnTop] puts the leaving
 * picture in front, for transitions where it is the one that shrinks or opens away.
 */
class Transition(
    val id: String,
    val name: String,
    val group: String,
    val backdrop: Color? = null,
    val outOnTop: Boolean = false,
    val out: Look.(Float) -> Unit = {},
    val into: Look.(Float) -> Unit,
)

/**
 * Every transition here is made only of moving, scaling, turning, fading and masking whole
 * pictures, which the preview can do live on any phone. Effects that need to rework the pixels
 * themselves (blur, glitch, light leaks, distortion) are not in this list.
 */
object Transitions {
    /** Cubic in-out: starts gently, moves decisively, settles gently. */
    private fun ease(p: Float) = if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).pow(3) / 2f
    private fun easeOut(p: Float) = 1f - (1f - p).pow(3)
    private fun overshoot(p: Float): Float {
        val q = p - 1f
        return q * q * (2.7f * q + 1.7f) + 1f
    }
    private fun bounce(p: Float): Float = when {
        p < 0.3636f -> 7.5625f * p * p
        p < 0.7273f -> 7.5625f * (p - 0.5455f).pow(2) + 0.75f
        p < 0.9091f -> 7.5625f * (p - 0.8182f).pow(2) + 0.9375f
        else -> 7.5625f * (p - 0.9545f).pow(2) + 0.984375f
    }

    // The picture underneath sinks back a little and dims as the new one slides over it, which gives the move depth.
    private fun slide(id: String, name: String, dx: Float, dy: Float) = Transition(id, name, "Slide",
        out = { p -> alpha = 1f - 0.45f * ease(p); scale = 1f - 0.06f * ease(p) },
        into = { p -> tx = dx * (1f - ease(p)); ty = dy * (1f - ease(p)) })

    private fun push(id: String, name: String, dx: Float, dy: Float) = Transition(id, name, "Slide",
        out = { p -> tx = -dx * ease(p); ty = -dy * ease(p) }, into = { p -> tx = dx * (1f - ease(p)); ty = dy * (1f - ease(p)) })

    private fun wipe(id: String, name: String, edge: Look.(Float) -> Unit) = Transition(id, name, "Wipe", into = { p -> edge(ease(p)) })

    val all: List<Transition> = listOf(
        // Basic
        Transition("fade", "Fade", "Basic", out = { p -> alpha = 1f - ease(p) }, into = { p -> alpha = ease(p) }),
        Transition("dip_black", "Fade to black", "Basic", backdrop = Color.Black,
            out = { p -> alpha = (1f - 2f * p).coerceIn(0f, 1f) }, into = { p -> alpha = (2f * p - 1f).coerceIn(0f, 1f) }),
        Transition("dip_white", "Fade to white", "Basic", backdrop = Color.White,
            out = { p -> alpha = (1f - 2f * p).coerceIn(0f, 1f) }, into = { p -> alpha = (2f * p - 1f).coerceIn(0f, 1f) }),
        Transition("soft_zoom", "Soft zoom", "Basic",
            out = { p -> alpha = 1f - ease(p); scale = 1f + 0.08f * ease(p) }, into = { p -> alpha = ease(p); scale = 1.08f - 0.08f * ease(p) }),

        // Slide: the new picture moves in over the old one, or pushes it out
        slide("slide_left", "Slide left", 1f, 0f),
        slide("slide_right", "Slide right", -1f, 0f),
        slide("slide_up", "Slide up", 0f, 1f),
        slide("slide_down", "Slide down", 0f, -1f),
        push("push_left", "Push left", 1f, 0f),
        push("push_right", "Push right", -1f, 0f),
        push("push_up", "Push up", 0f, 1f),
        push("push_down", "Push down", 0f, -1f),
        slide("corner_tl", "From top left", -1f, -1f),
        slide("corner_tr", "From top right", 1f, -1f),
        slide("corner_bl", "From bottom left", -1f, 1f),
        slide("corner_br", "From bottom right", 1f, 1f),

        // Wipe: the new picture is uncovered along an edge or from the middle
        wipe("wipe_right", "Wipe right") { e -> right = e },
        wipe("wipe_left", "Wipe left") { e -> left = 1f - e },
        wipe("wipe_down", "Wipe down") { e -> bottom = e },
        wipe("wipe_up", "Wipe up") { e -> top = 1f - e },
        wipe("split_h", "Open sideways") { e -> left = 0.5f - e / 2; right = 0.5f + e / 2 },
        wipe("split_v", "Open up and down") { e -> top = 0.5f - e / 2; bottom = 0.5f + e / 2 },
        wipe("box_open", "Box open") { e -> left = 0.5f - e / 2; right = 0.5f + e / 2; top = 0.5f - e / 2; bottom = 0.5f + e / 2 },
        Transition("box_close", "Box close", "Wipe", outOnTop = true,
            out = { p -> val e = ease(p); left = e / 2; right = 1f - e / 2; top = e / 2; bottom = 1f - e / 2 }, into = {}),
        Transition("iris_open", "Circle open", "Wipe", into = { p -> circle = ease(p) }),
        Transition("iris_close", "Circle close", "Wipe", outOnTop = true, out = { p -> circle = 1f - ease(p) }, into = {}),
        Transition("curtain_close", "Close sideways", "Wipe", outOnTop = true,
            out = { p -> val e = ease(p); left = e / 2; right = 1f - e / 2 }, into = {}),

        // Zoom
        Transition("zoom_in", "Zoom in", "Zoom",
            out = { p -> scale = 1f + 0.7f * ease(p); alpha = 1f - ease(p) }, into = { p -> scale = 0.7f + 0.3f * ease(p); alpha = ease(p) }),
        Transition("zoom_out", "Zoom out", "Zoom",
            out = { p -> scale = 1f - 0.3f * ease(p); alpha = 1f - ease(p) }, into = { p -> scale = 1.6f - 0.6f * ease(p); alpha = ease(p) }),
        Transition("pull_in", "Grow in", "Zoom", into = { p -> scale = easeOut(p).coerceAtLeast(0.001f) }),
        Transition("pull_out", "Shrink away", "Zoom", outOnTop = true, out = { p -> scale = (1f - ease(p)).coerceAtLeast(0.001f) }, into = {}),
        Transition("pop", "Pop", "Zoom", out = { p -> alpha = 1f - ease(p) },
            into = { p -> scale = overshoot(p).coerceAtLeast(0.001f); alpha = (p * 3f).coerceAtMost(1f) }),
        Transition("stack", "Stack", "Zoom",
            out = { p -> scale = 1f - 0.15f * ease(p); alpha = 1f - 0.5f * ease(p) }, into = { p -> ty = 1f - easeOut(p) }),

        // Spin and flip
        Transition("spin_cw", "Spin right", "Spin",
            out = { p -> rot = 120f * ease(p); scale = 1f - 0.4f * ease(p); alpha = 1f - ease(p) },
            into = { p -> rot = -120f * (1f - ease(p)); scale = 0.6f + 0.4f * ease(p); alpha = ease(p) }),
        Transition("spin_ccw", "Spin left", "Spin",
            out = { p -> rot = -120f * ease(p); scale = 1f - 0.4f * ease(p); alpha = 1f - ease(p) },
            into = { p -> rot = 120f * (1f - ease(p)); scale = 0.6f + 0.4f * ease(p); alpha = ease(p) }),
        Transition("whirl", "Whirl", "Spin",
            out = { p -> rot = 360f * ease(p); scale = (1f - ease(p)).coerceAtLeast(0.001f) },
            into = { p -> rot = -360f * (1f - ease(p)); scale = ease(p).coerceAtLeast(0.001f) }, outOnTop = false),
        Transition("flip_h", "Flip sideways", "Spin",
            out = { p -> sx = (1f - 2f * p).coerceAtLeast(0.001f); alpha = if (p < 0.5f) 1f else 0f },
            into = { p -> sx = (2f * p - 1f).coerceAtLeast(0.001f); alpha = if (p < 0.5f) 0f else 1f }),
        Transition("flip_v", "Flip over", "Spin",
            out = { p -> sy = (1f - 2f * p).coerceAtLeast(0.001f); alpha = if (p < 0.5f) 1f else 0f },
            into = { p -> sy = (2f * p - 1f).coerceAtLeast(0.001f); alpha = if (p < 0.5f) 0f else 1f }),
        Transition("swing", "Swing in", "Spin", out = { p -> alpha = 1f - 0.4f * ease(p) },
            into = { p -> rot = -18f * (1f - easeOut(p)); tx = 1f - easeOut(p) }),

        // Motion
        Transition("drop", "Drop", "Motion", into = { p -> ty = -(1f - bounce(p)) }),
        Transition("rise", "Rise", "Motion", out = { p -> ty = -0.2f * ease(p); alpha = 1f - ease(p) }, into = { p -> ty = 1f - easeOut(p) }),
        Transition("bounce_in", "Bounce in", "Motion", into = { p -> tx = 1f - bounce(p) }),
        Transition("whip_left", "Whip left", "Motion",
            out = { p -> tx = -1.2f * p.pow(2); sx = 1f + 0.25f * sin(p * 3.14f) }, into = { p -> tx = 1.2f * (1f - p).pow(2); sx = 1f + 0.25f * sin(p * 3.14f) }),
        Transition("whip_right", "Whip right", "Motion",
            out = { p -> tx = 1.2f * p.pow(2); sx = 1f + 0.25f * sin(p * 3.14f) }, into = { p -> tx = -1.2f * (1f - p).pow(2); sx = 1f + 0.25f * sin(p * 3.14f) }),
        Transition("shake", "Shake", "Motion",
            out = { p -> tx = 0.04f * sin(p * 40f) * p; alpha = 1f - ease(p) }, into = { p -> tx = 0.04f * sin(p * 40f) * (1f - p); alpha = ease(p) }),
        Transition("float_up", "Float away", "Motion", outOnTop = true,
            out = { p -> ty = -ease(p); alpha = 1f - ease(p); scale = 1f - 0.1f * abs(ease(p)) }, into = {}),
    )

    val byId: Map<String, Transition> = all.associateBy { it.id }
    val groups: List<String> = all.map { it.group }.distinct()
}

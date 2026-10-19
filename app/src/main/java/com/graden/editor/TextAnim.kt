package com.graden.editor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * How a text arrives ("in") and leaves ("out"). Each is a shape of the text over its first or last moments: how
 * visible, where, how big, how turned, and how much of it shows (letter by letter, or wiped on). An out is its in
 * played backwards.
 */
object TextAnim {
    const val NONE = "none"

    class Kind(val id: String, val name: String)

    val ALL = listOf(
        Kind(NONE, "None"),
        Kind("fade", "Fade"),
        Kind("up", "Rise"),
        Kind("down", "Drop"),
        Kind("left", "Slide left"),
        Kind("right", "Slide right"),
        Kind("zoom", "Zoom in"),
        Kind("shrink", "Zoom out"),
        Kind("pop", "Pop"),
        Kind("bounce", "Bounce"),
        Kind("spin", "Spin"),
        Kind("type", "Typewriter"),
        Kind("wipe", "Wipe"),
        Kind("blink", "Flicker"),
    )
    private val names = ALL.associate { it.id to it.name }
    fun name(id: String) = names[id] ?: "None"

    /**
     * The text's shape at a moment: [alpha] 0..1; [dx], [dy] moved, in text heights; [scale]; [rot] degrees;
     * [reveal]: how much of the text shows, letter by letter (1 all); [wipe]: how much, from the left (1 all).
     */
    class State(var alpha: Float = 1f, var dx: Float = 0f, var dy: Float = 0f, var scale: Float = 1f, var rot: Float = 0f,
                var reveal: Float = 1f, var wipe: Float = 1f) {
        val still: Boolean get() = alpha >= 1f && dx == 0f && dy == 0f && scale == 1f && rot == 0f && reveal >= 1f && wipe >= 1f
    }

    /** The shape at [t] µs into a text that lasts [durUs], with [cap]'s in and out. */
    fun at(cap: Caption, t: Long, durUs: Long): State {
        val s = State()
        // Never longer than half the text each, so a short text still shows whole between them.
        val half = max(1L, durUs / 2)
        val inUs = min(cap.inUs, half); val outUs = min(cap.outUs, half)
        if (cap.animIn != NONE && t < inUs) apply(s, cap.animIn, (t.toFloat() / inUs).coerceIn(0f, 1f))
        else if (cap.animOut != NONE && t > durUs - outUs) apply(s, cap.animOut, ((durUs - t).toFloat() / outUs).coerceIn(0f, 1f))
        return s
    }

    /** Whether [cap] is moving at [t] (else it is drawn the same as a moment ago). */
    fun moving(cap: Caption, t: Long, durUs: Long): Boolean {
        val half = max(1L, durUs / 2)
        return (cap.animIn != NONE && t < min(cap.inUs, half)) || (cap.animOut != NONE && t > durUs - min(cap.outUs, half))
    }

    private fun easeOut(p: Float) = 1f - (1f - p).pow(3)
    private fun back(p: Float): Float { val c = 1.7f; val q = p - 1f; return 1f + (c + 1f) * q * q * q + c * q * q }

    /** [p]: 0 at the start of an in (end of an out) … 1 fully there. */
    private fun apply(s: State, id: String, p: Float) {
        val e = easeOut(p)
        when (id) {
            "fade" -> s.alpha = e
            "up" -> { s.alpha = e; s.dy = (1f - e) * 0.9f }
            "down" -> { s.alpha = e; s.dy = -(1f - e) * 0.9f }
            "left" -> { s.alpha = e; s.dx = (1f - e) * 2.5f }
            "right" -> { s.alpha = e; s.dx = -(1f - e) * 2.5f }
            "zoom" -> { s.alpha = e; s.scale = 0.55f + 0.45f * e }
            "shrink" -> { s.alpha = e; s.scale = 1.5f - 0.5f * e }
            "pop" -> { s.alpha = min(1f, p * 3f); s.scale = max(0.01f, back(p)) }
            "bounce" -> {
                s.alpha = min(1f, p * 4f)
                // A drop that bounces twice, settling.
                s.dy = -(exp(-5f * p) * cos(p * 3.2f * PI.toFloat())).toFloat() * 1.2f * (1f - p)
            }
            "spin" -> { s.alpha = e; s.rot = (1f - e) * -180f; s.scale = 0.4f + 0.6f * e }
            "type" -> s.reveal = p
            "wipe" -> s.wipe = e
            "blink" -> s.alpha = if (p >= 1f) 1f else (if (sin(p * 9f * PI.toFloat()) > -0.2f) p.coerceAtLeast(0.15f) else 0f)
        }
    }
}

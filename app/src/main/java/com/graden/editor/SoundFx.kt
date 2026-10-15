package com.graden.editor

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A clip's sound settings. [volume] is linear (1 = as recorded, up to 2); the equaliser bands are in
 * dB; [pan] runs from −1 (left) to 1 (right); [pitch] is in semitones. With [normalize] the clip is
 * raised or lowered so its loudest moment peaks just under full scale.
 */
data class Snd(
    val volume: Float = 1f,
    val muted: Boolean = false,
    val normalize: Boolean = false,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val pan: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val pitch: Float = 0f,
) {
    val isDefault: Boolean get() = this == Snd()
}

/**
 * The sound one player puts out right now: an overall [gain] (volume, fades, mute, normalising,
 * crossfade and lane mute all multiplied together), plus the clip's pan and equaliser.
 */
data class SoundNow(val gain: Float = 1f, val pan: Float = 0f, val bass: Float = 0f, val mid: Float = 0f, val treble: Float = 0f)

/**
 * Applies a [SoundNow] to a player's 16-bit sound: three-band equaliser (low shelf at 200 Hz, a bell
 * at 1 kHz, high shelf at 4 kHz), pan, and gain. Gain may go above 1 (the player's own volume
 * cannot), with hard limiting at full scale. Gain changes are ramped across each buffer, so moving a
 * slider or a fade never clicks.
 */
@OptIn(UnstableApi::class)
class SoundProcessor : BaseAudioProcessor() {
    @Volatile var target = SoundNow()

    private var gainNow = 1f
    private var applied: SoundNow? = null
    private val bands = Array(3) { Biquad() }
    // Filter state per band per channel (up to 8 channels).
    private val z1 = Array(3) { FloatArray(8) }
    private val z2 = Array(3) { FloatArray(8) }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val t = target
        val ch = inputAudioFormat.channelCount.coerceIn(1, 8)
        val rate = inputAudioFormat.sampleRate.toFloat()
        if (applied == null || applied!!.bass != t.bass || applied!!.mid != t.mid || applied!!.treble != t.treble) {
            bands[0].lowShelf(200f, t.bass, rate)
            bands[1].peak(1000f, t.mid, 1f, rate)
            bands[2].highShelf(4000f, t.treble, rate)
        }
        applied = t
        val eq = t.bass != 0f || t.mid != 0f || t.treble != 0f
        // Constant-power-ish pan: the far side is turned down, the near side left alone.
        val left = if (t.pan > 0f) 1f - t.pan else 1f
        val right = if (t.pan < 0f) 1f + t.pan else 1f
        val inBuf = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = n / 2 / ch
        val g0 = gainNow
        val g1 = t.gain
        for (f in 0 until frames) {
            val g = g0 + (g1 - g0) * (f + 1) / frames
            for (c in 0 until ch) {
                var x = inBuf.short.toFloat() / 32768f
                if (eq) for (b in 0..2) {
                    val q = bands[b]
                    val y = q.b0 * x + z1[b][c]
                    z1[b][c] = q.b1 * x - q.a1 * y + z2[b][c]
                    z2[b][c] = q.b2 * x - q.a2 * y
                    x = y
                }
                if (ch == 2) x *= if (c == 0) left else right
                x *= g
                out.putShort((x.coerceIn(-1f, 0.99997f) * 32768f).toInt().toShort())
            }
        }
        gainNow = g1
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        for (b in 0..2) {
            z1[b].fill(0f)
            z2[b].fill(0f)
        }
    }

    /** One biquad section (RBJ cookbook), normalised so a0 = 1. */
    private class Biquad {
        var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f

        private fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
            this.b0 = (b0 / a0).toFloat(); this.b1 = (b1 / a0).toFloat(); this.b2 = (b2 / a0).toFloat()
            this.a1 = (a1 / a0).toFloat(); this.a2 = (a2 / a0).toFloat()
        }

        fun peak(f: Float, db: Float, q: Float, rate: Float) {
            val a = 10.0.pow(db / 40.0)
            val w = 2 * PI * f / rate
            val al = sin(w) / (2 * q)
            set(1 + al * a, -2 * cos(w), 1 - al * a, 1 + al / a, -2 * cos(w), 1 - al / a)
        }

        fun lowShelf(f: Float, db: Float, rate: Float) = shelf(f, db, rate, low = true)

        fun highShelf(f: Float, db: Float, rate: Float) = shelf(f, db, rate, low = false)

        private fun shelf(f: Float, db: Float, rate: Float, low: Boolean) {
            val a = 10.0.pow(db / 40.0)
            val w = 2 * PI * f / rate
            val c = cos(w)
            val al = sin(w) / 2 * sqrt(2.0)
            val s = 2 * sqrt(a) * al
            if (low) set(
                a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
                (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s,
            ) else set(
                a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
                (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s,
            )
        }
    }
}

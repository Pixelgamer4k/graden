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
    val fx: SoundFxSet = SoundFxSet(),
) {
    val isDefault: Boolean get() = this == Snd()
}

/**
 * The sound one player puts out right now: an overall [gain] (volume, fades, mute, normalising, ducking, crossfade and
 * lane mute all multiplied together), plus the clip's pan, equaliser and effects.
 */
data class SoundNow(
    val gain: Float = 1f, val pan: Float = 0f, val bass: Float = 0f, val mid: Float = 0f, val treble: Float = 0f,
    val fx: SoundFxSet = SoundFxSet(),
)

/**
 * Applies a [SoundNow] to a player's 16-bit sound through the clip's [FxChain] (the same chain the export uses), then its
 * gain, ramped across each buffer so nothing clicks, and the same soft ceiling as the export. Always puts out stereo, so
 * a mono clip can be panned.
 */
@OptIn(UnstableApi::class)
class SoundProcessor : BaseAudioProcessor() {
    @Volatile var target = SoundNow()

    private var gainNow = 1f
    private var chain: FxChain? = null
    private var inCh = 2
    private var work = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        inCh = inputAudioFormat.channelCount.coerceIn(1, 8)
        if (chain == null || inputAudioFormat.sampleRate != rateNow) { chain = FxChain(inputAudioFormat.sampleRate); rateNow = inputAudioFormat.sampleRate }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }
    private var rateNow = 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val ch = inCh
        val frames = n / 2 / ch
        val out = replaceOutputBuffer(frames * 4)
        val t = target
        val inBuf = inputBuffer.order(ByteOrder.nativeOrder())
        if (work.size < frames * 2) work = FloatArray(frames * 2)
        val x = work
        for (f in 0 until frames) {
            val base = inBuf.position()
            val l = inBuf.getShort(base).toFloat() / 32768f
            val r = if (ch > 1) inBuf.getShort(base + 2).toFloat() / 32768f else l
            inBuf.position(base + ch * 2)
            x[f * 2] = l; x[f * 2 + 1] = r
        }
        val needs = t.fx.processing || t.pan != 0f || t.bass != 0f || t.mid != 0f || t.treble != 0f
        if (needs) chain?.process(x, frames, t.bass, t.mid, t.treble, t.pan, t.fx, monoSource = ch == 1)
        val g0 = gainNow
        val g1 = t.gain
        for (f in 0 until frames) {
            val g = g0 + (g1 - g0) * (f + 1) / frames
            for (c in 0..1) out.putShort((FxChain.softClip(x[f * 2 + c] * g).coerceIn(-1f, 0.99997f) * 32768f).toInt().toShort())
        }
        gainNow = g1
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        chain?.reset()
    }
}

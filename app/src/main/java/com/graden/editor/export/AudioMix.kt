package com.graden.editor.export

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.graden.editor.Clip
import com.graden.editor.MediaAsset
import com.graden.editor.Snd
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * One clip's sound as it is heard on the timeline: the source range it plays (lengthened into a crossfade where there
 * is one), where that starts, at what speed and pitch, and the gain at every moment.
 */
private class Segment(
    val path: String,
    val asset: MediaAsset,
    val clip: Clip,
    val srcIn: Long,
    val srcOut: Long,
    val startUs: Long,
    val endUs: Long,
    val fadeInFrom: Long?,
    val fadeOutFrom: Long?,
    val xfadeUs: Long,
    val norm: Float,
) {
    /** Gain at timeline time [us], exactly as the preview computes it. */
    fun gain(us: Long): Float {
        val sn: Snd = clip.snd
        if (sn.muted) return 0f
        var e = sn.volume * norm
        if (sn.fadeInUs > 0) e *= ((us - clip.startUs).toFloat() / sn.fadeInUs).coerceIn(0f, 1f)
        if (sn.fadeOutUs > 0) e *= ((clip.endUs - us).toFloat() / sn.fadeOutUs).coerceIn(0f, 1f)
        fadeInFrom?.let { e *= ((us - it).toFloat() / xfadeUs).coerceIn(0f, 1f) }
        fadeOutFrom?.let { e *= (1f - (us - it).toFloat() / xfadeUs).coerceIn(0f, 1f) }
        return e
    }
}

/** Every clip's sound in the project, ready to mix. */
private fun segments(p: ExportProject): List<Segment> {
    val out = ArrayList<Segment>()
    for (t in p.tracks) {
        if (t.id in p.muted) continue
        val clips = t.clips.sortedBy { it.startUs }
        for (c in clips) {
            val a = p.assets[c.assetId] ?: continue
            if (a.isImage || !(a.hasAudio || a.isAudio)) continue
            val path = p.sources[a.id] ?: continue
            var srcIn = c.inUs
            var srcOut = c.outUs
            var start = c.startUs
            var fadeInFrom: Long? = null
            var fadeOutFrom: Long? = null
            var xf = 1L
            if (t.video) {
                val tIn = c.transition?.takeIf { tr -> tr.crossfade && clips.any { it.endUs == c.startUs } }
                if (tIn != null) {
                    val pre = minOf((tIn.durationUs / 2 * c.speed).toLong(), c.inUs)
                    srcIn -= pre
                    start -= (pre / c.speed).toLong()
                    fadeInFrom = c.startUs - tIn.durationUs / 2
                    xf = tIn.durationUs
                }
                val after = clips.firstOrNull { it.startUs == c.endUs && it.transition?.crossfade == true }
                if (after != null) {
                    srcOut = minOf(a.durationUs, srcOut + (after.transition!!.durationUs / 2 * c.speed).toLong())
                    fadeOutFrom = c.endUs - after.transition.durationUs / 2
                    xf = after.transition.durationUs
                }
            }
            val end = start + ((srcOut - srcIn) / c.speed).toLong()
            out += Segment(path, a, c, srcIn, srcOut, start, end, fadeInFrom, fadeOutFrom, xf, p.normGain[c.id] ?: 1f)
        }
    }
    return out.sortedBy { it.startUs }
}

/**
 * Reads one segment's sound forwards as 48 kHz stereo float: decoded, sped up or slowed with its pitch kept (Sonic, as
 * the player does), pitch-shifted if asked, then equalised and panned as in the preview.
 */
@OptIn(UnstableApi::class)
private class SegmentReader(context: Context, val seg: Segment, private val rate: Int) {
    private val ex = MediaExtractor()
    private val dec: MediaCodec
    private var inDone = false
    private var outDone = false
    private val info = MediaCodec.BufferInfo()
    private var inRate = 48_000
    private var inCh = 2
    private var floatPcm = false
    private var sonic = SonicAudioProcessor()
    private var sonicOn = false
    /** Stereo frames ready to be mixed. */
    private var fifo = FloatArray(0)
    private var fifoLen = 0
    private val eq = Eq(seg.clip.snd, rate)

    init {
        val path = seg.path
        if (path.startsWith("content:")) ex.setDataSource(context, Uri.parse(path), null)
        else ex.setDataSource(if (path.startsWith("file:")) Uri.parse(path).path!! else path)
        val tr = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(tr)
        val f = ex.getTrackFormat(tr)
        inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(f, null, null, 0)
        dec.start()
        ex.seekTo(seg.srcIn, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        setUpSonic()
    }

    /**
     * Speed, pitch and sample-rate change for the sound as it is decoded at [inRate]: a new processor each time the
     * decoder says what it really gives. (The file's own rate can differ: HE-AAC files give their core rate, half the
     * real one. A processor told to change to "nothing to do" keeps its old setting, which played such files an
     * octave low at half speed.)
     */
    private fun setUpSonic() {
        val pitch = 2.0.pow(seg.clip.snd.pitch / 12.0).toFloat()
        sonicOn = seg.clip.speed != 1f || pitch != 1f || inRate != rate
        runCatching { sonic.reset() }
        sonic = SonicAudioProcessor()
        if (sonicOn) {
            sonic.setSpeed(seg.clip.speed)
            sonic.setPitch(pitch)
            sonic.setOutputSampleRateHz(rate)
            sonic.configure(AudioProcessor.AudioFormat(inRate, 2, C.ENCODING_PCM_16BIT))
            sonic.flush()
        }
    }

    /** Fills [n] stereo frames into [dst] (interleaved); silence once the source has ended. */
    fun read(dst: FloatArray, n: Int) {
        while (fifoLen < n && !(outDone && (!sonicOn || sonic.isEnded))) pump()
        val take = minOf(n, fifoLen)
        System.arraycopy(fifo, 0, dst, 0, take * 2)
        java.util.Arrays.fill(dst, take * 2, n * 2, 0f)
        System.arraycopy(fifo, take * 2, fifo, 0, (fifoLen - take) * 2)
        fifoLen -= take
    }

    private fun pump() {
        if (!inDone) {
            val i = dec.dequeueInputBuffer(2_000)
            if (i >= 0) {
                val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                if (n < 0 || ex.sampleTime > seg.srcOut + 100_000) {
                    dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inDone = true
                } else {
                    dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                    ex.advance()
                }
            }
        }
        if (outDone) {
            if (sonicOn && !sonic.isEnded) drainSonic()
            return
        }
        val o = dec.dequeueOutputBuffer(info, 2_000)
        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val f = dec.outputFormat
            inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            setUpSonic()
            return
        }
        if (o < 0) return
        val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        // Frames of this buffer to keep: those from the segment's in point to its out point.
        val bytesPer = if (floatPcm) 4 else 2
        val frames = info.size / bytesPer / inCh
        val frameUs = 1_000_000.0 / inRate
        var first = 0
        var last = frames
        if (info.presentationTimeUs < seg.srcIn) first = ((seg.srcIn - info.presentationTimeUs) / frameUs).toInt().coerceIn(0, frames)
        val endAt = info.presentationTimeUs + (frames * frameUs).toLong()
        if (endAt > seg.srcOut) last = ((seg.srcOut - info.presentationTimeUs) / frameUs).toInt().coerceIn(first, frames)
        if (info.presentationTimeUs >= seg.srcOut) last = first
        val stereo = ShortArray((last - first) * 2)
        for (f in first until last) {
            var l: Float
            var r: Float
            if (floatPcm) {
                l = buf.getFloat(info.offset + (f * inCh) * 4)
                r = if (inCh > 1) buf.getFloat(info.offset + (f * inCh + 1) * 4) else l
            } else {
                l = buf.getShort(info.offset + (f * inCh) * 2) / 32768f
                r = if (inCh > 1) buf.getShort(info.offset + (f * inCh + 1) * 2) / 32768f else l
            }
            stereo[(f - first) * 2] = (l.coerceIn(-1f, 0.99997f) * 32768f).toInt().toShort()
            stereo[(f - first) * 2 + 1] = (r.coerceIn(-1f, 0.99997f) * 32768f).toInt().toShort()
        }
        dec.releaseOutputBuffer(o, false)
        if (sonicOn) {
            if (stereo.isNotEmpty()) {
                val bb = ByteBuffer.allocateDirect(stereo.size * 2).order(ByteOrder.nativeOrder())
                bb.asShortBuffer().put(stereo)
                sonic.queueInput(bb)
                drainSonic()
            }
            if (eos || info.presentationTimeUs >= seg.srcOut) {
                sonic.queueEndOfStream()
                outDone = true
                drainSonic()
            }
        } else {
            push(FloatArray(stereo.size) { stereo[it] / 32768f })
            if (eos || info.presentationTimeUs >= seg.srcOut) outDone = true
        }
        if (eos) outDone = true
    }

    private fun drainSonic() {
        val out = sonic.output
        if (!out.hasRemaining()) return
        val s = out.order(ByteOrder.nativeOrder()).asShortBuffer()
        val f = FloatArray(s.remaining())
        for (i in f.indices) f[i] = s.get() / 32768f
        push(f)
    }

    private fun push(stereo: FloatArray) {
        eq.apply(stereo, inCh == 2)
        val frames = stereo.size / 2
        if ((fifoLen + frames) * 2 > fifo.size) fifo = fifo.copyOf(maxOf(fifo.size * 2, (fifoLen + frames) * 2 + 8192))
        System.arraycopy(stereo, 0, fifo, fifoLen * 2, stereo.size)
        fifoLen += frames
    }

    fun release() {
        runCatching { dec.stop() }
        runCatching { dec.release() }
        runCatching { ex.release() }
        runCatching { sonic.reset() }
    }
}

/** The preview's three-band equaliser and pan (SoundProcessor), on float stereo. */
private class Eq(private val snd: Snd, rate: Int) {
    private val on = snd.bass != 0f || snd.mid != 0f || snd.treble != 0f
    private val b = Array(3) { FloatArray(5) }
    private val z = Array(3) { FloatArray(4) }

    init {
        shelf(b[0], 200f, snd.bass, rate.toFloat(), true)
        peak(b[1], 1000f, snd.mid, 1f, rate.toFloat())
        shelf(b[2], 4000f, snd.treble, rate.toFloat(), false)
    }

    fun apply(x: FloatArray, stereoSource: Boolean) {
        val left = if (snd.pan > 0f) 1f - snd.pan else 1f
        val right = if (snd.pan < 0f) 1f + snd.pan else 1f
        val panOn = stereoSource && snd.pan != 0f
        if (!on && !panOn) return
        var i = 0
        while (i < x.size) {
            for (c in 0..1) {
                var v = x[i + c]
                if (on) for (k in 0..2) {
                    val q = b[k]
                    val y = q[0] * v + z[k][c * 2]
                    z[k][c * 2] = q[1] * v - q[3] * y + z[k][c * 2 + 1]
                    z[k][c * 2 + 1] = q[2] * v - q[4] * y
                    v = y
                }
                if (panOn) v *= if (c == 0) left else right
                x[i + c] = v
            }
            i += 2
        }
    }

    private fun set(o: FloatArray, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        o[0] = (b0 / a0).toFloat(); o[1] = (b1 / a0).toFloat(); o[2] = (b2 / a0).toFloat(); o[3] = (a1 / a0).toFloat(); o[4] = (a2 / a0).toFloat()
    }

    private fun peak(o: FloatArray, f: Float, db: Float, q: Float, rate: Float) {
        val a = 10.0.pow(db / 40.0)
        val w = 2 * PI * f / rate
        val al = sin(w) / (2 * q)
        set(o, 1 + al * a, -2 * cos(w), 1 - al * a, 1 + al / a, -2 * cos(w), 1 - al / a)
    }

    private fun shelf(o: FloatArray, f: Float, db: Float, rate: Float, low: Boolean) {
        val a = 10.0.pow(db / 40.0)
        val w = 2 * PI * f / rate
        val c = cos(w)
        val al = sin(w) / 2 * sqrt(2.0)
        val s = 2 * sqrt(a) * al
        if (low) set(o,
            a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
            (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s,
        ) else set(o,
            a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
            (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s,
        )
    }
}

/** Integrated loudness (ITU-R BS.1770-4 / EBU R128): K-weighting, 400 ms blocks overlapping by 75%, two gates. */
private class Loudness(rate: Int) {
    private val pre = DoubleArray(5)
    private val rlb = DoubleArray(5)
    private val zs = Array(2) { DoubleArray(4) }
    private val blocks = ArrayList<Double>()
    private val step = rate / 10
    private val acc = DoubleArray(4)
    private var inStep = 0
    private var stepIndex = 0

    init {
        // The standard's coefficients, designed for any rate (Brecht De Man's formulation).
        val fs = rate.toDouble()
        run {
            val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
            val k = kotlin.math.tan(PI * f0 / fs); val vh = 10.0.pow(g / 20); val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            pre[0] = (vh + vb * k / q + k * k) / a0; pre[1] = 2 * (k * k - vh) / a0; pre[2] = (vh - vb * k / q + k * k) / a0
            pre[3] = 2 * (k * k - 1) / a0; pre[4] = (1 - k / q + k * k) / a0
        }
        run {
            val f0 = 38.13547087602444; val q = 0.5003270373238773
            val k = kotlin.math.tan(PI * f0 / fs)
            val a0 = 1 + k / q + k * k
            rlb[0] = 1.0; rlb[1] = -2.0; rlb[2] = 1.0
            rlb[3] = 2 * (k * k - 1) / a0; rlb[4] = (1 - k / q + k * k) / a0
        }
    }

    fun add(x: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0..1) {
                val z = zs[c]
                var v = x[f * 2 + c].toDouble()
                var y = pre[0] * v + z[0]; z[0] = pre[1] * v - pre[3] * y + z[1]; z[1] = pre[2] * v - pre[4] * y; v = y
                y = rlb[0] * v + z[2]; z[2] = rlb[1] * v - rlb[3] * y + z[3]; z[3] = rlb[2] * v - rlb[4] * y
                sum += y * y
            }
            acc[stepIndex % 4] += sum
            if (++inStep == step) {
                inStep = 0
                stepIndex++
                if (stepIndex >= 4) blocks += acc.sum() / (step * 4)
                acc[stepIndex % 4] = 0.0
            }
        }
    }

    /** Integrated loudness in LUFS, or null if the mix is silent. */
    fun integrated(): Double? {
        fun lufs(ms: Double) = -0.691 + 10 * log10(ms)
        val abs = blocks.filter { it > 0 && lufs(it) > -70 }
        if (abs.isEmpty()) return null
        val rel = lufs(abs.average()) - 10
        val gated = abs.filter { lufs(it) > rel }
        return if (gated.isEmpty()) null else lufs(gated.average())
    }
}

/**
 * Mixes the project's sound into AAC, written to [out] as (time, flags, size, bytes) records; returns the stream's
 * format, or null if there is no sound at all. With [loudnessTarget] the mix is measured first and brought to it.
 */
@OptIn(UnstableApi::class)
fun mixAudio(
    context: Context, p: ExportProject, bitrate: Int, rate: Int, loudnessTarget: Float?, out: File,
    progress: (Float) -> Unit, cancelled: () -> Boolean,
): MediaFormat? {
    val segs = segments(p)
    if (segs.isEmpty() || p.totalUs <= 0) return null
    val totalFrames = (p.totalUs * rate / 1_000_000L).toInt()

    /** Runs the mix once, handing each chunk to [sink]. */
    fun run(gainDb: Double, share: Float, offset: Float, sink: (FloatArray, Int, Long) -> Unit) {
        val gain = 10.0.pow(gainDb / 20).toFloat()
        val chunk = 4800
        val mix = FloatArray(chunk * 2)
        val tmp = FloatArray(chunk * 2)
        val open = HashMap<Segment, SegmentReader>()
        var next = 0
        var pos = 0
        try {
            while (pos < totalFrames) {
                if (cancelled()) return
                val n = minOf(chunk, totalFrames - pos)
                val t0 = pos * 1_000_000L / rate
                val t1 = (pos + n) * 1_000_000L / rate
                while (next < segs.size && segs[next].startUs < t1) {
                    val s = segs[next++]
                    if (s.endUs > t0) runCatching { SegmentReader(context, s, rate) }.getOrNull()?.let { open[s] = it }
                }
                java.util.Arrays.fill(mix, 0f)
                for ((s, r) in open) {
                    // Where in this chunk the segment's sound begins (it starts at s.startUs on the timeline).
                    val from = (((s.startUs - t0) * rate) / 1_000_000L).toInt().coerceIn(0, n)
                    val to = (((s.endUs - t0) * rate) / 1_000_000L).toInt().coerceIn(from, n)
                    if (to <= from) continue
                    r.read(tmp, to - from)
                    for (f in from until to) {
                        val us = t0 + f * 1_000_000L / rate
                        val g = s.gain(us)
                        mix[f * 2] += tmp[(f - from) * 2] * g
                        mix[f * 2 + 1] += tmp[(f - from) * 2 + 1] * g
                    }
                }
                for ((s, r) in open.entries.toList()) if (s.endUs <= t1) { r.release(); open.remove(s) }
                if (gain != 1f) for (i in 0 until n * 2) mix[i] *= gain
                sink(mix, n, t0)
                pos += n
                progress(offset + share * pos / totalFrames)
            }
        } finally {
            open.values.forEach { it.release() }
        }
    }

    var gainDb = 0.0
    if (loudnessTarget != null) {
        val meter = Loudness(rate)
        run(0.0, 0.3f, 0f) { m, n, _ -> meter.add(m, n) }
        if (cancelled()) return null
        meter.integrated()?.let { gainDb = (loudnessTarget - it).coerceIn(-30.0, 30.0) }
    }

    val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4800 * 4 * 2)
    }
    enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    enc.start()
    var outFormat: MediaFormat? = null
    val info = MediaCodec.BufferInfo()
    DataOutputStream(out.outputStream().buffered(1 shl 16)).use { w ->
        fun drain(wait: Long) {
            while (true) {
                val o = enc.dequeueOutputBuffer(info, wait)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFormat = enc.outputFormat; continue }
                if (o < 0) return
                val b = enc.getOutputBuffer(o)!!
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val bytes = ByteArray(info.size)
                    b.position(info.offset); b.get(bytes)
                    w.writeLong(info.presentationTimeUs); w.writeInt(info.flags); w.writeInt(info.size); w.write(bytes)
                }
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                enc.releaseOutputBuffer(o, false)
                if (end) return
            }
        }
        val pcm = ShortArray(4800 * 2)
        run(gainDb, if (loudnessTarget != null) 0.7f else 1f, if (loudnessTarget != null) 0.3f else 0f) { m, n, t0 ->
            for (i in 0 until n * 2) {
                // A gentle limiter above −0.9 dBFS instead of hard clipping.
                var v = m[i]
                val av = kotlin.math.abs(v)
                if (av > 0.9f) v = kotlin.math.sign(v) * (0.9f + 0.1f * tanh((av - 0.9f) / 0.1f))
                pcm[i] = (v.coerceIn(-1f, 0.99997f) * 32767f).toInt().toShort()
            }
            var off = 0
            while (off < n) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i < 0) { drain(0); continue }
                val ib = enc.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                ib.clear()
                val frames = minOf(n - off, ib.capacity() / 4)
                ib.asShortBuffer().put(pcm, off * 2, frames * 2)
                enc.queueInputBuffer(i, 0, frames * 4, t0 + off * 1_000_000L / rate, 0)
                off += frames
                drain(0)
            }
        }
        while (true) {
            val i = enc.dequeueInputBuffer(10_000)
            if (i >= 0) { enc.queueInputBuffer(i, 0, 0, p.totalUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            drain(0)
        }
        drain(10_000)
    }
    enc.stop()
    enc.release()
    return if (cancelled()) null else outFormat
}

/** Reads back what [mixAudio] wrote, one encoded sample at a time. */
class AudioSamples(file: File) : AutoCloseable {
    private val r = DataInputStream(file.inputStream().buffered(1 shl 16))
    var timeUs = Long.MAX_VALUE
        private set
    var flags = 0
        private set
    var data = ByteArray(0)
        private set

    /** Moves to the next sample; false at the end. */
    fun next(): Boolean = try {
        timeUs = r.readLong(); flags = r.readInt()
        val n = r.readInt()
        data = ByteArray(n)
        r.readFully(data)
        true
    } catch (e: java.io.EOFException) {
        timeUs = Long.MAX_VALUE
        false
    }

    override fun close() = r.close()
}

package com.graden.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


/**
 * Makes captions from a file's sound: decoded to 16 kHz mono; YAMNet labels each half second speech, singing, music or
 * quiet; Whisper transcribes it with each word's time; the words become captions as Netflix's and YouTube's guidelines
 * have them (two balanced lines of at most 42 characters, at most 7 seconds, broken at pauses and sentence ends), sung
 * words marked 𝄞 and music without singing shown as 𝄞 alone. Open one [Session] for a run of clips (the models load
 * once).
 */
object Captioner {
    /** The G clef where the font has it, else the eighth note every font has. */
    val CLEF: String by lazy { if (android.graphics.Paint().hasGlyph("𝄞")) "𝄞" else "♪" }

    const val RATE = 16_000

    /** The languages offered (Whisper's), as code to name; "auto" detects. */
    val LANGUAGES = listOf(
        "auto" to "Detect automatically", "en" to "English", "hi" to "Hindi", "bn" to "Bengali", "ta" to "Tamil", "te" to "Telugu",
        "mr" to "Marathi", "gu" to "Gujarati", "kn" to "Kannada", "ml" to "Malayalam", "pa" to "Punjabi", "ur" to "Urdu", "ne" to "Nepali",
        "es" to "Spanish", "pt" to "Portuguese", "fr" to "French", "de" to "German", "it" to "Italian", "ru" to "Russian", "ar" to "Arabic",
        "tr" to "Turkish", "id" to "Indonesian", "ms" to "Malay", "vi" to "Vietnamese", "th" to "Thai", "zh" to "Chinese", "ja" to "Japanese",
        "ko" to "Korean", "fa" to "Persian", "uk" to "Ukrainian", "pl" to "Polish", "nl" to "Dutch", "sv" to "Swedish", "el" to "Greek",
        "he" to "Hebrew", "ro" to "Romanian", "cs" to "Czech", "hu" to "Hungarian", "fi" to "Finnish", "da" to "Danish", "no" to "Norwegian",
        "tl" to "Filipino", "sw" to "Swahili", "si" to "Sinhala", "my" to "Burmese", "km" to "Khmer", "as" to "Assamese", "or" to "Odia",
    )

    @Volatile private var stop = false
    /** Stops the captions being made (from any thread). */
    fun cancel() { stop = true; Whisper.cancel() }

    class Session(context: Context) : AutoCloseable {
        private val app = context.applicationContext
        private var whisper: Whisper.Model? = null
        private val sounds: SoundClassifier? = CaptionModels.file(app, CaptionModels.CED).takeIf { it.isFile && AudioEngine.available(app) }
            ?.let { runCatching { SoundClassifier(it) }.getOrNull() }
        private val vad = CaptionModels.vad(app)
        private val speakers = Speakers.ready(app)
        init {
            if (!Whisper.available) error("Captions need an ARM phone")
            if (CaptionModels.whisper(app) == null) error("Download or add a speech model first")
            stop = false
        }

        private fun whisperModel(): Whisper.Model = whisper ?: run {
            val (file, aheads) = CaptionModels.whisper(app)
                ?: error("This language needs a Whisper model: get Whisper Small or Turbo")
            Whisper.Model(file.path, aheads).also { if (!it.ok) error("The Whisper model couldn't be loaded (not enough memory?)"); whisper = it }
        }

        /**
         * Captions for [uri] between [inUs] and [outUs] (source time) in [lang]; [progress] 0..1. Null when it was
         * cancelled.
         */
        fun caption(uri: String, inUs: Long, outUs: Long, lang: String, progress: (Float) -> Unit): List<Cue>? {
            val pcm = decode(app, uri, inUs, outUs) ?: return emptyList()
            if (pcm.size < RATE / 2) return emptyList()
            progress(0.04f)
            val labels = sounds?.classify(pcm) { p -> progress(0.04f + 0.1f * p); !stop }
            if (stop) return null
            progress(0.14f)
            // Only where someone speaks or sings goes to speech recognition (silence and music are most of many videos).
            val voice = Voice.pack(pcm, vad?.takeIf { Whisper.available }?.let { Whisper.voice(it, pcm) }, labels)
            progress(0.17f)
            val w = if (speakers) 0.6f else 0.8f
            val words: List<Word> = if (voice.audio.isEmpty()) emptyList() else {
                val m = whisperModel()
                val json = m.transcribe(voice.audio, if (m.multilingual) lang else "en") { p -> progress(0.17f + w * p / 100f) } ?: return null
                whisperWords(JSONObject(json), voice::toOriginalUs)
            }
            if (stop) return null
            // Who speaks when (where the speaker model is here), on the voiced parts only.
            val turns = if (speakers && words.isNotEmpty()) {
                val base = 0.17f + w
                Speakers.diarize(app, voice.audio) { p -> progress(base + (0.98f - base) * p); !stop }
                    ?.map { Speakers.Turn(voice.toOriginalUs(it.startUs), voice.toOriginalUs(it.endUs), it.speaker) }
            } else null
            if (stop) return null
            return cues(words, labels, turns, pcm.size, inUs)
        }

        override fun close() { whisper?.close(); sounds?.close() }
    }

    /**
     * The voiced parts of a sound, end to end, for Whisper, and the way back to the sound's own time. Voice is what
     * Silero VAD hears, plus what YAMNet calls speech or singing (VAD can miss singing over loud music).
     */
    class Voice private constructor(val audio: FloatArray, private val packedAt: IntArray, private val origAt: IntArray, private val len: IntArray) {
        /** µs in [audio] to µs in the original sound. */
        fun toOriginalUs(us: Long): Long {
            if (packedAt.isEmpty()) return us
            val p = us * RATE / 1_000_000L
            var k = packedAt.size - 1
            while (k > 0 && packedAt[k] > p) k--
            val off = (p - packedAt[k]).coerceIn(0L, len[k].toLong())
            return (origAt[k] + off) * 1_000_000L / RATE
        }

        companion object {
            private const val PAD = RATE / 4          // kept around each voiced part
            private const val JOIN = RATE * 6 / 10    // parts closer than this are one
            private const val GAP = RATE * 3 / 10     // silence between parts in the packed sound

            /** [vad]: start, end sample pairs (null: no VAD). The whole sound when there is nothing to tell by. */
            fun pack(pcm: FloatArray, vad: IntArray?, labels: SoundClassifier.Labels?): Voice {
                val whole = Voice(pcm, IntArray(0), IntArray(0), IntArray(0))
                if (vad == null) return whole
                val spans = ArrayList<IntArray>()
                for (i in 0 until vad.size / 2) spans += intArrayOf(vad[2 * i], vad[2 * i + 1])
                labels?.let { l ->
                    for (kind in intArrayOf(SoundClassifier.SPEECH, SoundClassifier.SINGING))
                        for ((a, b) in l.runs(kind, 0)) spans += intArrayOf((a * RATE / 1_000_000L).toInt(), (b * RATE / 1_000_000L).toInt())
                }
                spans.sortBy { it[0] }
                val merged = ArrayList<IntArray>()
                for (sp in spans) {
                    val a = max(0, sp[0] - PAD); val b = min(pcm.size, sp[1] + PAD)
                    if (b <= a) continue
                    val last = merged.lastOrNull()
                    if (last != null && a <= last[1] + JOIN) last[1] = max(last[1], b) else merged += intArrayOf(a, b)
                }
                val kept = merged.sumOf { it[1] - it[0] }
                if (kept == 0) return Voice(FloatArray(0), IntArray(0), IntArray(0), IntArray(0))
                if (kept > pcm.size * 0.85) return whole   // mostly voice: not worth the cuts
                val out = FloatArray(kept + GAP * (merged.size - 1))
                val packedAt = IntArray(merged.size); val origAt = IntArray(merged.size); val len = IntArray(merged.size)
                var at = 0
                for ((k, sp) in merged.withIndex()) {
                    packedAt[k] = at; origAt[k] = sp[0]; len[k] = sp[1] - sp[0]
                    System.arraycopy(pcm, sp[0], out, at, len[k])
                    at += len[k] + GAP
                }
                return Voice(out, packedAt, origAt, len)
            }
        }
    }

    // ------------------------------------------------------------------ sound

    /** The sound of [uri] between [fromUs] and [toUs], 16 kHz mono; null when it has none. */
    fun decode(context: Context, uri: String, fromUs: Long, toUs: Long): FloatArray? {
        val ex = MediaExtractor()
        try {
            val u = Uri.parse(uri)
            if (u.scheme == "content") ex.setDataSource(context, u, null) else ex.setDataSource(u.path ?: uri)
            val tr = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val fmt = ex.getTrackFormat(tr)
            ex.selectTrack(tr)
            ex.seekTo(max(0L, fromUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val mono = FloatArrayList()
            var firstUs = -1L
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            try {
                while (!outDone) {
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(5_000)
                        if (ii >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                            if (n < 0 || ex.sampleTime > toUs + 100_000) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val frames = info.size / ((if (floatPcm) 4 else 2) * ch)
                        // Skip what lies before the start (the decoder began at the sync point before it).
                        val bufStart = info.presentationTimeUs
                        for (i in 0 until frames) {
                            val t = bufStart + i * 1_000_000L / rate
                            var s = 0f
                            for (c in 0 until ch) s += if (floatPcm) buf.float else buf.short / 32768f
                            if (t < fromUs || t > toUs) continue
                            if (firstUs < 0) firstUs = t
                            mono.add(s / ch)
                        }
                        codec.releaseOutputBuffer(o, false)
                    }
                }
            } finally { runCatching { codec.stop() }; codec.release() }
            if (mono.size == 0) return null
            return resample(mono.toArray(), rate)
        } catch (e: Exception) {
            android.util.Log.w("Captioner", "decode", e); return null
        } finally { ex.release() }
    }

    /** To 16 kHz: a box filter as wide as the step (no aliasing of what is above 8 kHz), then linear interpolation. */
    private fun resample(x: FloatArray, rate: Int): FloatArray {
        if (rate == RATE) return x
        val step = rate.toDouble() / RATE
        val k = max(1, step.roundToInt())
        val f = if (k > 1) FloatArray(x.size) { i -> var a = 0f; var n = 0; for (j in max(0, i - k / 2)..min(x.size - 1, i + k / 2)) { a += x[j]; n++ }; a / n } else x
        val n = (x.size / step).toInt()
        return FloatArray(n) { i -> val p = i * step; val j = p.toInt(); val a = (p - j).toFloat(); if (j + 1 < f.size) f[j] * (1 - a) + f[j + 1] * a else f[min(j, f.size - 1)] }
    }

    private class FloatArrayList {
        private var a = FloatArray(1 shl 16); var size = 0; private set
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }

    // ------------------------------------------------------------------ captions from words

    private class Word(val text: String, val startUs: Long, val endUs: Long, var kind: Int, var speaker: Int = -1)

    /** whisper.cpp's segments and tokens (hundredths of a second from the sound's start) as words; [time] maps µs. */
    private fun whisperWords(j: JSONObject, time: (Long) -> Long): List<Word> {
        val words = ArrayList<Word>()
        val segs = j.optJSONArray("segments") ?: return emptyList()
        for (si in 0 until segs.length()) {
            val seg = segs.getJSONObject(si)
            if (seg.optDouble("ns", 0.0) > 0.85) continue   // Whisper itself says nobody is speaking
            val toks = seg.optJSONArray("tokens") ?: continue
            var cur: StringBuilder? = null; var cs = 0L; var ce = 0L
            fun flush() { cur?.let { t -> val s = t.toString(); if (s.isNotBlank()) words += Word(s, cs, max(ce, cs + 50_000), Cue.SPEECH) }; cur = null }
            for (ti in 0 until toks.length()) {
                val t = toks.getJSONObject(ti)
                val text = t.optString("t")
                if (text.isEmpty() || (text.startsWith("[") && text.endsWith("]"))) continue
                val d = t.optLong("d", -1L)
                val t0 = time((if (d >= 0) d else t.optLong("t0")) * 10_000L)
                val t1 = max(time(t.optLong("t1") * 10_000L), t0)
                // A token beginning with a space starts a word; languages written without spaces go token by token.
                if (cur == null || text.startsWith(" ")) {
                    flush(); cur = StringBuilder(); cs = t0
                }
                cur!!.append(text); ce = t1
                if (isCjk(text)) flush()
            }
            flush()
        }
        return words
    }

    /** Words (µs from the sound's start) to captions in source time. */
    private fun cues(words: List<Word>, labels: SoundClassifier.Labels?, turns: List<Speakers.Turn>?, samples: Int, offsetUs: Long): List<Cue> {
        // What the sound classifier heard under each word: sung, spoken, or only music (then Whisper made it up).
        val kept = if (labels == null) words else words.filter { w ->
            val k = labels.at((w.startUs + w.endUs) / 2)
            w.kind = if (k == SoundClassifier.SINGING) Cue.LYRICS else Cue.SPEECH
            k != SoundClassifier.MUSIC
        }
        // Each word's speaker: the one whose turn covers most of it.
        if (turns != null) for (w in kept) {
            var best = -1; var most = 0L
            for (t in turns) { val o = min(w.endUs, t.endUs) - max(w.startUs, t.startUs); if (o > most) { most = o; best = t.speaker } }
            w.speaker = best
        }
        val out = ArrayList<Cue>()
        var i = 0
        while (i < kept.size) {
            val start = i
            var chars = 0
            while (i < kept.size) {
                val w = kept[i]
                if (i > start) {
                    val prev = kept[i - 1]
                    val pause = w.startUs - prev.endUs
                    val dur = w.endUs - kept[start].startUs
                    val sentenceEnd = prev.text.trimEnd().lastOrNull()?.let { it in ".?!।。？！" } == true
                    // A new speaker always starts a new caption: never two people's words in one line.
                    val newSpeaker = w.speaker >= 0 && prev.speaker >= 0 && w.speaker != prev.speaker
                    if (newSpeaker || chars + w.text.length > 84 || dur > 7_000_000 || pause > 700_000 || w.kind != prev.kind ||
                        (sentenceEnd && prev.endUs - kept[start].startUs > 1_200_000)) break
                }
                chars += w.text.length; i++
            }
            val ws = kept.subList(start, i)
            var text = ws.joinToString("") { it.text }.trim().replace(Regex("\\s+"), " ")
            if (text.length > 42) text = balance(text)
            val kind = ws.first().kind
            if (kind == Cue.LYRICS) text = "$CLEF $text"
            out += Cue(ws.first().startUs, ws.last().endUs, text, kind, ws.first().speaker)
        }
        // Two people in quick succession (short lines, the second straight after the first, or over it): one line each,
        // stacked; the first shows alone, then the reply joins it underneath in grey when it is said.
        run {
            val stacked = ArrayList<Cue>()
            var k = 0
            while (k < out.size) {
                val a = out[k]; val b = out.getOrNull(k + 1)
                if (b != null && a.kind == Cue.SPEECH && b.kind == Cue.SPEECH && a.speaker >= 0 && b.speaker >= 0 && a.speaker != b.speaker &&
                    '\n' !in a.text && '\n' !in b.text && a.text.length <= 42 && b.text.length <= 42 &&
                    b.startUs - a.endUs <= 600_000 && a.endUs - a.startUs <= 4_000_000) {
                    stacked += Cue(a.startUs, b.startUs, a.text, a.kind, a.speaker)
                    stacked += Cue(b.startUs, b.endUs, a.text + "\n" + b.text, b.kind, b.speaker, a.text.length + 1)
                    k += 2
                } else { stacked += a; k++ }
            }
            out.clear(); out += stacked
        }
        // Music with no singing: 𝄞 alone, over stretches of two seconds or more with no other caption.
        if (labels != null) for ((s, e) in labels.runs(SoundClassifier.MUSIC, 2_000_000)) {
            val free = out.none { it.startUs < e && it.endUs > s }
            if (free) out += Cue(s, e, CLEF, Cue.MUSIC)
        }
        out.sortBy { it.startUs }
        // Readable: each at least a second where there is room, a little after the last word, never overlapping.
        val total = samples * 1_000_000L / RATE
        val timed = ArrayList<Cue>()
        for ((k, c) in out.withIndex()) {
            val nx = out.getOrNull(k + 1)
            val next = nx?.startUs ?: total
            // The first line of a stacked pair runs straight into the pair (no flicker between them).
            val joins = nx != null && nx.tintFrom > 0 && nx.text.startsWith(c.text)
            val end = if (joins) next else min(max(c.endUs + 200_000, c.startUs + 1_000_000), next - 40_000).coerceAtLeast(c.startUs + 300_000).coerceAtMost(total)
            if (end > c.startUs) timed += Cue(c.startUs + offsetUs, end + offsetUs, c.text, c.kind, c.speaker, c.tintFrom)
        }
        return timed
    }

    private fun isCjk(s: String) = s.any { Character.UnicodeScript.of(it.code).let { sc -> sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.THAI } }

    /** Two lines of as even length as the spaces allow (Netflix's bottom-heavy pyramid when they can't be even). */
    private fun balance(t: String): String {
        val mid = t.length / 2
        var best = -1
        for (d in 0..mid) {
            val r = mid + d; val l = mid - d
            if (r < t.length && t[r] == ' ') { best = r; break }
            if (l > 0 && t[l] == ' ') { best = l; break }
        }
        return if (best <= 0) t else t.substring(0, best) + "\n" + t.substring(best + 1)
    }
}

/**
 * CED mini (Apache-2.0, distilled from AudioSet's best transformer models) in audio.cpp: AudioSet's 527 sound classes
 * for each 4 s of sound, every 2 s; boiled down to speech, singing, music or quiet. On AudioSet's evaluation clips it
 * finds 60% of singing at 75% precision, where YAMNet with the earlier rules found 16%.
 */
class SoundClassifier(model: java.io.File) : AutoCloseable {
    private val m = AudioEngine.Model(model, "ced", "cls", "offline")

    class Labels(private val l: IntArray, private val hopUs: Long) {
        fun at(us: Long): Int = if (l.isEmpty()) NONE else l[(us / hopUs).toInt().coerceIn(0, l.size - 1)]
        /** Spans of [kind] at least [minUs] long, in µs from the start. */
        fun runs(kind: Int, minUs: Long): List<Pair<Long, Long>> {
            val r = ArrayList<Pair<Long, Long>>()
            var i = 0
            while (i < l.size) {
                if (l[i] != kind) { i++; continue }
                val s = i; while (i < l.size && l[i] == kind) i++
                val a = s * hopUs; val b = i * hopUs
                if (b - a >= minUs) r += a to b
            }
            return r
        }
    }

    /** One label for each second of [pcm]; [progress] 0..1, false to stop (the labels so far are kept). */
    fun classify(pcm: FloatArray, progress: (Float) -> Boolean): Labels {
        val sec = Captioner.RATE
        val win = 4 * sec; val hop = 2 * sec
        val n = (pcm.size + sec - 1) / sec
        // Each second takes the scores of the windows over it (the highest), so short sung lines aren't averaged away.
        val speech = FloatArray(n); val sing = FloatArray(n); val music = FloatArray(n)
        var at = 0
        while (true) {
            val end = min(pcm.size, at + win)
            val sc = m.classify(pcm.copyOfRange(at, end))
            val sp = sc["Speech"] ?: 0f
            val sg = SUNG.maxOf { sc[it] ?: 0f }
            val mu = sc["Music"] ?: 0f
            for (k in at / sec until min(n, (end + sec - 1) / sec)) { speech[k] = max(speech[k], sp); sing[k] = max(sing[k], sg); music[k] = max(music[k], mu) }
            if (end >= pcm.size || !progress(end.toFloat() / pcm.size)) break
            at += hop
        }
        // Thresholds where each class did best on AudioSet's evaluation clips.
        val raw = IntArray(n) { k ->
            when {
                sing[k] >= 0.2f && music[k] >= 0.2f -> SINGING
                speech[k] >= 0.45f && speech[k] >= music[k] * 0.8f -> SPEECH
                music[k] >= 0.35f -> MUSIC
                speech[k] >= 0.3f -> SPEECH
                else -> NONE
            }
        }
        // A majority over three seconds, so a cough or a drum hit does not flip the label.
        val l = IntArray(n) { i -> val c = IntArray(4); for (j in max(0, i - 1)..min(n - 1, i + 1)) c[raw[j]]++; if (c[raw[i]] >= 2) raw[i] else (0 until 4).maxBy { c[it] } }
        return Labels(l, 1_000_000L)
    }

    override fun close() = m.close()

    companion object {
        const val NONE = 0; const val SPEECH = 1; const val SINGING = 2; const val MUSIC = 3
        private val SUNG = listOf("Singing", "Male singing", "Female singing", "Child singing", "Choir", "Rapping", "Synthetic singing", "Vocal music", "A capella")
    }
}

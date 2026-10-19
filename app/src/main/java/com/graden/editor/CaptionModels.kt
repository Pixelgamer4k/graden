package com.graden.editor

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The models captions need, kept out of the app: downloaded from their official sources when asked (checked against
 * the published SHA-256), or added from the phone's storage. Speech: Whisper large-v3-turbo or small (8-bit, 99
 * languages), with Silero VAD to skip what nobody says. Speakers: NVIDIA Nemotron 3 Diarization. Sound: CED, which tells speech, singing and music apart.
 */
object CaptionModels {
    class Spec(val id: String, val title: String, val note: String, val file: String, val url: String, val size: Long, val sha256: String, val aheads: Int = 0)

    // 8-bit: whisper.cpp repacks these weights for the phone's matrix instructions, about 2.5x quicker than the 5-bit
    // files (which it can't repack) at the same or better accuracy.
    val WHISPER_BEST = Spec("turbo", "Whisper large-v3-turbo", "Best accuracy, 99 languages · 874 MB", "ggml-large-v3-turbo-q8_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin", 874_188_075L,
        "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1", Whisper.AHEADS_LARGE_V3_TURBO)
    val WHISPER_FAST = Spec("small", "Whisper small", "Fast, 99 languages · 264 MB", "ggml-small-q8_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin", 264_464_607L,
        "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f", Whisper.AHEADS_SMALL)
    /** The earlier 5-bit downloads: still used until the 8-bit ones are here. */
    private val OLD = mapOf("turbo" to "ggml-large-v3-turbo-q5_0.bin", "small" to "ggml-small-q5_1.bin")
    /** Silero VAD (MIT): finds where someone speaks, so Whisper skips silence and music. Comes with either Whisper. */
    val VAD = Spec("vad", "Silero VAD", "", "ggml-silero-v6.2.0.bin",
        "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin", 885_098L,
        "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987")
    // audio.cpp's own packaging (GGUF) of the newer models, from its official Hugging Face pages.
    private const val AC = "https://huggingface.co/audio-cpp"
    /** NVIDIA Nemotron 3 Diarization (OpenMDW-1.1): who speaks when, up to eight people. */
    val DIARIZATION = Spec("diar", "Nemotron 3 Diarization", "", "nemotron-3-diarization-q8_0.gguf",
        "$AC/Nemotron-3-Diarization-GGUF/resolve/main/nemotron-3-diarization-q8_0.gguf", 106_675_136L,
        "9a737455bd10123bcf1e036d9a0b07b6e8c42e7d0dd1a5ee4141dc386db46d0b")
    /** CED mini (Apache-2.0): AudioSet's 527 sound classes; tells speech, singing and music apart. */
    val CED = Spec("ced", "CED sound classifier", "", "ced-mini-f32.gguf",
        "$AC/audio.cpp-gguf/resolve/main/CED-GGUF/ced-mini-f32.gguf", 38_904_352L,
        "5b84971616fff14e9f988f2c25424f6d4036fd4594edb2dccb6828793e605c6b")
    /** A Whisper model added from storage (any ggml Whisper model). */
    const val WHISPER_OWN_FILE = "ggml-whisper-own.bin"

    fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
    fun file(context: Context, s: Spec) = File(dir(context), s.file)
    fun has(context: Context, s: Spec) = file(context, s).let { it.isFile && it.length() > 0 } || old(context, s) != null
    /** The earlier, slower download of [s], when only that is here. */
    fun old(context: Context, s: Spec): File? =
        if (file(context, s).isFile) null else OLD[s.id]?.let { File(dir(context), it) }?.takeIf { it.isFile && it.length() > 0 }
    /** [s]'s file on this phone: the current one, else the earlier one. */
    private fun present(context: Context, s: Spec): File? = file(context, s).takeIf { it.isFile && it.length() > 0 } ?: old(context, s)
    fun ownWhisper(context: Context) = File(dir(context), WHISPER_OWN_FILE).takeIf { it.isFile && it.length() > 0 }

    private fun prefs(context: Context) = context.getSharedPreferences("captions", Context.MODE_PRIVATE)

    /** The Whisper model chosen: "turbo", "small" or "own". */
    fun chosen(context: Context): String = prefs(context).getString("whisper", null)?.takeIf { it != "nemotron" } ?: when {
        has(context, WHISPER_BEST) -> "turbo"; has(context, WHISPER_FAST) -> "small"; ownWhisper(context) != null -> "own"; else -> "small"
    }
    fun choose(context: Context, id: String) = prefs(context).edit().putString("whisper", id).apply()

    /** The Whisper model to use (file, alignment preset), when it is here. */
    fun whisper(context: Context): Pair<File, Int>? = when (chosen(context)) {
        "small" -> present(context, WHISPER_FAST)?.let { it to WHISPER_FAST.aheads }
        "own" -> ownWhisper(context)?.let { it to 0 }
        else -> present(context, WHISPER_BEST)?.let { it to WHISPER_BEST.aheads }
    } ?: listOf(WHISPER_BEST, WHISPER_FAST).firstNotNullOfOrNull { s -> present(context, s)?.let { it to s.aheads } } ?: ownWhisper(context)?.let { it to 0 }

    /** The voice finder, when it is here. */
    fun vad(context: Context): File? = file(context, VAD).takeIf { it.isFile && it.length() > 0 }

    /**
     * Downloads [s] from its official source, resuming a partial download, reporting progress 0..1; [cancelled] stops
     * it (the part is kept for next time). Checked against the published SHA-256 before it is used.
     */
    fun download(context: Context, s: Spec, progress: (Float) -> Unit, cancelled: () -> Boolean): Result<File> = runCatching {
        val out = file(context, s)
        val part = File(out.path + ".part")
        var have = if (part.isFile) part.length() else 0L
        if (have > s.size) { part.delete(); have = 0L }
        if (have < s.size) {
            val c = (URL(s.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "Graden")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = c.responseCode
                if (code !in 200..299) error("The server answered $code")
                if (code == 200 && have > 0) have = 0L   // no resume: from the start
                RandomAccessFile(part, "rw").use { f ->
                    f.setLength(have); f.seek(have)
                    c.inputStream.use { inp ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            if (cancelled()) error("Cancelled")
                            val n = inp.read(buf); if (n < 0) break
                            f.write(buf, 0, n); have += n
                            if (have - last > 1 shl 20) { last = have; progress((have.toFloat() / s.size).coerceAtMost(1f)) }
                        }
                    }
                }
            } finally { c.disconnect() }
        }
        progress(1f)
        if (part.length() != s.size || sha256(part) != s.sha256) { part.delete(); error("The download didn't match the official file; try again") }
        if (!part.renameTo(out)) error("Couldn't keep the file")
        // The earlier, slower file of this model is no longer needed.
        OLD[s.id]?.let { File(dir(context), it).delete() }
        out
    }

    /**
     * Adds a model from storage as [target] (an audio.cpp GGUF file); with [target] null, a Whisper ggml model (kept as
     * "own"). Checks it is the right kind of file.
     */
    fun import(context: Context, uri: Uri, target: Spec?): Result<File> = runCatching {
        val tmp = File(dir(context), "import.part")
        context.contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } } ?: error("Couldn't open the file")
        val head = String(tmp.inputStream().use { i -> ByteArray(4).also { i.read(it) } }, Charsets.ISO_8859_1)
        val out = when {
            target != null && head == "GGUF" -> file(context, target)
            target == null && head == "lmgg" -> File(dir(context), WHISPER_OWN_FILE)
            else -> { tmp.delete(); error(if (target == null) "Not a Whisper model (.bin)" else "Not a ${target.title} file (.gguf)") }
        }
        out.delete()
        if (!tmp.renameTo(out)) error("Couldn't keep the file")
        if (out.name == WHISPER_OWN_FILE) choose(context, "own")
        out
    }

    /** What earlier versions downloaded and this one no longer uses (the older speaker and sound models). */
    fun removeRetired(context: Context) {
        File(dir(context), "yamnet.tflite").delete()
        File(dir(context), "nemotron-3.5-asr-streaming-0.6b-q8_0.gguf").delete()
        File(dir(context), "speakers").let { d -> d.listFiles()?.forEach { it.setWritable(true); it.delete() }; d.delete() }
        dir(context).listFiles()?.filter { it.name.startsWith("sherpa-onnx") || it.name.startsWith("wespeaker") }?.forEach { it.delete() }
    }

    fun delete(context: Context, s: Spec) {
        file(context, s).delete(); File(file(context, s).path + ".part").delete()
        OLD[s.id]?.let { File(dir(context), it).delete() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

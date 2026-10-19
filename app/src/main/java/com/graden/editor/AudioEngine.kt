package com.graden.editor

import android.content.Context
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.File

/** Told how far a run is, 0..1 (called from native code, on the calling thread); false stops it. */
@Keep
abstract class AudioProgress {
    @Keep abstract fun onProgress(done: Float): Boolean
}

/**
 * The newer caption models, run by audio.cpp (Apache-2.0) on the phone's CPU: NVIDIA Nemotron 3 Diarization (who
 * speaks when, up to eight people, overlaps included) and CED (527 AudioSet sound classes). 64-bit ARM phones with the ARMv8.2 dot-product instructions (about
 * 2019 on); the build with the 8-bit matrix instructions where the processor has them.
 */
object AudioEngine {
    @Volatile private var opened: Boolean? = null

    /** Loads the library once; false where this phone can't run it. */
    @Synchronized fun available(context: Context): Boolean {
        opened?.let { return it }
        val ok = runCatching {
            if (android.os.Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return@runCatching false
            val cpu = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
            fun has(f: String) = Regex("\\b$f\\b").containsMatchIn(cpu)
            if (!(has("asimddp") && has("asimdhp"))) return@runCatching false
            System.loadLibrary("graden_audio")
            val tmp = File(context.cacheDir, "audiocpp").apply { mkdirs() }
            val libs = if (has("i8mm")) listOf("libaudiocpp_i8mm.so", "libaudiocpp_v82.so") else listOf("libaudiocpp_v82.so")
            libs.any { lib -> nativeOpen(lib, tmp.path).also { e -> if (e != null) android.util.Log.w("AudioEngine", "$lib: $e") } == null }
        }.onFailure { android.util.Log.w("AudioEngine", "unavailable", it) }.getOrDefault(false)
        opened = ok
        return ok
    }

    @JvmStatic private external fun nativeOpen(lib: String, tmp: String): String?
    @JvmStatic private external fun nativeLastError(): String
    @JvmStatic private external fun nativeLoad(path: String, family: String, task: String, mode: String, threads: Int, opts: Array<String>): Long
    @JvmStatic private external fun nativeFree(h: Long)
    @JvmStatic private external fun nativeDiar(h: Long, pcm: FloatArray, cb: AudioProgress): String?
    @JvmStatic private external fun nativeCls(h: Long, pcm: FloatArray, topK: Int): String?

    /** A loaded model, ready for one task. Not for two threads at once. */
    class Model(file: File, family: String, task: String, mode: String, vararg opts: Pair<String, String>) : AutoCloseable {
        private var h = nativeLoad(file.path, family, task, mode, Whisper.threads, opts.flatMap { listOf(it.first, it.second) }.toTypedArray())
        init { if (h == 0L) error("Couldn't load ${file.name}: ${nativeLastError()}") }

        /** Who speaks when in 16 kHz mono [pcm]: (start, end, speaker) in samples; null when stopped. */
        fun diarize(pcm: FloatArray, progress: (Float) -> Boolean): List<Triple<Int, Int, Int>>? {
            var stopped = false
            val r = nativeDiar(h, pcm, object : AudioProgress() { override fun onProgress(done: Float) = progress(done).also { if (!it) stopped = true } })
            if (r == null) { if (stopped) return null; error("Speaker detection failed: ${nativeLastError()}") }
            val a = JSONObject(r).getJSONArray("turns")
            return List(a.length()) { i ->
                a.getJSONArray(i).let { t -> Triple(t.getInt(0), t.getInt(1), t.getString(2).substringAfterLast('_').toIntOrNull() ?: 0) }
            }
        }

        /** Sound class scores (label to 0..1) of 16 kHz mono [pcm]. */
        fun classify(pcm: FloatArray): Map<String, Float> {
            val a = JSONObject(nativeCls(h, pcm, 527) ?: error("Sound tagging failed: ${nativeLastError()}")).getJSONObject("data").getJSONArray("scores")
            return HashMap<String, Float>(a.length() * 2).apply { for (i in 0 until a.length()) a.getJSONObject(i).let { put(it.getString("label"), it.getDouble("score").toFloat()) } }
        }

        override fun close() { if (h != 0L) nativeFree(h); h = 0L }
    }
}

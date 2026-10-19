package com.graden.editor

import androidx.annotation.Keep
import java.io.File

/** Told how far a transcription is, 0..100 (called from native code, on the transcribing thread). */
@Keep
abstract class WhisperProgress {
    @Keep abstract fun onProgress(percent: Int)
}

/**
 * Speech to text: whisper.cpp (cpp/whisper, MIT) on the phone's CPU. Three builds of it on 64-bit ARM: one also
 * using the 8-bit matrix-multiply instructions (phones from about 2022 on), one using the half-float and dot-product
 * instructions of ARMv8.2 cores (from about 2019 on), and a plain one for older cores; the best the CPU runs is loaded.
 */
object Whisper {
    /** Which build is loaded ("" when none could be). */
    val variant: String = run {
        val features = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        fun has(f: String) = Regex("\\b$f\\b").containsMatchIn(features)
        val fast = has("asimddp") && has("asimdhp")
        val tries = when {
            fast && has("i8mm") -> listOf("whisper_i8mm", "whisper_v82", "whisper")
            fast -> listOf("whisper_v82", "whisper")
            else -> listOf("whisper")
        }
        tries.firstOrNull { runCatching { System.loadLibrary(it); true }.getOrDefault(false) } ?: ""
    }
    val available: Boolean get() = variant.isNotEmpty()

    /** Threads to use: the phone's big cores (those within a quarter of the fastest clock), 2 to 6. */
    val threads: Int by lazy {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        val n = if (freqs.isEmpty()) Runtime.getRuntime().availableProcessors() / 2 else freqs.count { it >= freqs.max() * 0.75 }
        n.coerceIn(2, 6)
    }

    @JvmStatic private external fun nativeInit(path: String, aheads: Int): Long
    @JvmStatic private external fun nativeFree(h: Long)
    @JvmStatic private external fun nativeCancel()
    @JvmStatic private external fun nativeMultilingual(h: Long): Boolean
    @JvmStatic private external fun nativeVad(path: String, pcm: FloatArray, threads: Int): FloatArray?
    @JvmStatic private external fun nativeTranscribe(h: Long, pcm: FloatArray, lang: String, threads: Int, prompt: String?, cb: WhisperProgress): String?

    /** A loaded model. [aheads]: whisper.cpp's alignment-heads preset for word timing (0: none). */
    class Model(path: String, aheads: Int) : AutoCloseable {
        private var h = nativeInit(path, aheads)
        val ok get() = h != 0L
        val multilingual: Boolean get() = h != 0L && nativeMultilingual(h)

        /** 16 kHz mono [pcm] to whisper.cpp's JSON (see whisper_jni.cpp); null when it failed or was cancelled. */
        fun transcribe(pcm: FloatArray, lang: String, progress: (Int) -> Unit): String? {
            if (h == 0L) return null
            return nativeTranscribe(h, pcm, lang, threads, null, object : WhisperProgress() { override fun onProgress(percent: Int) = progress(percent) })
        }

        override fun close() { if (h != 0L) nativeFree(h); h = 0L }
    }

    /**
     * Where someone speaks or sings in 16 kHz mono [pcm], by Silero VAD (the ggml model [model]): start and end
     * sample pairs; null when the model couldn't be used.
     */
    fun voice(model: File, pcm: FloatArray): IntArray? {
        if (!available) return null
        val t = nativeVad(model.path, pcm, threads) ?: return null
        return IntArray(t.size) { i -> (t[i] * Captioner.RATE).toInt().coerceIn(0, pcm.size) }
    }

    /** Stops the transcription that is running (from any thread). */
    fun cancel() { if (available) nativeCancel() }

    /** whisper.cpp's alignment-head presets (whisper.h), for word timing. */
    const val AHEADS_SMALL = 8
    const val AHEADS_LARGE_V3_TURBO = 14
}

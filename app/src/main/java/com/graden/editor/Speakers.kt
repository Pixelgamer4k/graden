package com.graden.editor

import android.content.Context

/**
 * Who speaks when: NVIDIA Nemotron 3 Diarization (OpenMDW-1.1) in audio.cpp, an end-to-end model that tells up to
 * eight people apart by the order they first speak, overlapping speech included, with no clustering to tune. On
 * YouTube-style recordings (VoxConverse) it gets about 9% of speaking time wrong where the earlier pyannote + CAM++
 * pipeline got 54%, and finds the right number of people far more often. Run in its streaming form with the 30 s
 * profile, so memory stays flat however long the video.
 */
object Speakers {
    /** One speaker's stretch of speech, in µs from the start of the sound. Stretches of different speakers can overlap. */
    class Turn(val startUs: Long, val endUs: Long, val speaker: Int)

    fun ready(context: Context) = CaptionModels.has(context, CaptionModels.DIARIZATION) && AudioEngine.available(context)

    /** Who speaks when in 16 kHz [pcm]; null when the model is missing, failed, or [progress] said stop. */
    fun diarize(context: Context, pcm: FloatArray, progress: (Float) -> Boolean): List<Turn>? {
        if (!ready(context)) return null
        return runCatching {
            AudioEngine.Model(CaptionModels.file(context, CaptionModels.DIARIZATION), "nemotron_3_diar", "diar", "streaming",
                "nemotron_3_diar.latency_profile" to "very_high").use { m ->
                m.diarize(pcm, progress)?.map { (s, e, k) -> Turn(s * 1_000_000L / Captioner.RATE, e * 1_000_000L / Captioner.RATE, k) }?.sortedBy { it.startUs }
            }
        }.onFailure { android.util.Log.w("Speakers", "diarization failed", it) }.getOrNull()
    }
}

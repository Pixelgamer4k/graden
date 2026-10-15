package com.graden.editor

/** A source file on the device. All times are microseconds. */
data class MediaAsset(
    val id: Int,
    val uri: String,
    val name: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val hasAudio: Boolean,
    val hdr: Boolean,
    val mime: String,
    /** Playback tier chosen by [DeviceProfiler] at import: 0 plays the original, higher plays a smaller proxy. */
    val tier: Int = 0,
    /**
     * For a reversed copy made by the Reverse tool: the asset it was made from, and the range of it that was
     * reversed. Time t in this copy is [revOutUs] - t in the original. -1 for ordinary files.
     */
    val reverseOf: Int = -1,
    val revInUs: Long = 0L,
    val revOutUs: Long = 0L,
) {
    val isReversed: Boolean get() = reverseOf >= 0
    /** Audio-only files have no picture size. */
    val isAudio: Boolean get() = width == 0
    /** A still picture: it has no length of its own, so a clip of it can be stretched to any duration. */
    val isImage: Boolean get() = mime.startsWith("image/")
}

/**
 * A picture's place on the canvas. With all defaults it fits the canvas, centred. [x] and [y] move
 * its centre by fractions of the canvas width and height; [scale] is relative to fitting; [rot] is
 * in degrees. The crop values are fractions of the picture cut off each side.
 */
data class Tf(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rot: Float = 0f,
    val cropL: Float = 0f,
    val cropT: Float = 0f,
    val cropR: Float = 0f,
    val cropB: Float = 0f,
    /** Mirrored left-to-right / top-to-bottom. */
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) {
    val isIdentity: Boolean get() = this == Tf()
    val cropped: Boolean get() = cropL > 0f || cropT > 0f || cropR > 0f || cropB > 0f
}

/** A trimmed range of an asset, sitting at [startUs] on its track. */
data class Clip(
    val id: Long,
    val assetId: Int,
    val inUs: Long,
    val outUs: Long,
    val startUs: Long = 0L,
    /** The transition into this clip from the clip that ends exactly where this one starts, if there is one. */
    val transition: Fx? = null,
    /** How this clip arrives and leaves on its own, where there is no transition. */
    val animIn: Fx? = null,
    val animOut: Fx? = null,
    /** Where the picture sits on the canvas, how big, how turned, and what is cropped off it. */
    val tf: Tf = Tf(),
    /** Volume, fades, pan, equaliser and pitch of the clip's sound. */
    val snd: Snd = Snd(),
    /** Playback speed: 2 plays the source range in half the time, 0.5 in twice the time. The pitch is kept. */
    val speed: Float = 1f,
    /** The colour grade, made on the Color page. */
    val grade: Grade = Grade(),
) {
    /** How long the clip lasts on the timeline. */
    val durationUs: Long get() = ((outUs - inUs) / speed).toLong()
    /** How much of the source it uses. */
    val srcLenUs: Long get() = outUs - inUs

    /** The source time shown [localUs] into the clip on the timeline. */
    fun srcAt(localUs: Long): Long = inUs + (localUs * speed).toLong()
    val endUs: Long get() = startUs + durationUs
}

/**
 * One lane. Track id 0 is V0, the base video layer, which always exists. [name] ("V1", "A2"…) is
 * given when the lane is created and stays with it wherever it is moved. Clips never overlap.
 */
data class Track(val id: Int, val video: Boolean, val clips: List<Clip>, val name: String = if (id == 0) "V0" else "") {
    val endUs: Long get() = clips.maxOfOrNull { it.endUs } ?: 0L
    fun clipAt(us: Long): Clip? = clips.firstOrNull { us >= it.startUs && us < it.endUs }
}

const val MIN_CLIP_US = 100_000L

fun timecode(us: Long, fps: Float): String {
    val f = fps.toInt().coerceAtLeast(1).let { if (fps - it > 0.5f) it + 1 else it }
    val frames = us * f / 1_000_000L
    val ff = frames % f
    val s = frames / f
    val h = s / 3600
    val m = (s / 60) % 60
    val sec = s % 60
    return if (h > 0) "%02d:%02d:%02d:%02d".format(h, m, sec, ff) else "%02d:%02d:%02d".format(m, sec, ff)
}

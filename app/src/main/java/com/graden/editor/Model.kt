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
    /**
     * For a MotionCam RAW recording: the recording itself ([uri] is then its developed master, which everything else
     * reads), and its colour calibration (see [McRawInfo.toJson]).
     */
    val raw: String? = null,
    val rawCal: String? = null,
    /** How the master was developed: at half resolution, and with the lens's shading corrected. */
    val rawHalf: Boolean = false,
    val rawShading: Boolean = true,
    /** The file says its picture uses full range (0–1023); most video is recorded in video levels (64–940). */
    val fullRange: Boolean = false,
    /** An HDR file's curve as the file says: HLG (else PQ). Read at import, so it is known before anything is drawn. */
    val hlg: Boolean = false,
    /** For a RAW recording: the full develop options ([DevelopOptions.toJson]); null: just [rawHalf] and [rawShading]. */
    val rawDev: String? = null,
    /** The file's size (bytes) and when it was made (ms since 1970), for sorting the media box; 0 unknown. */
    val bytes: Long = 0L,
    val dateMs: Long = 0L,
) {
    val isGif: Boolean get() = mime == "image/gif"
    val developOptions: DevelopOptions get() = DevelopOptions.of(rawDev, rawHalf, rawShading)
    val isReversed: Boolean get() = reverseOf >= 0
    val isRaw: Boolean get() = raw != null
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
    /** The colour space the file is in, when set by hand (a camera log, say); null reads it from the file. */
    val cs: String? = null,
    /** Optical stabilisation, made on the Stabilize page; null: none. */
    val stab: Stab? = null,
    /** A caption ([assetId] is then [CAPTION_ASSET]): its text, on a caption layer. */
    val caption: Caption? = null,
    /** A cinema lens and its focus points, made on the Cinema page; null: none. */
    val cinema: Cinema? = null,
) {
    val isCaption: Boolean get() = caption != null
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
/** A text layer ([captions]) is a visual lane of texts (captions and titles) only, drawn in its place among the others. */
data class Track(val id: Int, val video: Boolean, val clips: List<Clip>, val name: String = if (id == 0) "V0" else "", val captions: Boolean = false) {
    /** A lane of pictures (video, not captions). */
    val pictures: Boolean get() = video && !captions
    val endUs: Long get() = clips.maxOfOrNull { it.endUs } ?: 0L
    fun clipAt(us: Long): Clip? = clips.firstOrNull { us >= it.startUs && us < it.endUs }
}

const val MIN_CLIP_US = 100_000L

/**
 * One caption, in source time ([startUs]..[endUs] of the file), with its kind, who says it ([speaker], -1 unknown) and,
 * for two people in quick succession, where the second one's line starts in [text] ([tintFrom], -1 none).
 */
class Cue(val startUs: Long, val endUs: Long, val text: String, val kind: Int, val speaker: Int = -1, val tintFrom: Int = -1) {
    companion object { const val SPEECH = 0; const val LYRICS = 1; const val MUSIC = 2 }
}

/** The asset id a caption clip carries: it has no file. */
const val CAPTION_ASSET = -1

/**
 * A text on the picture: a caption made from the sound, or a title, heading or any words added by hand. Captions and
 * texts are the same thing, on visual layers like any picture. [text]: its words (a line break where it is split);
 * [kind]: what it says ([Cue] speech, sung lyrics shown with 𝄞, or music alone; [TEXT] for words added by hand).
 * [tintFrom]: two speakers, from this character on the second one's line (-1: one speaker). [size]: [SIZES] 0..4,
 * times [scale]. [x], [y]: where its centre is on the video (0..1 of its width and height); NaN: the subtitle place,
 * centred near the bottom. [rot]: turned, degrees. [look]: font, colours, edge, box. [animIn], [animOut]: how it
 * arrives and leaves ([TextAnim] ids; "none" by default) and in how long.
 */
data class Caption(
    val text: String,
    val kind: Int = Cue.SPEECH,
    val tintFrom: Int = -1,
    val size: Int = 2,
    val scale: Float = 1f,
    val x: Float = Float.NaN,
    val y: Float = Float.NaN,
    val rot: Float = 0f,
    val look: TextLook = TextLook(),
    val animIn: String = TextAnim.NONE,
    val animOut: String = TextAnim.NONE,
    val inUs: Long = 400_000L,
    val outUs: Long = 400_000L,
) {
    val placed: Boolean get() = !x.isNaN() && !y.isNaN()
    /** Made from the sound (speech, lyrics, music), however it was edited, moved or restyled since; else added by hand.
     *  An export can write these out as a subtitle file and leave them off the picture. */
    val fromSound: Boolean get() = kind != TEXT
    companion object {
        const val TEXT = 3
        val SIZES = listOf("Very small", "Small", "Normal", "Large", "Very large")
        val SCALES = floatArrayOf(0.72f, 0.86f, 1f, 1.18f, 1.38f)
    }
}

/**
 * How a text looks. Colours are ARGB; widths and sizes are fractions of the text's size, so a look is the same at any
 * size. [font]: a [TextFonts] id; [weight] 100..900. [stroke]: edge width (0 none); [shadow]: shadow softness (0 none);
 * [box]: a box behind each line (alpha 0: none) with corners [boxRound]. [align]: 0 left, 1 centre, 2 right.
 * [letter]: extra space between letters (em); [line]: line height. [caps]: all capitals. [decor]: a line or frame
 * drawn with it ([DECOR_*]) in [decorColor]. [sub]: lines after the first at this size, in [subColor] (a subtitle
 * under a title; 1: the same).
 */
data class TextLook(
    val font: String = "inter",
    val weight: Int = 600,
    val italic: Boolean = false,
    val color: Int = 0xFFFFFFFF.toInt(),
    val stroke: Float = 0.13f,
    val strokeColor: Int = 0xD7000000.toInt(),
    val shadow: Float = 0.10f,
    val shadowColor: Int = 0x96000000.toInt(),
    val box: Int = 0,
    val boxRound: Float = 0.22f,
    val align: Int = 1,
    val letter: Float = 0f,
    val line: Float = 1.08f,
    val caps: Boolean = false,
    val decor: Int = DECOR_NONE,
    val decorColor: Int = 0xFFFFFFFF.toInt(),
    val sub: Float = 1f,
    val subColor: Int = 0xFFB4B8C2.toInt(),
    val opacity: Float = 1f,
) {
    companion object {
        const val DECOR_NONE = 0
        /** A bar under the text. */
        const val DECOR_UNDERLINE = 1
        /** Thin lines above and below. */
        const val DECOR_RULES = 2
        /** A thin frame around it. */
        const val DECOR_FRAME = 3
        /** A bar at its left, as on a lower third. */
        const val DECOR_SIDEBAR = 4
    }
}

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

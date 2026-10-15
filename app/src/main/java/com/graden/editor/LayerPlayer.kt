package com.graden.editor

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlin.math.abs
import kotlin.math.pow
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Plays one lane (a video layer or an audio lane). Clips on a lane sit anywhere, with gaps, so
 * the lane follows the timeline clock: every frame the editor says which clip is under the
 * playhead and which comes next.
 *
 * Two players leapfrog. While one plays the current clip, the other holds the next clip parked
 * on its first frame and is set rolling a moment before that clip starts, so a cut, or a clip
 * arriving after a gap, does not wait for a decoder.
 */
@OptIn(UnstableApi::class)
class LayerPlayer(context: Context, audioOnly: Boolean = false, val probeName: String = "", private val onActiveChanged: (Int) -> Unit) {
    private fun probe(msg: String) = android.util.Log.d("PlayProbe", "$probeName $msg")
    /** [imageMime] is set for a still picture, which plays for the clip's length instead of being cut from a file. */
    class Item(val clip: Clip, val source: String, val toEnd: Boolean, val imageMime: String? = null) {
        /** Built once: it is compared several times every frame. */
        val key: String = "$source|${clip.inUs}|${clip.outUs}|${clip.speed}"
    }

    /** Each player's sound goes through its own processor: gain beyond 100%, pan and equaliser. */
    private val sound = List(2) { SoundProcessor() }
    private val pitch = floatArrayOf(0f, 0f)
    /** The speed of the clip each player holds; drift corrections are on top of it. */
    private val clipSpeed = floatArrayOf(1f, 1f)

    val players: List<ExoPlayer> = List(2) { k ->
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(sound[k])).build()
        }
        ExoPlayer.Builder(context, renderers)
            // Local files: show the frame as soon as it is decoded instead of waiting for a deep buffer. The read-ahead
            // is capped in bytes too: left to its default it may hold well over 100 MB per player, which is meant for
            // streaming and, with several lanes and heavy files, runs the app out of memory.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(3_000, 8_000, 100, 200)
                    .setTargetBufferBytes(12 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build()
            )
            .build().apply {
                // Keep the display at its top refresh rate for the timeline instead of matching the clip's fps.
                videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                setSeekParameters(SeekParameters.EXACT)
                // An audio lane only needs the sound, even when its clip comes from a video file.
                if (audioOnly) trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING) probe("p$k BUFFERING")
                        if (state == Player.STATE_READY) pausedSeekAt[k] = 0L
                    }

                    override fun onRenderedFirstFrame() {}

                    // A codec taken back by the system, or any other error, leaves the player idle; it forgets its clip
                    // so the next frame loads it again. A lane never stays dead.
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        probe("p$k ERROR ${error.errorCodeName}: ${error.message} -> reloading")
                        loaded[k] = null
                    }
                })
                // The time of the frame really on screen, so the native frame at rest can be the very same picture.
                setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
                    shownPts[k] = presentationTimeUs
                    synchronized(released) {
                        val i = releasedAt[k] % RING
                        released[k][i * 2] = releaseTimeNs
                        released[k][i * 2 + 1] = presentationTimeUs
                        releasedAt[k]++
                    }
                }
                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                        probe("p$k DROPPED $droppedFrames in ${elapsedMs}ms")
                    }
                })
            }
    }

    private var active = 0
    /** Presentation time of the last frame each player put on screen (written on the playback thread). */
    private val shownPts = LongArray(2) { Long.MIN_VALUE }

    /** Recent frames each player released for display, as (release stamp ns, frame time us) pairs. */
    private val released = Array(2) { LongArray(RING * 2) }
    private val releasedAt = IntArray(2)

    /**
     * The file time of the frame player [k] released with stamp [releaseNs] (the stamp the view saw on the frame it
     * drew), so the frame really on screen is known exactly. Null if it is no longer in the recent list.
     */
    fun frameForStamp(k: Int, releaseNs: Long): Long? = synchronized(released) {
        val r = released[k]
        var best: Long? = null
        var bestD = 3_000_000L
        for (i in 0 until RING) {
            val d = abs(r[i * 2] - releaseNs)
            if (r[i * 2] != 0L && d < bestD) {
                bestD = d
                best = r[i * 2 + 1]
            }
        }
        best
    }

    /** Index of the player on screen. */
    val activeIndex: Int get() = active

    /** The timeline time of the frame this lane has on screen for [current], or null if it is not showing it. */
    fun shownTimelineUs(current: Item): Long? =
        shownFrameUs(current)?.let { current.clip.startUs + ((it - current.clip.inUs) / current.clip.speed).toLong() }

    /** The last frame on screen for [current], as time in its file, or null if this lane is not showing it. */
    fun shownFrameUs(current: Item): Long? {
        if (loaded[active] != current.key) return null
        val pts = shownPts[active]
        return if (pts == Long.MIN_VALUE) null else pts
    }

    /** The player whose picture is on screen (the other one is parked on the next clip). */
    val shownPlayer: ExoPlayer get() = players[active]
    private val loaded = arrayOfNulls<String>(2)
    private val speed = floatArrayOf(1f, 1f)
    private val seekStartedAt = longArrayOf(0L, 0L)
    private var seekAheadUs = 300_000L
    /**
     * How long before its time a parked clip is set rolling, learned from how late (or early) each one actually
     * arrived: starting a paused player takes a while (sound output, first frame), and it differs per phone and file.
     */
    private var startLeadUs = 60_000L
    /** After a resync seek: its landing is measured once it plays, to aim the next one better. */
    private val measureLanding = booleanArrayOf(false, false)
    private var volume = 1f

    private fun setSpeed(k: Int, v: Float) {
        if (v == speed[k]) return
        speed[k] = v
        players[k].playbackParameters = PlaybackParameters(v * clipSpeed[k], 2.0.pow(pitch[k] / 12.0).toFloat())
    }

    fun setVolume(v: Float) {
        volume = v
    }

    /** Loads [item] into player [k] at [localUs] unless it is already there. True if it had to load. */
    private fun load(k: Int, item: Item, localUs: Long): Boolean {
        // Idle while it should hold this clip (stopped, or left idle by an error): load it again.
        if (loaded[k] == item.key && players[k].playbackState == Player.STATE_IDLE) {
            probe("p$k found idle -> reloading")
            loaded[k] = null
        }
        if (loaded[k] == item.key) return false
        probe("p$k LOAD start=${item.clip.startUs / 1000}ms local=${localUs / 1000}ms")
        loaded[k] = item.key
        if (clipSpeed[k] != item.clip.speed) {
            clipSpeed[k] = item.clip.speed
            players[k].playbackParameters = PlaybackParameters(speed[k] * clipSpeed[k], 2.0.pow(pitch[k] / 12.0).toFloat())
        }
        seekStartedAt[k] = 0L
        val media = if (item.imageMime != null) {
            MediaItem.Builder().setUri(item.source).setMimeType(item.imageMime).setImageDurationMs(item.clip.durationUs / 1000L).build()
        } else MediaItem.Builder().setUri(item.source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(item.clip.inUs)
                    .setEndPositionUs(if (item.toEnd) C.TIME_END_OF_SOURCE else item.clip.outUs)
                    .build()
            ).build()
        val t0 = System.nanoTime()
        players[k].setMediaItem(media, (localUs * item.clip.speed).toLong() / 1000L)
        players[k].prepare()
        probe("p$k load took %.1fms".format((System.nanoTime() - t0) / 1e6))
        return true
    }

    /**
     * [current] is the clip under the timeline clock [clockUs] (null in a gap) and [next] the one
     * after it, if it is near. [exact] forces a re-seek while paused, after a jump or an edit.
     * [hold] is the clip just before [current] while a transition between the two is still running:
     * the idle player keeps its last frame on screen instead of moving on to [next].
     */
    /**
     * [light] while scrubbing: only the clip under the playhead is followed (nothing new is parked), and a seek is
     * sent only once the last one has landed, so a fast drag never piles up work on the players.
     */
    fun sync(current: Item?, next: Item?, clockUs: Long, playing: Boolean, exact: Boolean, hold: Item? = null, light: Boolean = false) {
        this.light = light
        if (current != null) {
            // The parked player (possibly already rolling) takes over when its clip's time comes.
            if (loaded[active] != current.key && loaded[1 - active] == current.key) {
                active = 1 - active
                onActiveChanged(active)
                // How far off the parked clip arrived: learn from it, so the next cut starts on time.
                val p = players[active]
                if (p.isPlaying) {
                    val err = ((p.currentPosition * 1000L) / current.clip.speed).toLong() - (clockUs - current.clip.startUs)
                    startLeadUs = (startLeadUs - err / 2).coerceIn(0L, 900_000L)
                    probe("SWITCH err ${err / 1000}ms -> lead ${startLeadUs / 1000}ms")
                } else probe("SWITCH to a player not yet playing")
            }
            follow(active, current, clockUs - current.clip.startUs, playing, exact)
            // In a transition the clip being left keeps moving underneath (it runs on into its source past the cut).
            if (hold != null) follow(1 - active, hold, clockUs - hold.clip.startUs, playing, exact)
            else if (light) rest(1 - active)
            else park(1 - active, next, clockUs, playing)
        } else if (light) {
            rest(0)
            rest(1)
        } else if (next == null) {
            // A gap with nothing coming up: neither player needs a decoder.
            release(0)
            release(1)
        } else {
            // A gap. Whichever player already holds the coming clip keeps it; the other rests.
            val holder = if (loaded[active] == next.key) active else 1 - active
            park(holder, next, clockUs, playing)
            rest(1 - holder)
        }
    }

    private var light = false
    /** When a paused seek was sent (0: none waiting), so a scrub does not stack them up. */
    private val pausedSeekAt = longArrayOf(0L, 0L)

    private fun follow(k: Int, item: Item, localUs: Long, playing: Boolean, exact: Boolean) {
        val p = players[k]
        val fresh = load(k, item, localUs)
        // The player's position is in the source, which runs at the clip's speed against the timeline.
        val sp = item.clip.speed
        // Positive: this lane is ahead of the timeline clock. Negative: behind (in timeline time).
        val error = ((p.currentPosition * 1000L) / sp).toLong() - localUs
        if (!playing) {
            setSpeed(k, 1f)
            if (p.playWhenReady) p.pause()
            if (!fresh && exact && abs(error) > 15_000L) {
                val now = SystemClock.uptimeMillis()
                if (light && pausedSeekAt[k] != 0L && now - pausedSeekAt[k] < 250L) return
                pausedSeekAt[k] = now
                p.seekTo((localUs * sp).toLong() / 1000L)
            }
            return
        }
        if (!fresh) when {
            // One seek at a time: a seek freezes the position while it buffers, so re-seeking every
            // frame until it "catches up" would keep the player buffering forever.
            seekInFlight(k) -> Unit
            // Far ahead (a second or more): wait for the timeline to arrive.
            error > 900_000L && error < 3_000_000L -> {
                if (p.playWhenReady) probe("p$k WAIT ahead ${error / 1000}ms")
                if (p.playWhenReady) p.pause()
                return
            }
            // Far off: seek, aimed ahead by what earlier seeks here really needed, since the timeline keeps moving.
            error < -700_000L || error >= 3_000_000L -> {
                probe("p$k RESEEK err ${error / 1000}ms aim ${seekAheadUs / 1000}ms state=${p.playbackState} playing=${p.isPlaying} pos=${p.currentPosition} local=${localUs / 1000} in=${item.clip.inUs / 1000} out=${item.clip.outUs / 1000} toEnd=${item.toEnd} speed=${speed[k]}")
                setSpeed(k, 1f)
                seekStartedAt[k] = SystemClock.elapsedRealtime()
                measureLanding[k] = true
                p.seekTo(((localUs + seekAheadUs) * sp).toLong() / 1000L)
            }
            // Within the better part of a second either way: catch up by running a little faster or slower (the
            // pitch is kept). A seek would freeze the picture while it buffers; this never stops it.
            error < -150_000L -> setSpeed(k, 1.12f)
            error < -40_000L -> setSpeed(k, 1.05f)
            error > 150_000L -> setSpeed(k, 0.88f)
            error > 40_000L -> setSpeed(k, 0.95f)
            abs(error) < 12_000L -> setSpeed(k, 1f)
        }
        // Where the last resync seek really landed, once the player is running again: aim the next one by it.
        if (!fresh && measureLanding[k] && !seekInFlight(k) && p.isPlaying) {
            measureLanding[k] = false
            seekAheadUs = (seekAheadUs - error).coerceIn(120_000L, 1_500_000L)
            probe("p$k landed ${error / 1000}ms off -> aim ${seekAheadUs / 1000}ms")
        }
        if (!p.playWhenReady) p.play()
    }

    /** True from a resync seek until the player is ready again (with a time limit as a safety net). */
    private fun seekInFlight(k: Int): Boolean {
        if (seekStartedAt[k] == 0L) return false
        val took = SystemClock.elapsedRealtime() - seekStartedAt[k]
        if (players[k].playbackState == Player.STATE_READY && took > 60L) seekStartedAt[k] = 0L
        else if (took > 2500L) seekStartedAt[k] = 0L
        return seekStartedAt[k] != 0L
    }

    /** Holds [next] paused on its first frame, and starts it a learned lead before its time while playing. */
    private fun park(k: Int, next: Item?, clockUs: Long, playing: Boolean) {
        if (next == null) {
            // Nothing coming up on this lane: its spare player lets go of its decoder altogether.
            release(k)
            return
        }
        val p = players[k]
        val fresh = load(k, next, 0L)
        setSpeed(k, 1f)
        if (playing && !fresh && next.clip.startUs - clockUs <= startLeadUs) {
            if (!p.playWhenReady) p.play()
        } else {
            if (p.playWhenReady) p.pause()
            if (p.currentPosition > 20L) p.seekTo(0L)
        }
    }

    /**
     * What each player sounds like now: [current] for the player on the clip under the playhead, [other]
     * for the one on the clip being left or the one coming next (null: silent). Pitch is in semitones.
     */
    fun setSound(current: SoundNow?, currentPitch: Float, other: SoundNow?, otherPitch: Float) {
        applySound(active, current, currentPitch)
        applySound(1 - active, other, otherPitch)
    }

    private fun applySound(k: Int, now: SoundNow?, semis: Float) {
        sound[k].target = (now ?: SoundNow(gain = 0f)).let { it.copy(gain = it.gain * volume) }
        if (semis != pitch[k]) {
            pitch[k] = semis
            players[k].playbackParameters = PlaybackParameters(speed[k] * clipSpeed[k], 2.0.pow(semis / 12.0).toFloat())
        }
    }

    private fun rest(k: Int) {
        setSpeed(k, 1f)
        if (players[k].playWhenReady) players[k].pause()
    }

    /** Timeline time according to this lane, if it is actually playing [current]; the editor may use it as the clock. */
    fun clockOf(current: Item): Long? {
        val p = players[active]
        return if (loaded[active] == current.key && p.isPlaying) current.clip.startUs + (p.currentPosition * 1000L / current.clip.speed).toLong() else null
    }

    /**
     * Lets go of the codec held by the player that is not on screen (parked on a clip ahead), so another job can have it.
     * It is loaded again when its clip comes near.
     */
    fun releaseIdle() = release(1 - active)

    /** Lets go of both decoders (an export needs them); the lane loads again on its next sync. */
    fun releaseAll() {
        release(0)
        release(1)
    }

    private fun release(k: Int) {
        if (loaded[k] == null) {
            rest(k)
            return
        }
        players[k].stop()
        players[k].clearMediaItems()
        loaded[k] = null
    }

    /** True when [current] is loaded and ready to play at once, for starting all lanes together. */
    fun ready(current: Item): Boolean = loaded[active] == current.key && players[active].playbackState == Player.STATE_READY

    fun pause() {
        rest(0)
        rest(1)
    }

    fun release() = players.forEach { it.release() }

    companion object {
        private const val RING = 24
    }
}

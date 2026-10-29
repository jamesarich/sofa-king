package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.data.ScrubTiles
import dev.jamesrich.sofaking.data.SeekSteps
import dev.jamesrich.sofaking.data.VodMarker
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** What the seekable player shows about what it plays: a library item or a recording. */
@Immutable
data class VodTitle(
    val title: String,
    /** A second line, such as an episode's show and number or a recording's channel and date. */
    val subtitle: String? = null,
    /** An image for the system's media controls. */
    val artworkUrl: String? = null,
    /** Spans drawn on the seek bar, such as a recording's commercial breaks; times are in the item. */
    val markers: List<VodMarker> = emptyList(),
    /** Thumbnails shown over the seek bar while scrubbing, for a library file the server has made them for. */
    val scrub: ScrubTiles? = null,
)

/** What a key does in the seekable player. */
internal enum class VodAction {
    PLAY_PAUSE,
    PLAY,
    PAUSE,
    BACK,
    FORWARD,
    SHOW_INFO,
    BUTTONS,
    CAPTIONS,
    TRACKS,
    ZOOM,
    STOP,
}

/**
 * Left, Rewind and a gamepad's left shoulder step back, Right, Fast Forward and the right shoulder step forward, OK,
 * Space, K and the media keys play and pause, Up and Info show the bar, Down shows it and then moves into its
 * buttons, CC and C switch the subtitles or captions, Audio Track opens the track card, Zoom steps the picture's zoom
 * and Stop leaves the player.
 */
internal fun Key.vodAction(): VodAction? = when (this) {
    Key.DirectionLeft, Key.MediaRewind, Key.MediaPrevious, Key.ButtonL1 -> VodAction.BACK
    Key.DirectionRight, Key.MediaFastForward, Key.MediaNext, Key.ButtonR1 -> VodAction.FORWARD
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar, Key.K -> VodAction.PLAY_PAUSE
    Key.MediaPlay -> VodAction.PLAY
    Key.MediaPause -> VodAction.PAUSE
    Key.DirectionUp, Key.Info -> VodAction.SHOW_INFO
    Key.DirectionDown -> VodAction.BUTTONS
    Key.Captions, Key.C -> VodAction.CAPTIONS
    Key.MediaAudioTrack -> VodAction.TRACKS
    Key.TvZoomMode -> VodAction.ZOOM
    Key.MediaStop -> VodAction.STOP
    else -> null
}

/**
 * The seek a run of Left and Right presses builds up before it is applied. A press moves the target one of [steps]
 * back or forward (10 s and 30 s unless Settings says otherwise); a held key moves it further with each repeat, a
 * minute and then five, so a long file can be crossed in seconds. [settled] applies it once the presses have rested for [SCRUB_SETTLE_MS].
 */
internal class VodScrub {
    /** Where the presses have taken the target, in the item; null while no seek is being built. */
    var targetMs: Long? = null
        private set
    private var lastPressMs = 0L

    /** One press back or forward, also the touch controls' and the media keys' steps. */
    var steps = SeekSteps()

    /** One press in [direction] (-1 back, +1 forward) from [positionMs], the [repeatCount]th repeat of a held key. */
    fun press(direction: Int, positionMs: Long, durationMs: Long?, repeatCount: Int, nowMs: Long): Long {
        val step = when {
            repeatCount >= FAST_REPEATS -> 300_000L
            repeatCount >= MEDIUM_REPEATS -> 60_000L
            direction < 0 -> steps.backMs
            else -> steps.forwardMs
        }
        val from = targetMs ?: positionMs
        val moved = from + direction * step
        val target = durationMs?.let { moved.coerceIn(0, it) } ?: moved.coerceAtLeast(0)
        targetMs = target
        lastPressMs = nowMs
        return target
    }

    /** The target to apply once the presses have rested, clearing it; null while they have not or there is none. */
    fun settled(nowMs: Long): Long? {
        val target = targetMs ?: return null
        if (nowMs - lastPressMs < SCRUB_SETTLE_MS) return null
        targetMs = null
        return target
    }

    /** The target to apply now, as OK asks, clearing it. */
    fun commit(): Long? = targetMs.also { targetMs = null }

    fun cancel() {
        targetMs = null
    }

    companion object {
        const val SCRUB_SETTLE_MS = 700L
        private const val MEDIUM_REPEATS = 8
        private const val FAST_REPEATS = 24
    }
}

/** When the item ends if it plays on from [positionMs] at [now] at [speed]; null while its length is unknown. */
internal fun vodEndsAt(now: Instant, positionMs: Long, durationMs: Long?, speed: Float = 1f): Instant? =
    durationMs?.takeIf { it > 0 }?.let { now + ((it - positionMs).coerceAtLeast(0) / speed).toLong().milliseconds }

/** Where a seek to [positionMs] lands in a seekable item: never before its start, nor past [durationMs] when known. */
internal fun clampSeek(positionMs: Long, durationMs: Long?): Long =
    durationMs?.let { positionMs.coerceIn(0L, it) } ?: positionMs.coerceAtLeast(0L)

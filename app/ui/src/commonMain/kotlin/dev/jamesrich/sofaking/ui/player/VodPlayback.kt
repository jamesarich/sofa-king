package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.PlaybackStream
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
)

/** A span of the item from [startMs] to [endMs]. */
data class VodMarker(val startMs: Long, val endMs: Long, val kind: VodMarkerKind)

enum class VodMarkerKind {
    /** A commercial break, which playback can skip on its own. */
    BREAK,

    /** An episode's intro, which the bar offers to skip; it is not drawn on the seek bar. */
    INTRO,

    /** An episode's end credits, which the bar offers to skip, or to leave for the next episode; not drawn either. */
    CREDITS,
}

/** Where a seek lands: in the stream the player holds, at its own time, or in a conversion restarted there. */
internal sealed interface VodSeekPlan {
    data class InStream(val streamMs: Long) : VodSeekPlan
    data class Restart(val itemMs: Long) : VodSeekPlan
}

/**
 * Where a seek to [targetMs] in the item lands. A file played as it is seeks anywhere in it. A converted stream
 * starts at [PlaybackStream.offsetMs] and holds what the server has written so far ([writtenMs], the stream's own
 * length): a target inside that, short of its edge, seeks in the stream; anything before it or past it restarts
 * the conversion there. The target stays [END_GUARD_MS] short of a known end, so a seek never lands on the last frame.
 */
internal fun vodSeekPlan(stream: PlaybackStream, targetMs: Long, writtenMs: Long?, itemDurationMs: Long?): VodSeekPlan {
    val target =
        itemDurationMs?.let { targetMs.coerceIn(0, (it - END_GUARD_MS).coerceAtLeast(0)) } ?: targetMs.coerceAtLeast(0)
    if (stream.mode == Wire.VodMode.DIRECT) return VodSeekPlan.InStream(target)
    val streamMs = target - stream.offsetMs
    val edge = (writtenMs ?: 0) - EDGE_MARGIN_MS
    return if (streamMs in 0..edge) VodSeekPlan.InStream(streamMs) else VodSeekPlan.Restart(target)
}

/**
 * The end of the break playback just ran into, to skip to; null otherwise. Only a break whose start lies between
 * [previousMs] and [nowMs] counts, so a viewer who seeks into a break on purpose stays in it.
 */
internal fun breakToSkip(markers: List<VodMarker>, previousMs: Long, nowMs: Long): Long? {
    if (nowMs <= previousMs || nowMs - previousMs > SKIP_WINDOW_MS) return null
    return markers.firstOrNull {
        it.kind == VodMarkerKind.BREAK && it.startMs > previousMs && it.startMs <= nowMs && nowMs < it.endMs
    }?.endMs
}

/** The end of the commercial break [positionMs] is in, to skip to; null outside one. */
internal fun breakEndAt(markers: List<VodMarker>, positionMs: Long): Long? =
    markers.firstOrNull { it.kind == VodMarkerKind.BREAK && positionMs >= it.startMs && positionMs < it.endMs }?.endMs

/** The intro [positionMs] is in; null outside one. */
internal fun introAt(markers: List<VodMarker>, positionMs: Long): VodMarker? =
    segmentAt(markers, positionMs, VodMarkerKind.INTRO)

/** The credits [positionMs] is in; null outside them. */
internal fun creditsAt(markers: List<VodMarker>, positionMs: Long): VodMarker? =
    segmentAt(markers, positionMs, VodMarkerKind.CREDITS)

private fun segmentAt(markers: List<VodMarker>, positionMs: Long, kind: VodMarkerKind): VodMarker? =
    markers.firstOrNull { it.kind == kind && positionMs >= it.startMs && positionMs < it.endMs }

/**
 * Whether [credits] run to the end of an item [durationMs] long, give or take [CREDITS_END_SLACK_MS], so that past
 * them there is nothing left to watch and the next episode is what skipping them means.
 */
internal fun creditsRunToEnd(credits: VodMarker, durationMs: Long?): Boolean =
    durationMs != null && credits.endMs >= durationMs - CREDITS_END_SLACK_MS

private const val CREDITS_END_SLACK_MS = 1_000L

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
 * The seek a run of Left and Right presses builds up before it is applied. A press moves the target 10 s back or
 * 30 s forward; a held key moves it further with each repeat, a minute and then five, so a long file can be crossed
 * in seconds. [settled] applies it once the presses have rested for [SCRUB_SETTLE_MS].
 */
internal class VodScrub {
    /** Where the presses have taken the target, in the item; null while no seek is being built. */
    var targetMs: Long? = null
        private set
    private var lastPressMs = 0L

    /** One press in [direction] (-1 back, +1 forward) from [positionMs], the [repeatCount]th repeat of a held key. */
    fun press(direction: Int, positionMs: Long, durationMs: Long?, repeatCount: Int, nowMs: Long): Long {
        val step = when {
            repeatCount >= FAST_REPEATS -> 300_000L
            repeatCount >= MEDIUM_REPEATS -> 60_000L
            direction < 0 -> BACK_STEP_MS
            else -> FORWARD_STEP_MS
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

        /** One press back or forward, also the touch controls' and the media keys' steps. */
        const val BACK_STEP_MS = 10_000L
        const val FORWARD_STEP_MS = 30_000L
        private const val MEDIUM_REPEATS = 8
        private const val FAST_REPEATS = 24
    }
}

/** When the item ends if it plays on from [positionMs] at [now] at [speed]; null while its length is unknown. */
internal fun vodEndsAt(now: Instant, positionMs: Long, durationMs: Long?, speed: Float = 1f): Instant? =
    durationMs?.takeIf { it > 0 }?.let { now + ((it - positionMs).coerceAtLeast(0) / speed).toLong().milliseconds }

/** How far short of a known end a seek may land. */
internal const val END_GUARD_MS = 2_000L

/** How far short of the written edge a seek still counts as inside the converted stream. */
internal const val EDGE_MARGIN_MS = 4_000L

/** Two position readings further apart than this are a seek, not playback, so a break they span is not skipped. */
internal const val SKIP_WINDOW_MS = 3_000L

/** Where a seek to [positionMs] lands in a seekable item: never before its start, nor past [durationMs] when known. */
internal fun clampSeek(positionMs: Long, durationMs: Long?): Long =
    durationMs?.let { positionMs.coerceIn(0L, it) } ?: positionMs.coerceAtLeast(0L)

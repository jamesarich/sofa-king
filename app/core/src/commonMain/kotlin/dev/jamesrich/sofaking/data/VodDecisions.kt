package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire

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
sealed interface VodSeekPlan {
    data class InStream(val streamMs: Long) : VodSeekPlan
    data class Restart(val itemMs: Long) : VodSeekPlan
}

/**
 * Where a seek to [targetMs] in the item lands. A file played as it is seeks anywhere in it. A converted stream
 * starts at [PlaybackStream.offsetMs] and holds what the server has written so far ([writtenMs], the stream's own
 * length): a target inside that, short of its edge, seeks in the stream; anything before it or past it restarts
 * the conversion there. The target stays [END_GUARD_MS] short of a known end, so a seek never lands on the last frame.
 */
fun vodSeekPlan(stream: PlaybackStream, targetMs: Long, writtenMs: Long?, itemDurationMs: Long?): VodSeekPlan {
    val target =
        itemDurationMs?.let { targetMs.coerceIn(0, (it - END_GUARD_MS).coerceAtLeast(0)) } ?: targetMs.coerceAtLeast(0)
    if (stream.mode == Wire.VodMode.DIRECT) return VodSeekPlan.InStream(target)
    val streamMs = target - stream.offsetMs
    val edge = (writtenMs ?: 0) - EDGE_MARGIN_MS
    return if (streamMs in 0..edge) VodSeekPlan.InStream(streamMs) else VodSeekPlan.Restart(target)
}

/** How far short of a known end a seek may land. */
const val END_GUARD_MS = 2_000L

/** How far short of the written edge a seek still counts as inside the converted stream. */
const val EDGE_MARGIN_MS = 4_000L

/** Two position readings further apart than this are a seek, not playback, so a break they span is not skipped. */
const val SKIP_WINDOW_MS = 3_000L

/**
 * The end of the break playback just ran into, to skip to; null otherwise. Only a break whose start lies between
 * [previousMs] and [nowMs] counts, so a viewer who seeks into a break on purpose stays in it.
 */
fun breakToSkip(markers: List<VodMarker>, previousMs: Long, nowMs: Long): Long? {
    if (nowMs <= previousMs || nowMs - previousMs > SKIP_WINDOW_MS) return null
    return markers.firstOrNull {
        it.kind == VodMarkerKind.BREAK && it.startMs > previousMs && it.startMs <= nowMs && nowMs < it.endMs
    }?.endMs
}

/** The end of the commercial break [positionMs] is in, to skip to; null outside one. */
fun breakEndAt(markers: List<VodMarker>, positionMs: Long): Long? =
    markers.firstOrNull { it.kind == VodMarkerKind.BREAK && positionMs >= it.startMs && positionMs < it.endMs }?.endMs

/** The intro [positionMs] is in; null outside one. */
fun introAt(markers: List<VodMarker>, positionMs: Long): VodMarker? =
    segmentAt(markers, positionMs, VodMarkerKind.INTRO)

/** The credits [positionMs] is in; null outside them. */
fun creditsAt(markers: List<VodMarker>, positionMs: Long): VodMarker? =
    segmentAt(markers, positionMs, VodMarkerKind.CREDITS)

/** An intro or the credits playback is in, and how [Skips] says to meet it. */
data class SegmentSkip(val marker: VodMarker, val mode: SkipMode)

/** The intro or credits [positionMs] is in, unless [skips] turns that kind off; null outside them. */
fun segmentToSkip(markers: List<VodMarker>, positionMs: Long, skips: Skips): SegmentSkip? {
    introAt(markers, positionMs)?.takeIf { skips.intros != SkipMode.OFF }?.let { return SegmentSkip(it, skips.intros) }
    return creditsAt(markers, positionMs)?.takeIf {
        skips.credits != SkipMode.OFF
    }?.let { SegmentSkip(it, skips.credits) }
}

private fun segmentAt(markers: List<VodMarker>, positionMs: Long, kind: VodMarkerKind): VodMarker? =
    markers.firstOrNull { it.kind == kind && positionMs >= it.startMs && positionMs < it.endMs }

/**
 * Whether credits ending at [creditsEndMs] run to the end of an item [durationMs] long, give or take
 * [CREDITS_END_SLACK_MS], so that past them there is nothing left to watch and the next episode is what skipping
 * them means. A length that is unknown or not positive never qualifies.
 */
fun creditsRunToEnd(creditsEndMs: Long, durationMs: Long?): Boolean =
    durationMs != null && durationMs > 0 && creditsEndMs >= durationMs - CREDITS_END_SLACK_MS

private const val CREDITS_END_SLACK_MS = 1_000L

/** How long Skip intro counts down before the intro skips itself, with auto skip on. */
const val AUTO_SKIP_MS = 5_000L

/** What the stream ending means for the item, from [EarlyEndGuard.onEnd]. */
enum class EarlyEnd {
    /** The item is over. */
    FINISHED,

    /** The stream dropped short of the item; open it again where it stopped. */
    REOPEN,

    /** The stream keeps dropping; show the failure. */
    FAIL,
}

/**
 * Tells the end of an item from a stream that dropped. An end more than [EARLY_END_MS] short of the item's length
 * is a drop (a connection or a conversion that died), answered with [EarlyEnd.REOPEN] up to two times until playback
 * moves on by 30 seconds, and then with [EarlyEnd.FAIL], so Up next never plays over an episode cut short.
 */
class EarlyEndGuard {
    // Drops since playback last moved on, and where the last one was.
    private var drops = 0
    private var dropAtMs = 0L

    /** The stream ended at [positionMs] in an item [durationMs] long (null while unknown). */
    fun onEnd(positionMs: Long, durationMs: Long?): EarlyEnd {
        if (durationMs == null || positionMs + EARLY_END_MS >= durationMs) return EarlyEnd.FINISHED
        return onDrop(positionMs)
    }

    /** The stream failed at [positionMs] before its end, whatever the item's length. */
    fun onDrop(positionMs: Long): EarlyEnd {
        if (positionMs >= dropAtMs + EARLY_END_PROGRESS_MS) drops = 0
        dropAtMs = positionMs
        return if (drops++ < EARLY_END_REOPENS) EarlyEnd.REOPEN else EarlyEnd.FAIL
    }
}

/** How far short of the item's length an end may come and still be the end: a conversion can stop a little short. */
const val EARLY_END_MS = 10_000L
private const val EARLY_END_REOPENS = 2
private const val EARLY_END_PROGRESS_MS = 30_000L

/** How long the player waits for a free converter before it shows the failure, and how often it asks. */
const val CONVERTER_WAIT_MS = 20_000L
const val CONVERTER_RETRY_MS = 5_000L

/**
 * How long to wait before asking again when the server answers that every converter is busy, after [waitedMs] of
 * asking; null once [CONVERTER_WAIT_MS] is spent, when the failure shows.
 */
fun converterRetryDelayMs(waitedMs: Long): Long? = if (waitedMs >= CONVERTER_WAIT_MS) null else CONVERTER_RETRY_MS

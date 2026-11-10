package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import kotlin.math.roundToLong

/**
 * The most the app's players are asked to play behind a live playlist's newest segment. They hold three target
 * durations back by default and fetch up to the newest segment, so the server reads a segment's cues and audio that
 * much before the picture reaches it. The window bounds the lead: a relayed window of 20 seconds gives 14, and a
 * packaged playlist (2 second segments) gives the default 6 seconds until its window passes 12 seconds, then 7. A
 * learned break is found only after 10 to 20 seconds of its audio, so on a packaged playlist the picture is already
 * inside it; a marker the stream carries, and a stitched break's end, arrive ahead of the picture.
 */
internal const val LIVE_HOLD_BACK_MS = 36_000L

/** How many target durations a player keeps behind it in the window, and the default hold-back. */
private const val TARGET_DURATIONS = 3

private val TARGET_DURATION = Regex("""^#EXT-X-TARGETDURATION:\s*(\d+(?:\.\d+)?)""", RegexOption.MULTILINE)

/**
 * [text], a live media playlist whose window holds [segments], with `#EXT-X-SERVER-CONTROL:HOLD-BACK` asking the
 * player to play [LIVE_HOLD_BACK_MS] behind its newest segment, or less where that would leave under three target
 * durations of the window behind the player, and the hold-back it was given. [kept], the one this playlist was given
 * last, stays while the window still fits it, so the player's target does not move with every refresh: AVPlayer
 * re-targets its live position on every HOLD-BACK change and never settles while a packaged window grows. A
 * hold-back is never under three target durations, so one the target duration outgrows is dropped; once a hold-back
 * has been dropped, [dropped], none is offered again and the player sees one change, not a second. A playlist that
 * ended, sets its own server control, or has no room to hold the player further back than the default comes back as
 * it was, with null.
 */
internal fun withHoldBack(
    text: String,
    segments: List<AdBreaks.Segment>,
    kept: Long?,
    dropped: Boolean = false,
): Pair<String, Long?> {
    if (dropped || text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-SERVER-CONTROL")) return text to null
    val target = TARGET_DURATION.find(text) ?: return text to null
    val targetMs = (target.groupValues[1].toDouble() * MS_PER_SECOND).roundToLong()
    val room = segments.sumOf { it.durationMs } - TARGET_DURATIONS * targetMs
    val holdBack = kept?.takeIf { it <= room } ?: (minOf(LIVE_HOLD_BACK_MS, room) / MS_PER_SECOND * MS_PER_SECOND)
    if (holdBack <= TARGET_DURATIONS * targetMs) return text to null
    val at = target.range.last + 1
    val line = "\n#EXT-X-SERVER-CONTROL:HOLD-BACK=${holdBack / MS_PER_SECOND}"
    return text.substring(0, at) + line + text.substring(at) to holdBack
}

/**
 * How far behind its newest segment a player plays [text], a live media playlist sent with [holdBack]: that, else
 * three target durations. Null for an ended playlist or one with a server control of its own, whose lead is the
 * source's.
 */
internal fun playerLeadMs(text: String, holdBack: Long?): Long? {
    if (holdBack != null) return holdBack
    if (text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-SERVER-CONTROL")) return null
    val target = TARGET_DURATION.find(text) ?: return null
    return TARGET_DURATIONS * (target.groupValues[1].toDouble() * MS_PER_SECOND).roundToLong()
}

/** Notes [holdBack], the one the last refresh was sent with, and that the one before it was dropped when it goes. */
internal fun AdTimeline.sentHoldBack(holdBack: Long?) {
    if (holdBackMs != null && holdBack == null) holdBackDropped = true
    holdBackMs = holdBack
}

package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import kotlin.math.roundToLong

/**
 * How far behind the newest segment the app's players are asked to play a live playlist. They hold three target
 * durations back by default, about 18 seconds, and fetch up to the newest segment, so the server reads a segment's
 * cues and audio that much before the picture reaches it. Further back, more of what the server finds lands ahead of
 * the picture: a learned break is found only after 10 to 20 seconds of its audio, a stitched break's end shows only
 * once the next show segment is in the window, and the break screen counts down from then.
 */
internal const val LIVE_HOLD_BACK_MS = 36_000L

/** How many target durations a player keeps behind it in the window, and the default hold-back. */
private const val TARGET_DURATIONS = 3

private val TARGET_DURATION = Regex("""^#EXT-X-TARGETDURATION:\s*(\d+(?:\.\d+)?)""", RegexOption.MULTILINE)

/**
 * [text], a live media playlist whose window holds [segments], with `#EXT-X-SERVER-CONTROL:HOLD-BACK` asking the
 * player to play [LIVE_HOLD_BACK_MS] behind its newest segment, or less where that would leave under three target
 * durations of the window behind the player, and the hold-back it was given. [kept], the one this playlist was given
 * last, stays while the window still fits it, so the player's target does not move with every refresh. A playlist
 * that ended, sets its own server control, or has no room to hold the player further back than the default comes
 * back as it was, with null.
 */
internal fun withHoldBack(text: String, segments: List<AdBreaks.Segment>, kept: Long?): Pair<String, Long?> {
    if (text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-SERVER-CONTROL")) return text to null
    val target = TARGET_DURATION.find(text) ?: return text to null
    val targetMs = (target.groupValues[1].toDouble() * MS_PER_SECOND).roundToLong()
    val room = segments.sumOf { it.durationMs } - TARGET_DURATIONS * targetMs
    val holdBack = kept?.takeIf { it <= room } ?: (minOf(LIVE_HOLD_BACK_MS, room) / MS_PER_SECOND * MS_PER_SECOND)
    if (holdBack <= TARGET_DURATIONS * targetMs) return text to null
    val at = target.range.last + 1
    val line = "\n#EXT-X-SERVER-CONTROL:HOLD-BACK=${holdBack / MS_PER_SECOND}"
    return text.substring(0, at) + line + text.substring(at) to holdBack
}

package dev.jamesrich.sofaking.backend.dvr

import java.time.Duration

/**
 * How long a recording waits before it reopens its source, from how the last read came to an end. A read that ended
 * with media, a stall or no media says nothing against the source: Pluto answers a fresh session with empty playlists
 * about half the time, and the next session is as likely to work as one in five seconds. So those reopen after
 * [first], and empty reads in a row back off by doubling up to [slowest]. Any other failure waits [slowest].
 */
internal class ReopenDelay(
    private val slowest: Duration,
    private val first: Duration = minOf(Duration.ofSeconds(1), slowest),
) {
    private var empty = 0

    /** The wait after a read that ended as [outcome]: null when it wrote media and the source ended. */
    fun after(outcome: String?): Duration {
        empty = if (outcome == "NO_MEDIA") empty + 1 else 0
        return when (outcome) {
            null, "SOURCE_STALLED" -> first
            "NO_MEDIA" -> minOf(slowest, first.multipliedBy(1L shl minOf(empty - 1, MAX_DOUBLINGS)))
            else -> slowest
        }
    }

    private companion object {
        const val MAX_DOUBLINGS = 4
    }
}

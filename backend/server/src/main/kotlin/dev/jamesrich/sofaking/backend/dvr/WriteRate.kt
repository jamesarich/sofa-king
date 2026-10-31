package dev.jamesrich.sofaking.backend.dvr

import java.time.Duration
import java.time.Instant

/**
 * Whether a read's write rate has fallen to a fraction of its own: FFmpeg left with the sound alone, or a trickle of
 * segments, writes on at a rate the no-write watchdog never sees, for as long as the source lets it. The rate over
 * the last [window] is compared with the best such window after [settle], since a fresh session opens with a burst
 * of catch-up segments that is no measure of the stream.
 */
internal class WriteRate(
    private val window: Duration = WINDOW,
    private val settle: Duration = SETTLE,
    private val floor: Int = FLOOR,
) {
    private val samples = ArrayDeque<Pair<Instant, Long>>()
    private var started: Instant? = null
    private var peak = 0L

    /**
     * A new read of the same recording starts, its count from zero: the best window carries over, since a read slow
     * from its first byte has no window of its own to show it up.
     */
    fun begin() {
        samples.clear()
        started = null
    }

    /** Records [written] bytes so far at [now]; true once a window wrote under a [floor]th of the best window. */
    fun slowed(written: Long, now: Instant): Boolean {
        val first = started ?: now.also { started = it }
        samples.addLast(now to written)
        while (samples.size > 1 && Duration.between(samples[1].first, now) >= window) samples.removeFirst()
        if (Duration.between(samples.first().first, now) < window || Duration.between(first, now) < settle) return false
        val recent = written - samples.first().second
        peak = maxOf(peak, recent)
        return recent * floor < peak
    }

    private companion object {
        /** The best window's rate over the latest one's, past which the read is slowed. */
        const val FLOOR = 3
        val WINDOW: Duration = Duration.ofSeconds(30)
        val SETTLE: Duration = Duration.ofSeconds(60)
    }
}

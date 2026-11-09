package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.contract.Wire
import java.time.Duration
import java.time.Instant

/**
 * How many recordings the space cull may still delete: [max] in any rolling [window], counted from memory, so a disk
 * that stays low for reasons outside the recordings can only ever lose that many a day.
 */
internal class CullBudget(
    private val max: Int = DAILY_MAX,
    private val window: Duration = Duration.ofHours(WINDOW_HOURS),
) {
    private val spent = ArrayDeque<Instant>()

    /** Set once the budget has run out and the operator has been told, until it has room again. */
    var announced = false

    /** Deletions left at [now]. */
    fun left(now: Instant): Int {
        while (spent.isNotEmpty() && spent.first() <= now.minus(window)) spent.removeFirst()
        if (spent.size < max) announced = false
        return max - spent.size
    }

    fun spend(now: Instant) {
        spent.addLast(now)
    }

    companion object {
        /** The most recordings the space cull deletes in 24 hours. */
        const val DAILY_MAX = 20
        private const val WINDOW_HOURS = 24L
        private const val SECONDS_PER_HOUR = 3600L

        /** What a recording is expected to write, per hour, when nothing better is known. */
        const val BYTES_PER_HOUR = 4L * Dvr.GIB

        /**
         * The bytes the recordings running at [now] still have to write, plus the next scheduled one that opens before
         * [soon], at [BYTES_PER_HOUR].
         */
        fun need(recordings: List<StoredRecording>, now: Instant, soon: Duration): Long {
            val running = recordings.filter { it.state == Wire.RecordingState.RECORDING }
            val next = recordings.filter {
                it.state == Wire.RecordingState.SCHEDULED && it.recordUntil > now &&
                    it.recordFrom < now.plus(soon)
            }.minByOrNull { it.recordFrom }
            val seconds = (running + listOfNotNull(next)).sumOf {
                Duration.between(maxOf(now, it.recordFrom), it.recordUntil).seconds.coerceAtLeast(0)
            }
            return seconds * BYTES_PER_HOUR / SECONDS_PER_HOUR
        }
    }
}

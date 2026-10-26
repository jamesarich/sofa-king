package dev.jamesrich.sofaking.backend.health

import java.time.Duration
import java.time.Instant

/** When probes finished over the last [window], for an estimate of how long a queue of probes takes. */
class ProbeRate(private val window: Duration = DEFAULT_WINDOW) {
    private val finished = ArrayDeque<Instant>()

    @Synchronized fun finished(at: Instant) {
        finished.addLast(at)
        prune(at)
    }

    /**
     * Probes finished per minute, over the window or since the oldest probe in it, whichever is shorter, but at least a
     * minute; null until [MIN_SAMPLES] have finished inside the window.
     */
    @Synchronized fun perMinute(now: Instant): Double? {
        prune(now)
        if (finished.size < MIN_SAMPLES) return null
        val span = Duration.between(finished.first(), now).coerceAtLeast(Duration.ofMinutes(1))
        return finished.size * MS_PER_MINUTE / span.toMillis()
    }

    /** How long [remaining] probes take at [perMinute], to the next minute; null while the rate is unknown. */
    fun timeFor(remaining: Int, now: Instant): Duration? {
        val rate = perMinute(now) ?: return null
        return Duration.ofMinutes(kotlin.math.ceil(remaining / rate).toLong().coerceAtLeast(1))
    }

    private fun prune(now: Instant) {
        val oldest = now.minus(window)
        while (finished.firstOrNull()?.isBefore(oldest) == true) finished.removeFirst()
    }

    private companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofMinutes(10)
        const val MIN_SAMPLES = 5
        const val MS_PER_MINUTE = 60_000.0
    }
}

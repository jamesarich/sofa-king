package dev.jamesrich.sofaking.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What the lock page remembers between tries: after [MAX_MISSES] wrong PINs in a row, the next try waits [WAIT].
 * One lives for the process, so backing out to the guide does not reset it; a restart does, which is fine for a lock
 * meant to keep a child out, not an attacker.
 */
class PinThrottle(private val timeSource: TimeSource) {
    constructor() : this(TimeSource.Monotonic)

    var misses: Int = 0
        private set

    /** When the current wait ends, or null while there is none. */
    var waitEnds: TimeMark? = null
        private set

    val waiting: Boolean get() = waitEnds?.hasNotPassedNow() == true

    /** How much of the wait is left, zero while there is none. */
    val remaining: Duration get() = waitEnds?.let { -it.elapsedNow() }?.takeIf { it.isPositive() } ?: Duration.ZERO

    /** [remaining] in milliseconds, for a host that cannot hold a [Duration]. */
    val remainingMs: Long get() = remaining.inWholeMilliseconds

    /** A wrong PIN; the [MAX_MISSES]th in a row starts the wait and the count starts over. */
    fun missed() {
        misses++
        if (misses >= MAX_MISSES) {
            misses = 0
            waitEnds = timeSource.markNow() + WAIT
        }
    }

    /** The right PIN, or the lock removed. */
    fun passed() {
        misses = 0
        waitEnds = null
    }

    companion object {
        const val MAX_MISSES = 5
        const val WAIT_SECONDS = 30
        val WAIT = WAIT_SECONDS.seconds
    }
}

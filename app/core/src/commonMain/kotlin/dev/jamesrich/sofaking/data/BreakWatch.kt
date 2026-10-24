package dev.jamesrich.sofaking.data

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The break on screen, from the readings a player gives every poll. A reading can lose a break for a moment (Media3
 * between playlists, a stream retried after a hiccup, a missed answer from the server), so a break that still had time
 * on its countdown, or had no known end, stays up until that time is up, for at most [GAP] after it was last seen.
 * [over] tells when it has been gone that long: until then a break that comes back is the same one, and OK or Back
 * still holds the picture over it.
 */
class BreakWatch internal constructor(private val timeSource: TimeSource) {
    constructor() : this(TimeSource.Monotonic)

    private var seen: TimeMark? = null
    private var ends: TimeMark? = null
    private var stalling = false
    private var frozen: AdBreakNow? = null

    val over: Boolean get() = if (stalling && frozen != null) {
        false
    } else {
        seen.let { it == null || it.elapsedNow() >= GAP }
    }

    /** The break to show after [found], the latest reading, or null when there is none. */
    fun shown(found: AdBreakNow?): AdBreakNow? {
        if (stalling) {
            // The picture moves again: the held break's countdown and gap go on from where the stall stopped them.
            stalling = false
            frozen?.let { held ->
                seen = timeSource.markNow()
                ends = held.remainingMs?.let { timeSource.markNow() + it.milliseconds }
            }
            frozen = null
        }
        if (found != null) {
            seen = timeSource.markNow()
            ends = found.remainingMs?.let { timeSource.markNow() + it.milliseconds }
            return found
        }
        if (over) {
            seen = null
            ends = null
            return null
        }
        val ends = ends ?: return AdBreakNow(remainingMs = null)
        return (-ends.elapsedNow()).inWholeMilliseconds.takeIf { it > 0 }?.let { AdBreakNow(it) }
    }

    /**
     * The break to show while the player has no reading because its picture is stalled (its buffer ran dry): the
     * picture does not move, so the break on screen stays as it is, its countdown and its gap stopped until readings
     * come back.
     */
    fun stalled(): AdBreakNow? {
        if (!stalling) {
            frozen = shown(null)
            stalling = true
        }
        return frozen
    }

    companion object {
        internal val GAP = 15.seconds
    }
}

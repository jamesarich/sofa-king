package dev.jamesrich.sofaking.data

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The break on screen, from the readings a player gives every poll. A reading can lose a break for a moment (Media3
 * between playlists, a stream retried after a hiccup, a missed answer from the server), so a break that still had time
 * on its countdown, or had no known end, stays up until that time is up, for at most [GAP] after it was last seen.
 * A reading can also lose the break's end but not the break (a window that no longer holds its cue), so the break
 * keeps the countdown it had until that runs out. [over] tells when it has been gone that long: until then a break
 * that comes back is the same one, and OK or Back still holds the picture over it.
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
            frozen?.let(::see)
            frozen = null
        }
        return when {
            found != null -> {
                val counting = left(ends).takeIf { found.remainingMs == null && !over && it?.remainingMs != null }
                (counting ?: found).also(::see)
            }

            over -> {
                seen = null
                ends = null
                null
            }

            else -> left(ends)
        }
    }

    /** Marks [found] as on screen now, its countdown running from here. */
    private fun see(found: AdBreakNow) {
        seen = timeSource.markNow()
        ends = found.remainingMs?.let { timeSource.markNow() + it.milliseconds }
    }

    /** The break still up until [ends], or one with no known end when that is null. */
    private fun left(ends: TimeMark?): AdBreakNow? = if (ends == null) {
        AdBreakNow(remainingMs = null)
    } else {
        (-ends.elapsedNow()).inWholeMilliseconds.takeIf { it > 0 }?.let { AdBreakNow(it) }
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

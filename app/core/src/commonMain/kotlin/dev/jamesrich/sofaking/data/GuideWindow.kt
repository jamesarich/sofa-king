package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The 24 hours the guide covers, the most one server query answers, starting on the half hour; the repository queries exactly what the grid shows. */
object GuideWindow {
    const val SLOT_MINUTES = 30L
    const val WINDOW_MINUTES = Wire.GUIDE_MAX_HOURS * 60L

    /** Floors [now] to the previous half hour on the UTC epoch grid. */
    fun start(now: Instant): Instant {
        val slotSeconds = SLOT_MINUTES * 60
        return Instant.fromEpochSeconds(now.epochSeconds.floorDiv(slotSeconds) * slotSeconds)
    }

    fun end(windowStart: Instant): Instant = windowStart + WINDOW_MINUTES.minutes
}

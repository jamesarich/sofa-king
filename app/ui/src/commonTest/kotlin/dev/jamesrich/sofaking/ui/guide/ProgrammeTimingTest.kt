package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Programme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ProgrammeTimingTest {
    private val programme = Programme("c", Instant.parse("2026-10-12T20:00:00Z"), Instant.parse("2026-10-12T21:30:00Z"), "Show", null)

    @Test
    fun `an airing programme reports the minutes left rounded up`() {
        assertEquals(ProgrammeTiming.Airing(42.minutes), programmeTiming(programme, Instant.parse("2026-10-12T20:48:10Z")))
        assertEquals(ProgrammeTiming.Airing(90.minutes), programmeTiming(programme, programme.start))
    }

    @Test
    fun `an upcoming programme reports the time until it starts`() {
        assertEquals(ProgrammeTiming.Upcoming(1.hours + 20.minutes), programmeTiming(programme, Instant.parse("2026-10-12T18:40:00Z")))
    }

    @Test
    fun `a finished programme has ended`() {
        assertEquals(ProgrammeTiming.Ended, programmeTiming(programme, programme.stop))
    }
}

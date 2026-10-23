package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Program
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ProgramTimingTest {
    private val program = Program("c", Instant.parse("2026-10-12T20:00:00Z"), Instant.parse("2026-10-12T21:30:00Z"), "Show", null)

    @Test
    fun `an airing program reports the minutes left rounded up`() {
        assertEquals(ProgramTiming.Airing(42.minutes), programTiming(program, Instant.parse("2026-10-12T20:48:10Z")))
        assertEquals(ProgramTiming.Airing(90.minutes), programTiming(program, program.start))
    }

    @Test
    fun `an upcoming program reports the time until it starts`() {
        assertEquals(ProgramTiming.Upcoming(1.hours + 20.minutes), programTiming(program, Instant.parse("2026-10-12T18:40:00Z")))
    }

    @Test
    fun `a finished program has ended`() {
        assertEquals(ProgramTiming.Ended, programTiming(program, program.stop))
    }
}

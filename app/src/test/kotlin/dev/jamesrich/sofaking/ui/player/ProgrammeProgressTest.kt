package dev.jamesrich.sofaking.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ProgrammeProgressTest {
    private val start = Instant.parse("2026-10-09T20:00:00Z")
    private val stop = Instant.parse("2026-10-09T21:00:00Z")

    @Test
    fun `is zero before the programme starts`() {
        assertEquals(0f, programmeProgress(start, stop, start.minusSeconds(1)), 0f)
    }

    @Test
    fun `is one after the programme stops`() {
        assertEquals(1f, programmeProgress(start, stop, stop.plusSeconds(1)), 0f)
    }

    @Test
    fun `is the elapsed share in between`() {
        assertEquals(0.25f, programmeProgress(start, stop, start.plusSeconds(900)), 0.0001f)
        assertEquals(0.5f, programmeProgress(start, stop, start.plusSeconds(1800)), 0.0001f)
    }

    @Test
    fun `a zero-length programme reads as not started`() {
        assertEquals(0f, programmeProgress(start, start, start), 0f)
    }
}

package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ProgrammeProgressTest {
    private val start = Instant.parse("2026-10-09T20:00:00Z")
    private val stop = Instant.parse("2026-10-09T21:00:00Z")

    @Test
    fun `is zero before the programme starts`() {
        assertEquals(0f, programmeProgress(start, stop, start - 1.seconds), 0f)
    }

    @Test
    fun `is one after the programme stops`() {
        assertEquals(1f, programmeProgress(start, stop, (stop + (1).seconds)), 0f)
    }

    @Test
    fun `is the elapsed share in between`() {
        assertEquals(0.25f, programmeProgress(start, stop, (start + (900).seconds)), 0.0001f)
        assertEquals(0.5f, programmeProgress(start, stop, (start + (1800).seconds)), 0.0001f)
    }

    @Test
    fun `a zero-length programme reads as not started`() {
        assertEquals(0f, programmeProgress(start, start, start), 0f)
    }
}

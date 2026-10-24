package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ProgramProgressTest {
    private val start = Instant.parse("2026-10-09T20:00:00Z")
    private val stop = Instant.parse("2026-10-09T21:00:00Z")

    @Test
    fun `is zero before the program starts`() {
        assertEquals(0f, programProgress(start, stop, start - 1.seconds), 0f)
    }

    @Test
    fun `is one after the program stops`() {
        assertEquals(1f, programProgress(start, stop, (stop + (1).seconds)), 0f)
    }

    @Test
    fun `is the elapsed share in between`() {
        assertEquals(0.25f, programProgress(start, stop, (start + (900).seconds)), 0.0001f)
        assertEquals(0.5f, programProgress(start, stop, (start + (1800).seconds)), 0.0001f)
    }

    @Test
    fun `a zero-length program reads as not started`() {
        assertEquals(0f, programProgress(start, start, start), 0f)
    }
}

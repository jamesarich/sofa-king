package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.contract.RecordingBreak
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingBreaksTest {
    private fun b(start: Long, end: Long) = RecordingBreak(start, end)

    @Test fun `guessed breaks that overlap or lie within five seconds are one`() {
        val guessed = listOf(b(100_000, 130_000), b(20_000, 50_000), b(48_000, 60_000), b(63_000, 70_000))
        assertEquals(
            listOf(b(20_000, 70_000), b(100_000, 130_000)),
            RecordingBreaks.combine(emptyList(), guessed),
        )
        assertEquals(
            listOf(b(20_000, 50_000), b(56_000, 60_000)),
            RecordingBreaks.combine(emptyList(), listOf(b(20_000, 50_000), b(56_000, 60_000))),
        )
    }

    @Test fun `a marked break is kept as it is and a guessed one that overlaps it is dropped`() {
        val marked = listOf(b(60_000, 120_000))
        val guessed = listOf(b(55_000, 100_000), b(110_000, 125_000), b(300_000, 330_000))
        assertEquals(listOf(b(60_000, 120_000), b(300_000, 330_000)), RecordingBreaks.combine(marked, guessed))
    }

    @Test fun `a guessed break beside a marked one without overlapping it is kept`() {
        assertEquals(
            listOf(b(10_000, 20_000), b(20_000, 40_000)),
            RecordingBreaks.combine(listOf(b(10_000, 20_000)), listOf(b(20_000, 40_000))),
        )
    }

    @Test fun `empty and backwards spans are dropped`() {
        val combined = RecordingBreaks.combine(listOf(b(5_000, 5_000)), listOf(b(1_000, 2_000), b(9, 3)))
        assertEquals(listOf(b(1_000, 2_000)), combined)
    }
}

package dev.jamesrich.sofaking.ui

import kotlin.test.assertEquals
import kotlin.test.Test

class ClockTest {
    @Test
    fun `the clock waits for the next wall-clock minute`() {
        assertEquals(1_000L, msToNextMinute(59_000L))
        assertEquals(59_999L, msToNextMinute(120_001L))
    }

    @Test
    fun `on a minute boundary it waits a full minute`() {
        assertEquals(60_000L, msToNextMinute(180_000L))
    }
}

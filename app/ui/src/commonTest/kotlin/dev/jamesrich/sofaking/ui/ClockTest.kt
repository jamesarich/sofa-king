package dev.jamesrich.sofaking.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.assertEquals
import kotlin.time.Instant
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

    @Test
    fun `the local day follows the zone`() {
        val lateEvening = Instant.parse("2026-10-23T03:30:00Z")
        assertEquals("2026-10-22", localDay(lateEvening, UtcOffset(hours = -4).asTimeZone()))
        assertEquals("2026-10-23", localDay(lateEvening, TimeZone.UTC))
    }
}

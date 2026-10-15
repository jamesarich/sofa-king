package dev.jamesrich.sofaking.ui

import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class TimeFormatterTest {
    // A fixed offset (Chicago in daylight time): named zones need a tz database on the web, the device zone does not.
    private val chicago = TimeFormatter(FixedOffsetTimeZone(UtcOffset(hours = -5)))

    @Test
    fun `times read as the twelve hour clock without a leading zero`() {
        assertEquals("1:30 PM", chicago.format(Instant.parse("2026-10-12T18:30:00Z")))
        assertEquals("12:05 AM", chicago.format(Instant.parse("2026-10-12T05:05:00Z")))
        assertEquals("12:00 PM", chicago.format(Instant.parse("2026-10-12T17:00:00Z")))
    }

    @Test
    fun `the twenty four hour clock pads the hour and drops the marker`() {
        val formatter = TimeFormatter(FixedOffsetTimeZone(UtcOffset(hours = -5)), twentyFourHour = true)
        assertEquals("13:30", formatter.format(Instant.parse("2026-10-12T18:30:00Z")))
        assertEquals("00:05", formatter.format(Instant.parse("2026-10-12T05:05:00Z")))
        assertEquals("13:00 - 14:30", formatter.range(Instant.parse("2026-10-12T18:00:00Z"), Instant.parse("2026-10-12T19:30:00Z")))
    }

    @Test
    fun `days between count the zone's calendar days`() {
        val monday = Instant.parse("2026-10-12T15:00:00Z")
        assertEquals(0, chicago.daysBetween(monday, Instant.parse("2026-10-13T03:00:00Z")))
        assertEquals(1, chicago.daysBetween(monday, Instant.parse("2026-10-13T06:00:00Z")))
        assertEquals(2, chicago.daysBetween(monday, Instant.parse("2026-10-14T15:00:00Z")))
    }

    @Test
    fun `a range joins two times with a dash`() {
        assertEquals("1:00 PM - 2:30 PM", chicago.range(Instant.parse("2026-10-12T18:00:00Z"), Instant.parse("2026-10-12T19:30:00Z")))
    }

    @Test
    fun `days are the zone's own and abbreviated`() {
        // 03:00 UTC Tuesday is still Monday evening in Chicago.
        assertEquals("Mon", chicago.day(Instant.parse("2026-10-13T03:00:00Z")))
        assertTrue(chicago.sameDay(Instant.parse("2026-10-13T03:00:00Z"), Instant.parse("2026-10-12T15:00:00Z")))
        assertFalse(chicago.sameDay(Instant.parse("2026-10-13T06:00:00Z"), Instant.parse("2026-10-12T15:00:00Z")))
    }
}

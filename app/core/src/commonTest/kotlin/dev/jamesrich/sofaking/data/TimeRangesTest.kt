package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeRangesTest {
    private val ranges = listOf(TimeSpan(0.0, 10.0), TimeSpan(20.0, 35.5))

    @Test
    fun `what is loaded ahead is the end of the range the picture is in less its time`() {
        assertEquals(4_000L, loadedAheadMs(6.0, ranges))
        assertEquals(15_500L, loadedAheadMs(20.0, ranges))
        assertEquals(0L, loadedAheadMs(10.0, ranges))
    }

    @Test
    fun `nothing is loaded ahead of a picture outside every range`() {
        assertNull(loadedAheadMs(15.0, ranges))
        assertNull(loadedAheadMs(40.0, ranges))
        assertNull(loadedAheadMs(1.0, emptyList()))
    }

    @Test
    fun `a time that is not a number loads nothing ahead`() {
        assertNull(loadedAheadMs(Double.NaN, ranges))
        assertNull(loadedAheadMs(Double.POSITIVE_INFINITY, ranges))
    }

    @Test
    fun `a finished file reports its own duration`() {
        assertEquals(5_400_000L, durationFromRanges(5_400.0, ranges))
    }

    @Test
    fun `a growing recording reports the end of its last seekable range`() {
        assertEquals(35_500L, durationFromRanges(Double.POSITIVE_INFINITY, ranges))
        assertEquals(35_500L, durationFromRanges(Double.NaN, ranges))
        assertEquals(35_500L, durationFromRanges(null, ranges))
        assertEquals(35_500L, durationFromRanges(0.0, ranges))
    }

    @Test
    fun `no duration and no range is an unknown length`() {
        assertNull(durationFromRanges(null, emptyList()))
        assertNull(durationFromRanges(Double.POSITIVE_INFINITY, listOf(TimeSpan(0.0, 0.0))))
    }
}

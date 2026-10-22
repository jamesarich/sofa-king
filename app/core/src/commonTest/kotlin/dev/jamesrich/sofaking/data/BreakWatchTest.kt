package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class BreakWatchTest {
    @Test
    fun `a break lost while its countdown still runs stays up until the countdown ends`() {
        val time = TestTimeSource()
        val watch = BreakWatch(time)
        assertEquals(AdBreakNow(remainingMs = 16_000), watch.shown(AdBreakNow(remainingMs = 16_000)))
        time += 6.seconds
        assertEquals(AdBreakNow(remainingMs = 10_000), watch.shown(null))
        time += 10.seconds
        assertNull(watch.shown(null))
    }

    @Test
    fun `a break with no known end is held for a gap only`() {
        val time = TestTimeSource()
        val watch = BreakWatch(time)
        watch.shown(AdBreakNow(remainingMs = null))
        time += 1.seconds
        assertEquals(AdBreakNow(remainingMs = null), watch.shown(null))
        time += BreakWatch.GAP
        assertNull(watch.shown(null))
        assertTrue(watch.over)
    }

    @Test
    fun `a break that ends on time is gone at once but over only after the gap`() {
        val time = TestTimeSource()
        val watch = BreakWatch(time)
        assertTrue(watch.over)
        watch.shown(AdBreakNow(remainingMs = 400))
        time += 1.seconds
        assertNull(watch.shown(null))
        assertFalse(watch.over)
        time += BreakWatch.GAP
        assertTrue(watch.over)
    }
}

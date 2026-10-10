package dev.jamesrich.sofaking.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallWatchTest {
    @Test
    fun `a download that keeps moving never reconnects`() {
        val watch = StallWatch()
        for (t in 0L..20_000L step 500) assertFalse(watch.shouldReconnect(t, bufferedPositionMs = 10_000 + t, bufferedDurationMs = 1_000))
    }

    @Test
    fun `a stalled download reconnects once the buffer runs low`() {
        val watch = StallWatch()
        assertFalse(watch.shouldReconnect(0, 50_000, 12_000))
        // Stalled but plenty left: keep playing what was downloaded.
        assertFalse(watch.shouldReconnect(5_000, 50_000, 7_000))
        assertTrue(watch.shouldReconnect(9_500, 50_000, 2_500))
    }

    @Test
    fun `a low buffer that is still growing is left alone`() {
        val watch = StallWatch()
        assertFalse(watch.shouldReconnect(0, 50_000, 2_000))
        assertFalse(watch.shouldReconnect(3_900, 50_000, 2_000))
        assertFalse(watch.shouldReconnect(4_000, 50_100, 2_000))
        assertFalse(watch.shouldReconnect(7_900, 50_100, 2_000))
    }

    @Test
    fun `a position reset counts as progress`() {
        val watch = StallWatch()
        assertFalse(watch.shouldReconnect(0, 50_000, 2_000))
        assertFalse(watch.shouldReconnect(3_000, 400, 400))
        assertFalse(watch.shouldReconnect(6_000, 400, 400))
        assertTrue(watch.shouldReconnect(7_000, 400, 400))
    }

    @Test
    fun `after a reconnect the watch starts over`() {
        val watch = StallWatch()
        watch.shouldReconnect(0, 50_000, 1_000)
        assertTrue(watch.shouldReconnect(4_000, 50_000, 1_000))
        assertFalse(watch.shouldReconnect(4_500, 50_000, 1_000))
        assertFalse(watch.shouldReconnect(8_000, 50_000, 1_000))
        assertTrue(watch.shouldReconnect(8_500, 50_000, 1_000))
    }
}

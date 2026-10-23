package dev.jamesrich.sofaking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchHistoryTest {
    @Test
    fun `a new channel pushes the one watched into previous`() {
        val history = WatchHistory().tuned("a").tuned("b")
        assertEquals(WatchHistory(watching = "b", previous = "a"), history)
    }

    @Test
    fun `tuning the channel already watched keeps its previous`() {
        assertEquals(WatchHistory("b", "a"), WatchHistory("b", "a").tuned("b"))
    }

    @Test
    fun `picking another channel in the guide makes the one watched the way back`() {
        // Watching a, Back to the guide, OK on b: Last returns to a.
        assertEquals("a", WatchHistory("a", "z").previousFor("b"))
    }

    @Test
    fun `reopening the channel watched keeps the way back it had`() {
        assertEquals("z", WatchHistory("a", "z").previousFor("a"))
    }

    @Test
    fun `nothing watched yet has no way back`() {
        assertNull(WatchHistory().previousFor("a"))
    }
}

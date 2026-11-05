package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.AdBreakNow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BreakCoverTest {
    private class FakePlayer(var found: AdBreakNow? = null, var stalled: Boolean = false) : PlayerTimeline {
        override fun adBreak(): AdBreakNow? = found
        override val pictureStalled: Boolean get() = stalled
    }

    private val minute = AdBreakNow(remainingMs = 60_000)

    @Test
    fun `a break the stream marks shows the screen with its countdown`() = runTest {
        val cover = BreakCover()
        val player = FakePlayer()
        backgroundScope.launch { cover.watch(player) }
        runCurrent()
        assertFalse(cover.up)

        player.found = minute
        advanceTimeBy(600)
        assertTrue(cover.up)
        assertEquals(60_000L, cover.leftMs)
    }

    @Test
    fun `a stalled picture holds the screen up with the countdown stopped`() = runTest {
        val cover = BreakCover()
        val player = FakePlayer(found = minute)
        backgroundScope.launch { cover.watch(player) }
        runCurrent()
        assertTrue(cover.up)

        player.found = null
        player.stalled = true
        advanceTimeBy(10_000)
        assertTrue(cover.up, "a picture with nothing to play keeps its break on screen")
    }

    @Test
    fun `OK keeps the picture showing while the break goes on and across a stall`() = runTest {
        val cover = BreakCover()
        val player = FakePlayer(found = minute)
        backgroundScope.launch { cover.watch(player) }
        runCurrent()

        cover.dismissed = true
        assertFalse(cover.up)
        advanceTimeBy(5_000)
        assertTrue(cover.dismissed, "the break is not over")
        assertFalse(cover.up)

        player.found = null
        player.stalled = true
        advanceTimeBy(5_000)
        assertTrue(cover.dismissed, "a stall is not the end of the break")
        assertFalse(cover.up)
    }
}

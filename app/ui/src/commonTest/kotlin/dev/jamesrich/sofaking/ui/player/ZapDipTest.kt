package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZapDipTest {
    @Test
    fun `a zap dips the picture only with animations on and nothing to cover`() {
        assertTrue(zapDipAllowed(motionEnabled = true, failed = false, breakScreenUp = false))
        assertFalse(zapDipAllowed(motionEnabled = false, failed = false, breakScreenUp = false))
        assertFalse(zapDipAllowed(motionEnabled = true, failed = true, breakScreenUp = false))
        assertFalse(zapDipAllowed(motionEnabled = true, failed = false, breakScreenUp = true))
    }

    @Test
    fun `the black holds until the new stream plays and never past 400 ms`() {
        assertTrue(zapDipHolds(sinceZapMs = 0, newStreamPlaying = false))
        assertTrue(zapDipHolds(sinceZapMs = ZAP_DIP_MAX_MS - 1, newStreamPlaying = false))
        assertFalse(zapDipHolds(sinceZapMs = ZAP_DIP_MAX_MS, newStreamPlaying = false))
        assertFalse(zapDipHolds(sinceZapMs = 100, newStreamPlaying = true))
    }

    @Test
    fun `the dip fits the zap settle time`() {
        assertEquals(ZAP_SETTLE_MS, ZAP_DIP_MAX_MS)
    }

    @Test
    fun `typed digits and the miss notice take the corner from the zap number`() {
        assertTrue(zapNumberShown(withinWindow = true, typing = false))
        assertFalse(zapNumberShown(withinWindow = true, typing = true))
        assertFalse(zapNumberShown(withinWindow = false, typing = false))
    }

    @Test
    fun `each move to another channel counts as a zap and a repeat on the same one does not`() {
        val channels = listOf("1", "2", "3").map { Channel(it, it.toDouble(), "Ch $it", null, listOf("Test")) }
        val state = PlayerState(channels, startIndex = 0)
        assertEquals(0, state.zaps)
        state.zapStep(1)
        state.zapStep(1)
        assertEquals(2, state.zaps)
        state.zapTo(state.index)
        assertEquals(2, state.zaps)
        state.recallLastChannel()
        assertEquals(3, state.zaps)
    }
}

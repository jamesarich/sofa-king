package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class ZapSettleTest {
    private fun channel(id: String) = Channel(id, id.toDouble(), "Ch $id", null, listOf("Test"))

    @Test
    fun `the first tune starts at once so the preview's stream is taken over without a gap`() {
        assertEquals(0L, zapSettleMs(tuned = null, next = channel("1")))
    }

    @Test
    fun `a zap waits for the presses to settle`() {
        assertEquals(ZAP_SETTLE_MS, zapSettleMs(tuned = channel("1"), next = channel("2")))
    }

    @Test
    fun `a refreshed lineup that keeps the channel does not wait`() {
        assertEquals(0L, zapSettleMs(tuned = channel("1"), next = channel("1")))
    }
}

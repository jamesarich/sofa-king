package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZapScopeTest {
    private fun channel(id: String) = Channel(id, id.toDouble(), "Ch $id", null, listOf("Test"))
    private val lineup = listOf(channel("1"), channel("2"), channel("3"), channel("4"))

    @Test
    fun `a scope keeps the player inside the guide's filter in lineup order`() {
        val ring = zapRing(lineup, ZapScope("Favorites", setOf("4", "2")), widened = false)
        assertEquals(listOf("2", "4"), ring.map { it.guideId })
    }

    @Test
    fun `no scope or a widened one cycles the whole lineup`() {
        assertEquals(lineup, zapRing(lineup, null, widened = false))
        assertEquals(lineup, zapRing(lineup, ZapScope("Favorites", setOf("2")), widened = true))
    }

    @Test
    fun `a scope the lineup no longer has any of falls back to the whole lineup`() {
        assertEquals(lineup, zapRing(lineup, ZapScope("Sports", setOf("gone")), widened = false))
    }

    @Test
    fun `a scope lists the guide ids of the channels its filter shows`() {
        assertEquals(setOf("1", "3"), zapScopeOf("Favorites", listOf(channel("1"), channel("3"))).guideIds)
    }

    @Test
    fun `a step wraps at both ends of the ring`() {
        assertEquals(0, zapIndex(index = 3, size = 4, step = 1))
        assertEquals(3, zapIndex(index = 0, size = 4, step = -1))
    }

    @Test
    fun `the neighbor is the channel a step away inside the ring`() {
        val ring = zapRing(lineup, ZapScope("Favorites", setOf("2", "4")), widened = false)
        assertEquals("4", zapNeighbor(ring, from = "2", step = 1)?.guideId)
        assertEquals("4", zapNeighbor(ring, from = "2", step = -1)?.guideId)
        assertEquals("2", zapNeighbor(ring, from = "4", step = 1)?.guideId)
    }

    @Test
    fun `a channel the ring lacks steps onto its first or last channel`() {
        assertEquals("1", zapNeighbor(lineup, from = "gone", step = 1)?.guideId)
        assertEquals("4", zapNeighbor(lineup, from = "gone", step = -1)?.guideId)
    }

    @Test
    fun `an empty ring has no neighbor`() {
        assertNull(zapNeighbor(emptyList(), from = "1", step = 1))
    }

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

package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class ZapScopeTest {
    private fun channel(id: String) = Channel(id, id.toDouble(), "Ch $id", null, listOf("Test"))
    private val lineup = listOf(channel("1"), channel("2"), channel("3"), channel("4"))

    @Test
    fun `a scope keeps the player inside the guide's filter, in lineup order`() {
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
}

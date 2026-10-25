package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.data.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MultiviewTilesTest {
    @Test
    fun `two tiles sit side by side across the middle and four fill a grid`() {
        assertEquals(Rect(0f, 0.25f, 0.5f, 0.75f), multiviewTile(2, 0))
        assertEquals(Rect(0.5f, 0.25f, 1f, 0.75f), multiviewTile(2, 1))
        assertEquals(Rect(0f, 0.5f, 0.5f, 1f), multiviewTile(4, 2))
        assertEquals(Rect(0.5f, 0.5f, 1f, 1f), multiviewTile(4, 3))
    }

    @Test
    fun `a tap finds its tile and misses the black around two and the empty fourth of three`() {
        assertEquals(1, multiviewTileAt(2, 0.8f, 0.5f))
        assertNull(multiviewTileAt(2, 0.8f, 0.1f))
        assertEquals(2, multiviewTileAt(3, 0.2f, 0.8f))
        assertNull(multiviewTileAt(3, 0.8f, 0.8f))
    }

    @Test
    fun `the arrows move the sound across the grid and stop at its edges`() {
        assertEquals(1, multiviewMove(2, 0, Key.DirectionRight))
        assertEquals(0, multiviewMove(2, 0, Key.DirectionDown), "two tiles have no second row")
        assertEquals(3, multiviewMove(4, 1, Key.DirectionDown))
        assertEquals(2, multiviewMove(4, 3, Key.DirectionLeft))
        assertEquals(0, multiviewMove(4, 2, Key.DirectionUp))
        assertEquals(2, multiviewMove(3, 2, Key.DirectionRight), "three tiles leave the fourth place empty")
        assertEquals(1, multiviewMove(3, 1, Key.DirectionDown))
    }

    @Test
    fun `the picker lists the picked then the starred then the recent then the rest each once`() {
        val channels = (1..6).map { Channel("c$it", it.toDouble(), "Channel $it", null, listOf("All")) }
        val order =
            multiviewChoices(
                channels,
                picked = listOf("c4"),
                favoritesOrder = listOf("c2", "c4"),
                recent = listOf("c6", "gone"),
            )
        assertEquals(listOf("c4", "c2", "c6", "c1", "c3", "c5"), order.map { it.guideId })
    }

    @Test
    fun `a pick toggles and stops at four`() {
        assertEquals(listOf("a"), togglePick(listOf("a", "b"), "b"))
        assertEquals(listOf("a", "b", "c", "d"), togglePick(listOf("a", "b", "c", "d"), "e"))
        assertEquals(listOf("a", "b", "c"), togglePick(listOf("a", "b"), "c"))
    }
}

package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.LiveBreaks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `one tile fills the screen while the second is still to pick`() {
        assertEquals(Rect(0f, 0f, 1f, 1f), multiviewTile(1, 0))
        assertEquals(0, multiviewMove(1, 0, Key.DirectionRight))
    }

    @Test
    fun `the picker groups the opened then the starred then the recent then the rest each once`() {
        val groups = multiviewGroups(
            channels(),
            opened = listOf("c4"),
            favoritesOrder = listOf("c2", "c4"),
            recent = listOf("c6", "gone"),
        )
        assertEquals(
            listOf(
                PickSection.WATCHING to listOf("c4"),
                PickSection.FAVORITES to listOf("c2"),
                PickSection.RECENT to listOf("c6"),
                PickSection.ALL to listOf("c1", "c3", "c5"),
            ),
            groups.map { group -> group.section to group.channels.map { it.guideId } },
        )
    }

    @Test
    fun `a search lists the channels it matches by number or name or program under one heading`() {
        val airing = { channel: Channel -> "News".takeIf { channel.guideId == "c5" } }
        fun found(query: String) = multiviewGroups(channels(), listOf("c4"), emptyList(), emptyList(), query, airing)
            .single()
            .also { assertEquals(PickSection.RESULTS, it.section) }
            .channels.map { it.guideId }
        assertEquals(listOf("c3"), found("channel 3"))
        assertEquals(listOf("c6"), found("6"))
        assertEquals(listOf("c5"), found("news"))
        assertEquals(emptyList(), found("nothing"))
    }

    @Test
    fun `a channel keeps its player while others come and go and a new one takes the lowest free`() {
        val first = paneSlots(emptyMap(), listOf("a", "b", "c"))
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2), first)
        val second = paneSlots(first, listOf("b", "c"))
        assertEquals(mapOf("b" to 1, "c" to 2), second)
        assertEquals(mapOf("b" to 1, "c" to 2, "d" to 0, "e" to 3), paneSlots(second, listOf("b", "c", "d", "e")))
    }

    @Test
    fun `the sound stays on its channel and moves to the first when that one goes`() {
        assertEquals("b", soundAfter("b", listOf("a", "b")))
        assertEquals("a", soundAfter("b", listOf("a", "c")))
        assertNull(soundAfter("b", emptyList()))
    }

    @Test
    fun `ticking opens a pane and unticking the heard one moves the sound and closing empties them once`() {
        val state = MultiviewState(listOf("a"))
        state.toggle("b")
        state.sound = "b"
        assertEquals(mapOf("a" to 0, "b" to 1), state.slots)
        state.toggle("b")
        assertEquals(listOf("a"), state.picked)
        assertEquals("a", state.sound)
        assertTrue(state.close())
        assertEquals(emptyList(), state.picked)
        assertFalse(state.close())
    }

    @Test
    fun `a silent pane always shows captions and the heard one follows the setting`() {
        assertTrue(paneCaptions(sound = false, setting = false))
        assertFalse(paneCaptions(sound = true, setting = false))
        assertTrue(paneCaptions(sound = true, setting = true))
    }

    @Test
    fun `the heard pane goes silent only under a break screen that mutes`() {
        assertTrue(paneSilent(sound = false, covered = false, LiveBreaks.PLAY))
        assertFalse(paneSilent(sound = true, covered = false, LiveBreaks.SCREEN_MUTED))
        assertTrue(paneSilent(sound = true, covered = true, LiveBreaks.SCREEN_MUTED))
        assertFalse(paneSilent(sound = true, covered = true, LiveBreaks.SCREEN))
    }

    @Test
    fun `a pick toggles and stops at four`() {
        assertEquals(listOf("a"), togglePick(listOf("a", "b"), "b"))
        assertEquals(listOf("a", "b", "c", "d"), togglePick(listOf("a", "b", "c", "d"), "e"))
        assertEquals(listOf("a", "b", "c"), togglePick(listOf("a", "b"), "c"))
    }

    private fun channels() = (1..6).map { Channel("c$it", it.toDouble(), "Channel $it", null, listOf("All")) }
}

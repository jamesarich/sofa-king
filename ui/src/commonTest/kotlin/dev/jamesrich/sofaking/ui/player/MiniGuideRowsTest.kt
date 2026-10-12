package dev.jamesrich.sofaking.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniGuideRowsTest {

    @Test
    fun `without a last channel every channel is listed in zap order`() {
        val rows = miniGuideRows(channelCount = 3, previousIndex = null)
        assertEquals(listOf(0, 1, 2), rows.map { it.channelIndex })
        assertTrue(rows.none { it.pinnedLast })
    }

    @Test
    fun `the last channel is pinned first and also keeps its place in the list`() {
        val rows = miniGuideRows(channelCount = 3, previousIndex = 2)
        assertEquals(listOf(2, 0, 1, 2), rows.map { it.channelIndex })
        assertTrue(rows.first().pinnedLast)
        assertFalse(rows.drop(1).any { it.pinnedLast })
    }

    @Test
    fun `row keys stay unique when the last channel is duplicated`() {
        val keys = miniGuideRows(channelCount = 3, previousIndex = 2).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `an out-of-range last channel is ignored`() {
        assertEquals(3, miniGuideRows(channelCount = 3, previousIndex = 7).size)
        assertEquals(3, miniGuideRows(channelCount = 3, previousIndex = -1).size)
    }

    @Test
    fun `focus lands on the current channel's own row`() {
        assertEquals(1, miniGuideFocusRow(channelCount = 3, previousIndex = null, currentIndex = 1))
        assertEquals(2, miniGuideFocusRow(channelCount = 3, previousIndex = 2, currentIndex = 1))
    }

    @Test
    fun `focus row matches the list position of the current channel`() {
        val rows = miniGuideRows(channelCount = 5, previousIndex = 4)
        val focus = miniGuideFocusRow(channelCount = 5, previousIndex = 4, currentIndex = 2)
        assertEquals(2, rows[focus].channelIndex)
        assertFalse(rows[focus].pinnedLast)
    }
}

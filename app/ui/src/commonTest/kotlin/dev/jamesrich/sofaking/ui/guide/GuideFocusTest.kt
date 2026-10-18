package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Channel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class GuideFocusTest {
    private fun channel(id: String) = Channel(id, id.toDouble(), "Ch $id", null, listOf("Test"))
    private val visible = listOf(channel("1"), channel("2"), channel("3"))

    @Test
    fun `the channel the player was left on wins over the one focused before the player`() {
        // Focused 3 in the guide, played it, zapped to 1 and pressed Back.
        assertEquals(0, initialGuideRow(visible, returnedFrom = "1", lastFocused = "3", lastWatched = "3"))
    }

    @Test
    fun `without a player trip the channel last focused in the guide wins over the one watched`() {
        assertEquals(2, initialGuideRow(visible, returnedFrom = null, lastFocused = "3", lastWatched = "1"))
    }

    @Test
    fun `a cold start lands on the last watched channel`() {
        assertEquals(1, initialGuideRow(visible, returnedFrom = null, lastFocused = null, lastWatched = "2"))
    }

    @Test
    fun `an unknown or missing channel leaves the first row to take focus`() {
        assertEquals(-1, initialGuideRow(visible, returnedFrom = null, lastFocused = null, lastWatched = null))
        assertEquals(-1, initialGuideRow(visible, returnedFrom = null, lastFocused = "9", lastWatched = "2"))
        assertEquals(-1, initialGuideRow(visible, returnedFrom = "9", lastFocused = "2", lastWatched = "2"))
        assertEquals(-1, initialGuideRow(emptyList(), returnedFrom = null, lastFocused = "1", lastWatched = null))
    }

    @Test
    fun `a watched channel hidden by the filter makes the filter give way`() {
        val all = visible + channel("4")
        assertTrue(watchedIsHidden("4", visible, all))
        assertTrue(watchedIsHidden("4", emptyList(), all))
    }

    @Test
    fun `a watched channel on screen gone from the lineup or unknown leaves the filter alone`() {
        assertFalse(watchedIsHidden("2", visible, visible))
        assertFalse(watchedIsHidden("9", visible, visible))
        assertFalse(watchedIsHidden(null, visible, visible))
    }

    @Test
    fun `back from the player a cursor in the past moves to now and one in the future stays`() {
        assertEquals(300L, cursorOnReturn(cursor = 30L, nowMinute = 300L))
        assertEquals(600L, cursorOnReturn(cursor = 600L, nowMinute = 300L))
    }

    // guideBackAction

    private fun back(searchOpen: Boolean = false, grid: Boolean = false, chips: Boolean = false, header: Boolean = true, armed: Boolean = false) =
        guideBackAction(searchOpen = searchOpen, gridFocused = grid, chipsFocused = chips, hasHeader = header, exitArmed = armed)

    @Test
    fun `open search closes before anything else`() {
        assertEquals(GuideBack.CLOSE_SEARCH, back(searchOpen = true, grid = true))
        assertEquals(GuideBack.CLOSE_SEARCH, back(searchOpen = true, armed = true))
    }

    @Test
    fun `Back from the grid climbs to the chips`() {
        assertEquals(GuideBack.FOCUS_CHIPS, back(grid = true, armed = true))
    }

    @Test
    fun `Back from the chips climbs to the header's tabs when there are some`() {
        assertEquals(GuideBack.FOCUS_HEADER, back(chips = true))
        assertEquals(GuideBack.ARM_EXIT, back(chips = true, header = false))
    }

    @Test
    fun `Back from the header asks once and exits on the second press`() {
        assertEquals(GuideBack.ARM_EXIT, back())
        assertEquals(GuideBack.EXIT, back(armed = true))
    }

    // selectedChipIndex

    @Test
    fun `the selected chip sits after Now and Search`() {
        val filters = listOf<GuideFilter>(GuideFilter.All, GuideFilter.Favorites, GuideFilter.Group("News"))
        assertEquals(2, selectedChipIndex(filters, GuideFilter.All))
        assertEquals(4, selectedChipIndex(filters, GuideFilter.Group("News")))
        assertEquals(2, selectedChipIndex(filters, GuideFilter.Group("Gone")))
    }
}

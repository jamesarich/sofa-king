package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideFocusTest {
    private fun channel(id: String) = Channel(id, id.toDouble(), "Ch $id", null, "Test")
    private val visible = listOf(channel("1"), channel("2"), channel("3"))

    @Test
    fun `the channel focused before the player wins over the one watched`() {
        assertEquals(2, initialGuideRow(visible, lastFocused = "3", lastWatched = "1"))
    }

    @Test
    fun `a cold start lands on the last watched channel`() {
        assertEquals(1, initialGuideRow(visible, lastFocused = null, lastWatched = "2"))
    }

    @Test
    fun `an unknown or missing channel leaves the first row to take focus`() {
        assertEquals(-1, initialGuideRow(visible, lastFocused = null, lastWatched = null))
        assertEquals(-1, initialGuideRow(visible, lastFocused = "9", lastWatched = "2"))
        assertEquals(-1, initialGuideRow(emptyList(), lastFocused = "1", lastWatched = null))
    }

    // guideBackAction

    @Test
    fun `open search closes before anything else`() {
        assertEquals(GuideBack.CLOSE_SEARCH, guideBackAction(searchOpen = true, gridFocused = true))
        assertEquals(GuideBack.CLOSE_SEARCH, guideBackAction(searchOpen = true, gridFocused = false))
    }

    @Test
    fun `Back from the grid climbs to the chips`() {
        assertEquals(GuideBack.FOCUS_CHIPS, guideBackAction(searchOpen = false, gridFocused = true))
    }

    @Test
    fun `Back from the chips or the header exits`() {
        assertEquals(GuideBack.EXIT, guideBackAction(searchOpen = false, gridFocused = false))
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

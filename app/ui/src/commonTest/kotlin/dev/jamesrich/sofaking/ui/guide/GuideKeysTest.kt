package dev.jamesrich.sofaking.ui.guide

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideKeysTest {
    @Test
    fun `Guide and Menu jump to Now`() {
        assertEquals(GuideShortcut.NOW, Key.Guide.guideShortcut())
        assertEquals(GuideShortcut.NOW, Key.Menu.guideShortcut())
    }

    @Test
    fun `Left on the first cell and Right on the last stay in the row`() {
        val first = GuideCell(null, startMinute = 0, endMinute = 30)
        val middle = GuideCell(null, startMinute = 30, endMinute = 60)
        val last = GuideCell(null, startMinute = 1410, endMinute = 1440)
        assertTrue(Key.DirectionLeft.stopsAtRowEdge(first, 1440))
        assertFalse(Key.DirectionRight.stopsAtRowEdge(first, 1440))
        assertFalse(Key.DirectionLeft.stopsAtRowEdge(middle, 1440))
        assertTrue(Key.DirectionRight.stopsAtRowEdge(last, 1440))
        assertFalse(Key.DirectionUp.stopsAtRowEdge(first, 1440))
    }

    @Test
    fun `navigation keys are not shortcuts`() {
        assertNull(Key.DirectionDown.guideShortcut())
        assertNull(Key.Back.guideShortcut())
        assertNull(Key.DirectionCenter.guideShortcut())
    }

    @Test
    fun `Back and Escape close the program details`() {
        assertTrue(Key.Back.closesDetails())
        assertTrue(Key.Escape.closesDetails())
        assertFalse(Key.DirectionCenter.closesDetails())
        assertFalse(Key.Info.closesDetails())
    }

    @Test
    fun `Search opens voice search`() {
        assertEquals(GuideShortcut.SEARCH, Key.Search.guideShortcut())
    }

    @Test
    fun `Info opens the program details`() {
        assertEquals(GuideShortcut.DETAILS, Key.Info.guideShortcut())
    }

    @Test
    fun `Fast Forward and Rewind move a day`() {
        assertEquals(GuideShortcut.DAY_FORWARD, Key.MediaFastForward.guideShortcut())
        assertEquals(GuideShortcut.DAY_FORWARD, Key.MediaSkipForward.guideShortcut())
        assertEquals(GuideShortcut.DAY_BACK, Key.MediaRewind.guideShortcut())
        assertEquals(GuideShortcut.DAY_BACK, Key.MediaSkipBackward.guideShortcut())
    }

    @Test
    fun `a day jump stays between the live window and the last loadable day`() {
        assertEquals(1, dayOffsetAfter(0, GuideShortcut.DAY_FORWARD))
        assertEquals(0, dayOffsetAfter(0, GuideShortcut.DAY_BACK))
        assertEquals(MAX_DAY_OFFSET, dayOffsetAfter(MAX_DAY_OFFSET, GuideShortcut.DAY_FORWARD))
        assertEquals(MAX_DAY_OFFSET - 1, dayOffsetAfter(MAX_DAY_OFFSET, GuideShortcut.DAY_BACK))
        assertEquals(2, dayOffsetAfter(2, GuideShortcut.NOW))
    }

    @Test
    fun `Up and Down step one row`() {
        assertEquals(GridStep(-1, paged = false), Key.DirectionUp.gridStep())
        assertEquals(GridStep(1, paged = false), Key.DirectionDown.gridStep())
    }

    @Test
    fun `Channel and Page keys move a page that stops at the grid's ends`() {
        assertEquals(GridStep(GUIDE_PAGE_ROWS, paged = true), Key.ChannelDown.gridStep())
        assertEquals(GridStep(GUIDE_PAGE_ROWS, paged = true), Key.PageDown.gridStep())
        assertEquals(GridStep(-GUIDE_PAGE_ROWS, paged = true), Key.ChannelUp.gridStep())
        assertEquals(GridStep(-GUIDE_PAGE_ROWS, paged = true), Key.PageUp.gridStep())
    }

    @Test
    fun `Left and Right and OK do not move rows`() {
        assertNull(Key.DirectionLeft.gridStep())
        assertNull(Key.DirectionRight.gridStep())
        assertNull(Key.DirectionCenter.gridStep())
    }
}

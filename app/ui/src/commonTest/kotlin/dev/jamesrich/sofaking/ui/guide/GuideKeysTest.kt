package dev.jamesrich.sofaking.ui.guide

import androidx.compose.ui.input.key.Key
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class GuideKeysTest {
    @Test
    fun `Guide and Menu jump to Now`() {
        assertEquals(GuideShortcut.NOW, Key.Guide.guideShortcut())
        assertEquals(GuideShortcut.NOW, Key.Menu.guideShortcut())
    }

    @Test
    fun `navigation keys are not shortcuts`() {
        assertNull(Key.DirectionDown.guideShortcut())
        assertNull(Key.Back.guideShortcut())
        assertNull(Key.DirectionCenter.guideShortcut())
    }

    @Test
    fun `Search opens voice search`() {
        assertEquals(GuideShortcut.SEARCH, Key.Search.guideShortcut())
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

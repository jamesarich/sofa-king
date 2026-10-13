package dev.jamesrich.sofaking.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveTest {
    @Test
    fun `a 1080p TV keeps the overscan margins and type scale and shows the preview`() {
        val tv = windowLayoutFor(960.dp, 540.dp)
        assertEquals(WindowLayout.TV, tv)
        assertEquals(OVERSCAN_HORIZONTAL, tv.horizontalMargin)
        assertEquals(OVERSCAN_VERTICAL, tv.verticalMargin)
        assertTrue(tv.showsPreview)
        assertFalse(tv.isCompact)
    }

    @Test
    fun `a phone held sideways is short and the grid takes the preview's room`() {
        val phone = windowLayoutFor(800.dp, 360.dp)
        assertEquals(SizeClass.COMPACT, phone.height)
        assertFalse(phone.showsPreview)
        assertTrue(phone.isCompact)
        assertEquals(OVERSCAN_HORIZONTAL, phone.horizontalMargin)
        assertTrue(phone.verticalMargin < OVERSCAN_VERTICAL)
    }

    @Test
    fun `a narrow window drops the preview and the side overscan`() {
        val narrow = windowLayoutFor(560.dp, 720.dp)
        assertEquals(SizeClass.COMPACT, narrow.width)
        assertFalse(narrow.showsPreview)
        assertTrue(narrow.horizontalMargin < OVERSCAN_HORIZONTAL)
        assertEquals(OVERSCAN_VERTICAL, narrow.verticalMargin)
    }

    @Test
    fun `a tablet held upright keeps the preview`() {
        val tablet = windowLayoutFor(800.dp, 1280.dp)
        assertEquals(WindowLayout(SizeClass.MEDIUM, SizeClass.EXPANDED), tablet)
        assertTrue(tablet.showsPreview)
    }

    @Test
    fun `the breakpoints are Material's`() {
        assertEquals(SizeClass.COMPACT, windowLayoutFor(599.dp, 600.dp).width)
        assertEquals(SizeClass.MEDIUM, windowLayoutFor(600.dp, 600.dp).width)
        assertEquals(SizeClass.EXPANDED, windowLayoutFor(840.dp, 600.dp).width)
        assertEquals(SizeClass.COMPACT, windowLayoutFor(800.dp, 479.dp).height)
        assertEquals(SizeClass.MEDIUM, windowLayoutFor(800.dp, 480.dp).height)
        assertEquals(SizeClass.EXPANDED, windowLayoutFor(800.dp, 900.dp).height)
    }
}

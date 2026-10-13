package dev.jamesrich.sofaking.ui

import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutProfileTest {
    /** The size class of a window [widthDp] by [heightDp], as the adaptive library computes it. */
    private fun window(widthDp: Int, heightDp: Int) = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(widthDp, heightDp)

    @Test
    fun `a TV keeps its layout at any window size`() {
        assertEquals(LayoutProfile.Tv, LayoutProfile.of(tv = true, sizeClass = window(960, 540)))
        assertEquals(LayoutProfile.Tv, LayoutProfile.of(tv = true, sizeClass = window(400, 300)))
        assertTrue(LayoutProfile.Tv.showsPreviewBand)
    }

    @Test
    fun `a landscape tablet the size of a TV keeps the preview band`() {
        val tablet = LayoutProfile.of(tv = false, sizeClass = window(960, 540))
        assertFalse(tablet.tv)
        assertFalse(tablet.compactWidth)
        assertFalse(tablet.compactHeight)
        assertTrue(tablet.showsPreviewBand)
    }

    @Test
    fun `a landscape phone is compact in height and drops the preview band`() {
        val phone = LayoutProfile.of(tv = false, sizeClass = window(800, 380))
        assertFalse(phone.compactWidth)
        assertTrue(phone.compactHeight)
        assertFalse(phone.showsPreviewBand)
    }

    @Test
    fun `a portrait phone is compact in width and drops the preview band`() {
        val phone = LayoutProfile.of(tv = false, sizeClass = window(390, 840))
        assertTrue(phone.compactWidth)
        assertFalse(phone.compactHeight)
        assertFalse(phone.showsPreviewBand)
    }

    @Test
    fun `the size class bounds are exclusive below`() {
        assertFalse(LayoutProfile.of(tv = false, sizeClass = window(600, 480)).compactWidth)
        assertFalse(LayoutProfile.of(tv = false, sizeClass = window(600, 480)).compactHeight)
        assertTrue(LayoutProfile.of(tv = false, sizeClass = window(599, 479)).compactWidth)
        assertTrue(LayoutProfile.of(tv = false, sizeClass = window(599, 479)).compactHeight)
    }
}

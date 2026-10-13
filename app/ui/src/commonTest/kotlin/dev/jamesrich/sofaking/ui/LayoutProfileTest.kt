package dev.jamesrich.sofaking.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutProfileTest {
    @Test
    fun `a TV keeps its layout at any window size`() {
        assertEquals(LayoutProfile.Tv, LayoutProfile.of(tv = true, widthDp = 960, heightDp = 540))
        assertEquals(LayoutProfile.Tv, LayoutProfile.of(tv = true, widthDp = 400, heightDp = 300))
        assertTrue(LayoutProfile.Tv.showsPreviewBand)
    }

    @Test
    fun `a landscape tablet the size of a TV keeps the preview band`() {
        val tablet = LayoutProfile.of(tv = false, widthDp = 960, heightDp = 540)
        assertFalse(tablet.tv)
        assertFalse(tablet.compactWidth)
        assertFalse(tablet.compactHeight)
        assertTrue(tablet.showsPreviewBand)
    }

    @Test
    fun `a landscape phone is compact in height and drops the preview band`() {
        val phone = LayoutProfile.of(tv = false, widthDp = 800, heightDp = 380)
        assertFalse(phone.compactWidth)
        assertTrue(phone.compactHeight)
        assertFalse(phone.showsPreviewBand)
    }

    @Test
    fun `a portrait phone is compact in width and drops the preview band`() {
        val phone = LayoutProfile.of(tv = false, widthDp = 390, heightDp = 840)
        assertTrue(phone.compactWidth)
        assertFalse(phone.compactHeight)
        assertFalse(phone.showsPreviewBand)
    }

    @Test
    fun `the size class bounds are exclusive below`() {
        assertFalse(LayoutProfile.of(tv = false, widthDp = 600, heightDp = 480).compactWidth)
        assertFalse(LayoutProfile.of(tv = false, widthDp = 600, heightDp = 480).compactHeight)
        assertTrue(LayoutProfile.of(tv = false, widthDp = 599, heightDp = 479).compactWidth)
        assertTrue(LayoutProfile.of(tv = false, widthDp = 599, heightDp = 479).compactHeight)
    }
}

package dev.jamesrich.sofaking

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeaderShownTest {
    @Test
    fun `a TV's header makes way while the viewer moves through a page's rows`() {
        assertTrue(headerShown(showsTabs = true, browsing = false, notice = false))
        assertFalse(headerShown(showsTabs = true, browsing = true, notice = false))
    }

    @Test
    fun `a notice brings the header back, since it shows nowhere else`() {
        assertTrue(headerShown(showsTabs = true, browsing = true, notice = true))
    }

    @Test
    fun `a header without the tabs stays`() {
        assertTrue(headerShown(showsTabs = false, browsing = true, notice = false))
    }
}

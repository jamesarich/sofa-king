package dev.jamesrich.sofaking

import kotlin.test.Test
import kotlin.test.assertEquals

class TabBackTest {
    @Test
    fun `back from the page climbs to the header`() {
        assertEquals(TabBack.FOCUS_HEADER, tabBackAction(hasHeader = true, headerFocused = false))
    }

    @Test
    fun `back from the header opens Live`() {
        assertEquals(TabBack.OPEN_LIVE, tabBackAction(hasHeader = true, headerFocused = true))
    }

    @Test
    fun `back with no header opens Live`() {
        assertEquals(TabBack.OPEN_LIVE, tabBackAction(hasHeader = false, headerFocused = false))
    }
}

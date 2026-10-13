package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwipeZapTest {
    @Test
    fun `a swipe up changes to the next channel as Up does`() {
        assertEquals(1, swipeZapStep(draggedPx = -200f, thresholdPx = 150f))
    }

    @Test
    fun `a swipe down changes to the previous channel`() {
        assertEquals(-1, swipeZapStep(draggedPx = 150f, thresholdPx = 150f))
    }

    @Test
    fun `a short drag changes nothing`() {
        assertNull(swipeZapStep(draggedPx = 149f, thresholdPx = 150f))
        assertNull(swipeZapStep(draggedPx = -149f, thresholdPx = 150f))
    }
}

package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TuneMarkTest {
    private var clock = 1_000L
    private val mark = TuneMark { clock }

    @Test
    fun `the first frame after a tune is timed from the tune`() {
        mark.start()
        clock += 350

        assertEquals(350L, mark.take())
    }

    @Test
    fun `a later frame is not timed`() {
        mark.start()
        mark.take()
        clock += 5_000

        assertNull(mark.take())
    }

    @Test
    fun `a failed or canceled tune leaves no mark for a re-attached surface`() {
        mark.start()
        mark.clear()
        clock += 5_000

        assertNull(mark.take())
    }

    @Test
    fun `a frame before any tune is not timed`() {
        assertNull(mark.take())
    }
}

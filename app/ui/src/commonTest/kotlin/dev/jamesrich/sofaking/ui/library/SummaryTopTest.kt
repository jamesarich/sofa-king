package dev.jamesrich.sofaking.ui.library

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryTopTest {
    @Test
    fun `an item page with a picture starts its title a third of the way down the hero`() {
        assertEquals(96.dp, summaryTop(pictured = true, heroHeight = 320.dp, top = 27.dp))
        assertEquals(27.dp, summaryTop(pictured = true, heroHeight = 60.dp, top = 27.dp))
    }

    @Test
    fun `an item page without a picture starts its title at the top margin`() {
        assertEquals(27.dp, summaryTop(pictured = false, heroHeight = 320.dp, top = 27.dp))
    }
}

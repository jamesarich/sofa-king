package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFocusTest {
    private val channel = Channel("1", 1.0, "One", null, listOf("Test"))

    @Test
    fun `an unconfigured server starts on Settings`() {
        assertEquals(HomeFocus.SETTINGS, homeFocusFor(LineupState.Unconfigured))
    }

    @Test
    fun `a lineup with channels starts in the grid`() {
        assertEquals(HomeFocus.GRID, homeFocusFor(LineupState.Ready(Lineup(listOf(channel), emptyMap()))))
    }

    @Test
    fun `loading, failed and empty lineups leave focus alone`() {
        assertEquals(HomeFocus.NONE, homeFocusFor(LineupState.Loading))
        assertEquals(HomeFocus.NONE, homeFocusFor(LineupState.Failed("HTTP_500")))
        assertEquals(HomeFocus.NONE, homeFocusFor(LineupState.Ready(Lineup(emptyList(), emptyMap()))))
    }
}

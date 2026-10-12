package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchTest {
    private val lineup = Lineup(listOf(Channel("1", 1.0, "One", null, listOf("Test")), Channel("2", 2.0, "Two", null, listOf("Test"))), emptyMap())
    private val ready = LineupState.Ready(lineup)

    @Test
    fun `nothing is decided while loading or before settings are read`() {
        assertEquals(LaunchTarget.Undecided, launchTarget(LineupState.Loading, AppSettings(lastChannel = "1")))
        assertEquals(LaunchTarget.Undecided, launchTarget(ready, null))
    }

    @Test
    fun `the last watched channel opens full screen`() {
        assertEquals(LaunchTarget.Player("2"), launchTarget(ready, AppSettings(lastChannel = "2")))
    }

    @Test
    fun `a first run opens the guide`() {
        assertEquals(LaunchTarget.Guide, launchTarget(ready, AppSettings(lastChannel = null)))
    }

    @Test
    fun `a last channel the lineup lost opens the guide`() {
        assertEquals(LaunchTarget.Guide, launchTarget(ready, AppSettings(lastChannel = "9")))
        assertEquals(LaunchTarget.Guide, launchTarget(LineupState.Ready(Lineup(emptyList(), emptyMap())), AppSettings(lastChannel = "1")))
    }

    @Test
    fun `an unconfigured or unreachable server opens the guide`() {
        assertEquals(LaunchTarget.Guide, launchTarget(LineupState.Unconfigured, AppSettings(lastChannel = "1")))
        assertEquals(LaunchTarget.Guide, launchTarget(LineupState.Failed("HTTP_503"), AppSettings(lastChannel = "1")))
    }
}

package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.data.TopLevelTab
import kotlin.test.assertEquals
import kotlin.test.Test

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

    @Test
    fun `a startup of the guide opens the guide whatever was watched`() {
        assertEquals(LaunchTarget.Guide, launchTarget(ready, AppSettings(lastChannel = "2", startup = StartupTarget.GUIDE)))
    }

    @Test
    fun `a chosen startup channel opens full screen or the guide once the lineup lost it`() {
        assertEquals(LaunchTarget.Player("1"), launchTarget(ready, AppSettings(lastChannel = "2", startup = StartupTarget.CHANNEL, startupChannel = "1")))
        assertEquals(LaunchTarget.Guide, launchTarget(ready, AppSettings(lastChannel = "2", startup = StartupTarget.CHANNEL, startupChannel = "9")))
        assertEquals(LaunchTarget.Guide, launchTarget(ready, AppSettings(lastChannel = "2", startup = StartupTarget.CHANNEL, startupChannel = null)))
    }

    @Test
    fun `where the viewer left off reopens the Library or Recordings tab with nothing playing`() {
        assertEquals(LaunchTarget.OpenTab(Tab.LIBRARY), launchTarget(ready, AppSettings(lastChannel = "2", lastTab = TopLevelTab.LIBRARY)))
        assertEquals(LaunchTarget.OpenTab(Tab.RECORDINGS), launchTarget(ready, AppSettings(lastChannel = "2", lastTab = TopLevelTab.RECORDINGS)))
    }

    @Test
    fun `a startup of the library opens the Library tab whatever was watched`() {
        assertEquals(LaunchTarget.OpenTab(Tab.LIBRARY), launchTarget(ready, AppSettings(lastChannel = "2", startup = StartupTarget.LIBRARY)))
    }

    @Test
    fun `an unconfigured server opens the guide even when the library was last open`() {
        assertEquals(LaunchTarget.Guide, launchTarget(LineupState.Unconfigured, AppSettings(lastTab = TopLevelTab.LIBRARY)))
        assertEquals(LaunchTarget.Guide, launchTarget(LineupState.Failed("HTTP_503"), AppSettings(startup = StartupTarget.LIBRARY)))
    }
}

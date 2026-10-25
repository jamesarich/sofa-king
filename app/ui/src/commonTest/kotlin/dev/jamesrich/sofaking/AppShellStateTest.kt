package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.MediaHome
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.data.RecordingsState
import dev.jamesrich.sofaking.data.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppShellStateTest {
    private val shell = AppShellState()

    @Test
    fun `back from the player lands the guide on the channel last tuned`() {
        shell.push(Route.Player("a"))
        shell.history = shell.history.tuned("a").tuned("b")
        shell.back()
        assertEquals(Route.Guide, shell.top)
        assertEquals("b", shell.nav.arrival.returnedFrom)
    }

    @Test
    fun `the Guide key in the player opens the guide at Now on the channel last tuned`() {
        shell.push(Route.Player("a"))
        shell.history = shell.history.tuned("c")
        shell.toGuide(Arrival(atNow = true))
        assertEquals(Route.Guide, shell.top)
        assertEquals(Arrival(returnedFrom = "c", atNow = true), shell.nav.arrival)
    }

    @Test
    fun `leaving Settings returns focus to the header's Settings button`() {
        shell.push(Route.Settings)
        shell.push(Route.Channels)
        shell.leaveSettings()
        assertEquals(Route.Guide, shell.top)
        assertEquals(HeaderReturn.SETTINGS, shell.headerReturn)
        assertTrue(shell.nav.arrival.inHeader)
    }

    @Test
    fun `back to Live from another tab keeps focus on the Live pill`() {
        shell.history = shell.history.tuned("a")
        shell.openTab(Tab.LIBRARY)
        shell.backToLive()
        assertEquals(Tab.LIVE, shell.nav.tab)
        assertEquals(HeaderReturn.TAB, shell.headerReturn)
        assertEquals(Arrival(returnedFrom = "a", inHeader = true), shell.nav.arrival)
    }

    @Test
    fun `a cold start decides once and waits while undecided`() {
        shell.decideLaunch(LaunchTarget.Undecided)
        assertFalse(shell.launchDecided)

        shell.decideLaunch(LaunchTarget.Player("a"))
        assertTrue(shell.launchDecided)
        assertEquals(Route.Player("a"), shell.top)

        shell.decideLaunch(LaunchTarget.OpenTab(Tab.LIBRARY))
        assertEquals(Route.Player("a"), shell.top, "a later target never moves the viewer")
    }

    @Test
    fun `a Watch Next link on a cold start plays the item over the Library in place of the last channel`() {
        shell.playItem("e1")
        assertTrue(shell.launchDecided)
        assertEquals(Tab.LIBRARY, shell.nav.tab)
        assertEquals(listOf(Route.Library, Route.Vod("e1")), shell.nav.stack(Tab.LIBRARY))

        shell.decideLaunch(LaunchTarget.Player("a"))
        assertEquals(Route.Vod("e1"), shell.top)
        shell.back()
        assertEquals(Route.Library, shell.top)
        shell.back()
        assertEquals(Route.Guide, shell.top, "no live player waits underneath")
    }

    @Test
    fun `a live link plays its channel over the guide in place of the one playing`() {
        shell.playChannel("a")
        assertTrue(shell.launchDecided, "the link is the cold start's page")
        assertEquals(listOf(Route.Guide, Route.Player("a")), shell.nav.stack(Tab.LIVE))

        shell.openTab(Tab.LIBRARY)
        shell.playChannel("b")
        assertEquals(Tab.LIVE, shell.nav.tab)
        assertEquals(listOf(Route.Guide, Route.Player("b")), shell.nav.stack(Tab.LIVE))
    }

    @Test
    fun `an item link may start the item partway`() {
        shell.playItem("e1", startMs = 90_000)
        assertEquals(Route.Vod("e1", startMs = 90_000), shell.top)
    }

    @Test
    fun `a Watch Next link while the app runs leaves Settings and keeps the Library pages`() {
        shell.decideLaunch(LaunchTarget.OpenTab(Tab.LIBRARY))
        shell.push(Route.LibraryGrid("tv"))
        shell.push(Route.Settings)
        shell.playItem("e1")
        assertEquals(listOf(Route.Library, Route.LibraryGrid("tv"), Route.Vod("e1")), shell.nav.stack(Tab.LIBRARY))

        shell.openTab(Tab.LIVE)
        shell.playItem("e2")
        assertEquals(Route.Vod("e2"), shell.top)
        assertEquals(
            listOf(Route.Library, Route.LibraryGrid("tv"), Route.Vod("e2")),
            shell.nav.stack(Tab.LIBRARY),
            "the item playing gives way",
        )
    }

    @Test
    fun `Settings locks again once the viewer leaves for the guide or the player`() {
        shell.push(Route.Settings)
        shell.settingsUnlocked = true
        shell.push(Route.Channels)
        shell.relockUnlessInSettings()
        assertTrue(shell.settingsUnlocked)

        shell.leaveSettings()
        shell.relockUnlessInSettings()
        assertFalse(shell.settingsUnlocked)
    }

    @Test
    fun `pairing a new server starts every tab over`() {
        shell.openTab(Tab.LIBRARY)
        shell.push(Route.LibraryGrid("movies"))
        shell.startOver()
        assertEquals(Navigation(), shell.nav)
    }

    @Test
    fun `the Library tab shows only for a server with a library`() {
        val movies = MediaLibrary("m", "Movies", LibraryKind.MOVIES, itemCount = 3)
        val serves = ServerCapabilities(library = true)
        assertTrue(tabsOffered(serves, Tab.LIVE, LibraryHomeState.Loading, RecordingsState.Loading).library)
        assertTrue(
            tabsOffered(
                serves,
                Tab.LIVE,
                LibraryHomeState.Ready(MediaHome(emptyList(), listOf(movies))),
                RecordingsState.Loading,
            ).library,
        )
        assertFalse(
            tabsOffered(
                serves,
                Tab.LIVE,
                LibraryHomeState.Ready(MediaHome(emptyList(), emptyList())),
                RecordingsState.Loading,
            ).library,
        )
        assertFalse(tabsOffered(serves, Tab.LIVE, LibraryHomeState.Unavailable, RecordingsState.Loading).library)
        assertFalse(
            tabsOffered(ServerCapabilities(), Tab.LIVE, LibraryHomeState.Loading, RecordingsState.Loading).library,
        )
        assertTrue(
            tabsOffered(ServerCapabilities(), Tab.LIBRARY, LibraryHomeState.Loading, RecordingsState.Loading).library,
            "a start on the tab shows it before the bootstrap answers",
        )
    }

    @Test
    fun `the Recordings tab shows for a recording server or while open until the server says it does not record`() {
        assertTrue(
            tabsOffered(
                ServerCapabilities(recordings = true),
                Tab.LIVE,
                LibraryHomeState.Loading,
                RecordingsState.Loading,
            ).recordings,
        )
        assertFalse(
            tabsOffered(ServerCapabilities(), Tab.LIVE, LibraryHomeState.Loading, RecordingsState.Loading).recordings,
        )
        assertTrue(
            tabsOffered(
                ServerCapabilities(),
                Tab.RECORDINGS,
                LibraryHomeState.Loading,
                RecordingsState.Loading,
            ).recordings,
        )
        assertFalse(
            tabsOffered(
                ServerCapabilities(),
                Tab.RECORDINGS,
                LibraryHomeState.Loading,
                RecordingsState.Unavailable,
            ).recordings,
        )
    }
}

package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.ui.library.SEARCH_FOCUS_KEY
import dev.jamesrich.sofaking.ui.library.libraryFocusKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class NavigationTest {
    private val start = Navigation()

    @Test
    fun `the app opens on the guide`() {
        assertEquals(Tab.LIVE, start.tab)
        assertEquals(listOf<Route>(Route.Guide), start.shown)
    }

    @Test
    fun `back from an item's page focuses the item on the page below`() {
        val nav = start.open(Tab.LIBRARY).push(Route.LibraryGrid("movies")).push(Route.LibraryItem("m1")).pop()
        assertEquals(Route.LibraryGrid("movies"), nav.top)
        assertEquals("m1", nav.arrival.returnedFrom)
    }

    @Test
    fun `back from a grid or search focuses its chip on the library home`() {
        val library = start.open(Tab.LIBRARY)
        assertEquals(libraryFocusKey("tv"), library.push(Route.LibraryGrid("tv")).pop().arrival.returnedFrom)
        assertEquals(SEARCH_FOCUS_KEY, library.push(Route.LibrarySearch()).pop().arrival.returnedFrom)
    }

    @Test
    fun `back from the player hands the guide the channel it was left on`() {
        val nav = start.push(Route.Player("a")).pop(returnedFrom = "b")
        assertEquals(Route.Guide, nav.top)
        assertEquals("b", nav.arrival.returnedFrom)
    }

    @Test
    fun `back from a tab's first page opens Live where it was`() {
        val nav = start.open(Tab.RECORDINGS)
        assertEquals(listOf(Route.Guide, Route.Recordings), nav.shown)
        assertEquals(Tab.LIVE, nav.pop().tab)
    }

    @Test
    fun `back on the guide is the guide's own`() {
        assertSame(start, start.pop())
    }

    @Test
    fun `a page already open is returned to rather than opened twice`() {
        val nav = start.open(Tab.LIBRARY)
            .push(Route.LibraryItem("show"))
            .push(Route.LibrarySearch(query = "Ann"))
            .push(Route.LibraryItem("show"))
        assertEquals(listOf(Route.Library, Route.LibraryItem("show")), nav.stack(Tab.LIBRARY))
    }

    @Test
    fun `the next episode takes the player's place`() {
        val nav = start.open(Tab.LIBRARY).push(Route.LibraryItem("show")).push(Route.Vod("e1")).replaceTop(Route.Vod("e2"))
        assertEquals(listOf(Route.Library, Route.LibraryItem("show"), Route.Vod("e2")), nav.stack(Tab.LIBRARY))
        assertEquals("e2", nav.pop().arrival.returnedFrom)
    }

    @Test
    fun `a tab opens again where it was left`() {
        val nav = start.open(Tab.LIBRARY).push(Route.LibraryGrid("movies")).open(Tab.LIVE).open(Tab.LIBRARY)
        assertEquals(Route.LibraryGrid("movies"), nav.top)
    }

    @Test
    fun `picking the open tab again closes its pages`() {
        val nav = start.open(Tab.LIBRARY).push(Route.LibraryGrid("movies")).open(Tab.LIBRARY)
        assertEquals(listOf<Route>(Route.Library), nav.stack(Tab.LIBRARY))
        assertSame(nav, nav.open(Tab.LIBRARY))
    }

    @Test
    fun `leaving Settings returns to the tab it was opened from`() {
        val nav = start.open(Tab.RECORDINGS).push(Route.Settings).push(Route.Channels).closeSettings()
        assertEquals(Tab.RECORDINGS, nav.tab)
        assertEquals(Route.Recordings, nav.top)
        assertNull(nav.arrival.returnedFrom)
    }
}

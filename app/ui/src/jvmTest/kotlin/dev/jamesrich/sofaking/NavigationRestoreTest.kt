package dev.jamesrich.sofaking

import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import kotlin.test.Test
import kotlin.test.assertEquals

/** On the JVM, where saved state is plain data; Android's host tests have no real Bundle. */
class NavigationRestoreTest {
    @Test
    fun `the back stacks survive a restore and the arrival does not`() {
        val nav = Navigation().open(Tab.LIBRARY).push(Route.LibraryItem("m1")).push(Route.Vod("m1", fromStart = true)).pop()
        val restored = decodeFromSavedState<Navigation>(encodeToSavedState(nav))
        assertEquals(nav.copy(arrival = Arrival()), restored)
    }
}

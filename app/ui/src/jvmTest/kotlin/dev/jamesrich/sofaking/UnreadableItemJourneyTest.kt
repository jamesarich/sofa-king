package dev.jamesrich.sofaking

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A library file the server cannot read (#708), on the TV: its page carries an Unreadable badge, Play says so at once
 * rather than spinning, and Back returns to the page with Play focused and no session left open.
 */
@OptIn(ExperimentalTestApi::class)
class UnreadableItemJourneyTest {
    @Test
    fun `an unreadable movie is badged, Play says the server cannot read it and Back returns to Play`() =
        runSofaKing(tv = true, File("build/journeys", "unreadable"), FakeServer(unreadableMovie = true)) {
            assertFocused("a guide cell") { it?.startsWith("Show at") == true }
            keys("Back", "Back", "Right", "OK", "Down", "Down", "Down")
            assertFocused(UNREADABLE_CARD)
            keys("OK")
            assertFocused("Play")
            assertShows("Unreadable")
            keys("OK")
            assertShows("The server can't read this file")
            assertEquals(0, server.openSessions, "a refused open left a session behind")
            keys("Back")
            assertFocused("Play")
            assertShows("Unreadable")
        }

    @Test
    fun `a readable movie has no badge`() = runSofaKing(tv = true, File("build/journeys", "readable")) {
        assertFocused("a guide cell") { it?.startsWith("Show at") == true }
        keys("Back", "Back", "Right", "OK", "Down", "Down", "Down")
        assertFocused(UNREADABLE_CARD)
        keys("OK")
        assertFocused("Play")
        val badged = ui.onAllNodes(hasText("Unreadable")).fetchSemanticsNodes().isNotEmpty()
        if (badged) failWith("a readable movie shows the Unreadable badge")
    }

    private fun AppDriver.assertShows(text: String) {
        val shown = runCatching {
            ui.waitUntil(timeoutMillis = AppDriver.SETTLE_MS) {
                ui.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        if (!shown) failWith("expected \"$text\" on screen")
    }

    private companion object {
        /** The first movie's card, the one [FakeServer] makes unreadable. */
        const val UNREADABLE_CARD = "Movie 1, 1990"
    }
}

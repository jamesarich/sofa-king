package dev.jamesrich.sofaking

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Zapping in the full-screen player on the TV, then Back: the guide comes back on the channel now watched, one row
 * below the channel the player opened on, not on the channel it opened on.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerZapJourneyTest {
    @Test
    fun `Channel Up in the player tunes the next channel and Back focuses that channel in the guide`() =
        runSofaKing(tv = true, File("build/journeys", "player-zap")) {
            val openedTop = focusedTop()
            keys("OK")
            ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannel == "ch0" }
            keys("ChannelUp")
            ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannel == "ch1" }
            keys("Back")
            assertFocused("a guide cell") { it?.startsWith("Show at") == true }
            val returnedTop = focusedTop()
            assertTrue(returnedTop > openedTop, "Back focused row at $returnedTop, not below the opened row $openedTop")
        }

    /** The top edge of the focused node, which tells one guide row from the next when their labels match. */
    private fun AppDriver.focusedTop() = ui.onAllNodes(isFocused()).fetchSemanticsNodes().last().boundsInRoot.top

    private companion object {
        const val WAIT_MS = 10_000L
    }
}

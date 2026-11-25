package dev.jamesrich.sofaking

import androidx.compose.ui.test.ExperimentalTestApi
import java.io.File
import kotlin.test.Test

/**
 * The live bar's More menu on the TV: OK on More opens it, OK on Zoom steps the picture and leaves the menu up, and
 * Back closes the menu with focus on More again. (The fixture's stream fails, which holds the bar up, so that the menu
 * holds it up against its idle timeout is checked on a device.)
 */
@OptIn(ExperimentalTestApi::class)
class PlayerMoreMenuJourneyTest {
    @Test
    fun `More opens a menu whose Zoom steps in place and Back closes it`() =
        runSofaKing(tv = true, File("build/journeys", "player-more")) {
            keys("OK")
            ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannel == "ch0" }
            repeat(MAX_BUTTONS) {
                if (focusedLabel() != "More") keys("Right")
            }
            assertFocused("More")
            keys("OK")
            repeat(MAX_ENTRIES) {
                if (focusedLabel()?.startsWith("Zoom") != true) keys("Down")
            }
            assertFocused("Zoom: Fit")
            keys("OK")
            assertFocused("Zoom: Fill")
            keys("Back")
            assertFocused("More")
        }

    private companion object {
        const val WAIT_MS = 10_000L
        const val MAX_BUTTONS = 12
        const val MAX_ENTRIES = 3
    }
}

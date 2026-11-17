package dev.jamesrich.sofaking

import androidx.compose.ui.test.ExperimentalTestApi
import java.io.File
import kotlin.test.Test

/**
 * The sleep timer in the live player on the TV (#701): the bar's Sleep button steps 15, 30 then 60 minutes with OK, the
 * timer lasts through a zap, and leaving the player turns it off, so the next channel opened starts with it off.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerSleepJourneyTest {
    @Test
    fun `OK on Sleep steps the timer, a zap keeps it and leaving the player turns it off`() =
        runSofaKing(tv = true, File("build/journeys", "player-sleep")) {
            keys("OK")
            ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannel == "ch0" }
            reachSleep()
            assertFocused("Sleep timer")
            keys("OK")
            assertFocused("Sleep in 15 min")
            keys("OK")
            assertFocused("Sleep in 30 min")
            keys("OK")
            assertFocused("Sleep in 60 min")
            keys("ChannelUp")
            ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannel == "ch1" }
            reachSleep()
            assertFocused("Sleep in 60 min")
            keys("Back", "Back")
            assertFocused("a guide cell") { it?.startsWith("Show at") == true }
            keys("OK")
            ui.waitUntil(timeoutMillis = WAIT_MS) { server.watching }
            reachSleep()
            assertFocused("Sleep timer")
        }

    /** Presses Right through the bar's buttons until the Sleep button has focus. */
    private fun AppDriver.reachSleep() {
        repeat(MAX_BUTTONS) {
            if (focusedLabel()?.startsWith("Sleep") != true) keys("Right")
        }
        if (focusedLabel()?.startsWith("Sleep") != true) failWith("Right through the bar never reached Sleep")
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val MAX_BUTTONS = 12
    }
}

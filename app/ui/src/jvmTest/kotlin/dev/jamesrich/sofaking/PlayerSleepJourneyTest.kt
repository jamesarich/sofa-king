package dev.jamesrich.sofaking

import androidx.compose.ui.test.ExperimentalTestApi
import java.io.File
import kotlin.test.Test

/**
 * The sleep timer in the live player on the TV (#701): the Sleep entry of the bar's More menu steps 15, 30 then 60
 * minutes with OK, the timer lasts through a zap, and leaving the player turns it off, so the next channel opened
 * starts with it off.
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
            keys("Back", "ChannelUp")
            ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannel == "ch1" }
            reachSleep()
            assertFocused("Sleep in 60 min")
            keys("Back", "Back", "Back")
            assertFocused("a guide cell") { it?.startsWith("Show at") == true }
            keys("OK")
            ui.waitUntil(timeoutMillis = WAIT_MS) { server.watching }
            reachSleep()
            assertFocused("Sleep timer")
        }

    /** Presses Right through the bar's buttons to More, opens its menu and presses Down to the Sleep entry. */
    private fun AppDriver.reachSleep() {
        repeat(MAX_BUTTONS) {
            if (focusedLabel() != "More") keys("Right")
        }
        if (focusedLabel() != "More") failWith("Right through the bar never reached More")
        keys("OK")
        repeat(MAX_ENTRIES) {
            if (focusedLabel()?.startsWith("Sleep") != true) keys("Down")
        }
        if (focusedLabel()?.startsWith("Sleep") != true) failWith("Down through the menu never reached Sleep")
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val MAX_BUTTONS = 12
        const val MAX_ENTRIES = 3
    }
}

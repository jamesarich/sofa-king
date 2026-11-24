package dev.jamesrich.sofaking

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The multiview picker on the TV, driven with the remote: how many channels it takes and how Search narrows them. */
class MultiviewPickerJourneyTest {
    @Test
    fun `the fifth channel cannot be ticked and the four stay playing`() = journey {
        openPicker()
        val opened = server.watchedChannels.single()
        repeat(MAX_PANES - 1) { at ->
            keys("Down", "OK")
            waitFor("server.watchedChannels.size == ${at + 2}", WAIT_MS) { server.watchedChannels.size == at + 2 }
        }
        val sessions = server.sessionsOpened.get()
        ui.onAllNodes(hasText("Watch $MAX_PANES channels")).onFirst().assertIsDisplayed()
        // The rows past the fourth are off, so Down finds nothing to land on and OK ticks nothing new.
        val focused = focusedLabel()
        keys("Down", "Down")
        assertEquals(focused, focusedLabel(), "Down stays on the fourth ticked channel")
        assertEquals(MAX_PANES, server.watchedChannels.size)
        assertEquals(sessions, server.sessionsOpened.get(), "a refused tick opens nothing")
        assertTrue(opened in server.watchedChannels)
    }

    @Test
    fun `a search lists the channels it matches and OK on one starts its pane`() = journey {
        openPicker()
        val opened = server.watchedChannels.single()
        keys("Up")
        if (focusedLabel() != "Search") keys("Left")
        assertFocused("Search")
        keys("OK")
        assertFocused("Channel, number or program")
        ui.onNode(hasSetTextAction()).performTextInput("Channel 7")
        ui.waitForIdle()
        ui.onAllNodes(hasText("Results")).onFirst().assertIsDisplayed()
        ui.onAllNodes(hasText("Channel 8", substring = true)).assertCountEquals(0)
        keys("Down")
        assertFocused("the match") { it?.contains("Channel 7") == true }
        keys("OK")
        waitFor("server.watchedChannels.size == 2", WAIT_MS) { server.watchedChannels.size == 2 }
        assertTrue(opened in server.watchedChannels)
    }

    @Test
    fun `a search nothing matches says so`() = journey {
        openPicker()
        keys("Up")
        if (focusedLabel() != "Search") keys("Left")
        assertFocused("Search")
        keys("OK")
        assertFocused("Channel, number or program")
        ui.onNode(hasSetTextAction()).performTextInput("zzzz")
        ui.waitForIdle()
        ui.onAllNodes(hasText("No channel matches")).onFirst().assertIsDisplayed()
        assertEquals(1, server.watchedChannels.size, "searching ticks nothing")
    }

    /** From the guide, the focused channel full screen, then its bar's Multiview button pressed. */
    private fun AppDriver.openPicker() {
        assertFocused("a guide cell") { it?.startsWith("Show at") == true }
        keys("OK")
        waitFor("server.watching", WAIT_MS) { server.watching }
        keys("Right")
        if (focusedLabel() != "Channels") keys("Right")
        assertFocused("Channels")
        var presses = 0
        while (focusedLabel() != "Multiview" && presses++ < BAR_BUTTONS) keys("Right")
        assertFocused("Multiview")
        keys("OK")
        assertFocused("the channel watched") { it?.contains("Channel") == true }
        waitFor("server.watchedChannels.size == 1", WAIT_MS) { server.watchedChannels.size == 1 }
    }

    private fun journey(block: AppDriver.() -> Unit) =
        runSofaKing(tv = true, File("build/journeys", "multiview-picker"), block = block)

    private companion object {
        const val MAX_PANES = 4
        const val BAR_BUTTONS = 14
        const val WAIT_MS = 10_000L
    }
}

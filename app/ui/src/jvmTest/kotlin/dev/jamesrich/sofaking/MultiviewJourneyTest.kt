package dev.jamesrich.sofaking

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Multiview on the TV, driven with the remote: from the live player's bar, through the picker, to the panes. */
class MultiviewJourneyTest {
    @Test
    fun `a ticked channel plays in a pane of its own and the arrows move the sound without a new session`() = journey {
        openPicker()
        val opened = server.watchedChannels.single()
        keys("Down", "OK")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannels.size == 2 }
        val other = server.watchedChannels.single { it != opened }
        keys("Right")
        assertFocused("the first pane, with the sound") { it?.endsWith("playing sound") == true }
        val heard = focusedLabel()
        val sessions = server.sessionsOpened.get()
        keys("Right")
        assertFocused("the second pane, with the sound") { it?.endsWith("playing sound") == true && it != heard }
        assertEquals(sessions, server.sessionsOpened.get(), "moving the sound opens nothing")
        assertEquals(setOf(opened, other), server.watchedChannels.toSet())
        keys("OK")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannels == listOf(other) }
    }

    @Test
    fun `Watch puts the list away and Back returns to it then to the live player`() = journey {
        openPicker()
        val opened = server.watchedChannels.single()
        keys("Down", "OK")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannels.size == 2 }
        keys("Up", "Up", "Up")
        assertFocused("Watch 2 channels")
        keys("OK")
        assertFocused("the pane with the sound") { it?.endsWith("playing sound") == true }
        keys("Back")
        assertFocused("the first channel") { it?.contains("Channel") == true }
        keys("Back")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannels == listOf(opened) }
    }

    @Test
    fun `unticking a channel closes its pane and frees its session`() = journey {
        openPicker()
        val opened = server.watchedChannels.single()
        keys("Down", "OK")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannels.size == 2 }
        keys("OK")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannels == listOf(opened) }
    }

    /** From the guide, the focused channel full screen, then its bar's Multiview button pressed. */
    private fun AppDriver.openPicker() {
        assertFocused("a guide cell") { it?.startsWith("Show at") == true }
        keys("OK")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.watching }
        // Down zaps on live TV; Right brings the bar up, and Right again enters its buttons.
        keys("Right")
        if (focusedLabel() != "Channels") keys("Right")
        assertFocused("Channels")
        var presses = 0
        while (focusedLabel() != "Multiview" && presses++ < BAR_BUTTONS) keys("Right")
        assertFocused("Multiview")
        keys("OK")
        assertFocused("the channel watched") { it?.contains("Channel") == true }
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannels.size == 1 }
    }

    private fun journey(block: AppDriver.() -> Unit) =
        runSofaKing(tv = true, File("build/journeys", "multiview"), block = block)

    private companion object {
        const val BAR_BUTTONS = 14
        const val WAIT_MS = 10_000L
    }
}

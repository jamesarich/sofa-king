package dev.jamesrich.sofaking

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Multiview on the TV, driven with the remote: from the live player's bar, through the picker, to the picture. */
class MultiviewJourneyTest {
    @Test
    fun `the bar opens the picker and Watch plays two channels whose sound the arrows move`() = journey {
        openPicker()
        keys("Down", "OK")
        keys("Up", "Up")
        assertFocused("Watch 2 channels")
        keys("OK")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.multiviews.size == 1 }
        val (channels, audio) = server.multiviews.values.single()
        assertEquals(2, channels.size)
        assertEquals(0, audio)
        keys("Right")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.multiviews.values.singleOrNull()?.second == 1 }
        keys("OK")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.multiviews.isEmpty() && server.watching }
        assertFocused("the live player") { it != null && "Watch" !in it }
    }

    @Test
    fun `Back from the picture returns to the picker and Back again to the live player`() = journey {
        openPicker()
        keys("Down", "OK", "Up", "Up", "OK")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.multiviews.size == 1 }
        keys("Back")
        assertFocused("the first channel") { it?.contains("Channel") == true }
        keys("Back")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.multiviews.isEmpty() && server.watching }
    }

    @Test
    fun `a server that cannot draw multiview leaves the button off the bar`() =
        runSofaKing(tv = true, File("build/journeys", "multiview-off")) {
            openPlayerBar()
            val seen = mutableListOf<String?>()
            repeat(BAR_BUTTONS) {
                seen += focusedLabel()
                keys("Right")
            }
            assertTrue("Multiview" !in seen, seen.toString())
        }

    /** From the guide, the focused channel full screen, then its bar's Multiview button pressed. */
    private fun AppDriver.openPicker() {
        openPlayerBar()
        var presses = 0
        while (focusedLabel() != "Multiview" && presses++ < BAR_BUTTONS) keys("Right")
        assertFocused("Multiview")
        keys("OK")
        assertFocused("the channel watched") { it?.contains("Channel") == true }
    }

    private fun AppDriver.openPlayerBar() {
        assertFocused("a guide cell") { it?.startsWith("Show at") == true }
        keys("OK")
        ui.waitUntil(timeoutMillis = WAIT_MS) { server.watching }
        // Down zaps on live TV; Right brings the bar up, and Right again enters its buttons.
        keys("Right")
        if (focusedLabel() != "Channels") keys("Right")
        assertFocused("Channels")
    }

    private fun journey(block: AppDriver.() -> Unit) =
        runSofaKing(tv = true, File("build/journeys", "multiview"), FakeServer(multiview = true), block)

    private companion object {
        const val BAR_BUTTONS = 14
        const val WAIT_MS = 10_000L
    }
}

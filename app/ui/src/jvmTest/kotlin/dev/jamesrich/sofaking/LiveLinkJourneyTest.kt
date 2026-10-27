package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppLink
import java.io.File
import kotlin.test.Test

/** A `sofaking://live` link on the TV, from a shortcut or the assistant, while a channel already plays full screen. */
class LiveLinkJourneyTest {
    @Test
    fun `a live link while the player is open tunes the linked channel`() =
        runSofaKing(tv = true, File("build/journeys", "live-link")) {
            assertFocused("a guide cell") { it?.startsWith("Show at") == true }
            keys("OK")
            ui.waitUntil(timeoutMillis = WAIT_MS) { server.watching }
            // The fake server's channels are numbered from 2, so channel 5 is ch3, which the guide does not open.
            check(server.watchedChannel != "ch3") { "the guide opened the linked channel itself" }
            openLink(AppLink.Live("5"))
            ui.waitUntil(timeoutMillis = WAIT_MS) { server.watchedChannel == "ch3" }
        }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}

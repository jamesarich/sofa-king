package dev.jamesrich.sofaking

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import java.io.File
import kotlin.test.Test

/**
 * A device the server turned away (a revoked token, a 401 on every route) on the TV: Live says so, and focus lands on
 * the header's Settings button, the one place to pair again, rather than on Retry or nowhere.
 */
@OptIn(ExperimentalTestApi::class)
class SignedOutJourneyTest {
    @Test
    fun `a signed-out device says so on Live and focuses Settings, with no other tab offered`() = signedOut {
        assertFocused("Settings")
        assertSignedOut()
        keys("Left")
        assertFocused("Live")
        keys("Right")
        assertFocused("Settings")
    }

    @Test
    fun `OK on Settings opens it for pairing again and Back returns to the signed-out tab`() = signedOut {
        assertFocused("Settings")
        keys("OK")
        assertFocused("the Live TV row") { it?.startsWith("Live TV") == true }
        keys("Back")
        assertFocused("Settings")
        assertSignedOut()
    }

    private fun AppDriver.assertSignedOut() {
        ui.waitUntil(timeoutMillis = AppDriver.SETTLE_MS) {
            ui.onAllNodes(hasText("This device is signed out")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun signedOut(block: AppDriver.() -> Unit) = runSofaKing(
        tv = true,
        out = File("build/journeys", "signed-out"),
        server = FakeServer(signedOut = true),
        ready = { "GET channels" in server.recentRequests(1) && focusedLabel() == "Settings" },
        block = block,
    )
}

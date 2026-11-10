package dev.jamesrich.sofaking

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.jamesrich.sofaking.data.PairingRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `sofaking://pair` link that names another server than the one this TV uses: the app asks first, with focus on
 * Cancel so a stray OK keeps the saved server, and only Switch moves the device.
 */
@OptIn(ExperimentalTestApi::class)
class PairingLinkJourneyTest {
    @Test
    fun `a link to another server asks first, focuses Cancel and Back keeps the saved server`() =
        pairedWithOther { other ->
            openPairing(PairingRequest(other.url, "654321"))
            assertFocused("Cancel")
            keys("Back")
            assertGuideFocused()
            assertEquals(0, other.guideQueries.get(), "the other server was queried after Cancel")
            assertTrue(other.recentRequests(1).isEmpty(), "the device paired with the other server after Back")
        }

    @Test
    fun `OK on the default Cancel keeps the saved server and Switch moves to the new one`() = pairedWithOther { other ->
        openPairing(PairingRequest(other.url, "654321"))
        assertFocused("Cancel")
        keys("OK")
        assertGuideFocused()
        assertTrue(other.recentRequests(1).isEmpty(), "OK on Cancel paired with the other server")
        openPairing(PairingRequest(other.url, "111111"))
        assertFocused("Cancel")
        ui.onNodeWithText("Switch").performClick()
        ui.waitUntil(timeoutMillis = SWITCH_MS) { other.guideQueries.get() > 0 }
        assertGuideFocused()
    }

    private fun AppDriver.assertGuideFocused() = assertFocused("a guide cell") { it?.startsWith("Show at") == true }

    private fun pairedWithOther(block: AppDriver.(other: FakeServer) -> Unit) {
        val other = FakeServer()
        try {
            runSofaKing(tv = true, File("build/journeys", "pairing-link")) { block(other) }
        } finally {
            other.stop()
        }
    }

    private companion object {
        const val SWITCH_MS = 15_000L
    }
}

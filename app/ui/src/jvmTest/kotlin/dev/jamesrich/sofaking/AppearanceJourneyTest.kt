package dev.jamesrich.sofaking

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import java.io.File
import kotlin.test.Test

/**
 * The Appearance row on the TV (#700): the list of Settings pages ends with it, offering System and Prevue only (a TV
 * is always dark), the remote reaches Prevue and picks it, and the pick is still there after Settings is left and
 * opened again.
 */
@OptIn(ExperimentalTestApi::class)
class AppearanceJourneyTest {
    @Test
    fun `the TV offers System and Prevue, OK picks Prevue and it stays picked after leaving Settings`() =
        runSofaKing(tv = true, File("build/journeys", "appearance")) {
            openSettings()
            reachAppearance()
            assertSelected("System")
            noSegment("Dark")
            noSegment("Light")
            keys("Right")
            assertFocused("Prevue")
            keys("OK")
            assertSelected("Prevue")
            keys("Back")
            assertFocused("Settings")
            keys("OK")
            assertFocused("the Live TV row") { it?.startsWith("Live TV") == true }
            reachAppearance()
            assertFocused("Prevue")
            assertSelected("Prevue")
        }

    private fun AppDriver.openSettings() {
        assertFocused("a guide cell") { it?.startsWith("Show at") == true }
        keys("Back", "Back", "Right", "Right", "Right", "Right")
        assertFocused("Settings")
        keys("OK")
        assertFocused("the Live TV row") { it?.startsWith("Live TV") == true }
    }

    /** Presses Down until focus lands on the Appearance row's selected segment, which the pages list ends with. */
    private fun AppDriver.reachAppearance() {
        repeat(MAX_ROWS) {
            if (focusedLabel() != "System" && focusedLabel() != "Prevue") keys("Down")
        }
        if (focusedLabel() != "System" && focusedLabel() != "Prevue") {
            failWith("Down from the first page row never reached the Appearance row")
        }
    }

    private fun AppDriver.assertSelected(label: String) {
        val selected = runCatching {
            ui.waitUntil(timeoutMillis = AppDriver.SETTLE_MS) {
                ui.onAllNodes(hasText(label) and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        if (!selected) failWith("expected $label selected in the Appearance row")
    }

    private fun AppDriver.noSegment(label: String) {
        if (ui.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty()) {
            failWith("a TV offers $label, which it cannot draw")
        }
    }

    private companion object {
        const val MAX_ROWS = 20
    }
}

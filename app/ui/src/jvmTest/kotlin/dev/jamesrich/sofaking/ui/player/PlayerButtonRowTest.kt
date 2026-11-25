package dev.jamesrich.sofaking.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PlayerButtonRowTest {
    private fun button(label: String) = PlayerButtonItem(Icons.Filled.PlayArrow, label, {})

    private val more = PlayerButtonItem(
        Icons.Filled.PlayArrow,
        "More",
        {},
        menu = listOf(PlayerMenuEntry(Icons.Filled.PlayArrow, "Sleep timer", {}, keepOpen = true)),
    )

    @Test
    fun `an open More menu stays up when a button comes to the front of the row`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            var items by mutableStateOf(listOf(button("Captions"), more, button("Start over"), button("Next")))
            setContent {
                SofaKingTheme {
                    PlayerButtonRow(items, remember { FocusRequester() }, onFocus = {}, showLabel = false)
                }
            }
            onNodeWithContentDescription("More").performClick()
            waitForIdle()
            onNodeWithText("Sleep timer").assertIsDisplayed()

            // As the credits start, Next leads the row and the buttons after it move along.
            items = listOf(button("Next"), button("Captions"), more, button("Start over"))
            waitForIdle()
            onNodeWithText("Sleep timer").assertIsDisplayed()
        }

    @Test
    fun `Back from an open More menu returns focus to More after the row reorders`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            var items by mutableStateOf(listOf(button("Captions"), more, button("Start over"), button("Next")))
            val entry = FocusRequester()
            setContent {
                SofaKingTheme {
                    PlayerButtonRow(items, entry, onFocus = {}, showLabel = false)
                }
            }
            onNodeWithContentDescription("More").requestFocus()
            waitForIdle()
            onNodeWithContentDescription("More").performClick()
            waitForIdle()
            onNodeWithText("Sleep timer").assertIsDisplayed()

            items = listOf(button("Next"), button("Captions"), more, button("Start over"))
            waitForIdle()
            onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithContentDescription("More").assertIsFocused()
        }

    @Test
    fun `the caption under the bar follows More when the row reorders`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            var items by mutableStateOf(listOf(more, button("Captions"), button("Start over"), button("Next")))
            setContent {
                SofaKingTheme {
                    PlayerButtonRow(items, remember { FocusRequester() }, onFocus = {}, showLabel = true)
                }
            }
            onNodeWithContentDescription("More").requestFocus()
            waitForIdle()
            onNodeWithText("More").assertIsDisplayed()

            items = listOf(button("Next"), button("Captions"), more, button("Start over"))
            waitForIdle()
            onNodeWithText("More").assertIsDisplayed()
        }
}

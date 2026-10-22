package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Buttons that turn off or go away under a D-pad press keep focus on the page instead of dropping it to another row. */
@OptIn(ExperimentalTestApi::class)
class SettingsButtonFocusTest {
    private fun ComposeUiTest.show(content: @Composable () -> Unit) {
        setContent {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.pressOk() {
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
    }

    @Test
    fun `a button turned off by its own press keeps focus until the D-pad moves on`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        var presses = 0
        show {
            var enabled by remember { mutableStateOf(true) }
            Column {
                SofaButton(onClick = { presses++; enabled = false }, enabled = enabled) { Text("Start") }
                SofaButton(onClick = {}) { Text("Next") }
            }
        }
        onNodeWithText("Start").requestFocus()
        waitForIdle()
        pressOk()
        onNodeWithText("Start").assertIsFocused().assertIsNotEnabled()
        pressOk()
        assertEquals(1, presses, "a disabled button ignores OK")
        onRoot().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithText("Next").assertIsFocused()
        // Once focus leaves, the disabled button drops out of the focus order.
        onNodeWithText("Start").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
    }

    @Test
    fun `setting a PIN focuses Remove the PIN`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        var locked by mutableStateOf(false)
        show { LockSection(locked = locked, onPinChanged = { locked = it != null }) }
        onNodeWithText("Set a PIN").requestFocus()
        waitForIdle()
        pressOk()
        val fields = onAllNodes(hasSetTextAction())
        fields[0].assertIsFocused().performTextInput("2468")
        fields[1].performTextInput("2468")
        onNodeWithText("Lock settings").requestFocus()
        waitForIdle()
        pressOk()
        assertEquals(true, locked)
        onNodeWithText("Remove the PIN").assertIsFocused()
        pressOk()
        assertEquals(false, locked)
        onNodeWithText("Set a PIN").assertIsFocused()
    }

    @Test
    fun `moving a favorite to the top hands focus to its Down button`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        val channels = listOf(
            Channel(guideId = "a", number = 2.0, name = "Alpha", logo = null, groups = emptyList()),
            Channel(guideId = "b", number = 3.0, name = "Beta", logo = null, groups = emptyList()),
        )
        var order by mutableStateOf(listOf("a", "b"))
        show {
            ChannelManagerScreen(
                channels = channels,
                favorites = setOf("a", "b"),
                favoritesOrder = order,
                hidden = emptySet(),
                zapSkipsHidden = false,
                onFavoritesOrderChanged = { order = it },
                onChannelHiddenChanged = { _, _ -> },
                onChannelsHiddenChanged = { _, _ -> },
                onZapSkipsHiddenChanged = {},
                onBack = {},
            )
        }
        onNodeWithContentDescription("Beta up", substring = true).requestFocus()
        waitForIdle()
        pressOk()
        assertEquals(listOf("b", "a"), order)
        onNodeWithContentDescription("Beta down", substring = true).assertIsFocused()
    }
}

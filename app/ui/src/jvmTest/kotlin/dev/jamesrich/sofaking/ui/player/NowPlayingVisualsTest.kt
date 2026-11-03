package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The visualizer's buttons and full screen on Now playing (#487, #633): Next look and Full screen appear with the
 * visualizer, and in full screen any key or click on the TV brings the controls back.
 */
@OptIn(ExperimentalTestApi::class)
class NowPlayingVisualsTest {
    private fun ComposeUiTest.themed(content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalLayoutProfile provides LayoutProfile.Tv) {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `Next look and Full screen appear with the visualizer and do what they say`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            var shown by mutableStateOf(false)
            var looks = 0
            var fullscreens = 0
            themed {
                Row {
                    VisualToggles(VisualsChoice(shown, { shown = !shown }, { looks++ }, { fullscreens++ }))
                }
            }
            onNodeWithContentDescription("Visualizer").assertIsOff()
            onAllNodesWithContentDescription("Next look").assertCountEquals(0)
            onAllNodesWithContentDescription("Full screen").assertCountEquals(0)
            onNodeWithContentDescription("Visualizer").performClick()
            onNodeWithContentDescription("Visualizer").assertIsOn()
            onNodeWithContentDescription("Next look").performClick()
            onNodeWithContentDescription("Full screen").performClick()
            assertEquals(1, looks)
            assertEquals(1, fullscreens)
            onNodeWithContentDescription("Visualizer").performClick()
            onAllNodesWithContentDescription("Next look").assertCountEquals(0)
        }

    @Test
    fun `in full screen a key brings the controls back`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        var bare by mutableStateOf(true)
        themed { ControlsOrFullscreen(bare, scrimmed = true, exit = { bare = false }) { Text("Controls") } }
        onAllNodesWithText("Controls").assertCountEquals(0)
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onAllNodesWithText("Controls").assertCountEquals(1)
    }

    @Test
    fun `in full screen a click brings the controls back`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        var bare by mutableStateOf(true)
        themed { ControlsOrFullscreen(bare, scrimmed = true, exit = { bare = false }) { Text("Controls") } }
        onAllNodesWithText("Controls").assertCountEquals(0)
        onRoot().performClick()
        waitForIdle()
        onAllNodesWithText("Controls").assertCountEquals(1)
    }
}

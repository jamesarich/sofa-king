package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test

/**
 * The startup channel list on a TV opens on the picked channel, however far down the lineup it is: the list composes
 * only the rows on screen, so it scrolls to the pick before focusing it, or focus fell back to the top.
 */
@OptIn(ExperimentalTestApi::class)
class ChannelPickerFocusTest {
    private val channels = (1..80).map { n ->
        Channel(guideId = "c$n", number = n + 1.0, name = "Channel $n", logo = null, groups = emptyList())
    }

    private fun ComposeUiTest.showPicker(selected: String?) {
        setContent {
            CompositionLocalProvider(LocalLayoutProfile provides LayoutProfile.Tv) {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ChannelPickerScreen(channels, selected, onPick = {}, onBack = {})
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `the list opens on a pick far down the lineup`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        showPicker(selected = "c64")
        onNodeWithText("65  Channel 64").assertIsDisplayed().assertIsFocused()
        onNodeWithText("Open on start").assertIsDisplayed()
    }

    @Test
    fun `with no pick a TV list opens on its first channel`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        showPicker(selected = null)
        onNodeWithText("2  Channel 1").assertIsDisplayed().assertIsFocused()
    }
}

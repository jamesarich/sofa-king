package dev.jamesrich.sofaking.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LastInput
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.TV_PROPERTY
import dev.jamesrich.sofaking.ui.trackLastInput
import kotlin.test.Test

/** A Google TV pointer remote, or a mouse on a TV: the cursor moving over a button focuses it; off a TV it does not. */
@OptIn(ExperimentalTestApi::class)
class TvFocusHoverTest {
    private fun hover(tv: Boolean, focused: Boolean) {
        val before = System.getProperty(TV_PROPERTY)
        System.setProperty(TV_PROPERTY, tv.toString())
        try {
            runSkikoComposeUiTest {
                setContent {
                    val last = remember { LastInput(InputKind.KEYS) }
                    CompositionLocalProvider(LocalLastInput provides last) {
                        SofaKingTheme {
                            Column(Modifier.trackLastInput(last)) {
                                SofaButton(onClick = {}) { Text("First") }
                                SofaButton(onClick = {}) { Text("Second") }
                            }
                        }
                    }
                }
                onNodeWithText("Second").performMouseInput {
                    moveTo(topLeft)
                    moveTo(center)
                }
                waitForIdle()
                if (focused) {
                    onNodeWithText("Second").assertIsFocused()
                } else {
                    onNodeWithText("Second").assertIsNotFocused()
                }
            }
        } finally {
            if (before == null) System.clearProperty(TV_PROPERTY) else System.setProperty(TV_PROPERTY, before)
        }
    }

    @Test
    fun `on a TV the cursor moving over a button focuses it`() = hover(tv = true, focused = true)

    @Test
    fun `off a TV hovering focuses nothing`() = hover(tv = false, focused = false)
}

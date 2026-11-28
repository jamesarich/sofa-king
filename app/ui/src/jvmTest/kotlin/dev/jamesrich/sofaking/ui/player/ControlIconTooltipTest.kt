package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import dev.jamesrich.sofaking.ui.InputKind
import dev.jamesrich.sofaking.ui.LastInput
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test

/** A player button's name: a tooltip for a mouse or a finger, and none for a remote, which reads it under the row. */
@OptIn(ExperimentalTestApi::class)
class ControlIconTooltipTest {
    private fun SkikoComposeUiTest.show(kind: InputKind) = setContent {
        CompositionLocalProvider(LocalLastInput provides LastInput(kind)) {
            SofaKingTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ControlIcon(Icons.Filled.Info, NAME, onClick = {})
                }
            }
        }
    }

    @Test
    fun aMouseRestingOnAControlShowsItsName() = runSkikoComposeUiTest(Size(400f, 300f)) {
        show(InputKind.POINTER)
        onNodeWithText(NAME).assertDoesNotExist()
        onNodeWithContentDescription(NAME).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(HOVER_MS)
        onNodeWithText(NAME).assertExists()
    }

    @Test
    fun aRemoteFocusingAControlShowsNoTooltip() = runSkikoComposeUiTest(Size(400f, 300f)) {
        show(InputKind.KEYS)
        onNodeWithContentDescription(NAME).requestFocus()
        mainClock.advanceTimeBy(HOVER_MS)
        onNodeWithText(NAME).assertDoesNotExist()
    }

    private companion object {
        const val NAME = "Stream info"
        const val HOVER_MS = 2_000L
    }
}

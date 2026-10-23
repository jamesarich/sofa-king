package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.SubtitleLook
import dev.jamesrich.sofaking.data.SubtitleRaise
import dev.jamesrich.sofaking.data.SubtitleSize
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SubtitleLookTest {
    @Test
    fun `a larger size draws taller subtitles and a raise lifts them`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(1f)) {
        var look by mutableStateOf(SubtitleLook(SubtitleSize.SMALL))
        setContent {
            SofaKingTheme {
                Box(Modifier.fillMaxSize()) { SubtitleText("Where are we going", bottom = 24.dp + look.raise.height, look = look) }
            }
        }
        val small = onNodeWithText("Where are we going").getBoundsInRoot()
        look = SubtitleLook(SubtitleSize.LARGE)
        waitForIdle()
        val large = onNodeWithText("Where are we going").getBoundsInRoot()
        assertTrue(large.bottom - large.top > small.bottom - small.top, "large $large, small $small")
        look = SubtitleLook(SubtitleSize.LARGE, SubtitleRaise.HIGH)
        waitForIdle()
        val raised = onNodeWithText("Where are we going").getBoundsInRoot()
        assertTrue(raised.bottom < large.bottom - 100.dp, "raised $raised, at the bottom $large")
    }
}

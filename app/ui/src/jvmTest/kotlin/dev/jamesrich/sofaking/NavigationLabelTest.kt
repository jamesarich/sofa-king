package dev.jamesrich.sofaking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The navigation suite's labels away from a TV each fit on one line, in the rail and in the bar. The tab once named
 * Recordings wrapped as "Recording / s" in the 96 dp rail on Linux and iPad, whose fonts set it wider than the item
 * at the theme's 18 sp. A monospaced font stands in for those wide fonts, so the test fails the same way anywhere.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationLabelTest {
    private val labels = listOf("Live", "Library", "DVR", "Settings")

    private fun ComposeUiTest.showTabs(font: FontFamily?) {
        setContent {
            SofaKingTheme {
                val base = MaterialTheme.typography
                val typography =
                    if (font == null) {
                        base
                    } else {
                        base.copy(
                            labelMedium = base.labelMedium.copy(fontFamily = font),
                            labelLarge = base.labelLarge.copy(fontFamily = font),
                        )
                    }
                MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = typography) {
                    TopLevel(
                        tv = false,
                        tabs = TabsModel(Tab.LIVE, libraryShown = true, recordingsShown = true, onTab = {}),
                        shown = true,
                        settingsOpen = false,
                        onSettings = {},
                        settingsFocus = remember { FocusRequester() },
                        tabFocus = remember { FocusRequester() },
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.layoutOf(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun ComposeUiTest.assertOneLineEach(where: String, fits: Boolean = true) {
        for (label in labels) {
            val layout = layoutOf(label)
            assertEquals(1, layout.lineCount, "$label wraps in the $where")
            val width = layout.multiParagraph.width
            if (fits) {
                assertTrue(
                    width <= layout.layoutInput.constraints.maxWidth,
                    "$label runs past its item in the $where",
                )
            }
        }
    }

    /** A tablet or desktop window: the collapsed rail, 96 dp wide. */
    private fun rail(font: FontFamily?) = runSkikoComposeUiTest(Size(2560f, 1600f), Density(2f)) {
        showTabs(font)
        val bounds = onNodeWithText("DVR", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(bounds.right <= 96.dp, "DVR sits in the rail: $bounds")
        assertOneLineEach("rail")
    }

    /** A phone held upright: the short bar, a quarter of 360 dp each. */
    private fun bar(font: FontFamily?) = runSkikoComposeUiTest(Size(720f, 1600f), Density(2f)) {
        showTabs(font)
        val bounds = onNodeWithText("DVR", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(bounds.top > 700.dp, "DVR sits in the bottom bar: $bounds")
        assertOneLineEach("bar")
    }

    @Test
    fun `labels fit on one line in the rail`() = rail(font = null)

    @Test
    fun `labels fit on one line in the rail in a wide font`() = rail(FontFamily.Monospace)

    @Test
    fun `labels fit on one line in the bar on a 360 dp phone`() = bar(font = null)

    @Test
    fun `labels fit on one line in the bar in a wide font`() = bar(FontFamily.Monospace)

    /** At a larger text size a label may run past its item's edge, but it never breaks mid-word. */
    @Test
    fun `labels stay on one line in the rail at a larger text size`() =
        runSkikoComposeUiTest(Size(2560f, 1600f), Density(2f, fontScale = 1.5f)) {
            showTabs(FontFamily.Monospace)
            assertOneLineEach("rail", fits = false)
        }
}

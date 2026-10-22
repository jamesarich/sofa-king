package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.PublishedBuild
import dev.jamesrich.sofaking.data.UpdateState
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * About, the last block of Settings, as a TV reaches it: focus on its last button scrolls only as far as that button,
 * and Check again turned itself off while checking, which dropped focus to another row.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsAboutFocusTest {
    private val latest = PublishedBuild(version = "0.1.0-dev.400+abc1234", run = 400, pageUrl = "https://example.invalid/release", apkUrl = null)
    private val checkAgain = "Check again"
    private val tmdbNotice = "This product uses the TMDB API"

    private fun ComposeUiTest.showSettings(update: () -> UpdateState?, onCheck: (again: Boolean) -> Unit = {}) {
        setContent {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(
                        settings = AppSettings(serverUrl = "http://sofa.lan:9193", apiToken = "s3cret-token"),
                        onSave = { _, _ -> },
                        onBack = {},
                        channelCount = 0,
                        actions = SettingsActions(onCheckForUpdate = { _, again -> onCheck(again) }),
                        update = update(),
                    )
                }
            }
        }
        waitForIdle()
    }

    /** Scrolls to the end, then back by the notice's height, so Check again shows and the notice under it does not. */
    private fun ComposeUiTest.hideNoticeUnderTheButton() {
        val list = onNode(hasScrollAction())
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        waitForIdle()
        val notice = onNodeWithText(tmdbNotice, substring = true).getUnclippedBoundsInRoot()
        val shift = with(density) { (notice.bottom - notice.top).toPx() }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -shift) }
        waitForIdle()
        assertTrue(!noticeShown(), "the setup leaves the notice below the visible list")
    }

    private fun ComposeUiTest.noticeShown(): Boolean {
        val list = onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        val notice = onNodeWithText(tmdbNotice, substring = true).getUnclippedBoundsInRoot()
        return notice.top >= list.top && notice.bottom <= list.bottom
    }

    @Test
    fun `focus on Check again brings the notice under it into view`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        showSettings(update = { UpdateState.UpToDate(latest) })
        hideNoticeUnderTheButton()
        onNodeWithText(checkAgain).requestFocus()
        waitForIdle()
        assertTrue(noticeShown(), "the page's last line shows once focus reaches its last button")
    }

    @Test
    fun `Check again keeps focus while the check runs`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        var update by mutableStateOf<UpdateState?>(UpdateState.UpToDate(latest))
        var checks = 0
        showSettings(update = { update }, onCheck = { again ->
            if (again) {
                checks++
                update = UpdateState.Checking
            }
        })
        hideNoticeUnderTheButton()
        onNodeWithText(checkAgain).requestFocus()
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(UpdateState.Checking, update)
        onNodeWithText(checkAgain).assertIsFocused()
        // A press while the check runs starts no second one.
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, checks)
        update = UpdateState.UpToDate(latest)
        waitForIdle()
        onNodeWithText(checkAgain).assertIsFocused()
    }
}

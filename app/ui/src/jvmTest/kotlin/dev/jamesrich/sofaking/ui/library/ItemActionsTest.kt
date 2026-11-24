package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ItemActionsTest {
    private val movie = MediaItem("m1", "movies", ItemKind.MOVIE, "A movie", year = 2001)

    private fun show(
        tv: Boolean,
        listed: Boolean,
        watched: MutableList<Boolean>,
        onListed: () -> Unit,
        block: ComposeUiTest.() -> Unit,
    ) = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        var onList by mutableStateOf(listed)
        val actions = ItemDetailsActions(
            onPlay = { _, _ -> },
            onSetWatched = { _, value -> watched += value },
            onSelectSeason = {},
            onRetry = {},
            onToggleWatchlist = {
                onListed()
                onList = !onList
            },
            watchlisted = { onList },
        )
        val profile = if (tv) {
            LayoutProfile.Tv
        } else {
            LayoutProfile(
                tv = false,
                compactWidth = false,
                compactHeight = false,
            )
        }
        setContent {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalLayoutProfile provides profile) {
                        Summary(MediaItemDetail(movie), actions, FocusRequester(), hideSpoilers = false)
                    }
                }
            }
        }
        waitForIdle()
        block()
    }

    @Test
    fun `a window that is not a TV offers watched and the watchlist as icon toggles`() {
        val watched = mutableListOf<Boolean>()
        var toggled = 0
        show(tv = false, listed = false, watched, { toggled++ }) {
            // No text buttons beside Play: the toggles are named for a screen reader and the tooltip only.
            onNodeWithText("Mark watched").assertDoesNotExist()
            val mark = onNodeWithContentDescription("Mark watched")
            mark.assertIsOff()
            mark.performClick()
            val list = onNodeWithContentDescription("Add to watchlist")
            list.assertIsOff()
            list.performClick()
        }
        assertEquals(listOf(true), watched)
        assertEquals(1, toggled)
    }

    @Test
    fun `a TV keeps the text buttons its remote reads`() {
        val watched = mutableListOf<Boolean>()
        show(tv = true, listed = true, watched, {}) {
            onNodeWithText("Remove from watchlist").assertExists()
            onNodeWithText("Mark watched").performClick()
        }
        assertEquals(listOf(true), watched)
    }
}

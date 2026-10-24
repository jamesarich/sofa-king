package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.ItemDetailState
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.PlayProgress
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ItemDetailsSpoilersTest {
    private val season = MediaItem("s1", "shows", ItemKind.SEASON, "Season 1", season = 1)
    private fun episode(number: Int, progress: PlayProgress?) = MediaItem(
        "e$number", "shows", ItemKind.EPISODE, "Episode $number",
        parentId = "s1", season = 1, episode = number, overview = "What happens in $number", progress = progress,
    )
    private val detail = MediaItemDetail(
        item = season,
        children = listOf(
            episode(1, PlayProgress(0, 2_700_000, watched = true)),
            episode(2, PlayProgress(600_000, 2_700_000, watched = false)),
            episode(3, null),
            episode(4, null),
        ),
    )

    private fun show(hideSpoilers: Boolean) = runSkikoComposeUiTest(Size(1920f, 4000f), Density(1f)) {
        setContent {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ItemDetailsScreen(
                        ItemDetailState.Ready(detail),
                        season = null,
                        selectedSeasonId = null,
                        actions = ItemDetailsActions({
                                _,
                                _,
                            ->
                        }, { _, _ -> }, {}, {}),
                        hideSpoilers = hideSpoilers,
                    )
                }
            }
        }
        val shown = if (hideSpoilers) 0 else 1
        onAllNodesWithText("What happens in 1").assertCountEquals(1)
        onAllNodesWithText("What happens in 2").assertCountEquals(1)
        onAllNodesWithText("What happens in 3").assertCountEquals(shown)
        onAllNodesWithText("What happens in 4").assertCountEquals(shown)
        onAllNodesWithText("Unwatched episode", substring = true).assertCountEquals(2 - 2 * shown)
    }

    @Test
    fun `Hide spoilers keeps back the overviews of episodes not started`() = show(hideSpoilers = true)

    @Test
    fun `episodes show every overview by default`() = show(hideSpoilers = false)
}

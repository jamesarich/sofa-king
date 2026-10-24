package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.ItemDetailState
import dev.jamesrich.sofaking.data.ExtraKind
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ItemDetailsExtrasTest {
    private val movie = MediaItem("m1", "movies", ItemKind.MOVIE, "Alien", year = 1979, genres = listOf("Horror"))
    private val detail = MediaItemDetail(
        item = movie,
        extras = listOf(
            MediaItem("x1", "movies", ItemKind.EXTRA, "Trailer", parentId = "m1", extra = ExtraKind.TRAILER),
            MediaItem(
                "x2",
                "movies",
                ItemKind.EXTRA,
                "The Beast Within",
                parentId = "m1",
                extra = ExtraKind.BEHIND_THE_SCENES,
            ),
        ),
        related = listOf(MediaItem("m2", "movies", ItemKind.MOVIE, "Prometheus", year = 2012)),
    )

    @Test
    fun `a movie's page lists its extras, which play, and more like it, which open`() =
        runSkikoComposeUiTest(Size(1920f, 3000f), Density(1f)) {
            val played = mutableListOf<String>()
            val opened = mutableListOf<String>()
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ItemDetailsScreen(
                            ItemDetailState.Ready(detail),
                            season = null,
                            selectedSeasonId = null,
                            actions = ItemDetailsActions({ item, _ ->
                                played += item.id
                            }, { _, _ -> }, {}, {}, onOpenItem = {
                                opened +=
                                    it.id
                            }),
                        )
                    }
                }
            }
            onAllNodesWithText("Extras").assertCountEquals(1)
            onAllNodesWithText("More like this").assertCountEquals(1)
            onNode(hasContentDescription("The Beast Within, Behind the scenes")).performClick()
            onNode(hasContentDescription("Prometheus, 2012")).performClick()
            assertEquals(listOf("x2"), played)
            assertEquals(listOf("m2"), opened)
        }

    @Test
    fun `back from a title like it lands on its card`() = runSkikoComposeUiTest(Size(1920f, 3000f), Density(1f)) {
        setContent {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ItemDetailsScreen(
                        ItemDetailState.Ready(detail),
                        season = null,
                        selectedSeasonId = null,
                        actions = ItemDetailsActions({ _, _ -> }, { _, _ -> }, {}, {}),
                        returnedFrom = "m2",
                    )
                }
            }
        }
        waitForIdle()
        onNode(hasContentDescription("Prometheus, 2012")).assertIsFocused()
    }

    @Test
    fun `a page with no extras or related titles shows neither row`() =
        runSkikoComposeUiTest(Size(1920f, 3000f), Density(1f)) {
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ItemDetailsScreen(
                            ItemDetailState.Ready(MediaItemDetail(movie)),
                            season = null,
                            selectedSeasonId = null,
                            actions = ItemDetailsActions({
                                    _,
                                    _,
                                ->
                            }, { _, _ -> }, {}, {}),
                        )
                    }
                }
            }
            onAllNodesWithText("Extras").assertCountEquals(0)
            onAllNodesWithText("More like this").assertCountEquals(0)
        }
}

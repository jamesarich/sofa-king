package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.ItemDetailState
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.ItemScore
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ItemDetailsRatingsTest {
    private val movie = MediaItem("m1", "movies", ItemKind.MOVIE, "Heat", year = 1995, rating = 7.5)

    private fun page(detail: MediaItemDetail, check: ComposeUiTest.() -> Unit) =
        runSkikoComposeUiTest(Size(1920f, 1500f), Density(1f)) {
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ItemDetailsScreen(
                            ItemDetailState.Ready(detail),
                            season = null,
                            selectedSeasonId = null,
                            actions = ItemDetailsActions({ _, _ -> }, { _, _ -> }, {}, {}),
                        )
                    }
                }
            }
            check()
        }

    @Test
    fun `the line under the title names each source's score in place of the one rating`() = page(
        MediaItemDetail(
            item = movie,
            ratings = listOf(
                ItemScore(Wire.RatingSource.IMDB, 7.5, 10.0, 700000),
                ItemScore(Wire.RatingSource.ROTTEN_TOMATOES, 91.0, 100.0),
                ItemScore(Wire.RatingSource.METACRITIC, 76.0, 100.0),
            ),
        ),
    ) {
        onAllNodesWithText("1995  ·  IMDb 7.5  ·  Rotten Tomatoes 91%  ·  Metacritic 76", substring = true)
            .assertCountEquals(1)
        onAllNodesWithText("★", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a server that sends no scores still shows the one rating`() = page(MediaItemDetail(item = movie)) {
        onAllNodesWithText("1995  ·  ★ 7.5", substring = true).assertCountEquals(1)
    }
}

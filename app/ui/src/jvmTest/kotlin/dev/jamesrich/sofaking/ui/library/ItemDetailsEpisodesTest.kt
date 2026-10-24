package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.ItemDetailState
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ItemDetailsEpisodesTest {
    private val season = MediaItem("se1", "shows", ItemKind.SEASON, "Season 1", parentId = "sh1", children = 1)
    private val episode = MediaItem("e1", "shows", ItemKind.EPISODE, "Pilot", parentId = "se1", episode = 1)

    @Test
    fun `holding an episode's row offers its own page`() = runSkikoComposeUiTest(Size(1920f, 3000f), Density(1f)) {
        val played = mutableListOf<String>()
        val opened = mutableListOf<String>()
        setContent {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ItemDetailsScreen(
                        ItemDetailState.Ready(MediaItemDetail(season, children = listOf(episode))),
                        season = null,
                        selectedSeasonId = null,
                        actions = ItemDetailsActions(
                            { item, _ -> played += item.id },
                            { _, _ -> },
                            {},
                            {},
                            onOpenItem = { opened += it.id },
                        ),
                    )
                }
            }
        }
        onNodeWithText("1. Pilot").performTouchInput { longClick() }
        onNodeWithText("Details").performClick()
        assertEquals(listOf("e1"), opened)
        assertTrue(played.isEmpty())
    }
}

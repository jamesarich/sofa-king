package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.ItemDetailState
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.ui.LayoutProfile
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An artist's page on a TV (#455, #456): the band's members as chips, the albums, the Popular songs with their album
 * under them, and the related artists in a row of their own. Arriving takes focus to the first album, since an
 * artist's page has no Play button, and coming back from a song or an artist lands on its row.
 */
@OptIn(ExperimentalTestApi::class)
class ArtistPageTest {
    private val artist = MediaItem("ar1", "music", ItemKind.ARTIST, "The Hollow Pines")
    private val albums = listOf(
        MediaItem("al1", "music", ItemKind.ALBUM, "Slow Burn", parentId = "ar1", year = 2021),
        MediaItem("al2", "music", ItemKind.ALBUM, "First Frost", parentId = "ar1", year = 2017),
    )
    private val popular = listOf(
        MediaItem("t1", "music", ItemKind.TRACK, "Porch Light", parentId = "al1", album = "Slow Burn"),
        MediaItem("t2", "music", ItemKind.TRACK, "Ash and Ember", parentId = "al2", album = "First Frost"),
    )
    private val related = listOf(
        MediaItem("ar2", "music", ItemKind.ARTIST, "Low Tide Choir"),
        MediaItem("ar3", "music", ItemKind.ARTIST, "Marlow Vane"),
    )
    private val detail = MediaItemDetail(
        item = artist,
        children = albums,
        cast = listOf("Ada Rook", "Ben Calloway"),
        popular = popular,
        related = related,
    )

    private class Taken {
        val played = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val people = mutableListOf<String>()
    }

    private fun ComposeUiTest.show(detail: MediaItemDetail, taken: Taken = Taken(), returnedFrom: String? = null) {
        setContent {
            CompositionLocalProvider(LocalLayoutProfile provides LayoutProfile.Tv) {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ItemDetailsScreen(
                            ItemDetailState.Ready(detail),
                            season = null,
                            selectedSeasonId = null,
                            actions = ItemDetailsActions(
                                onPlay = { item, _ -> taken.played += item.id },
                                onSetWatched = { _, _ -> },
                                onSelectSeason = {},
                                onRetry = {},
                                onPerson = { taken.people += it },
                                onOpenItem = { taken.opened += it.id },
                            ),
                            returnedFrom = returnedFrom,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `the page lists members, albums, popular songs and related artists, which play and open`() =
        runSkikoComposeUiTest(Size(1920f, 3000f), Density(1f)) {
            val taken = Taken()
            show(detail, taken)
            onAllNodesWithText("Members").assertCountEquals(1)
            onAllNodesWithText("Albums").assertCountEquals(1)
            onAllNodesWithText("Popular").assertCountEquals(1)
            onAllNodesWithText("More like this").assertCountEquals(1)
            onNodeWithText("Ben Calloway").performClick()
            onNode(hasContentDescription("Ash and Ember", substring = true)).performClick()
            onNode(hasContentDescription("Marlow Vane", substring = true)).performClick()
            onNode(hasContentDescription("First Frost, 2017")).performClick()
            assertEquals(listOf("Ben Calloway"), taken.people)
            assertEquals(listOf("t2"), taken.played)
            assertEquals(listOf("ar3", "al2"), taken.opened)
        }

    @Test
    fun `arriving lands on the first album`() = runSkikoComposeUiTest(Size(1920f, 3000f), Density(1f)) {
        show(detail)
        onNode(hasContentDescription("Slow Burn, 2021")).assertIsFocused()
    }

    @Test
    fun `back from a popular song lands on its row`() = runSkikoComposeUiTest(Size(1920f, 3000f), Density(1f)) {
        show(detail, returnedFrom = "t2")
        onNode(hasContentDescription("Ash and Ember", substring = true)).assertIsFocused()
    }

    @Test
    fun `back from a related artist lands on its card`() = runSkikoComposeUiTest(Size(1920f, 3000f), Density(1f)) {
        show(detail, returnedFrom = "ar3")
        onNode(hasContentDescription("Marlow Vane", substring = true)).assertIsFocused()
    }

    @Test
    fun `an artist with no members, songs or related artists shows only the albums`() =
        runSkikoComposeUiTest(Size(1920f, 3000f), Density(1f)) {
            show(MediaItemDetail(artist, children = albums))
            onAllNodesWithText("Albums").assertCountEquals(1)
            onAllNodesWithText("Members").assertCountEquals(0)
            onAllNodesWithText("Popular").assertCountEquals(0)
            onAllNodesWithText("More like this").assertCountEquals(0)
        }
}

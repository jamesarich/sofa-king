package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.LibraryGrid
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LibraryFilterBarTest {
    private val library = MediaLibrary("movies", "Movies", LibraryKind.MOVIES, itemCount = 3)

    private val grid = LibraryGrid(
        libraryId = library.id,
        sort = LibrarySort.TITLE,
        unwatched = false,
        genres = listOf("Action", "Comedy"),
        items = listOf(MediaItem("m0", library.id, ItemKind.MOVIE, "First movie")),
        total = 3,
        loading = false,
    )

    @Test
    fun `the sort and the genre are menus that report the choice and keep focus`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val sorts = mutableListOf<LibrarySort>()
            val genres = mutableListOf<String?>()
            val actions = LibraryGridActions(
                onSort = { sorts += it },
                onUnwatched = {},
                onGenre = { genres += it },
                onOpenItem = {},
                onPlayItem = {},
                onLoadMore = {},
            )
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LibraryGridScreen(library, grid, actions)
                    }
                }
            }
            // The genres are one chip until it opens, not a chip each.
            onNodeWithText("Comedy").assertDoesNotExist()
            onNodeWithText("Sort: Title").performClick()
            onNodeWithText("Year").performClick()
            waitForIdle()
            assertEquals(listOf(LibrarySort.YEAR), sorts)
            onNodeWithText("Sort: Title").assertIsFocused()

            onNodeWithText("All genres").performClick()
            onNodeWithText("Comedy").performClick()
            waitForIdle()
            assertEquals(listOf<String?>("Comedy"), genres)
        }
}

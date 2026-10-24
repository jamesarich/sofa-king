package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.LibraryGrid
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LetterJump
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibraryGridLettersTest {
    private val library = MediaLibrary("movies", "Movies", LibraryKind.MOVIES, itemCount = 80)

    // Ten titles under each of eight letters.
    private val titles = ('A'..'H').flatMap { letter -> (0 until 10).map { "$letter movie $it" } }
    private val letters = ('A'..'H').mapIndexed { i, letter -> LetterJump(letter.toString(), i * 10) }

    private fun grid(loaded: Int) = LibraryGrid(
        libraryId = library.id,
        sort = LibrarySort.TITLE,
        unwatched = false,
        items = titles.take(loaded).mapIndexed { i, title -> MediaItem("m$i", library.id, ItemKind.MOVIE, title) },
        letters = letters,
        total = titles.size,
        next = if (loaded < titles.size) "next" else null,
        loading = false,
    )

    @Test
    fun `a letter loads through its first title and focuses it`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            var shown by mutableStateOf(grid(loaded = 30))
            val asked = mutableListOf<Int>()
            val actions = LibraryGridActions(
                onSort = {},
                onUnwatched = {},
                onGenre = {},
                onOpenItem = {},
                onPlayItem = {},
                onLoadMore = {},
                onLoadThrough = { asked += it },
            )
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LibraryGridScreen(library, shown, actions)
                    }
                }
            }
            onNodeWithContentDescription("Jump to G").performClick()
            waitForIdle()
            assertEquals(listOf(60), asked)

            shown = grid(loaded = 80)
            waitForIdle()
            onNode(hasContentDescription("G movie 0", substring = true)).assertIsFocused()
        }

    @Test
    fun `the letters sit between the genres and the grid for every column`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val actions = LibraryGridActions(
                onSort = {},
                onUnwatched = {},
                onGenre = {},
                onOpenItem = {},
                onPlayItem = {},
                onLoadMore = {},
                onLoadThrough = {},
            )
            lateinit var focusManager: FocusManager
            setContent {
                focusManager = LocalFocusManager.current
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LibraryGridScreen(library, grid(loaded = 80).copy(genres = listOf("Comedy", "Drama")), actions)
                    }
                }
            }
            fun move(direction: FocusDirection) {
                runOnUiThread { focusManager.moveFocus(direction) }
                waitForIdle()
            }
            waitForIdle()
            onNode(hasContentDescription("A movie 0", substring = true)).assertIsFocused()
            move(FocusDirection.Up)
            onNodeWithContentDescription("Jump to A").assertIsFocused()
            move(FocusDirection.Up)
            onNodeWithText("All genres").assertIsFocused()
            move(FocusDirection.Down)
            onNodeWithContentDescription("Jump to A").assertIsFocused()

            move(FocusDirection.Down)
            move(FocusDirection.Right)
            move(FocusDirection.Up)
            onNodeWithContentDescription("Jump to A").assertIsFocused()
        }

    @Test
    fun `the letters show only in title order on a long library`() {
        assertTrue(showsLetters(grid(loaded = 30)))
        assertFalse(showsLetters(grid(loaded = 30).copy(sort = LibrarySort.ADDED)))
        assertFalse(showsLetters(grid(loaded = 30).copy(total = 79)))
        assertFalse(showsLetters(grid(loaded = 30).copy(letters = letters.take(5))))
    }
}

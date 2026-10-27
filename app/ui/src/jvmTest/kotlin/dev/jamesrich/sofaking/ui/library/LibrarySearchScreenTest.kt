package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.SearchResults
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibrarySearchScreenTest {
    private val loader = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun `clearing the query mid-search takes the loader down`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val queries = mutableListOf<String>()
            val actions = LibrarySearchActions(
                search = {
                    queries += it
                    awaitCancellation()
                },
                onOpenItem = {},
                onPlayItem = {},
            )
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LibrarySearchScreen(actions, voice = false, returnedFrom = null, initialQuery = "star")
                    }
                }
            }
            waitUntil { queries.isNotEmpty() }
            assertEquals(listOf("star"), queries)
            onAllNodes(loader, useUnmergedTree = true).assertCountEquals(1)

            onNode(hasSetTextAction()).performTextClearance()
            waitForIdle()
            onAllNodes(loader, useUnmergedTree = true).assertCountEquals(0)
        }

    /** Titles the query matched by their cast: the server names the people, and the row is titled by them. */
    private fun ComposeUiTest.searchFindingPeople(people: List<String>) {
        val actions = LibrarySearchActions(
            search = {
                SearchResults(
                    listOf(
                        MediaItem("m1", "movies", ItemKind.MOVIE, "Amber Road"),
                        MediaItem("m2", "movies", ItemKind.MOVIE, "Night Train"),
                    ),
                    people,
                )
            },
            onOpenItem = {},
            onPlayItem = {},
        )
        setContent {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LibrarySearchScreen(actions, voice = false, returnedFrom = null, initialQuery = "lovelace")
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("With ", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `the With row names the person search found titles by`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            searchFindingPeople(listOf("Ada Lovelace"))
            onNodeWithText("With Ada Lovelace").assertIsDisplayed()
            assertTrue(
                onAllNodesWithText("With lovelace").fetchSemanticsNodes().isEmpty(),
                "the query is not the title",
            )
        }

    @Test
    fun `the With row names two people`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        searchFindingPeople(listOf("Ada Lovelace", "Byron Lovelace"))
        onNodeWithText("With Ada Lovelace and Byron Lovelace").assertIsDisplayed()
    }

    @Test
    fun `the With row names the first two of more people`() = runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
        searchFindingPeople(listOf("Ada Lovelace", "Byron Lovelace", "Kit Lovelace"))
        onNodeWithText("With Ada Lovelace, Byron Lovelace and others").assertIsDisplayed()
    }

    @Test
    fun `a server that names no one titles the row by the query`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            searchFindingPeople(emptyList())
            onNodeWithText("With lovelace").assertIsDisplayed()
        }
}

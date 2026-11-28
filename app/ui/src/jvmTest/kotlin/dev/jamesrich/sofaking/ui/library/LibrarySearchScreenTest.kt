package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LiveHit
import dev.jamesrich.sofaking.data.LiveSearchResults
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.SearchResults
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

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

    private val news = Channel("a", 5.0, "News 5", null, listOf("News"))
    private val start = Instant.parse("2026-11-27T20:00:00Z")
    private val report = Program("a", start, start + 1.hours, "Evening Report", null)
    private val late = Program("a", start + 3.hours, start + 4.hours, "Late Report", null)

    /** A search whose lineup finds a channel on now and a program later, beside [items] from the libraries. */
    private fun ComposeUiTest.searchFindingLive(items: List<MediaItem>, opened: MutableList<LiveHit>) {
        val actions = LibrarySearchActions(
            search = { SearchResults(items, emptyList()) },
            onOpenItem = {},
            onPlayItem = {},
            live = LiveSearch(
                find = { LiveSearchResults(now = listOf(LiveHit(news, report)), later = listOf(LiveHit(news, late))) },
                details = { hit, onClose ->
                    LaunchedEffect(hit) {
                        opened += hit
                        onClose()
                    }
                },
            ),
        )
        setContent {
            SofaKingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LibrarySearchScreen(actions, voice = false, returnedFrom = null, initialQuery = "report")
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("On now").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `live TV is searched with the libraries, channels on now first and programs coming up last`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val opened = mutableListOf<LiveHit>()
            searchFindingLive(listOf(MediaItem("m1", "movies", ItemKind.MOVIE, "The Report")), opened)
            val onNow = onNodeWithText("On now").fetchSemanticsNode().boundsInRoot.top
            val movies = onNodeWithText("Movies").fetchSemanticsNode().boundsInRoot.top
            val onLater = onNodeWithText("On later").fetchSemanticsNode().boundsInRoot.top
            assertTrue(onNow < movies && movies < onLater, "On now $onNow, Movies $movies, On later $onLater")
            onNodeWithText("Evening Report").assertIsDisplayed()
            onNodeWithText("5  News 5").assertIsDisplayed()
            // A spoken or handed-over query puts focus on the first result, which is now the channel.
            waitForIdle()
            mainClock.advanceTimeBy(1000)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(
                    hasText("Evening Report") and hasClickAction() and isFocused(),
                ).fetchSemanticsNodes().isNotEmpty()
            }

            // The row may sit under the fold, where a pointer cannot reach it; OK on the card is its click action.
            onNode(hasText("Late Report") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertEquals(listOf(LiveHit(news, late)), opened)
            // Closing a hit's details gives its card focus back.
            mainClock.advanceTimeBy(1000)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(
                    hasText("Late Report") and hasClickAction() and isFocused(),
                ).fetchSemanticsNodes().isNotEmpty()
            }
        }

    @Test
    fun `a query only live TV answers is not one nothing matches`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            searchFindingLive(emptyList(), mutableListOf())
            onNodeWithText("Late Report").assertIsDisplayed()
            assertTrue(onAllNodesWithText("Nothing matches").fetchSemanticsNodes().isEmpty())
            onNode(hasSetTextAction()).assert(hasContentDescription("Channel, program, title or cast"))
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

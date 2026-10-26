package dev.jamesrich.sofaking

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.NavDisplay
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LibraryPanesTest {
    private val grid = Route.LibraryGrid("movies")
    private val item = Route.LibraryItem("m1")

    /** [stack] as the app shows it, each page only its route's name. */
    @Composable
    private fun Pages(stack: List<Route>, tv: Boolean = false) {
        val entries = stack.map { route ->
            NavEntry(route, contentKey = route.id, metadata = panesOf(route)) { Text(route.toString()) }
        }
        NavDisplay(entries = entries, sceneStrategies = listOf(rememberLibraryPanes(tv)), onBack = {})
    }

    @Test
    fun `a wide window shows the grid beside the title picked from it`() = runDesktopComposeUiTest(1280, 800) {
        setContent { Pages(listOf(Route.Library, grid, item)) }
        onNodeWithText(grid.toString()).assertIsDisplayed()
        onNodeWithText(item.toString()).assertIsDisplayed()
    }

    @Test
    fun `Back from a title beside its grid closes only the title`() = runDesktopComposeUiTest(1280, 800) {
        var previous: List<Any>? = null
        setContent {
            val strategy = rememberLibraryPanes(tv = false)
            val entries = listOf(Route.Library, grid, item).map { route ->
                NavEntry(route, contentKey = route.id, metadata = panesOf(route)) { Text(route.toString()) }
            }
            previous = with(strategy) { SceneStrategyScope<Route>().calculateScene(entries) }
                ?.previousEntries?.map { it.contentKey }
        }
        waitForIdle()
        assertEquals(listOf<Any>(Route.Library.id, grid.id), previous)
    }

    @Test
    fun `a narrow window shows only the title`() = runDesktopComposeUiTest(400, 800) {
        setContent { Pages(listOf(Route.Library, grid, item)) }
        onNodeWithText(grid.toString()).assertDoesNotExist()
        onNodeWithText(item.toString()).assertIsDisplayed()
    }

    @Test
    fun `a TV shows only the title`() = runDesktopComposeUiTest(1280, 800) {
        setContent { Pages(listOf(Route.Library, grid, item), tv = true) }
        onNodeWithText(grid.toString()).assertDoesNotExist()
        onNodeWithText(item.toString()).assertIsDisplayed()
    }

    @Test
    fun `a title opened from the library home fills a wide window`() = runDesktopComposeUiTest(1280, 800) {
        setContent { Pages(listOf(Route.Library, item)) }
        onNodeWithText(Route.Library.toString()).assertDoesNotExist()
        onNodeWithText(item.toString()).assertIsDisplayed()
    }
}

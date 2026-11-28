package dev.jamesrich.sofaking

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.SceneStrategy

/** Marks the library grid's entry, so [rememberLibraryPanes] knows a title page has its grid to sit beside. */
private const val LIBRARY_GRID = "dev.jamesrich.sofaking.LibraryGrid"

/** A library grid's pane: the list, once a title picked from it is the detail. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val GridPanes = ListDetailSceneStrategy.listPane() + (LIBRARY_GRID to true)

/** A title page's pane: the detail, beside the grid it was picked from. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val ItemPanes = ListDetailSceneStrategy.detailPane()

/**
 * Where [route]'s entry goes in a list-detail layout: a library grid is the list and a title page the detail.
 * Every other page fills the window.
 */
internal fun panesOf(route: Route): Map<String, Any> = when (route) {
    is Route.LibraryGrid -> GridPanes
    is Route.LibraryItem -> ItemPanes
    else -> emptyMap()
}

/**
 * On a window wide enough for two panes, a library grid with the title picked from it beside it, as Material's
 * list-detail layout, which Back closes one title at a time. Until a title is picked the grid fills the window: an
 * empty detail pane would take most of it from the posters. A TV keeps one page at a time for the remote, and so does
 * a title opened from anywhere but a grid. Navigation 3 shows one page where this gives no scene.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun rememberLibraryPanes(tv: Boolean): SceneStrategy<Route> {
    // Back pops one page, the title, and leaves the grid, whatever shape the panes had.
    val listDetail = rememberListDetailSceneStrategy<Route>(
        backNavigationBehavior = BackNavigationBehavior.PopUntilContentChange,
    )
    return remember(tv, listDetail) {
        SceneStrategy { entries ->
            // The pages on top that have a pane. Two panes only for a title on top of its grid: a grid alone, or a
            // title with no grid under it, fills the window.
            val panes = entries.takeLastWhile { it.metadata.isNotEmpty() }
            val titleOverGrid = panes.any { it.isLibraryGrid } && !panes.last().isLibraryGrid
            if (tv || !titleOverGrid) null else with(listDetail) { calculateScene(entries) }
        }
    }
}

private val NavEntry<Route>.isLibraryGrid: Boolean get() = metadata[LIBRARY_GRID] == true

package dev.jamesrich.sofaking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.SceneStrategy
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_pick_title
import org.jetbrains.compose.resources.stringResource

/** Marks the library grid's entry, so [rememberLibraryPanes] knows a title page has its grid to sit beside. */
private const val LIBRARY_GRID = "dev.jamesrich.sofaking.LibraryGrid"

/** A library grid's panes: the list, with a hint in the detail pane until a title is picked. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val GridPanes = ListDetailSceneStrategy.listPane(detailPlaceholder = { PickATitle() }) + (LIBRARY_GRID to true)

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
 * list-detail layout, which Back closes one title at a time. A TV keeps one page at a time for the remote, and so
 * does a title opened from anywhere but a grid. Navigation 3 shows one page where this gives no scene.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun rememberLibraryPanes(tv: Boolean): SceneStrategy<Route> {
    // Back pops one page: the default pops until the panes change shape, and a grid with its placeholder has the same
    // shape as a grid with a title, so Back would leave the grid too.
    val listDetail = rememberListDetailSceneStrategy<Route>(
        backNavigationBehavior = BackNavigationBehavior.PopUntilContentChange,
    )
    return remember(tv, listDetail) {
        SceneStrategy { entries ->
            // The pages on top that have a pane; with no grid among them, the page fills the window.
            val panes = entries.takeLastWhile { it.metadata.isNotEmpty() }
            if (tv || panes.none { it.isLibraryGrid }) null else with(listDetail) { calculateScene(entries) }
        }
    }
}

private val NavEntry<Route>.isLibraryGrid: Boolean get() = metadata[LIBRARY_GRID] == true

/** The detail pane before a title is picked. */
@Composable
private fun PickATitle() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(Res.string.library_pick_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

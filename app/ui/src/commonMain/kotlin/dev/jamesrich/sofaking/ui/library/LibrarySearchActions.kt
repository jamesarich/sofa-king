package dev.jamesrich.sofaking.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import dev.jamesrich.sofaking.data.LiveHit
import dev.jamesrich.sofaking.data.LiveSearchResults
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.SearchResults

/** What the search's cards do, as on the library's other pages; [live] adds live TV to what it finds. */
internal class LibrarySearchActions(
    val search: suspend (String) -> SearchResults,
    val onOpenItem: (MediaItem) -> Unit,
    val onPlayItem: (MediaItem) -> Unit,
    val menu: CardMenu? = null,
    val live: LiveSearch? = null,
)

/**
 * Live TV in the search: [find] answers a query from the lineup, and [details] draws a hit's program details over the
 * page, where its channel can be watched and its program recorded, until the viewer closes them.
 */
internal class LiveSearch(
    val find: (String) -> LiveSearchResults,
    val details: @Composable (hit: LiveHit, onClose: () -> Unit) -> Unit,
)

/** Where focus lands in a row of live hits: on its first card for a new search, or on the hit the viewer left. */
internal class LiveRowFocus(val first: FocusRequester?, val returnKey: String?, val returnTo: FocusRequester)

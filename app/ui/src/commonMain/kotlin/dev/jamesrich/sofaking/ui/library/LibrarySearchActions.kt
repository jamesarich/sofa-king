package dev.jamesrich.sofaking.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.SearchResults

/** What the search's cards do, as on the library's other pages. */
internal class LibrarySearchActions(
    val search: suspend (String) -> SearchResults,
    val onOpenItem: (MediaItem) -> Unit,
    val onPlayItem: (MediaItem) -> Unit,
    val menu: CardMenu? = null,
)

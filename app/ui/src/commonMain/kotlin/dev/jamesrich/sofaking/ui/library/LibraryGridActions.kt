package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.only
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem

/** What the library grid's controls and cards do. */
internal class LibraryGridActions(
    val onSort: (LibrarySort) -> Unit,
    val onUnwatched: (Boolean) -> Unit,
    /** Shows only one genre, or every one when null. */
    val onGenre: (String?) -> Unit,
    val onOpenItem: (MediaItem) -> Unit,
    val onPlayItem: (MediaItem) -> Unit,
    val onLoadMore: () -> Unit,
    /** Loads pages until the grid holds the item at the offset given, for a letter jump. */
    val onLoadThrough: (Int) -> Unit = {},
    /** What a held OK on a card offers. */
    val menu: CardMenu? = null,
)

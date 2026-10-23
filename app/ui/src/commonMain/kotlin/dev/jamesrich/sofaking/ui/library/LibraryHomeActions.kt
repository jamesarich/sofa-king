package dev.jamesrich.sofaking.ui.library

import androidx.compose.ui.input.key.key
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaLibrary

/** What the library home's cards do. OK opens an item's page; the remote's Play key plays an item that plays. */
internal class LibraryHomeActions(
    val onOpenItem: (MediaItem) -> Unit,
    val onPlayItem: (MediaItem) -> Unit,
    val onOpenLibrary: (MediaLibrary) -> Unit,
    val onRetry: () -> Unit,
    /** Opens the search; true starts it by voice. */
    val onSearch: (voice: Boolean) -> Unit = {},
    /** What a held OK on a card offers. */
    val menu: CardMenu? = null,
)

package dev.jamesrich.sofaking.ui.library

import androidx.compose.ui.input.key.key
import dev.jamesrich.sofaking.data.CONTINUE_SHELF
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaLibrary
import dev.jamesrich.sofaking.data.MediaShelf

/**
 * What the library home's cards do. OK opens an item's page, except on Continue watching, where it plays on from
 * where the viewer stopped; the remote's Play key plays an item that plays.
 */
internal class LibraryHomeActions(
    val onOpenItem: (MediaItem) -> Unit,
    val onPlayItem: (MediaItem) -> Unit,
    val onOpenLibrary: (MediaLibrary) -> Unit,
    val onRetry: () -> Unit,
    /** Opens the search; true starts it by voice. */
    val onSearch: (voice: Boolean) -> Unit = {},
    /** What a held OK on a card offers. */
    val menu: CardMenu? = null,
    /** Takes an item off Continue watching, from its card's menu on that shelf. */
    val onRemoveFromContinue: ((MediaItem) -> Unit)? = null,
    /** Reads the shelves again, from a pull down on a touch screen. */
    val onRefresh: (() -> Unit)? = null,
) {
    /** The menu for [shelf]'s cards: Continue watching's adds Remove from Continue watching. */
    fun menuFor(shelf: MediaShelf): CardMenu? {
        val menu = menu ?: return null
        if (shelf.id != CONTINUE_SHELF || onRemoveFromContinue == null) return menu
        // OK resumes there, so the item's page moves into the menu.
        return CardMenu(menu.onOpen ?: onOpenItem, menu.onPlayFromStart, menu.onSetWatched, onRemoveFromContinue)
    }
}

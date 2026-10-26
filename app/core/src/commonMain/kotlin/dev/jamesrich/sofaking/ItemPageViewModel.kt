package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * One library item's screen: its page, and on a show the page of the season it lists. Each is held open for as long
 * as the screen is on the back stack, so returning to it shows what it showed, and let go when the screen leaves.
 */
class ItemPageViewModel(private val library: LibraryBrowsing, itemId: String) : ViewModel() {
    private val page = library.openItem(itemId)
    private var seasonPage: ItemPage? = null

    init {
        addCloseable(page)
        addCloseable(AutoCloseable { seasonPage?.close() })
    }

    /** The item's page. */
    val detail: StateFlow<ItemDetailState> get() = page.state

    /** Loads the item's page again. */
    fun retry() = page.refresh()

    /** The page of the season [id] the show lists; the season listed before it is let go. */
    fun season(id: String): StateFlow<ItemDetailState> {
        val open = seasonPage?.takeIf { it.id == id } ?: library.openItem(id).also {
            seasonPage?.close()
            seasonPage = it
        }
        return open.state
    }
}

package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.MediaItem

/** What an item's page does. [onPlay] resumes an item in progress unless [fromStart] is set. */
internal class ItemDetailsActions(
    val onPlay: (item: MediaItem, fromStart: Boolean) -> Unit,
    val onSetWatched: (MediaItem, Boolean) -> Unit,
    val onSelectSeason: (String) -> Unit,
    val onRetry: () -> Unit,
    val onPerson: (String) -> Unit = {},
    val onOpenShow: (String) -> Unit = {},
    /** Opens another item's page: an episode from its row's menu, or a title from More like this. */
    val onOpenItem: (MediaItem) -> Unit = {},
    /** Adds a movie, show or video to the household's watchlist, or takes it off. */
    val onToggleWatchlist: (MediaItem) -> Unit = {},
    /** Whether an item is on the household's watchlist; null on a server without one, which hides the button. */
    val watchlisted: (MediaItem) -> Boolean? = { null },
)

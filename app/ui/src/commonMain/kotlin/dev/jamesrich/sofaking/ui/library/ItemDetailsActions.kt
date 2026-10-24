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
)

package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.CONTINUE_SHELF
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.RECENT_SHELF

/** The card styles of the placeholder rows shown while the home loads: Continue watching's, then a poster row. */
internal fun skeletonStyles(): List<CardStyle> = listOf(
    cardStyleFor(MediaShelf(CONTINUE_SHELF, "", emptyList())),
    cardStyleFor(MediaShelf(RECENT_SHELF, "", emptyList())),
)

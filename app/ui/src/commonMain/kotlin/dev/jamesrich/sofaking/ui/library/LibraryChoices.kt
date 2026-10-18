package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.CONTINUE_SHELF
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaShelf

/** How a row draws its items: 2:3 posters for movies and shows, 16:9 cards for episodes and what is being watched. */
internal enum class CardStyle { POSTER, WIDE }

/** Continue watching and a row of episodes or videos are wide; anything else shows posters. */
internal fun cardStyleFor(shelf: MediaShelf): CardStyle = when {
    shelf.id == CONTINUE_SHELF -> CardStyle.WIDE
    shelf.items.isNotEmpty() && shelf.items.all { it.kind == ItemKind.EPISODE || it.kind == ItemKind.VIDEO } -> CardStyle.WIDE
    else -> CardStyle.POSTER
}

/** Hours and minutes of a running time, rounded to the nearest minute; null when unknown or under half a minute. */
internal fun runtimeParts(durationMs: Long?): Pair<Int, Int>? {
    val minutes = ((durationMs ?: return null) + 30_000) / 60_000
    if (minutes <= 0) return null
    return (minutes / 60).toInt() to (minutes % 60).toInt()
}

/** True when the grid has shown close enough to its last loaded item that the next page should load. */
internal fun shouldLoadMore(lastVisibleIndex: Int, loaded: Int, hasMore: Boolean): Boolean =
    hasMore && loaded > 0 && lastVisibleIndex >= loaded - LOAD_AHEAD

/** How many items short of the end of a grid the next page starts loading: about three rows of posters. */
private const val LOAD_AHEAD = 18

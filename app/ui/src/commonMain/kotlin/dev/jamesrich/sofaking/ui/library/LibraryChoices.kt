package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.MediaShelf

/** How a row draws its items: 2:3 posters for movies and shows, 16:9 cards for episodes and what is being watched. */
internal enum class CardStyle { POSTER, WIDE }

/** Continue watching and a row of episodes or videos are wide; anything else shows posters. */
internal fun cardStyleFor(shelf: MediaShelf): CardStyle = when {
    shelf.id == CONTINUE_SHELF -> CardStyle.WIDE
    shelf.items.isNotEmpty() && shelf.items.all { it.kind == ItemKind.EPISODE || it.kind == ItemKind.VIDEO } -> CardStyle.WIDE
    else -> CardStyle.POSTER
}

/** What the details page's first button does. */
internal sealed interface PrimaryAction {
    val item: MediaItem

    /** Plays [item] from the start. */
    data class Play(override val item: MediaItem) : PrimaryAction

    /** Picks [item] up where it stopped. */
    data class Resume(override val item: MediaItem, val positionMs: Long) : PrimaryAction
}

/**
 * The page's first button: the item itself when it plays, resumed when it is in progress; for a show or a season
 * the episode the server names next, resumed the same way. Null when there is nothing to play yet.
 */
internal fun primaryAction(detail: MediaItemDetail): PrimaryAction? {
    val target = if (detail.item.kind.playable) detail.item else detail.next?.takeIf { it.kind.playable } ?: return null
    val progress = target.progress
    return if (progress != null && progress.inProgress) PrimaryAction.Resume(target, progress.positionMs) else PrimaryAction.Play(target)
}

/**
 * The season a show's page opens on: the one holding the next episode, else the first with something unwatched,
 * else the first. Null for a page without seasons.
 */
internal fun initialSeasonId(detail: MediaItemDetail): String? {
    val seasons = detail.children.filter { it.kind == ItemKind.SEASON }
    if (seasons.isEmpty()) return null
    val nextSeason = detail.next?.parentId
    return seasons.firstOrNull { it.id == nextSeason }?.id
        ?: seasons.firstOrNull { it.unwatched > 0 }?.id
        ?: seasons.first().id
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

/** The server's id for the row of items in progress and next episodes. */
internal const val CONTINUE_SHELF = "continue"

/** How many items short of the end of a grid the next page starts loading: about three rows of posters. */
private const val LOAD_AHEAD = 18

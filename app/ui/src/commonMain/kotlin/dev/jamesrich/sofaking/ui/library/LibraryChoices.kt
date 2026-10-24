package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.CONTINUE_SHELF
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaShelf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How a row draws its items: 2:3 posters for movies and shows, 16:9 cards for episodes and what is being watched. */
internal enum class CardStyle { POSTER, WIDE }

/** Search results split as the search draws them: movies and shows as posters, episodes and videos as wide cards. */
internal class SearchSections(val posters: List<MediaItem>, val wide: List<MediaItem>) {
    /** Whether the search found anything. */
    val found: Boolean get() = posters.isNotEmpty() || wide.isNotEmpty()
}

internal fun searchSections(results: List<MediaItem>): SearchSections {
    val (wide, posters) = results.partition { it.kind == ItemKind.EPISODE || it.kind == ItemKind.VIDEO }
    return SearchSections(posters, wide)
}

/** Continue watching and a row of episodes or videos are wide; anything else shows posters. */
internal fun cardStyleFor(shelf: MediaShelf): CardStyle = when {
    shelf.id == CONTINUE_SHELF -> CardStyle.WIDE

    shelf.items.isNotEmpty() && shelf.items.all {
        it.kind == ItemKind.EPISODE || it.kind == ItemKind.VIDEO
    } -> CardStyle.WIDE

    else -> CardStyle.POSTER
}

/** A running time rounded to the nearest minute; null when unknown or under half a minute. */
internal fun runtime(durationMs: Long?): Duration? {
    val minutes = ((durationMs ?: return null) + 30_000) / 60_000
    return minutes.takeIf { it > 0 }?.minutes
}

/** What a card's menu offers, in order. */
internal enum class CardMenuEntry { DETAILS, PLAY_FROM_START, MARK_WATCHED, MARK_UNWATCHED }

/** Whether [item] counts as watched: a show or season once every episode is, anything else once it is. */
internal fun isWatched(item: MediaItem): Boolean = when (item.kind) {
    ItemKind.SHOW, ItemKind.SEASON -> item.unwatched == 0 && item.children > 0
    else -> item.progress?.watched == true
}

/**
 * A card's menu: Details first when [details] (where OK does not already open the page), Play from beginning for
 * something that plays and is partway in, when [playFromStart], then the watched toggle.
 */
internal fun cardMenuEntries(item: MediaItem, playFromStart: Boolean, details: Boolean = false): List<CardMenuEntry> =
    listOfNotNull(
        CardMenuEntry.DETAILS.takeIf { details },
        CardMenuEntry.PLAY_FROM_START.takeIf {
            playFromStart && item.kind.playable && item.progress?.inProgress == true
        },
        if (isWatched(item)) CardMenuEntry.MARK_UNWATCHED else CardMenuEntry.MARK_WATCHED,
    )

/** True when the grid has shown close enough to its last loaded item that the next page should load. */
internal fun shouldLoadMore(lastVisibleIndex: Int, loaded: Int, hasMore: Boolean): Boolean =
    hasMore && loaded > 0 && lastVisibleIndex >= loaded - LOAD_AHEAD

/** How many items short of the end of a grid the next page starts loading: about three rows of posters. */
private const val LOAD_AHEAD = 18

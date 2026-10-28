package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.CONTINUE_SHELF
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.MusicView
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How a row draws its items: 2:3 posters for movies and shows, 16:9 cards for episodes and what is being watched,
 * square covers for music.
 */
internal enum class CardStyle { POSTER, WIDE, SQUARE }

/**
 * A row of search results: movies, shows, what the query's person is in, episodes, videos, then artists, albums and
 * tracks, in that order.
 */
internal enum class SearchGroup(val style: CardStyle) {
    MOVIES(CardStyle.POSTER),
    SHOWS(CardStyle.POSTER),

    /** Movies, shows and videos found by their cast or directors rather than their titles. */
    CAST_AND_CREW(CardStyle.POSTER),
    EPISODES(CardStyle.WIDE),
    VIDEOS(CardStyle.WIDE),
    ARTISTS(CardStyle.SQUARE),
    ALBUMS(CardStyle.SQUARE),
    TRACKS(CardStyle.SQUARE),
}

/** One [SearchGroup]'s results, in the order the server sent them. */
internal class SearchSection(val group: SearchGroup, val items: List<MediaItem>)

/**
 * Search results for [query] as the search draws them, the groups that found nothing left out; [people] are the cast
 * and directors the server found [SearchGroup.CAST_AND_CREW]'s titles by, when it names them.
 */
internal class SearchSections(
    val query: String,
    val rows: List<SearchSection>,
    val people: List<String> = emptyList(),
) {
    /** Whether the search found anything. */
    val found: Boolean get() = rows.isNotEmpty()
}

/**
 * [results] for [query] in their groups. The server matches titles, then cast and directors, then episode titles, so a
 * movie, show or video whose title lacks [query] was found by the people in it. Music goes by kind alone.
 */
internal fun searchSections(
    results: List<MediaItem>,
    query: String,
    people: List<String> = emptyList(),
): SearchSections {
    val needle = query.trim().lowercase()
    val groups = results.groupBy { item ->
        when {
            item.kind == ItemKind.ARTIST -> SearchGroup.ARTISTS
            item.kind == ItemKind.ALBUM -> SearchGroup.ALBUMS
            item.kind == ItemKind.TRACK -> SearchGroup.TRACKS
            item.kind == ItemKind.EPISODE -> SearchGroup.EPISODES
            needle.isNotEmpty() && needle !in item.title.lowercase() -> SearchGroup.CAST_AND_CREW
            item.kind == ItemKind.SHOW -> SearchGroup.SHOWS
            item.kind == ItemKind.VIDEO -> SearchGroup.VIDEOS
            else -> SearchGroup.MOVIES
        }
    }
    val rows = SearchGroup.entries.mapNotNull { group -> groups[group]?.let { SearchSection(group, it) } }
    return SearchSections(query.trim(), rows, people)
}

/** Continue watching and a row of episodes or videos are wide, a row of music square; anything else shows posters. */
internal fun cardStyleFor(shelf: MediaShelf): CardStyle = when {
    shelf.id == CONTINUE_SHELF -> CardStyle.WIDE

    shelf.items.isNotEmpty() && shelf.items.all { it.kind.music } -> CardStyle.SQUARE

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

/** How a library's grid draws its items: wide for videos, square for music, posters for the rest. */
internal fun gridStyleFor(kind: LibraryKind?): CardStyle = when (kind) {
    LibraryKind.VIDEOS -> CardStyle.WIDE
    LibraryKind.MUSIC -> CardStyle.SQUARE
    else -> CardStyle.POSTER
}

/**
 * The orders a library's grid offers: music has no ratings or watched dates, so by name, by date added, by when it
 * was last played and by how often, and by year for the albums, which carry one; a song's year is its album's, so
 * the songs do not sort by it. Other libraries have no plays.
 */
internal fun gridSorts(kind: LibraryKind?, view: MusicView = MusicView.ARTISTS): List<LibrarySort> =
    if (kind == LibraryKind.MUSIC) {
        listOfNotNull(
            LibrarySort.TITLE,
            LibrarySort.ADDED,
            LibrarySort.YEAR.takeIf { view == MusicView.ALBUMS },
            LibrarySort.PLAYED,
            LibrarySort.PLAYS,
        )
    } else {
        LibrarySort.entries.filter { !it.musicOnly }
    }

/** Whether a library's grid offers the Unwatched filter: not for music, which has no watched state. */
internal fun gridFiltersUnwatched(kind: LibraryKind?): Boolean = kind != LibraryKind.MUSIC

/** A square card's second line: an album's or track's artist, then the year, when known. */
internal fun squareSubtitle(item: MediaItem): String? = listOfNotNull(
    item.trackArtist ?: item.artist,
    item.year?.toString(),
).joinToString(" · ").ifEmpty { null }

/** What a card's menu offers, in order. */
internal enum class CardMenuEntry { DETAILS, PLAY_FROM_START, MARK_WATCHED, MARK_UNWATCHED, REMOVE_FROM_CONTINUE }

/** Whether [item] counts as watched: a show or season once every episode is, anything else once it is. */
internal fun isWatched(item: MediaItem): Boolean = when (item.kind) {
    ItemKind.SHOW, ItemKind.SEASON -> item.unwatched == 0 && item.children > 0
    else -> item.progress?.watched == true
}

/**
 * A card's menu: Details first when [details] (where OK does not already open the page), Play from beginning for
 * something that plays and is partway in, when [playFromStart], then the watched toggle, and Remove from Continue
 * watching last on that shelf ([removeFromContinue]).
 */
internal fun cardMenuEntries(
    item: MediaItem,
    playFromStart: Boolean,
    details: Boolean = false,
    removeFromContinue: Boolean = false,
): List<CardMenuEntry> = listOfNotNull(
    CardMenuEntry.DETAILS.takeIf { details },
    CardMenuEntry.PLAY_FROM_START.takeIf {
        playFromStart && item.kind.playable && item.progress?.inProgress == true && !item.kind.music
    },
    when {
        item.kind.music -> null
        isWatched(item) -> CardMenuEntry.MARK_UNWATCHED
        else -> CardMenuEntry.MARK_WATCHED
    },
    CardMenuEntry.REMOVE_FROM_CONTINUE.takeIf { removeFromContinue },
)

/** True when the grid has shown close enough to its last loaded item that the next page should load. */
internal fun shouldLoadMore(lastVisibleIndex: Int, loaded: Int, hasMore: Boolean): Boolean =
    hasMore && loaded > 0 && lastVisibleIndex >= loaded - LOAD_AHEAD

/** How many items short of the end of a grid the next page starts loading: about three rows of posters. */
private const val LOAD_AHEAD = 18

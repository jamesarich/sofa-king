package dev.jamesrich.sofaking.data

/**
 * A row of search results: movies, shows, what the query's person is in, episodes, videos, then artists, albums and
 * tracks, in that order.
 */
enum class SearchGroup {
    MOVIES,
    SHOWS,

    /** Movies, shows and videos found by their cast or directors rather than their titles. */
    CAST_AND_CREW,
    EPISODES,
    VIDEOS,
    ARTISTS,
    ALBUMS,
    TRACKS,
}

/** One [SearchGroup]'s results, in the order the server sent them. */
class SearchSection(val group: SearchGroup, val items: List<MediaItem>)

/**
 * Search results for [query] as the search shows them, the groups that found nothing left out; [people] are the cast
 * and directors the server found [SearchGroup.CAST_AND_CREW]'s titles by, when it names them.
 */
class SearchSections(val query: String, val rows: List<SearchSection>, val people: List<String> = emptyList()) {
    /** Whether the search found anything. */
    val found: Boolean get() = rows.isNotEmpty()
}

/** Long enough that typing a word on a remote's keyboard asks the server once, not per letter. */
const val SEARCH_DEBOUNCE_MS = 400L

/**
 * [results] for [query] in their groups. The server matches titles, then cast and directors, then episode titles, so a
 * movie, show or video whose title lacks [query] was found by the people in it. Music goes by kind alone.
 */
fun searchSections(results: List<MediaItem>, query: String, people: List<String> = emptyList()): SearchSections {
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

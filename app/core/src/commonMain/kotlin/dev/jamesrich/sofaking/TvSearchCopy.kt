package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.SearchGroup
import dev.jamesrich.sofaking.data.SearchSections

/** The library search's words in the SwiftUI Apple TV app; see [TvCopy]. */
object TvSearchCopy {
    private val table = CopyTable()

    /** Resource name to the text this object holds for it. */
    val twins: Map<String, String> get() = table.twins

    private fun twin(key: String, text: String) = table.twin(key, text)

    val search = twin("library_search", "Search")
    val hint = twin("library_search_hint", "Title, show or cast")
    val none = twin("library_search_none", "Nothing matches")

    private val movies = twin("library_search_movies", "Movies")
    private val shows = twin("library_search_shows", "Shows")
    private val people = twin("library_search_people", "With %1\$s")
    private val peopleTwo = twin("library_search_people_two", "With %1\$s and %2\$s")
    private val peopleMore = twin("library_search_people_more", "With %1\$s, %2\$s and others")
    private val episodes = twin("library_search_episodes", "Episodes")
    private val videos = twin("library_search_videos", "Videos")
    private val artists = twin("library_search_artists", "Artists")
    private val albums = twin("library_search_albums", "Albums")
    private val tracks = twin("library_search_tracks", "Songs")

    /** The title of [group]'s row in [sections]; the row found by cast and crew names up to two of those people. */
    fun groupTitle(group: SearchGroup, sections: SearchSections): String = when (group) {
        SearchGroup.MOVIES -> movies
        SearchGroup.SHOWS -> shows
        SearchGroup.CAST_AND_CREW -> peopleTitle(sections.people, sections.query)
        SearchGroup.EPISODES -> episodes
        SearchGroup.VIDEOS -> videos
        SearchGroup.ARTISTS -> artists
        SearchGroup.ALBUMS -> albums
        SearchGroup.TRACKS -> tracks
    }

    private fun peopleTitle(names: List<String>, query: String): String = when (names.size) {
        0 -> table.fill(people, query)
        1 -> table.fill(people, names[0])
        2 -> table.fill(peopleTwo, names[0], names[1])
        else -> table.fill(peopleMore, names[0], names[1])
    }
}

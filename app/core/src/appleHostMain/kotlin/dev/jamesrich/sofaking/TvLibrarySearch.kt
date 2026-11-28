package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LiveSearchResults
import dev.jamesrich.sofaking.data.SEARCH_DEBOUNCE_MS
import dev.jamesrich.sofaking.data.SearchSections
import dev.jamesrich.sofaking.data.search
import dev.jamesrich.sofaking.data.searchSections
import kotlin.time.Clock

/** Search across live TV and the media libraries, as the Apple apps' Search page shows it. */
interface TvLibrarySearch {
    /** How long a query rests, in milliseconds, before Swift asks [searchLibrary]: typing a word asks once. */
    val searchDebounceMs: Long

    /**
     * What the libraries hold for [query], in rows by kind, the groups that found nothing left out; nothing for a blank
     * query or a server that could not be read. Kids mode shows only what it allows.
     */
    @Throws(Exception::class)
    suspend fun searchLibrary(query: String): SearchSections

    /**
     * What [lineup] holds for [query] at this moment: the channels on now and the programs coming up; see
     * [Lineup.search]. The Swift page passes the lineup it shows, so kids mode's channels are the only ones searched.
     */
    fun searchLive(lineup: Lineup, query: String): LiveSearchResults
}

internal class TvLibrarySearchCalls(private val parts: TvParts) : TvLibrarySearch {
    override val searchDebounceMs: Long = SEARCH_DEBOUNCE_MS

    @Throws(Exception::class)
    override suspend fun searchLibrary(query: String): SearchSections =
        parts.library.search(query).let { found -> searchSections(found.items, query, found.people) }

    override fun searchLive(lineup: Lineup, query: String): LiveSearchResults = lineup.search(query, Clock.System.now())
}

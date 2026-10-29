package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.SEARCH_DEBOUNCE_MS
import dev.jamesrich.sofaking.data.SearchSections
import dev.jamesrich.sofaking.data.searchSections

/** Search across the media libraries, as the Apple TV app's Library search shows it. */
interface TvLibrarySearch {
    /** How long a query rests, in milliseconds, before Swift asks [searchLibrary]: typing a word asks once. */
    val searchDebounceMs: Long

    /**
     * What the libraries hold for [query], in rows by kind, the groups that found nothing left out; nothing for a blank
     * query or a server that could not be read. Kids mode shows only what it allows.
     */
    @Throws(Exception::class)
    suspend fun searchLibrary(query: String): SearchSections
}

internal class TvLibrarySearchCalls(private val parts: TvParts) : TvLibrarySearch {
    override val searchDebounceMs: Long = SEARCH_DEBOUNCE_MS

    @Throws(Exception::class)
    override suspend fun searchLibrary(query: String): SearchSections =
        parts.library.search(query).let { found -> searchSections(found.items, query, found.people) }
}

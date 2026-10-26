package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.deleteLibraryMatch
import dev.jamesrich.sofaking.backend.store.libraries
import dev.jamesrich.sofaking.backend.store.libraryItem
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.libraryMatches
import dev.jamesrich.sofaking.backend.store.libraryMetadata
import dev.jamesrich.sofaking.backend.store.saveLibraryMatch
import java.time.Clock

/**
 * A present movie or show in [library]: [found] is its last TMDB lookup, if it had one, and [chosen] the TMDB id
 * chosen for it by hand, if any.
 */
internal class MatchRow(
    val item: LibraryItemRecord,
    val library: LibraryRecord,
    val found: MetadataRecord?,
    val chosen: Int?,
) {
    /** Whether the scan has fetched the chosen entry yet. */
    val fetched: Boolean get() = chosen != null && found?.tmdbId == chosen
}

/**
 * Fixing what TMDB matched: the movies and shows its search found nothing for, a search of TMDB for one of them, and
 * the entry chosen by hand, which every scan then looks up in place of a search. Choosing or clearing one asks
 * [rescan] to scan its library, which fetches the entry.
 */
class LibraryMatches internal constructor(
    private val store: BackendStore,
    private val clock: Clock,
    private val metadata: LibraryMetadata,
    private val rescan: (String) -> Unit,
) {
    /** Every present movie and show a lookup found nothing for or someone chose a match for, by library and title. */
    internal suspend fun rows(): List<MatchRow> = store.libraries()
        .filter { it.kind == LibraryKind.MOVIES || it.kind == LibraryKind.SHOWS }
        .sortedBy { it.name.lowercase() }
        .flatMap { library ->
            val items = store.libraryItems(library.id).filter {
                it.missingSince == null && (it.kind == ItemKind.MOVIE || it.kind == ItemKind.SHOW)
            }
            val ids = items.map { it.id }
            val found = store.libraryMetadata(ids)
            val chosen = store.libraryMatches(ids)
            items.map { MatchRow(it, library, found[it.id], chosen[it.id]) }
                .filter { it.chosen != null || (it.found != null && it.found.tmdbId == null) }
                .sortedBy { it.item.sortTitle }
        }

    /** The movie or show [id], or null when no library holds one by that id. */
    internal suspend fun item(id: String): LibraryItemRecord? =
        store.libraryItem(id)?.takeIf { it.kind == ItemKind.MOVIE || it.kind == ItemKind.SHOW }

    internal suspend fun search(item: LibraryItemRecord, query: String, year: Int?): List<TmdbCandidate> =
        metadata.candidates(item.kind, query, year)

    /** Matches [item] to TMDB's entry [tmdbId] from now on and returns that entry's title; a [TmdbFailure] if none. */
    internal suspend fun choose(item: LibraryItemRecord, tmdbId: Int): String {
        val title = metadata.titleOf(item.kind, tmdbId)
        store.saveLibraryMatch(item.id, tmdbId, clock.instant())
        rescan(item.libraryId)
        return title
    }

    /** Lets the scan match [item] itself again; false when no match was chosen for it. */
    internal suspend fun clear(item: LibraryItemRecord): Boolean =
        store.deleteLibraryMatch(item.id).also { if (it) rescan(item.libraryId) }
}

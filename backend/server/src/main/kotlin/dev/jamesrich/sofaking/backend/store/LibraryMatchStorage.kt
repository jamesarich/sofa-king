package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/** The TMDB ids chosen by hand for [itemIds], by item id. */
suspend fun BackendStore.libraryMatches(itemIds: Collection<String>): Map<String, Int> = read {
    itemIds.chunked(ROW_CHUNK).flatMap { chunk ->
        LibraryMatchRows.selectAll().where { LibraryMatchRows.itemId inList chunk }
            .map { it[LibraryMatchRows.itemId] to it[LibraryMatchRows.tmdbId] }
    }.toMap()
}

suspend fun BackendStore.saveLibraryMatch(itemId: String, tmdbId: Int, at: Instant) = query {
    LibraryMatchRows.upsert {
        it[LibraryMatchRows.itemId] = itemId
        it[LibraryMatchRows.tmdbId] = tmdbId
        it[chosenAt] = at.toString()
    }
    Unit
}

/**
 * Drops the match chosen by hand for [itemId] and the lookup it led to, so the next scan matches the item itself;
 * false when it had none.
 */
suspend fun BackendStore.deleteLibraryMatch(itemId: String): Boolean = query {
    val had = LibraryMatchRows.deleteWhere { LibraryMatchRows.itemId eq itemId } > 0
    if (had) LibraryMetadataRows.deleteWhere { LibraryMetadataRows.itemId eq itemId }
    had
}

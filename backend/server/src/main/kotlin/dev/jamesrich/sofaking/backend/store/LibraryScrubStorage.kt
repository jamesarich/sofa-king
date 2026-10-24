package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.library.ScrubRecord
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/** The scrub thumbnails made for [itemIds], by item id. */
suspend fun BackendStore.libraryScrubs(itemIds: Collection<String>): Map<String, ScrubRecord> = read {
    itemIds.chunked(ROW_CHUNK).flatMap { chunk ->
        LibraryScrubRows.selectAll().where { LibraryScrubRows.itemId inList chunk }.map { it.scrub() }
    }
        .associateBy { it.itemId }
}

/** [itemId]'s scrub thumbnails and the file they were made from. */
suspend fun BackendStore.libraryScrub(itemId: String): ScrubRecord? = read {
    LibraryScrubRows.selectAll().where { LibraryScrubRows.itemId eq itemId }.singleOrNull()?.scrub()
}

suspend fun BackendStore.saveLibraryScrub(record: ScrubRecord) = query {
    LibraryScrubRows.upsert {
        it[itemId] = record.itemId
        it[fileKey] = record.fileKey
        it[intervalMs] = record.intervalMs
        it[tileWidth] = record.tileWidth
        it[tileHeight] = record.tileHeight
        it[columnCount] = record.columns
        it[rowCount] = record.rows
        it[sheets] = record.sheets
        it[frames] = record.frames
        it[madeAt] = record.madeAt
    }
}

/** Drops the rows of items that are gone, and answers the ids of the items that keep theirs. */
suspend fun BackendStore.deleteOrphanLibraryScrubs(): Set<String> = query {
    LibraryScrubRows.deleteWhere { itemId notInSubQuery LibraryItems.select(LibraryItems.id) }
    LibraryScrubRows.select(LibraryScrubRows.itemId).map { it[LibraryScrubRows.itemId] }.toSet()
}

private fun ResultRow.scrub() = ScrubRecord(
    this[LibraryScrubRows.itemId],
    this[LibraryScrubRows.fileKey],
    this[LibraryScrubRows.intervalMs],
    this[LibraryScrubRows.tileWidth],
    this[LibraryScrubRows.tileHeight],
    this[LibraryScrubRows.columnCount],
    this[LibraryScrubRows.rowCount],
    this[LibraryScrubRows.sheets],
    this[LibraryScrubRows.frames],
    this[LibraryScrubRows.madeAt],
)

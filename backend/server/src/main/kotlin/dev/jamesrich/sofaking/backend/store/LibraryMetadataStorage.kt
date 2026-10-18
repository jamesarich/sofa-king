package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.library.MetadataFacts
import dev.jamesrich.sofaking.backend.library.MetadataRecord
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll

private val factsJson = Json { ignoreUnknownKeys = true }

/** The stored lookups of [itemIds], by item id. */
suspend fun BackendStore.libraryMetadata(itemIds: Collection<String>): Map<String, MetadataRecord> = query {
    itemIds.chunked(500).flatMap { chunk -> LibraryMetadataRows.selectAll().where { LibraryMetadataRows.itemId inList chunk }.map { it.metadata() } }
        .associateBy { it.itemId }
}

suspend fun BackendStore.saveLibraryMetadata(records: List<MetadataRecord>) = query {
    records.chunked(500).forEach { chunk ->
        LibraryMetadataRows.batchUpsert(chunk) { record ->
            this[LibraryMetadataRows.itemId] = record.itemId
            this[LibraryMetadataRows.tmdbId] = record.tmdbId
            this[LibraryMetadataRows.checkedAt] = record.checkedAt
            this[LibraryMetadataRows.factsJson] = record.facts?.let { factsJson.encodeToString(MetadataFacts.serializer(), it) }
        }
    }
}

/** Forgets the lookups of [itemIds], so the next refresh asks again. */
suspend fun BackendStore.deleteLibraryMetadata(itemIds: Collection<String>) = query {
    itemIds.chunked(500).forEach { chunk -> LibraryMetadataRows.deleteWhere { itemId inList chunk } }
}

private fun ResultRow.metadata() = MetadataRecord(
    this[LibraryMetadataRows.itemId], this[LibraryMetadataRows.tmdbId], this[LibraryMetadataRows.checkedAt],
    this[LibraryMetadataRows.factsJson]?.let { runCatching { factsJson.decodeFromString(MetadataFacts.serializer(), it) }.getOrNull() },
)

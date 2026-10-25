package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.library.ItemDetails
import dev.jamesrich.sofaking.backend.library.LibraryItemRecord
import dev.jamesrich.sofaking.backend.library.MediaInfo
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/** A stored document may carry fields this server does not declare. */
private val libraryJson = Json { ignoreUnknownKeys = true }

/** Every item of the library, present or missing. */
suspend fun BackendStore.libraryItems(libraryId: String): List<LibraryItemRecord> = read {
    LibraryItems.selectAll().where { LibraryItems.libraryId eq libraryId }.map { it.item() }
}

/** One item, present or missing; null when no library holds it. */
suspend fun BackendStore.libraryItem(id: String): LibraryItemRecord? = read {
    LibraryItems.selectAll().where { LibraryItems.id eq id }.singleOrNull()?.item()
}

/** Present items by library and kind, for the portal's counts. */
suspend fun BackendStore.libraryItemCounts(): Map<String, Map<String, Int>> = read {
    val count = LibraryItems.id.count()
    LibraryItems.select(LibraryItems.libraryId, LibraryItems.kind, count).where { LibraryItems.missingSince.isNull() }
        .groupBy(LibraryItems.libraryId, LibraryItems.kind)
        .groupBy({ it[LibraryItems.libraryId] }, { it[LibraryItems.kind] to it[count].toInt() })
        .mapValues { (_, pairs) -> pairs.toMap() }
}

/** Writes [items] whole, replacing the stored rows with the same ids. */
suspend fun BackendStore.saveLibraryItems(items: List<LibraryItemRecord>) = query {
    items.chunked(ROW_CHUNK).forEach { chunk -> LibraryItems.batchUpsert(chunk) { item -> write(item) } }
}

/** Marks [ids] missing since [at], leaving those already marked as they are. */
suspend fun BackendStore.markLibraryItemsMissing(ids: Collection<String>, at: Instant) = query {
    ids.chunked(ROW_CHUNK).forEach { chunk ->
        LibraryItems.update({ (LibraryItems.id inList chunk) and LibraryItems.missingSince.isNull() }) {
            it[missingSince] =
                at.toString()
        }
    }
}

/** Deletes [ids], their watch state and their TMDB lookups. */
suspend fun BackendStore.deleteLibraryItems(ids: Collection<String>) = query {
    ids.chunked(ROW_CHUNK).forEach { chunk ->
        PlayStates.deleteWhere { itemId inList chunk }
        LibraryMetadataRows.deleteWhere { itemId inList chunk }
        LibraryMatchRows.deleteWhere { itemId inList chunk }
        LibraryIntroRows.deleteWhere { itemId inList chunk }
        LibraryItems.deleteWhere { id inList chunk }
    }
}

private fun UpdateBuilder<*>.write(item: LibraryItemRecord) {
    this[LibraryItems.id] = item.id
    this[LibraryItems.libraryId] = item.libraryId
    this[LibraryItems.kind] = item.kind
    this[LibraryItems.parentId] = item.parentId
    this[LibraryItems.path] = item.path
    this[LibraryItems.title] = item.title
    this[LibraryItems.sortTitle] = item.sortTitle
    this[LibraryItems.year] = item.year
    this[LibraryItems.season] = item.season
    this[LibraryItems.episode] = item.episode
    this[LibraryItems.episodeEnd] = item.episodeEnd
    this[LibraryItems.aired] = item.aired
    this[LibraryItems.overview] = item.overview
    this[LibraryItems.durationMs] = item.durationMs
    this[LibraryItems.size] = item.size
    this[LibraryItems.modifiedAt] = item.modifiedAt
    this[LibraryItems.mediaJson] = item.media?.let { libraryJson.encodeToString(MediaInfo.serializer(), it) }
    this[LibraryItems.detailsJson] = item.details?.let { libraryJson.encodeToString(ItemDetails.serializer(), it) }
    this[LibraryItems.poster] = item.poster
    this[LibraryItems.backdrop] = item.backdrop
    this[LibraryItems.addedAt] = item.addedAt
    this[LibraryItems.missingSince] = item.missingSince
}

private fun ResultRow.item() = LibraryItemRecord(
    id = this[LibraryItems.id],
    libraryId = this[LibraryItems.libraryId],
    kind = this[LibraryItems.kind],
    parentId = this[LibraryItems.parentId],
    path = this[LibraryItems.path],
    title = this[LibraryItems.title],
    sortTitle = this[LibraryItems.sortTitle],
    year = this[LibraryItems.year],
    season = this[LibraryItems.season],
    episode = this[LibraryItems.episode],
    episodeEnd = this[LibraryItems.episodeEnd],
    aired = this[LibraryItems.aired],
    overview = this[LibraryItems.overview],
    durationMs = this[LibraryItems.durationMs],
    size = this[LibraryItems.size],
    modifiedAt = this[LibraryItems.modifiedAt],
    media = this[LibraryItems.mediaJson]?.let {
        runCatching { libraryJson.decodeFromString(MediaInfo.serializer(), it) }.getOrNull()
    },
    details = this[LibraryItems.detailsJson]?.let {
        runCatching { libraryJson.decodeFromString(ItemDetails.serializer(), it) }.getOrNull()
    },
    poster = this[LibraryItems.poster],
    backdrop = this[LibraryItems.backdrop],
    addedAt = this[LibraryItems.addedAt],
    missingSince = this[LibraryItems.missingSince],
)

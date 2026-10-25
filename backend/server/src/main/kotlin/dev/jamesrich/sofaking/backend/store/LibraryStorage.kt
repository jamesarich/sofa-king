package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.LibraryRecord
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

suspend fun BackendStore.libraries(): List<LibraryRecord> = read {
    Libraries.selectAll().mapNotNull { it.library() }.sortedBy { it.name.lowercase() }
}

suspend fun BackendStore.library(id: String): LibraryRecord? = read {
    Libraries.selectAll().where { Libraries.id eq id }.singleOrNull()?.library()
}

suspend fun BackendStore.insertLibrary(library: LibraryRecord) = query {
    Libraries.insert {
        it[id] = library.id
        it[name] = library.name
        it[kind] = library.kind.wire
        it[path] = library.path
        it[createdAt] = library.createdAt
    }
    Unit
}

suspend fun BackendStore.renameLibrary(id: String, name: String): Boolean = query {
    Libraries.update({ Libraries.id eq id }) { it[Libraries.name] = name } > 0
}

/** Removes the library, its items, their watch state and their TMDB lookups; the files are never touched. */
suspend fun BackendStore.deleteLibrary(id: String): Boolean = query {
    val itemIds = LibraryItems.select(LibraryItems.id).where {
        LibraryItems.libraryId eq id
    }.map { it[LibraryItems.id] }
    itemIds.chunked(ROW_CHUNK).forEach { chunk ->
        PlayStates.deleteWhere { itemId inList chunk }
        LibraryMetadataRows.deleteWhere { itemId inList chunk }
        LibraryMatchRows.deleteWhere { itemId inList chunk }
        LibraryIntroRows.deleteWhere { itemId inList chunk }
    }
    LibraryItems.deleteWhere { libraryId eq id }
    Libraries.deleteWhere { Libraries.id eq id } > 0
}

suspend fun BackendStore.recordLibraryScan(id: String, at: Instant, ok: Boolean, note: String) = query {
    Libraries.update({ Libraries.id eq id }) {
        it[scannedAt] = at.toString()
        it[scanOk] = ok
        it[scanNote] = note
    }
    Unit
}

/** A row with a kind this server does not know is skipped, so a downgrade never fails the whole list. */
private fun ResultRow.library(): LibraryRecord? = LibraryKind.of(this[Libraries.kind])?.let { kind ->
    LibraryRecord(
        this[Libraries.id],
        this[Libraries.name],
        kind,
        this[Libraries.path],
        this[Libraries.createdAt],
        this[Libraries.scannedAt],
        this[Libraries.scanOk],
        this[Libraries.scanNote],
    )
}

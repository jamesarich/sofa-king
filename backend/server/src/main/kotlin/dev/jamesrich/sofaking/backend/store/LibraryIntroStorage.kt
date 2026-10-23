package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.library.IntroRecord
import dev.jamesrich.sofaking.backend.library.IntroSpan
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The stored fingerprints, intros and credits of [itemIds], by item id. */
suspend fun BackendStore.libraryIntros(itemIds: Collection<String>): Map<String, IntroRecord> = read {
    itemIds.chunked(500).flatMap { chunk ->
        LibraryIntroRows.selectAll().where {
            LibraryIntroRows.itemId inList chunk
        }.map { it.intro(withFingerprint = true) }
    }
        .associateBy { it.itemId }
}

/** [itemId]'s intro and credits and the file they were found in, without the fingerprints. */
suspend fun BackendStore.libraryIntro(itemId: String): IntroRecord? = read {
    LibraryIntroRows.select(
        LibraryIntroRows.itemId,
        LibraryIntroRows.fileKey,
        LibraryIntroRows.introStartMs,
        LibraryIntroRows.introEndMs,
        LibraryIntroRows.readAt,
        LibraryIntroRows.tailStartMs,
        LibraryIntroRows.creditsStartMs,
        LibraryIntroRows.creditsEndMs,
    )
        .where { LibraryIntroRows.itemId eq itemId }.singleOrNull()?.intro(withFingerprint = false)
}

/**
 * Keeps newly read fingerprints; the intro and credits of the file they replace are dropped
 * until the show is matched again.
 */
suspend fun BackendStore.saveLibraryFingerprint(record: IntroRecord) = query {
    LibraryIntroRows.upsert {
        it[itemId] = record.itemId
        it[fileKey] = record.fileKey
        it[fingerprint] = record.fingerprint?.let(::bytes)
        it[introStartMs] = record.intro?.startMs
        it[introEndMs] = record.intro?.endMs
        it[readAt] = record.readAt
        it[tailFingerprint] = record.tail?.let(::bytes)
        it[tailStartMs] = record.tailStartMs
        it[creditsStartMs] = record.credits?.startMs
        it[creditsEndMs] = record.credits?.endMs
    }
}

/** Sets the intro of each item, null where none was found. */
suspend fun BackendStore.saveLibraryIntros(intros: Map<String, IntroSpan?>) = query {
    intros.forEach { (id, span) ->
        LibraryIntroRows.update({ LibraryIntroRows.itemId eq id }) {
            it[introStartMs] = span?.startMs
            it[introEndMs] = span?.endMs
        }
    }
}

/** Sets the credits of each item, from the start of its file, null where none were found. */
suspend fun BackendStore.saveLibraryCredits(credits: Map<String, IntroSpan?>) = query {
    credits.forEach { (id, span) ->
        LibraryIntroRows.update({ LibraryIntroRows.itemId eq id }) {
            it[creditsStartMs] = span?.startMs
            it[creditsEndMs] = span?.endMs
        }
    }
}

/** Drops the rows of items that are gone, such as those of a library removed while its episodes were being read. */
suspend fun BackendStore.deleteOrphanLibraryIntros() = query {
    LibraryIntroRows.deleteWhere { itemId notInSubQuery LibraryItems.select(LibraryItems.id) }
}

private fun ResultRow.intro(withFingerprint: Boolean): IntroRecord {
    fun span(start: Long?, end: Long?) = if (start != null && end != null) IntroSpan(start, end) else null
    return IntroRecord(
        this[LibraryIntroRows.itemId],
        this[LibraryIntroRows.fileKey],
        if (withFingerprint) this[LibraryIntroRows.fingerprint]?.let(::points) else null,
        span(this[LibraryIntroRows.introStartMs], this[LibraryIntroRows.introEndMs]),
        this[LibraryIntroRows.readAt],
        tail = if (withFingerprint) this[LibraryIntroRows.tailFingerprint]?.let(::points) else null,
        tailStartMs = this[LibraryIntroRows.tailStartMs],
        credits = span(this[LibraryIntroRows.creditsStartMs], this[LibraryIntroRows.creditsEndMs]),
    )
}

/** Chromaprint's points as little-endian 32-bit words, as FFmpeg writes them. */
internal fun bytes(points: IntArray): ByteArray =
    ByteBuffer.allocate(points.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(points) }.array()

internal fun points(bytes: ByteArray): IntArray {
    val words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
    return IntArray(words.remaining()).also(words::get)
}

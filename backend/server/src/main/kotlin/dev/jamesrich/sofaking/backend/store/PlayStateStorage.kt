package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.library.ItemDetails
import dev.jamesrich.sofaking.backend.library.LibraryItemRecord
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.LibraryRecord
import dev.jamesrich.sofaking.backend.library.MediaInfo
import dev.jamesrich.sofaking.backend.library.PlayStateRecord
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/** Every stored watch state, by item id; the table holds a row only for items someone started or finished. */
suspend fun BackendStore.playStates(): Map<String, PlayStateRecord> = read {
    PlayStates.selectAll().map {
        PlayStateRecord(
            it[PlayStates.itemId],
            it[PlayStates.positionMs],
            it[PlayStates.durationMs],
            it[PlayStates.watched],
            it[PlayStates.updatedAt],
        )
    }.associateBy { it.itemId }
}

/** Writes [states], and forgets [cleared]: an item back at the start and unwatched keeps no row. */
suspend fun BackendStore.savePlayStates(states: List<PlayStateRecord>, cleared: Collection<String> = emptyList()) =
    query {
        states.chunked(ROW_CHUNK).forEach { chunk ->
            PlayStates.batchUpsert(chunk) { state ->
                this[PlayStates.itemId] = state.itemId
                this[PlayStates.positionMs] = state.positionMs
                this[PlayStates.durationMs] = state.durationMs
                this[PlayStates.watched] = state.watched
                this[PlayStates.updatedAt] = state.updatedAt
            }
        }
        cleared.chunked(ROW_CHUNK).forEach { chunk -> PlayStates.deleteWhere { itemId inList chunk } }
    }

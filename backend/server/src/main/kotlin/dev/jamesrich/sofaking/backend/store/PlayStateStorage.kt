package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.library.PlayStateRecord
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Every stored watch state, by item id; the table holds a row only for items someone started, finished or liked. */
suspend fun BackendStore.playStates(): Map<String, PlayStateRecord> = read {
    PlayStates.selectAll().map {
        PlayStateRecord(
            it[PlayStates.itemId],
            it[PlayStates.positionMs],
            it[PlayStates.durationMs],
            it[PlayStates.watched],
            it[PlayStates.updatedAt],
            it[PlayStates.dismissed],
            it[PlayStates.plays],
            it[PlayStates.favorite],
            it[PlayStates.lastPlayedAt],
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
                this[PlayStates.dismissed] = state.dismissed
                this[PlayStates.plays] = state.plays
                this[PlayStates.favorite] = state.favorite
                this[PlayStates.lastPlayedAt] = state.lastPlayedAt
            }
        }
        cleared.chunked(ROW_CHUNK).forEach { chunk -> PlayStates.deleteWhere { itemId inList chunk } }
    }

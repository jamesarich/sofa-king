package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.HistoryEntry
import dev.jamesrich.sofaking.backend.api.HistoryQuery
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Appends ended sessions to the history; their times are whole seconds, so the text columns sort as time. */
suspend fun BackendStore.addHistory(entries: List<HistoryEntry>) {
    if (entries.isEmpty()) return
    query {
        entries.chunked(ROW_CHUNK).forEach { chunk ->
            SessionHistoryRows.batchInsert(chunk, ignore = true, shouldReturnGeneratedValues = false) { entry ->
                this[SessionHistoryRows.id] = entry.id
                this[SessionHistoryRows.kind] = entry.kind
                this[SessionHistoryRows.clientId] = entry.clientId
                this[SessionHistoryRows.address] = entry.address
                this[SessionHistoryRows.channelId] = entry.channelId
                this[SessionHistoryRows.itemId] = entry.itemId
                this[SessionHistoryRows.recordingId] = entry.recordingId
                this[SessionHistoryRows.providerId] = entry.providerId
                this[SessionHistoryRows.mode] = entry.mode
                this[SessionHistoryRows.reason] = entry.reason
                this[SessionHistoryRows.startedAt] = entry.startedAt
                this[SessionHistoryRows.endedAt] = entry.endedAt
                this[SessionHistoryRows.bytes] = entry.bytes
            }
        }
    }
}

/** The ended sessions [query] matches, the newest end first. */
suspend fun BackendStore.history(query: HistoryQuery): List<HistoryEntry> = read {
    val filters = listOfNotNull<Op<Boolean>>(
        query.since?.let { SessionHistoryRows.endedAt greaterEq it.wholeSeconds() },
        query.kind?.let { SessionHistoryRows.kind eq it },
        query.client?.let { (SessionHistoryRows.clientId eq it) or (SessionHistoryRows.address eq it) },
    )
    val rows = filters.reduceOrNull { a, b -> a and b }?.let { SessionHistoryRows.selectAll().where(it) }
        ?: SessionHistoryRows.selectAll()
    rows.orderBy(SessionHistoryRows.endedAt to SortOrder.DESC).limit(query.limit).map {
        HistoryEntry(
            it[SessionHistoryRows.id],
            it[SessionHistoryRows.kind],
            it[SessionHistoryRows.clientId],
            it[SessionHistoryRows.address],
            it[SessionHistoryRows.channelId],
            it[SessionHistoryRows.itemId],
            it[SessionHistoryRows.recordingId],
            it[SessionHistoryRows.providerId],
            it[SessionHistoryRows.mode],
            it[SessionHistoryRows.reason],
            it[SessionHistoryRows.startedAt],
            it[SessionHistoryRows.endedAt],
            it[SessionHistoryRows.bytes],
        )
    }
}

/** Deletes the sessions that ended before [cutoff]; the count deleted. */
suspend fun BackendStore.pruneHistory(cutoff: Instant): Int = query {
    SessionHistoryRows.deleteWhere { endedAt less cutoff.wholeSeconds() }
}

/** [this] as the history stores times: whole seconds, which always print with the same width. */
internal fun Instant.wholeSeconds(): String = truncatedTo(ChronoUnit.SECONDS).toString()

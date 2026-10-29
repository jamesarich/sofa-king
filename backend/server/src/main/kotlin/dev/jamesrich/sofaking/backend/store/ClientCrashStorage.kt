package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.ClientCrash
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/** How many reported crashes the server keeps; each new one beyond this removes the oldest. */
const val KEPT_CRASHES = 100

private val framesJson = ListSerializer(String.serializer())

/** Keeps [crash] unless one with its id is stored already (a report sent twice), then prunes to [KEPT_CRASHES]. */
suspend fun BackendStore.addClientCrash(crash: ClientCrash) {
    query {
        ClientCrashRows.insertIgnore {
            it[id] = crash.id
            it[clientId] = crash.clientId
            it[clientName] = crash.clientName
            it[receivedAt] = crash.receivedAt
            it[occurredAt] = crash.occurredAt
            it[exception] = crash.exception
            it[message] = crash.message
            it[frames] = Json.encodeToString(framesJson, crash.frames)
            it[appVersion] = crash.appVersion
            it[platform] = crash.platform
            it[osVersion] = crash.osVersion
            it[device] = crash.device
        }
        exec(
            "DELETE FROM client_crash WHERE id NOT IN " +
                "(SELECT id FROM client_crash ORDER BY received_at DESC, id DESC LIMIT $KEPT_CRASHES)",
        )
    }
}

/** The newest [limit] crashes, optionally only those received at or after [since]. */
suspend fun BackendStore.clientCrashes(limit: Int, since: Instant? = null): List<ClientCrash> = read {
    val all = ClientCrashRows.selectAll()
    val rows = if (since == null) all else all.where { ClientCrashRows.receivedAt greaterEq since.wholeSeconds() }
    rows.orderBy(ClientCrashRows.receivedAt to SortOrder.DESC, ClientCrashRows.id to SortOrder.DESC)
        .limit(limit).map { it.crash() }
}

private fun ResultRow.crash() = ClientCrash(
    id = this[ClientCrashRows.id],
    clientId = this[ClientCrashRows.clientId],
    clientName = this[ClientCrashRows.clientName],
    receivedAt = this[ClientCrashRows.receivedAt],
    occurredAt = this[ClientCrashRows.occurredAt],
    exception = this[ClientCrashRows.exception],
    message = this[ClientCrashRows.message],
    frames = Json.decodeFromString(framesJson, this[ClientCrashRows.frames]),
    appVersion = this[ClientCrashRows.appVersion],
    platform = this[ClientCrashRows.platform],
    osVersion = this[ClientCrashRows.osVersion],
    device = this[ClientCrashRows.device],
)

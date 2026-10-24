package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.PlexStatus
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

private const val PLEX_STATUS_KEY = "plex_status"

/**
 * The last Plex sync's status, kept so it outlives a restart; null before the first
 * sync or when it no longer decodes.
 */
suspend fun BackendStore.plexStatus(): PlexStatus? {
    val encoded =
        query { Metadata.selectAll().where { Metadata.key eq PLEX_STATUS_KEY }.singleOrNull()?.get(Metadata.value) }
            ?: return null
    return runCatching { snapshotJson.decodeFromString<PlexStatus>(encoded) }.getOrNull()
}

suspend fun BackendStore.savePlexStatus(status: PlexStatus) {
    val encoded = Json.encodeToString(status)
    query {
        Metadata.upsert {
            it[key] = PLEX_STATUS_KEY
            it[value] = encoded
        }
    }
}

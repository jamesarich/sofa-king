package dev.jamesrich.sofaking.backend.store

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

private const val HDHR_ALLOW_KEY = "hdhr_allow"

/**
 * The addresses the portal allowed onto the HDHomeRun emulation, as IP literals or CIDR
 * blocks; empty before any is saved.
 */
suspend fun BackendStore.hdhrAllowed(): List<String> {
    val encoded =
        read { Metadata.selectAll().where { Metadata.key eq HDHR_ALLOW_KEY }.singleOrNull()?.get(Metadata.value) }
            ?: return emptyList()
    return runCatching { Json.decodeFromString<List<String>>(encoded) }.getOrDefault(emptyList())
}

suspend fun BackendStore.saveHdhrAllowed(entries: List<String>) {
    val encoded = Json.encodeToString(entries)
    query {
        Metadata.upsert {
            it[key] = HDHR_ALLOW_KEY
            it[value] = encoded
        }
        Unit
    }
}

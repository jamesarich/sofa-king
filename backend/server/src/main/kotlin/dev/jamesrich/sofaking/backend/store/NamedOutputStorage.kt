package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.NamedOutput
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

private const val NAMED_OUTPUTS_KEY = "named_outputs"

/** The named player outputs saved from the portal or the admin API; empty before any is saved. */
suspend fun BackendStore.namedOutputs(): List<NamedOutput> {
    val encoded =
        read { Metadata.selectAll().where { Metadata.key eq NAMED_OUTPUTS_KEY }.singleOrNull()?.get(Metadata.value) }
            ?: return emptyList()
    return runCatching { Json.decodeFromString<List<NamedOutput>>(encoded) }.getOrDefault(emptyList())
}

suspend fun BackendStore.saveNamedOutputs(outputs: List<NamedOutput>) {
    val encoded = Json.encodeToString(outputs)
    query {
        Metadata.upsert {
            it[key] = NAMED_OUTPUTS_KEY
            it[value] = encoded
        }
        Unit
    }
}

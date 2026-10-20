package dev.jamesrich.sofaking.backend.store

import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** The runtime settings the operator saved, by key; a key that is absent takes the environment's value. */
suspend fun BackendStore.runtimeSettings(): Map<String, String> = read {
    RuntimeSettings.selectAll().associate { it[RuntimeSettings.key] to it[RuntimeSettings.value] }
}

/** Replaces the saved runtime settings with [values]; an empty map puts every setting back to the environment's value. */
suspend fun BackendStore.saveRuntimeSettings(values: Map<String, String>, now: Instant) = query {
    RuntimeSettings.deleteAll()
    for ((key, value) in values) RuntimeSettings.insert {
        it[RuntimeSettings.key] = key
        it[RuntimeSettings.value] = value
        it[updatedAt] = now.toString()
    }
    Unit
}

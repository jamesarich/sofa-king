package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/** The runtime settings the operator saved, by key; a key that is absent takes the environment's value. */
suspend fun BackendStore.runtimeSettings(): Map<String, String> = read {
    RuntimeSettingTable.selectAll().associate { it[RuntimeSettingTable.key] to it[RuntimeSettingTable.value] }
}

/**
 * Replaces the saved runtime settings with [values]; an empty map puts every setting back to the environment's value.
 */
suspend fun BackendStore.saveRuntimeSettings(values: Map<String, String>, now: Instant) = query {
    RuntimeSettingTable.deleteAll()
    for ((key, value) in values) {
        RuntimeSettingTable.insert {
            it[RuntimeSettingTable.key] = key
            it[RuntimeSettingTable.value] = value
            it[updatedAt] = now.toString()
        }
    }
    Unit
}

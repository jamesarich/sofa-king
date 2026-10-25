package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.contract.HouseholdPreferences
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/** A stored document may carry fields this server's contract does not declare. */
private val householdJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The household's shared preferences, empty with no [HouseholdPreferences.updatedAt] until a device writes them. */
suspend fun BackendStore.householdPreferences(): HouseholdPreferences = read {
    HouseholdPreferencesTable.selectAll().where { HouseholdPreferencesTable.scope eq HOUSEHOLD }.singleOrNull()
        ?.let { row ->
            runCatching {
                householdJson.decodeFromString(
                    HouseholdPreferences.serializer(),
                    row[HouseholdPreferencesTable.preferencesJson],
                )
            }.getOrNull()?.copy(updatedAt = row[HouseholdPreferencesTable.updatedAt])
        }
        ?: HouseholdPreferences()
}

/** Replaces the household's shared preferences with [preferences], stamped [now]; the stored document is returned. */
suspend fun BackendStore.saveHouseholdPreferences(
    preferences: HouseholdPreferences,
    now: Instant,
): HouseholdPreferences = query {
    val stored = preferences.copy(updatedAt = now.toString())
    HouseholdPreferencesTable.upsert {
        it[scope] = HOUSEHOLD
        it[preferencesJson] = householdJson.encodeToString(HouseholdPreferences.serializer(), stored)
        it[updatedAt] = stored.updatedAt
    }
    stored
}

package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.catalog.StationAvailability
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

private val availabilityJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }

@Serializable private data class StoredAvailability(
    val unavailableSince: String? = null, val autoDisabled: Boolean = false, val missingSince: String? = null,
    val lastNumber: String? = null, val lastName: String? = null, val pinOfferings: Map<String, List<String>> = emptyMap(),
    val verifiedAt: String? = null,
)

/** What the last policy run remembered per station key. */
suspend fun BackendStore.stationAvailability(): Map<String, StationAvailability> = query {
    StationAvailabilityRows.selectAll().associate { row ->
        val stored = availabilityJson.decodeFromString<StoredAvailability>(row[StationAvailabilityRows.availabilityJson])
        row[StationAvailabilityRows.key] to StationAvailability(
            stored.unavailableSince?.let(Instant::parse), stored.autoDisabled, stored.missingSince?.let(Instant::parse),
            stored.lastNumber, stored.lastName, stored.pinOfferings, stored.verifiedAt?.let(Instant::parse),
        )
    }
}

/** Replaces every row with [availability] in one transaction. */
suspend fun BackendStore.saveStationAvailability(availability: Map<String, StationAvailability>, at: Instant) = query {
    StationAvailabilityRows.deleteAll()
    availability.entries.sortedBy { it.key }.chunked(2000).forEach { chunk ->
        StationAvailabilityRows.batchInsert(chunk) { (key, value) ->
            this[StationAvailabilityRows.key] = key
            this[StationAvailabilityRows.availabilityJson] = availabilityJson.encodeToString(
                StoredAvailability(
                    value.unavailableSince?.toString(), value.autoDisabled, value.missingSince?.toString(), value.lastNumber, value.lastName, value.pinOfferings,
                    value.verifiedAt?.toString(),
                ),
            )
            this[StationAvailabilityRows.updatedAt] = at.toString()
        }
    }
    Unit
}

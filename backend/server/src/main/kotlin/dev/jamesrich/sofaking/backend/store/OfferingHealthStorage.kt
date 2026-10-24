package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.health.HealthPolicy
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.Observation
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val policy = HealthPolicy()

/** The offering_health table in memory, published after each commit that changes it. */
internal class HealthMirror {
    val states = AtomicReference<Map<String, HealthState>>(emptyMap())

    /** The table's state at open and the writes since, so [version] never queries SQLite on the catalog route. */
    private val seed = AtomicReference("")
    private val writes = AtomicLong()

    /** Changes whenever any health row is written, added or removed, and across a restart that wrote any. */
    val version: String get() = "${seed.get()}:${writes.get()}"

    /** Reads the table, inside the store's opening transaction. */
    fun load() {
        states.set(
            OfferingHealth.selectAll().associate {
                it[OfferingHealth.offeringId] to
                    decodeHealth(it[OfferingHealth.stateJson])
            },
        )
        val count = OfferingHealth.offeringId.count()
        val latest = OfferingHealth.updatedAt.max()
        seed.set(OfferingHealth.select(count, latest).single().let { "${it[count]}:${it[latest] ?: ""}" })
    }

    fun publish(changed: Map<String, HealthState>) {
        if (changed.isEmpty()) return
        states.updateAndGet { it + changed }
        writes.incrementAndGet()
    }

    fun remove(gone: Set<String>) {
        if (gone.isEmpty()) return
        states.updateAndGet { current -> current.filterKeys { it !in gone } }
        writes.incrementAndGet()
    }
}

suspend fun BackendStore.registerOffering(offeringId: String) {
    require(offeringId.isNotBlank()) { "Offering ID is required" }
    writeHealth { changed ->
        OfferingHealth.insertIgnore {
            it[OfferingHealth.offeringId] = offeringId
            it[stateJson] = HealthState().encode()
            it[updatedAt] = clock.instant().toString()
        }
        if (offeringId !in healthMirror.states.get()) changed[offeringId] = HealthState()
    }
}

suspend fun BackendStore.health(offeringId: String): HealthState? = healthMirror.states.get()[offeringId]

suspend fun BackendStore.recordObservation(offeringId: String, observation: Observation, now: Instant): HealthState =
    writeHealth { changed ->
        val current = requireNotNull(healthMirror.states.get()[offeringId]) { "Unknown offering" }
        val duplicate = HealthObservations.selectAll().where {
            (HealthObservations.offeringId eq offeringId) and
                (HealthObservations.incidentId eq observation.incidentId)
        }.any()
        if (duplicate) {
            current
        } else {
            val next = policy.apply(current, observation, now)
            HealthObservations.insert {
                it[HealthObservations.offeringId] = offeringId
                it[incidentId] = observation.incidentId
                it[observedAt] = observation.observedAt.toString()
                it[observationJson] = Json.encodeToString(observation.stored())
            }
            saveHealth(offeringId, next, now)
            changed[offeringId] = next
            next
        }
    }

/**
 * Deletes observation history older than [before]; health state and strikes live in
 * offering_health and are unaffected.
 */
suspend fun BackendStore.pruneObservations(before: Instant): Int = query {
    HealthObservations.deleteWhere { observedAt less before.toString() }
}

/**
 * Deletes the health, observations, probe attempt and detected protocol of every offering outside [live] whose
 * health last changed before [before]. A source disabled by hand keeps its row. Returns how many were deleted.
 */
suspend fun BackendStore.pruneOfferings(live: Set<String>, before: Instant): Int {
    val removed = query {
        val stale = OfferingHealth.selectAll().mapNotNull { row ->
            val id = row[OfferingHealth.offeringId]
            val updated = runCatching { Instant.parse(row[OfferingHealth.updatedAt]) }.getOrNull()
            id.takeIf {
                it !in live && updated != null && updated < before &&
                    !decodeHealth(row[OfferingHealth.stateJson]).manuallyDisabled
            }
        }
        for (chunk in stale.chunked(ROW_CHUNK)) {
            HealthObservations.deleteWhere { offeringId inList chunk }
            ProbeAttempts.deleteWhere { offeringId inList chunk }
            OfferingProtocols.deleteWhere { offeringId inList chunk }
            OfferingHealth.deleteWhere { offeringId inList chunk }
        }
        stale
    }
    healthMirror.remove(removed.toHashSet())
    return removed.size
}

suspend fun BackendStore.setManuallyDisabled(offeringId: String, disabled: Boolean): HealthState =
    writeHealth { changed ->
        val next = requireNotNull(healthMirror.states.get()[offeringId]) {
            "Unknown offering"
        }.copy(manuallyDisabled = disabled)
        saveHealth(offeringId, next, clock.instant())
        changed[offeringId] = next
        next
    }

suspend fun BackendStore.allHealth(): Map<String, HealthState> = healthMirror.states.get()

/** Changes whenever any health row is written, added or removed, and across a restart that wrote any; in memory. */
fun BackendStore.healthVersion(): String = healthMirror.version

private fun saveHealth(offeringId: String, state: HealthState, at: Instant) {
    OfferingHealth.update({ OfferingHealth.offeringId eq offeringId }) {
        it[stateJson] = state.encode()
        it[updatedAt] = at.toString()
    }
}

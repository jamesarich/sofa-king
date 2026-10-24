package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.catalog.FeedSnapshot
import dev.jamesrich.sofaking.backend.health.HealthState
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/** A stored snapshot may carry fields [FeedSnapshot] does not declare. */
internal val snapshotJson = Json { ignoreUnknownKeys = true }

/** Deletes the stored feed snapshots of [providerIds]. */
suspend fun BackendStore.deleteSnapshots(providerIds: Collection<String>) = query {
    Metadata.deleteWhere { Metadata.key inList providerIds.map { "feed:$it" } }
    Unit
}

suspend fun BackendStore.snapshots(): List<FeedSnapshot> {
    val encoded = query { Metadata.selectAll().where { keyPrefix("feed:") }.map { it[Metadata.value] } }
    return encoded.map { snapshotJson.decodeFromString(it) }
}

suspend fun BackendStore.saveSnapshot(snapshot: FeedSnapshot) {
    val encoded = Json.encodeToString(snapshot)
    val initial = HealthState().encode()
    writeHealth { changed ->
        val key = "feed:${snapshot.providerId}"
        Metadata.upsert {
            it[Metadata.key] = key
            it[value] = encoded
        }
        OfferingHealth.batchInsert(
            snapshot.offerings,
            ignore = true,
            shouldReturnGeneratedValues = false,
        ) { offering ->
            this[OfferingHealth.offeringId] = offering.id
            this[OfferingHealth.stateJson] = initial
            this[OfferingHealth.updatedAt] = snapshot.importedAt
        }
        val known = healthMirror.states.get()
        for (offering in snapshot.offerings) if (offering.id !in known) changed[offering.id] = HealthState()
    }
}

suspend fun BackendStore.probeAttempts(): Map<String, Instant> = query {
    ProbeAttempts.selectAll().associate {
        it[ProbeAttempts.offeringId] to
            Instant.parse(it[ProbeAttempts.attemptedAt])
    }
}

suspend fun BackendStore.markProbeAttempt(offeringId: String, at: Instant) = query {
    ProbeAttempts.upsert {
        it[ProbeAttempts.offeringId] = offeringId
        it[attemptedAt] = at.toString()
    }
    Unit
}

/** Protocols detected from a stream body, by offering ID; a detection outranks the protocol an import assigned. */
suspend fun BackendStore.offeringProtocols(): Map<String, String> = query {
    OfferingProtocols.selectAll().associate { it[OfferingProtocols.offeringId] to it[OfferingProtocols.protocol] }
}

suspend fun BackendStore.recordProtocol(offeringId: String, protocol: String, at: Instant) {
    require(protocol in setOf("hls", "mpegts")) { "Unsupported protocol" }
    query {
        OfferingProtocols.upsert {
            it[OfferingProtocols.offeringId] = offeringId
            it[OfferingProtocols.protocol] = protocol
            it[observedAt] = at.toString()
        }
    }
}

/** Range form of a prefix match, so the primary-key index serves it. */
private fun keyPrefix(prefix: String): Op<Boolean> {
    val end = prefix.dropLast(1) + (prefix.last() + 1)
    return (Metadata.key greaterEq prefix) and (Metadata.key less end)
}

package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.health.*
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** A stored snapshot may carry fields [dev.jamesrich.sofaking.backend.catalog.FeedSnapshot] does not declare. */
private val snapshotJson = Json { ignoreUnknownKeys = true }

private const val PLEX_STATUS_KEY = "plex_status"

interface IdentityStore {
    suspend fun serverId(): String
    suspend fun ready(): Boolean
}

/** Serializes short SQLite transactions; never owns network/media work. */
class BackendStore internal constructor(
    private val database: Database,
    private val executor: ExecutorService,
    private val dispatcher: ExecutorCoroutineDispatcher,
    private val clock: Clock,
) : IdentityStore, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val policy = HealthPolicy()

    /** Mirror of offering_health; only [writeHealth] replaces it, after the DB commit, so health reads never touch SQLite. */
    private val healthSnapshot = AtomicReference<Map<String, HealthState>>(emptyMap())

    /** The table's state at open and the writes since, so [healthVersion] never queries SQLite on the catalog route. */
    private val healthSeed = AtomicReference("")
    private val healthWrites = AtomicLong()

    internal suspend fun initialize() = databaseWork {
        applyMigrations(database, foundationMigrations(), clock)
        transaction(database) {
            Metadata.insertIgnore { it[key] = "server_id"; it[value] = UUID.randomUUID().toString() }
            healthSnapshot.set(OfferingHealth.selectAll().associate { it[OfferingHealth.offeringId] to decodeHealth(it[OfferingHealth.stateJson]) })
            val count = OfferingHealth.offeringId.count()
            val latest = OfferingHealth.updatedAt.max()
            healthSeed.set(OfferingHealth.select(count, latest).single().let { "${it[count]}:${it[latest] ?: ""}" })
        }
    }

    override suspend fun serverId(): String = query {
        Metadata.selectAll().where { Metadata.key eq "server_id" }.single()[Metadata.value]
    }

    override suspend fun ready(): Boolean {
        if (closed.get()) return false
        return try { query { exec("SELECT 1") { it.next() && it.getInt(1) == 1 } == true } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    suspend fun registerOffering(offeringId: String) {
        require(offeringId.isNotBlank()) { "Offering ID is required" }
        writeHealth { changed ->
            OfferingHealth.insertIgnore {
                it[OfferingHealth.offeringId] = offeringId
                it[stateJson] = HealthState().encode()
                it[updatedAt] = clock.instant().toString()
            }
            if (offeringId !in healthSnapshot.get()) changed[offeringId] = HealthState()
        }
    }

    suspend fun health(offeringId: String): HealthState? = healthSnapshot.get()[offeringId]

    suspend fun recordObservation(offeringId: String, observation: Observation, now: Instant): HealthState = writeHealth { changed ->
        val current = requireNotNull(healthSnapshot.get()[offeringId]) { "Unknown offering" }
        val duplicate = HealthObservations.selectAll().where {
            (HealthObservations.offeringId eq offeringId) and (HealthObservations.incidentId eq observation.incidentId)
        }.any()
        if (duplicate) current
        else {
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

    /** Deletes observation history older than [before]; health state and strikes live in offering_health and are unaffected. */
    suspend fun pruneObservations(before: Instant): Int = query {
        HealthObservations.deleteWhere { observedAt less before.toString() }
    }

    /**
     * Deletes the health, observations, probe attempt and detected protocol of every offering outside [live] whose
     * health last changed before [before]. A source disabled by hand keeps its row. Returns how many were deleted.
     */
    suspend fun pruneOfferings(live: Set<String>, before: Instant): Int {
        val removed = query {
            val stale = OfferingHealth.selectAll().mapNotNull { row ->
                val id = row[OfferingHealth.offeringId]
                val updated = runCatching { Instant.parse(row[OfferingHealth.updatedAt]) }.getOrNull()
                id.takeIf { it !in live && updated != null && updated < before && !decodeHealth(row[OfferingHealth.stateJson]).manuallyDisabled }
            }
            for (chunk in stale.chunked(500)) {
                HealthObservations.deleteWhere { offeringId inList chunk }
                ProbeAttempts.deleteWhere { offeringId inList chunk }
                OfferingProtocols.deleteWhere { offeringId inList chunk }
                OfferingHealth.deleteWhere { offeringId inList chunk }
            }
            stale
        }
        if (removed.isNotEmpty()) {
            val gone = removed.toHashSet()
            healthSnapshot.updateAndGet { current -> current.filterKeys { it !in gone } }
            healthWrites.incrementAndGet()
        }
        return removed.size
    }

    /** Deletes the stored feed snapshots of [providerIds]. */
    suspend fun deleteSnapshots(providerIds: Collection<String>) = query {
        Metadata.deleteWhere { Metadata.key inList providerIds.map { "feed:$it" } }
        Unit
    }

    suspend fun setManuallyDisabled(offeringId: String, disabled: Boolean): HealthState = writeHealth { changed ->
        val next = requireNotNull(healthSnapshot.get()[offeringId]) { "Unknown offering" }.copy(manuallyDisabled = disabled)
        saveHealth(offeringId, next, clock.instant())
        changed[offeringId] = next
        next
    }

    suspend fun snapshots(): List<dev.jamesrich.sofaking.backend.catalog.FeedSnapshot> {
        val encoded = query { Metadata.selectAll().where { keyPrefix("feed:") }.map { it[Metadata.value] } }
        return encoded.map { snapshotJson.decodeFromString(it) }
    }

    suspend fun saveSnapshot(snapshot: dev.jamesrich.sofaking.backend.catalog.FeedSnapshot) {
        val encoded = Json.encodeToString(snapshot)
        val initial = HealthState().encode()
        writeHealth { changed ->
            val key = "feed:${snapshot.providerId}"
            Metadata.upsert { it[Metadata.key] = key; it[value] = encoded }
            OfferingHealth.batchInsert(snapshot.offerings, ignore = true) { offering ->
                this[OfferingHealth.offeringId] = offering.id
                this[OfferingHealth.stateJson] = initial
                this[OfferingHealth.updatedAt] = snapshot.importedAt
            }
            val known = healthSnapshot.get()
            for (offering in snapshot.offerings) if (offering.id !in known) changed[offering.id] = HealthState()
        }
    }

    /** The last Plex sync's status, kept so it outlives a restart; null before the first sync or when it no longer decodes. */
    suspend fun plexStatus(): PlexStatus? {
        val encoded = query { Metadata.selectAll().where { Metadata.key eq PLEX_STATUS_KEY }.singleOrNull()?.get(Metadata.value) } ?: return null
        return runCatching { snapshotJson.decodeFromString<PlexStatus>(encoded) }.getOrNull()
    }

    suspend fun savePlexStatus(status: PlexStatus) {
        val encoded = Json.encodeToString(status)
        query {
            Metadata.upsert { it[key] = PLEX_STATUS_KEY; it[value] = encoded }
        }
    }

    /** The newest schema migration applied to the database. */
    suspend fun schemaVersion(): Int = query {
        exec("SELECT MAX(version) FROM schema_version") { if (it.next()) it.getInt(1) else 0 } ?: 0
    }

    suspend fun probeAttempts(): Map<String, Instant> = query {
        ProbeAttempts.selectAll().associate { it[ProbeAttempts.offeringId] to Instant.parse(it[ProbeAttempts.attemptedAt]) }
    }

    suspend fun markProbeAttempt(offeringId: String, at: Instant) = query {
        ProbeAttempts.upsert { it[ProbeAttempts.offeringId] = offeringId; it[attemptedAt] = at.toString() }
        Unit
    }

    /** Protocols detected from a stream body, by offering ID; a detection outranks the protocol an import assigned. */
    suspend fun offeringProtocols(): Map<String, String> = query {
        OfferingProtocols.selectAll().associate { it[OfferingProtocols.offeringId] to it[OfferingProtocols.protocol] }
    }

    suspend fun recordProtocol(offeringId: String, protocol: String, at: Instant) {
        require(protocol in setOf("hls", "mpegts")) { "Unsupported protocol" }
        query {
            OfferingProtocols.upsert {
                it[OfferingProtocols.offeringId] = offeringId
                it[OfferingProtocols.protocol] = protocol
                it[observedAt] = at.toString()
            }
        }
    }

    suspend fun allHealth(): Map<String, HealthState> = healthSnapshot.get()

    /** Changes whenever any health row is written, added or removed, and across a restart that wrote any; in memory. */
    fun healthVersion(): String = "${healthSeed.get()}:${healthWrites.get()}"

    private fun saveHealth(offeringId: String, state: HealthState, at: Instant) {
        OfferingHealth.update({ OfferingHealth.offeringId eq offeringId }) {
            it[stateJson] = state.encode()
            it[updatedAt] = at.toString()
        }
    }

    /** Range form of a prefix match, so the primary-key index serves it. */
    private fun keyPrefix(prefix: String): Op<Boolean> {
        val end = prefix.dropLast(1) + (prefix.last() + 1)
        return (Metadata.key greaterEq prefix) and (Metadata.key less end)
    }

    internal suspend fun <T> query(block: JdbcTransaction.() -> T): T = databaseWork {
        transaction(database) { maxAttempts = 1; block() }
    }

    /** Runs [block] in one transaction, then publishes the rows it changed; a rolled-back write publishes nothing. */
    private suspend fun <T> writeHealth(block: JdbcTransaction.(MutableMap<String, HealthState>) -> T): T = databaseWork {
        val changed = LinkedHashMap<String, HealthState>()
        val result = transaction(database) { maxAttempts = 1; block(changed) }
        if (changed.isNotEmpty()) {
            healthSnapshot.updateAndGet { it + changed }
            healthWrites.incrementAndGet()
        }
        result
    }

    private suspend fun <T> databaseWork(block: () -> T): T {
        check(!closed.get()) { "Store is closed" }
        return withContext(dispatcher) { check(!closed.get()) { "Store is closed" }; block() }
    }

    /** Lets SQLite refresh the statistics its query planner uses, on the tables that changed enough to need it. */
    suspend fun optimize() = query { exec("PRAGMA optimize"); Unit }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // SQLite advises PRAGMA optimize before closing a long-lived connection; a failure must not stop the close.
        try {
            executor.submit { transaction(database) { maxAttempts = 1; exec("PRAGMA optimize") } }.get(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Closing matters more than the statistics.
        }
        executor.shutdown()
        check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Database work did not stop" }
        dispatcher.close()
        closeDatabase(database)
    }
}

suspend fun openStore(dataDirectory: Path, clock: Clock = Clock.systemUTC()): BackendStore {
    val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "sofa-sqlite") }
    val dispatcher = executor.asCoroutineDispatcher()
    var store: BackendStore? = null
    try {
        val database = withContext(dispatcher) { databaseAt(dataDirectory) }
        store = BackendStore(database, executor, dispatcher, clock)
        store.initialize()
        return store
    } catch (failure: Throwable) {
        if (store != null) store.close() else dispatcher.close()
        throw failure
    }
}

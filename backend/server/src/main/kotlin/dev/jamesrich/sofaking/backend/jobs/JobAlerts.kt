package dev.jamesrich.sofaking.backend.jobs

import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.Metadata
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/** The jobs `job.failed` was sent for and that have not succeeded since, kept so a restart does not send it again. */
interface JobAlerts {
    suspend fun alerting(): Set<String>

    suspend fun remember(id: String, alerting: Boolean)
}

private const val JOB_ALERTS_KEY = "job_alerts"

/** [JobAlerts] in the store's metadata table. */
class StoredJobAlerts(private val store: BackendStore) : JobAlerts {
    private val mutex = Mutex()

    override suspend fun alerting(): Set<String> {
        val encoded = store.read {
            Metadata.selectAll().where { Metadata.key eq JOB_ALERTS_KEY }.singleOrNull()?.get(Metadata.value)
        } ?: return emptySet()
        return runCatching { Json.decodeFromString<Set<String>>(encoded) }.getOrDefault(emptySet())
    }

    override suspend fun remember(id: String, alerting: Boolean) = mutex.withLock {
        val current = alerting()
        val updated = if (alerting) current + id else current - id
        if (updated != current) {
            val encoded = Json.encodeToString(updated)
            store.query {
                Metadata.upsert {
                    it[key] = JOB_ALERTS_KEY
                    it[value] = encoded
                }
                Unit
            }
        }
    }
}

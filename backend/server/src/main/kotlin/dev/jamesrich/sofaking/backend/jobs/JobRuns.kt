package dev.jamesrich.sofaking.backend.jobs

import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.Metadata
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/** One finished run of a job: when it started and ended, in epoch milliseconds, and what it came to. */
@Serializable
data class LastRun(val startedAtMs: Long, val endedAtMs: Long, val ok: Boolean, val note: String? = null)

/** Each job's last finished run, kept so the Jobs page still shows it after a restart. */
interface JobRuns {
    suspend fun all(): Map<String, LastRun>

    suspend fun remember(id: String, run: LastRun)
}

private const val JOB_RUNS_KEY = "job_runs"

/** [JobRuns] in the store's metadata table, as one value; a value that does not parse reads as no runs. */
class StoredJobRuns(private val store: BackendStore) : JobRuns {
    private val mutex = Mutex()
    private var runs: Map<String, LastRun>? = null

    override suspend fun all(): Map<String, LastRun> = mutex.withLock { loaded() }

    private suspend fun loaded(): Map<String, LastRun> = runs ?: run {
        val encoded = store.read {
            Metadata.selectAll().where { Metadata.key eq JOB_RUNS_KEY }.singleOrNull()?.get(Metadata.value)
        }
        val decoded = encoded?.let { runCatching { Json.decodeFromString<Map<String, LastRun>>(it) }.getOrNull() }
        decoded.orEmpty().also { runs = it }
    }

    override suspend fun remember(id: String, run: LastRun) = mutex.withLock {
        val updated = loaded() + (id to run)
        val encoded = Json.encodeToString(updated)
        store.query {
            Metadata.upsert {
                it[key] = JOB_RUNS_KEY
                it[value] = encoded
            }
            Unit
        }
        runs = updated
    }
}

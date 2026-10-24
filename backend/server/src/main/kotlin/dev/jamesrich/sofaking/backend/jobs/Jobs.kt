package dev.jamesrich.sofaking.backend.jobs

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What one run came to: whether it [ok] and a short [note] with no URL or secret in it, such as "1,234 channels" or
 * "IMPORT_FAILED (IOException)".
 */
class JobOutcome(val ok: Boolean, val note: String? = null)

/**
 * One background job as the admin portal lists it: what it is, whether it is running, its
 * last run and when it is next due.
 */
class JobStatus(
    val id: String,
    val label: String,
    /** How often the loop runs the job on its own; null for one that runs only on request. */
    val every: Duration?,
    val runningSince: Instant?,
    val lastStartedAt: Instant?,
    val lastDurationMs: Long?,
    val lastOk: Boolean?,
    val lastNote: String?,
    /** When the loop runs the job next, from its last end and [every]; null while it runs or before its first run. */
    val nextDueAt: Instant?,
    /** Whether the portal can start it now. */
    val canRunNow: Boolean,
) {
    val running: Boolean get() = runningSince != null
}

/**
 * The server's background jobs: imports, guide imports, lineup policy runs, Plex sync and the logo sweep. Each service
 * registers its jobs and records every run around the work; the portal's Jobs page reads the snapshot and starts a
 * job through the kick its service registered.
 */
class Jobs(private val clock: Clock) {
    private class Entry(val id: String, val order: Int) {
        @Volatile var label: String = id

        @Volatile var every: Duration? = null

        @Volatile var runNow: (() -> Unit)? = null

        @Volatile var runningSince: Instant? = null

        @Volatile var lastStartedAt: Instant? = null

        @Volatile var lastEndedAt: Instant? = null

        @Volatile var lastOk: Boolean? = null

        @Volatile var lastNote: String? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val sequence = AtomicInteger()

    private fun entry(id: String): Entry = entries.computeIfAbsent(id) { Entry(id, sequence.getAndIncrement()) }

    /** Names the job [id] as [label], says how often its loop runs it, and gives the kick that starts it on request. */
    fun register(id: String, label: String, every: Duration? = null, runNow: (() -> Unit)? = null) {
        entry(id).apply {
            this.label = label
            this.every = every
            this.runNow = runNow
        }
    }

    /**
     * Runs [block] as one run of [id], recording its start, end and outcome; an exception records a
     * failed run by class and is rethrown.
     */
    suspend fun record(id: String, block: suspend () -> JobOutcome) {
        val entry = entry(id)
        val started = clock.instant()
        entry.runningSince = started
        val outcome = runCatching { block() }
            .onFailure { finish(entry, started, JobOutcome(false, it.javaClass.simpleName)) }
            .getOrThrow()
        finish(entry, started, outcome)
    }

    private fun finish(entry: Entry, started: Instant, outcome: JobOutcome) {
        entry.lastStartedAt = started
        entry.lastEndedAt = clock.instant()
        entry.lastOk = outcome.ok
        entry.lastNote = outcome.note
        entry.runningSince = null
    }

    /** Starts [id] now through its service's kick; false when no such job can be started. */
    fun runNow(id: String): Boolean {
        val kick = entries[id]?.runNow ?: return false
        kick()
        return true
    }

    /** Every job in registration order. */
    fun snapshot(): List<JobStatus> = entries.values.sortedBy { it.order }.map { entry ->
        val running = entry.runningSince
        val ended = entry.lastEndedAt
        val started = entry.lastStartedAt
        JobStatus(
            entry.id, entry.label, entry.every, running, started,
            if (started != null && ended != null) Duration.between(started, ended).toMillis() else null,
            entry.lastOk, entry.lastNote,
            if (running == null && ended != null) entry.every?.let(ended::plus) else null,
            entry.runNow != null,
        )
    }
}

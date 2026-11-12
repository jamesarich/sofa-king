package dev.jamesrich.sofaking.backend.jobs

import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.notify.NotifyEvent
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * How far a running job has come: [done] of [total] steps, or only [step] when it cannot count them ([total] is 0),
 * such as the library a pass is on.
 */
class JobProgress(val done: Int, val total: Int, val step: String? = null) {
    /** Whole percent done; null when the job cannot count its steps. */
    val percent: Int? get() = if (total <= 0) null else (done.coerceIn(0, total) * PERCENT / total)

    private companion object {
        const val PERCENT = 100
    }
}

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
    /**
     * When the loop runs the job next, from its last end and [every]; null while it runs, and before its first run
     * since the server started.
     */
    val nextDueAt: Instant?,
    /** Whether the portal can start it now. */
    val canRunNow: Boolean,
    /** How far it has come while it runs; null when it is not running or says nothing. */
    val progress: JobProgress? = null,
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

        @Volatile var progress: JobProgress? = null

        /** Runs that failed in a row; a success or a restart starts it again. */
        @Volatile var failures = 0

        /** Whether `job.failed` went out for the current streak, as the [JobAlerts] remembered or this process sent. */
        @Volatile var alerted = false

        @Volatile var seeded = false

        /** Whether the last run shown is one an earlier process finished, which says nothing about this one's loop. */
        @Volatile var fromEarlierProcess = false

        fun show(run: LastRun) {
            lastStartedAt = Instant.ofEpochMilli(run.startedAtMs)
            lastEndedAt = Instant.ofEpochMilli(run.endedAtMs)
            lastOk = run.ok
            lastNote = run.note
            fromEarlierProcess = true
        }
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val sequence = AtomicInteger()

    /**
     * Where `job.failed` is published, once after [FAILURES_BEFORE_ALERT] failed runs in a row and not again until
     * one succeeds; none until the runtime sets it.
     */
    @Volatile var events: ServerEvents? = null

    /** Remembers which jobs are alerting across restarts; without it a restart forgets. */
    @Volatile var alerts: JobAlerts? = null

    private suspend fun remembered(alerts: JobAlerts): Set<String> =
        runCatchingCancellable { alerts.alerting() }.getOrDefault(emptySet())

    companion object {
        /** Failed runs in a row before `job.failed` is published. */
        const val FAILURES_BEFORE_ALERT = 3
    }

    @Volatile private var runs: JobRuns? = null

    @Volatile private var earlier: Map<String, LastRun> = emptyMap()

    private fun entry(id: String): Entry = entries.computeIfAbsent(id) {
        Entry(id, sequence.getAndIncrement()).also { entry -> earlier[id]?.let(entry::show) }
    }

    /**
     * Keeps each job's last run in [runs] from here on, and shows the ones an earlier process left for every job
     * that has not finished a run in this one. A store that cannot be read shows none.
     */
    suspend fun keepRuns(runs: JobRuns) {
        earlier = runCatchingCancellable { runs.all() }.getOrDefault(emptyMap())
        this.runs = runs
        entries.values.filter { it.lastEndedAt == null }.forEach { entry -> earlier[entry.id]?.let(entry::show) }
    }

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
     * failed run by class and is rethrown. A run that is canceled is no outcome: the job keeps its last one.
     */
    suspend fun record(id: String, block: suspend () -> JobOutcome) {
        val entry = entry(id)
        val started = clock.instant()
        entry.runningSince = started
        entry.progress = null
        val outcome = try {
            runCatchingCancellable { block() }
                .onFailure { finish(entry, started, JobOutcome(false, it.javaClass.simpleName)) }
                .getOrThrow()
        } finally {
            entry.runningSince = null
            entry.progress = null
        }
        finish(entry, started, outcome)
    }

    private suspend fun finish(entry: Entry, started: Instant, outcome: JobOutcome) {
        entry.lastStartedAt = started
        entry.lastEndedAt = clock.instant()
        entry.lastOk = outcome.ok
        entry.lastNote = outcome.note
        entry.runningSince = null
        entry.progress = null
        entry.fromEarlierProcess = false
        runs?.let { runs ->
            val run = LastRun(started.toEpochMilli(), clock.millis(), outcome.ok, outcome.note)
            withContext(NonCancellable) { runCatchingCancellable { runs.remember(entry.id, run) } }
        }
        val alerts = alerts
        if (!entry.seeded) {
            entry.alerted = alerts != null && entry.id in withContext(NonCancellable) { remembered(alerts) }
            entry.seeded = true
        }
        if (outcome.ok) {
            entry.failures = 0
            if (entry.alerted) {
                entry.alerted = false
                alerts?.let { withContext(NonCancellable) { runCatchingCancellable { it.remember(entry.id, false) } } }
            }
            return
        }
        entry.failures++
        val startsFailing = entry.failures >= FAILURES_BEFORE_ALERT && !entry.alerted
        if (startsFailing) {
            entry.alerted = true
            alerts?.let { withContext(NonCancellable) { runCatchingCancellable { it.remember(entry.id, true) } } }
            events?.publish(
                NotifyEvent.JOB_FAILED,
                buildJsonObject {
                    put("id", entry.id)
                    put("label", entry.label)
                    put("note", outcome.note)
                },
            )
        }
    }

    /** Says how far the running job [id] has come; the Activity page shows it until the run ends. */
    fun progress(id: String, progress: JobProgress) {
        val entry = entries[id] ?: return
        if (entry.runningSince != null) entry.progress = progress
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
            if (running == null && ended != null && !entry.fromEarlierProcess) entry.every?.let(ended::plus) else null,
            entry.runNow != null,
            if (running != null) entry.progress else null,
        )
    }
}

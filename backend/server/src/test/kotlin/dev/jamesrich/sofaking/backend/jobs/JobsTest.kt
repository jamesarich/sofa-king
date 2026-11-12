package dev.jamesrich.sofaking.backend.jobs

import dev.jamesrich.sofaking.backend.api.ServerEvent
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.notify.NotifyEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobsTest {
    @Test fun `a run records its start, duration, outcome and when the loop is next due`() = runTest {
        val clock = MutableClock()
        val jobs = Jobs(clock)
        var kicked = 0
        jobs.register("import:a", "Import a", Duration.ofMinutes(15)) { kicked++ }
        val before = jobs.snapshot().single()
        assertEquals("Import a", before.label)
        assertNull(before.lastStartedAt)
        assertNull(before.nextDueAt)
        assertFalse(before.running)
        jobs.record("import:a") {
            assertTrue(jobs.snapshot().single().running)
            clock.now = clock.now.plusSeconds(3)
            JobOutcome(true, "12 channels")
        }
        val after = jobs.snapshot().single()
        assertFalse(after.running)
        assertEquals(3000, after.lastDurationMs)
        assertEquals(true, after.lastOk)
        assertEquals("12 channels", after.lastNote)
        assertEquals(clock.instant().plus(Duration.ofMinutes(15)), after.nextDueAt)
        assertTrue(jobs.runNow("import:a"))
        assertEquals(1, kicked)
        assertFalse(jobs.runNow("nothing"))
    }

    @Test
    fun `a thrown run is recorded as failed by class and rethrown, and a job registers on first record`() = runTest {
        val jobs = Jobs(MutableClock())
        assertFailsWith<IllegalStateException> { jobs.record("policy") { error("boom") } }
        val job = jobs.snapshot().single()
        assertEquals("policy", job.label)
        assertEquals(false, job.lastOk)
        assertEquals("IllegalStateException", job.lastNote)
        assertFalse(job.canRunNow)
        assertNull(job.nextDueAt, "a job with no interval is never due")
    }

    private class Remembered(val ids: MutableSet<String> = mutableSetOf()) : JobAlerts {
        override suspend fun alerting() = ids.toSet()

        override suspend fun remember(id: String, alerting: Boolean) {
            if (alerting) ids += id else ids -= id
        }
    }

    private suspend fun Jobs.fail(times: Int, id: String = "guide:a") =
        repeat(times) { record(id) { JobOutcome(false, "IMPORT_FAILED (IOException)") } }

    private fun published(events: ServerEvents, scope: kotlinx.coroutines.CoroutineScope) =
        mutableListOf<ServerEvent>().also { seen ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) { events.events.collect { seen += it } }
        }

    @Test fun `a restart does not publish job failed again for a job that is still failing`() = runTest {
        val clock = MutableClock()
        val remembered = Remembered()
        val events = ServerEvents(clock)
        val first = Jobs(clock).also {
            it.events = events
            it.alerts = remembered
        }
        val seen = published(events, backgroundScope)
        first.fail(3)
        runCurrent()
        assertEquals(1, seen.size)
        assertEquals(setOf("guide:a"), remembered.ids)
        val restarted = Jobs(clock).also {
            it.events = events
            it.alerts = remembered
        }
        restarted.fail(5)
        runCurrent()
        assertEquals(1, seen.size, "the restarted process knows the job already alerted")
        restarted.record("guide:a") { JobOutcome(true) }
        assertEquals(emptySet(), remembered.ids, "a success clears it")
        restarted.fail(3)
        runCurrent()
        assertEquals(2, seen.size)
    }

    private class Kept : JobRuns {
        val runs = mutableMapOf<String, LastRun>()
        var broken = false

        override suspend fun all(): Map<String, LastRun> = if (broken) throw IOException("locked") else runs.toMap()

        override suspend fun remember(id: String, run: LastRun) {
            runs[id] = run
        }
    }

    @Test fun `a restart shows each job's last run, with no next due time until it runs again`() = runTest {
        val clock = MutableClock()
        val kept = Kept()
        val first = Jobs(clock).also { it.keepRuns(kept) }
        val started = clock.instant()
        first.record("library-scan") {
            clock.now = clock.now.plusSeconds(40)
            JobOutcome(true, "12 files")
        }
        first.record("backup") { JobOutcome(false, "IOException") }
        clock.now = clock.now.plusSeconds(600)

        val restarted = Jobs(clock)
        restarted.register("library-scan", "Library scan", Duration.ofHours(6))
        restarted.keepRuns(kept)
        restarted.register("backup", "Database backup", Duration.ofDays(1))
        restarted.register("plex", "Plex sync", Duration.ofHours(1))
        val (scan, backup, plex) = restarted.snapshot()
        assertEquals(started, scan.lastStartedAt)
        assertEquals(40_000, scan.lastDurationMs)
        assertEquals(true, scan.lastOk)
        assertEquals("12 files", scan.lastNote)
        assertNull(scan.nextDueAt, "this process has not run it, so its loop's timing is unknown")
        assertEquals(false, backup.lastOk)
        assertNull(plex.lastStartedAt)

        restarted.record("library-scan") { JobOutcome(true, "13 files") }
        val again = restarted.snapshot().first()
        assertEquals("13 files", again.lastNote)
        assertEquals(clock.instant().plus(Duration.ofHours(6)), again.nextDueAt)
        assertEquals("13 files", kept.runs.getValue("library-scan").note)
    }

    @Test fun `a run this process finished is not replaced by an older kept one`() = runTest {
        val clock = MutableClock()
        val kept = Kept().apply { runs["import:a"] = LastRun(0, 1, false, "old") }
        val jobs = Jobs(clock)
        jobs.record("import:a") { JobOutcome(true, "new") }
        jobs.keepRuns(kept)
        assertEquals("new", jobs.snapshot().single().lastNote)
    }

    @Test fun `runs that cannot be read show none, and later runs are still kept`() = runTest {
        val kept = Kept().apply { broken = true }
        val jobs = Jobs(MutableClock())
        jobs.register("import:a", "Import a")
        jobs.keepRuns(kept)
        assertNull(jobs.snapshot().single().lastStartedAt)
        jobs.record("import:a") { JobOutcome(true, "2 channels") }
        assertEquals("2 channels", kept.runs.getValue("import:a").note)
    }

    @Test fun `a canceled run is neither a failure nor an outcome`() = runTest {
        val clock = MutableClock()
        val events = ServerEvents(clock)
        val jobs = Jobs(clock).also { it.events = events }
        val seen = published(events, backgroundScope)
        jobs.record("guide:a") { JobOutcome(true, "2 channels") }
        repeat(5) {
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                jobs.record("guide:a") { throw kotlinx.coroutines.CancellationException("shutdown") }
            }
        }
        runCurrent()
        val job = jobs.snapshot().single()
        assertEquals(true, job.lastOk)
        assertEquals("2 channels", job.lastNote)
        assertFalse(job.running)
        assertEquals(0, seen.size)
        jobs.fail(2)
        runCurrent()
        assertEquals(0, seen.size, "canceled runs do not count toward the three")
    }

    @Test fun `three failed runs in a row publish job failed once, until the job succeeds again`() = runTest {
        val clock = MutableClock()
        val events = ServerEvents(clock)
        val jobs = Jobs(clock).also { it.events = events }
        jobs.register("guide:a", "Guide a")
        val seen = mutableListOf<ServerEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { events.events.collect { seen += it } }
        jobs.record("guide:a") { JobOutcome(false, "IMPORT_FAILED (IOException)") }
        jobs.record("guide:a") { JobOutcome(false, "IMPORT_FAILED (IOException)") }
        runCurrent()
        assertEquals(0, seen.size, "two failures are not an alert")
        jobs.record("guide:a") { JobOutcome(false, "IMPORT_FAILED (IOException)") }
        assertFailsWith<IllegalStateException> { jobs.record("guide:a") { error("boom") } }
        jobs.record("guide:a") { JobOutcome(true, "2 channels") }
        runCurrent()
        assertEquals(1, seen.size, "one for the streak, none for the runs after its third")
        jobs.record("guide:a") { JobOutcome(false, "IMPORT_FAILED (IOException)") }
        jobs.record("guide:a") { JobOutcome(false, "IMPORT_FAILED (IOException)") }
        jobs.record("guide:a") { JobOutcome(false, "IMPORT_FAILED (IOException)") }
        runCurrent()
        assertEquals(listOf(NotifyEvent.JOB_FAILED, NotifyEvent.JOB_FAILED), seen.map { it.type })
        val data = seen.first().data
        assertEquals("guide:a", data["id"]!!.jsonPrimitive.content)
        assertEquals("Guide a", data["label"]!!.jsonPrimitive.content)
        assertEquals("IMPORT_FAILED (IOException)", data["note"]!!.jsonPrimitive.content)
    }

    @Test fun `progress shows only while the job runs`() = runTest {
        val jobs = Jobs(MutableClock())
        jobs.register("scrub", "Scrub thumbnails")
        jobs.progress("scrub", JobProgress(1, 4))
        assertNull(jobs.snapshot().single().progress, "a job that is not running takes no progress")
        jobs.record("scrub") {
            jobs.progress("scrub", JobProgress(1, 4, "Shows"))
            val progress = jobs.snapshot().single().progress!!
            assertEquals(25, progress.percent)
            assertEquals("Shows", progress.step)
            jobs.progress("scrub", JobProgress(0, 0, "Movies"))
            assertNull(jobs.snapshot().single().progress!!.percent, "a step it cannot count has no percent")
            JobOutcome(true)
        }
        assertNull(jobs.snapshot().single().progress, "the run's end clears it")
        jobs.progress("nothing", JobProgress(1, 2))
        assertEquals(1, jobs.snapshot().size, "progress for an unknown job registers nothing")
    }

    @Test fun `jobs keep registration order`() {
        val jobs = Jobs(MutableClock())
        jobs.register("b", "B")
        jobs.register("a", "A")
        jobs.register("c", "C")
        assertEquals(listOf("b", "a", "c"), jobs.snapshot().map { it.id })
    }
}

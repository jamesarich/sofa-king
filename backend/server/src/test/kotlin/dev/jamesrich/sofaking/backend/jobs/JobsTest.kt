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

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

    @Test fun `a job whose runs start failing publishes job failed once, until it succeeds again`() = runTest {
        val clock = MutableClock()
        val events = ServerEvents(clock)
        val jobs = Jobs(clock).also { it.events = events }
        jobs.register("guide:a", "Guide a")
        val seen = mutableListOf<ServerEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { events.events.collect { seen += it } }
        jobs.record("guide:a") { JobOutcome(false, "IMPORT_FAILED (IOException)") }
        jobs.record("guide:a") { JobOutcome(false, "IMPORT_FAILED (IOException)") }
        assertFailsWith<IllegalStateException> { jobs.record("guide:a") { error("boom") } }
        jobs.record("guide:a") { JobOutcome(true, "2 channels") }
        runCurrent()
        assertEquals(1, seen.size, "one for the streak, none for the runs after its first")
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

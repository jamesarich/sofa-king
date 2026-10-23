package dev.jamesrich.sofaking.backend.jobs

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import kotlinx.coroutines.test.runTest
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

    @Test fun `a thrown run is recorded as failed by class and rethrown, and a job registers on first record`() = runTest {
        val jobs = Jobs(MutableClock())
        assertFailsWith<IllegalStateException> { jobs.record("policy") { error("boom") } }
        val job = jobs.snapshot().single()
        assertEquals("policy", job.label)
        assertEquals(false, job.lastOk)
        assertEquals("IllegalStateException", job.lastNote)
        assertFalse(job.canRunNow)
        assertNull(job.nextDueAt, "a job with no interval is never due")
    }

    @Test fun `jobs keep registration order`() {
        val jobs = Jobs(MutableClock())
        jobs.register("b", "B")
        jobs.register("a", "A")
        jobs.register("c", "C")
        assertEquals(listOf("b", "a", "c"), jobs.snapshot().map { it.id })
    }
}

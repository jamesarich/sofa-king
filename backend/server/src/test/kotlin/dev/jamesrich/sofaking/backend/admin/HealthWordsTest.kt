package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.health.FailureClass
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.health.Observation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthWordsTest {
    private val t = Instant.parse("2026-10-25T12:00:00Z")

    private fun failed(minute: Long, failure: FailureClass) =
        Observation("f$minute", t.plusSeconds(minute * 60), false, failure)

    @Test fun `a suspect source gives its newest failure, and a healthy one nothing`() {
        val recent = listOf(failed(10, FailureClass.AUTH), failed(0, FailureClass.OFFERING))
        assertEquals(
            "The provider refused access (401 or 403)",
            healthWhy(HealthState(HealthStatus.SUSPECT), recent),
        )
        assertEquals(
            "The source failed: not found, no stream, or a picture that would not decode",
            healthWhy(
                HealthState(HealthStatus.QUARANTINED, failures = listOf(failed(0, FailureClass.OFFERING))),
                emptyList(),
            ),
        )
        assertNull(healthWhy(HealthState(HealthStatus.HEALTHY), recent))
        assertEquals(true, healthWhy(HealthState(HealthStatus.PROBATION), emptyList())?.startsWith("Passed once"))
    }

    @Test fun `a red channel gives the newest failure across its sources`() {
        val recent = mapOf(
            "a" to listOf(failed(0, FailureClass.OFFERING)),
            "b" to listOf(failed(20, FailureClass.RATE_LIMIT), failed(5, FailureClass.PROVIDER)),
        )
        assertEquals("the provider asked for fewer requests (429)", stationWhy(listOf("a", "b"), emptyMap(), recent))
        assertEquals("no source has passed a probe yet", stationWhy(listOf("a", "b"), emptyMap(), emptyMap()))
        val off = HealthState(manuallyDisabled = true)
        assertEquals(
            "every source is disabled by hand",
            stationWhy(listOf("a", "b"), mapOf("a" to off, "b" to off), recent),
        )
    }
}

package dev.jamesrich.sofaking.backend.health

import dev.jamesrich.sofaking.backend.admin.firstProbesLeft
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProbeRateTest {
    private val start = Instant.parse("2026-10-26T12:00:00Z")

    @Test
    fun `no estimate until five probes have finished`() {
        val rate = ProbeRate()
        repeat(4) { rate.finished(start.plusSeconds(it * 10L)) }
        assertNull(rate.timeFor(100, start.plusSeconds(60)))
        rate.finished(start.plusSeconds(50))
        assertEquals(5.0, rate.perMinute(start.plusSeconds(60)), "a span under a minute counts as one")
    }

    @Test
    fun `the rate counts the probes inside the window`() {
        val rate = ProbeRate(Duration.ofMinutes(10))
        // 60 probes over the first ten minutes, one every ten seconds.
        repeat(60) { rate.finished(start.plusSeconds(it * 10L)) }
        val now = start.plusSeconds(600)
        assertEquals(6.0, rate.perMinute(now)!!, 0.1)
        assertEquals(Duration.ofMinutes(50), rate.timeFor(300, now))
        assertEquals(Duration.ofMinutes(1), rate.timeFor(1, now), "never less than a minute")
        // Five minutes later, the first half has left the window: 30 probes over ten minutes.
        assertEquals(3.0, rate.perMinute(start.plusSeconds(900))!!, 0.1)
        assertNull(rate.perMinute(start.plusSeconds(2_000)), "an idle worker has no rate")
    }

    @Test
    fun `first probes left counts unobserved sources of waiting stations`() {
        val seen = HealthState(lastObservationAt = start)
        val targets = mapOf("a" to 0, "b" to 1, "c" to 1, "d" to 2, "e" to 0)
        val states = mapOf("a" to seen, "b" to HealthState(), "d" to HealthState())
        assertEquals(3, firstProbesLeft(targets, states), "b, c and e; d is a later tier")
    }
}

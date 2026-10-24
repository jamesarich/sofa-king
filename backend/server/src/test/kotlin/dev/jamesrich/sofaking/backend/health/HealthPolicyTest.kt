package dev.jamesrich.sofaking.backend.health

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HealthPolicyTest {
    private val t = Instant.parse("2026-10-10T00:00:00Z")
    private val policy = HealthPolicy()

    private fun fail(
        state: HealthState,
        minute: Long,
        id: String = "incident-$minute",
        kind: FailureClass = FailureClass.OFFERING,
    ): HealthState {
        val at = t.plusSeconds(minute * 60)
        return policy.apply(state, Observation(id, at, false, kind), at)
    }

    private fun success(state: HealthState, minute: Long): HealthState {
        val at = t.plusSeconds(minute * 60)
        return policy.apply(state, Observation("success-$minute", at, true), at)
    }

    private fun quarantined(): HealthState = fail(fail(fail(HealthState(status = HealthStatus.HEALTHY), 0), 5), 10)

    @Test fun `one offering failure is suspect`() {
        assertEquals(HealthStatus.SUSPECT, fail(HealthState(), 0).status)
    }

    @Test fun `independent failures spanning ten minutes quarantine`() {
        val state = quarantined()
        assertEquals(HealthStatus.QUARANTINED, state.status)
        assertEquals(t.plusSeconds(25 * 60), state.nextCheckAt)
    }

    @Test fun `rapid retries do not meet independent time span`() {
        val state = fail(fail(fail(HealthState(), 0), 1), 2)
        assertEquals(HealthStatus.SUSPECT, state.status)
    }

    @Test fun `one worker incident does not multiply strikes`() {
        val state = fail(fail(fail(HealthState(), 0, "same"), 5, "same"), 10, "same")
        assertEquals(HealthStatus.SUSPECT, state.status)
        assertEquals(1, state.failures.size)
    }

    @Test fun `failures outside six hour window expire`() {
        val state = fail(fail(fail(HealthState(), 0), 5), 361)
        assertEquals(HealthStatus.SUSPECT, state.status)
        assertEquals(2, state.failures.size)
    }

    @Test fun `successful qualification resets failure streak`() {
        val state = success(fail(fail(HealthState(), 0), 5), 6)
        assertEquals(HealthStatus.HEALTHY, state.status)
        assertTrue(state.failures.isEmpty())
        assertEquals(HealthStatus.SUSPECT, fail(state, 10).status)
    }

    @Test fun `other failure domains cannot condemn an offering`() {
        for (kind in FailureClass.entries.filter { it != FailureClass.OFFERING }) {
            val initial = HealthState(status = HealthStatus.HEALTHY)
            val state = fail(fail(fail(initial, 0, kind = kind), 5, kind = kind), 10, kind = kind)
            assertEquals(HealthStatus.HEALTHY, state.status, kind.name)
            assertTrue(state.failures.isEmpty())
        }
    }

    @Test fun `first recovery starts probation and early second success cannot restore`() {
        val state = success(quarantined(), 25)
        assertEquals(HealthStatus.PROBATION, state.status)
        assertEquals(HealthStatus.PROBATION, success(state, 34).status)
        assertEquals(HealthStatus.HEALTHY, success(state, 35).status)
    }

    @Test fun `recovery cannot run before its due time`() {
        assertEquals(HealthStatus.QUARANTINED, success(quarantined(), 11).status)
    }

    @Test fun `probation failure quarantines again`() {
        assertEquals(HealthStatus.QUARANTINED, fail(success(quarantined(), 25), 26).status)
    }

    @Test fun `failed recovery backs off to one hour six hours then daily`() {
        var state = fail(quarantined(), 25)
        assertEquals(t.plusSeconds(85 * 60), state.nextCheckAt)
        state = fail(state, 85)
        assertEquals(t.plusSeconds(445 * 60), state.nextCheckAt)
        state = fail(state, 445)
        assertEquals(t.plusSeconds(1885 * 60), state.nextCheckAt)
    }

    @Test fun `late results cannot undo a later qualification`() {
        val state = success(HealthState(), 20)
        assertEquals(
            state,
            policy.apply(state, Observation("old", t, false, FailureClass.OFFERING), t.plusSeconds(30 * 60)),
        )
    }

    @Test fun `old successes cannot undo newer failures`() {
        val state = fail(HealthState(), 20)
        assertEquals(state, policy.apply(state, Observation("old-success", t, true), t.plusSeconds(30 * 60)))
    }

    @Test fun `manual disable and archive survive success`() {
        val disabled = HealthState(status = HealthStatus.QUARANTINED, manuallyDisabled = true)
        assertEquals(disabled, success(disabled, 25))
        val archived = HealthState(status = HealthStatus.ARCHIVED)
        assertEquals(archived, success(archived, 25))
    }

    @Test fun `invalid observations are rejected`() {
        val invalid =
            listOf(
                Observation("", t, true),
                Observation("future", t.plusSeconds(1), true),
                Observation("missing-result", t, false),
                Observation("conflicting", t, true, FailureClass.OFFERING),
            )
        for (o in invalid) assertFailsWith<IllegalArgumentException> { policy.apply(HealthState(), o, t) }
    }

    @Test fun `expired evidence cannot change current health`() {
        val state = HealthState(status = HealthStatus.HEALTHY)
        val now = t.plusSeconds(7 * 3600)
        assertEquals(state, policy.apply(state, Observation("old-failure", t, false, FailureClass.OFFERING), now))
        val suspect = state.copy(status = HealthStatus.SUSPECT)
        assertEquals(suspect, policy.apply(suspect, Observation("old-success", t, true), now))
    }
}

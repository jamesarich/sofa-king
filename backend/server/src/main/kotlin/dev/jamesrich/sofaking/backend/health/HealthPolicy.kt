package dev.jamesrich.sofaking.backend.health

import java.time.Duration
import java.time.Instant

/** Evidence older than this says nothing about the offering now; strikes count within it too. */
private val EVIDENCE_WINDOW: Duration = Duration.ofHours(6)

/** How long a quarantined offering that answers again stays on probation. */
private val PROBATION: Duration = Duration.ofMinutes(10)

/** The least span of strikes that quarantines, so one bad minute does not. */
private val STRIKE_SPAN: Duration = Duration.ofMinutes(10)

/** When a newly quarantined offering is first checked again. */
private val FIRST_RECHECK: Duration = Duration.ofMinutes(15)

/** When a re-quarantined offering is checked again, by how many times it has been quarantined in a row. */
private val RECHECK_DELAYS: List<Duration> = listOf(Duration.ofHours(1), Duration.ofHours(6), Duration.ofDays(1))

/** How many strikes, over at least [STRIKE_SPAN], quarantine; and how many an offering's state keeps at most. */
private const val QUARANTINE_STRIKES = 3
private const val KEPT_STRIKES = 128

/** Offering-local evidence only. Provider-wide diagnosis belongs to the caller. */
class HealthPolicy {
    fun apply(state: HealthState, observation: Observation, now: Instant): HealthState {
        require(observation.incidentId.isNotBlank()) { "An incident ID is required" }
        require(!observation.observedAt.isAfter(now)) { "Observation cannot be in the future" }
        require(observation.qualifiedSuccess == (observation.failureClass == null)) {
            "Observation must be a qualified success or a classified failure"
        }
        val at = observation.observedAt
        return when {
            stale(state, at, now) || irrelevant(state, observation) -> state

            observation.qualifiedSuccess -> succeeded(state, at)

            state.status == HealthStatus.QUARANTINED || state.status == HealthStatus.PROBATION -> requarantined(
                state,
                at,
            )

            else -> struck(state, observation, now)
        }
    }

    /** Evidence from [at] is older than the window, than the last observation, or than a quarantine's next check. */
    private fun stale(state: HealthState, at: Instant, now: Instant): Boolean {
        val checkLater = state.status == HealthStatus.QUARANTINED && state.nextCheckAt?.let { at < it } == true
        return at < now.minus(EVIDENCE_WINDOW) || state.lastObservationAt?.let { at <= it } == true || checkLater
    }

    /** The offering is out of play, the failure is not the offering's, or its incident already counted. */
    private fun irrelevant(state: HealthState, observation: Observation): Boolean {
        val notOffering = !observation.qualifiedSuccess && observation.failureClass != FailureClass.OFFERING
        val outOfPlay = state.manuallyDisabled || state.status == HealthStatus.ARCHIVED
        return outOfPlay || notOffering || state.failures.any { it.incidentId == observation.incidentId }
    }

    /** A qualified success at [at]: a quarantined offering goes on probation, and probation ends once it is served. */
    private fun succeeded(state: HealthState, at: Instant): HealthState = when (state.status) {
        HealthStatus.QUARANTINED -> state.copy(
            status = HealthStatus.PROBATION,
            failures = emptyList(),
            lastQualifiedAt = at,
            probationStartedAt = at,
            nextCheckAt = at.plus(PROBATION),
            lastObservationAt = at,
        )

        HealthStatus.PROBATION -> if (at < checkNotNull(state.probationStartedAt).plus(PROBATION)) {
            state.copy(lastObservationAt = at)
        } else {
            qualified(state, at)
        }

        else -> qualified(state, at)
    }

    /** A failure at [at] while quarantined or on probation: quarantined again, checked again later each time. */
    private fun requarantined(state: HealthState, at: Instant): HealthState {
        val attempts = (state.quarantineAttempts + 1).coerceAtMost(RECHECK_DELAYS.size)
        return state.copy(
            status = HealthStatus.QUARANTINED,
            probationStartedAt = null,
            nextCheckAt = at.plus(RECHECK_DELAYS[attempts - 1]),
            quarantineAttempts = attempts,
            lastObservationAt = at,
        )
    }

    /** A strike against an offering in play: suspect, or quarantined once the strikes span long enough. */
    private fun struck(state: HealthState, observation: Observation, now: Instant): HealthState {
        val window =
            (state.failures + observation)
                .filter { it.observedAt >= now.minus(EVIDENCE_WINDOW) }
                .distinctBy { it.incidentId }
                .sortedBy { it.observedAt }
        // Retain the oldest strike and recent incidents, preserving the qualifying time span.
        val failures = if (window.size <=
            KEPT_STRIKES
        ) {
            window
        } else {
            listOf(window.first()) + window.takeLast(KEPT_STRIKES - 1)
        }
        val quarantine =
            failures.size >= QUARANTINE_STRIKES &&
                Duration.between(failures.first().observedAt, failures.last().observedAt) >= STRIKE_SPAN
        val at = observation.observedAt
        return state.copy(
            status = if (quarantine) HealthStatus.QUARANTINED else HealthStatus.SUSPECT,
            failures = failures,
            nextCheckAt = if (quarantine) at.plus(FIRST_RECHECK) else null,
            quarantineAttempts = 0,
            lastObservationAt = at,
        )
    }

    private fun qualified(state: HealthState, at: Instant): HealthState = state.copy(
        status = HealthStatus.HEALTHY,
        failures = emptyList(),
        lastQualifiedAt = at,
        probationStartedAt = null,
        nextCheckAt = null,
        quarantineAttempts = 0,
        lastObservationAt = at,
    )
}

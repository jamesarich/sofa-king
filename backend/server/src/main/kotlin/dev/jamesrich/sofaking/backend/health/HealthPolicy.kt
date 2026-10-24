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

/** Offering-local evidence only. Provider-wide diagnosis belongs to the caller. */
class HealthPolicy {
    fun apply(state: HealthState, observation: Observation, now: Instant): HealthState {
        require(observation.incidentId.isNotBlank()) { "An incident ID is required" }
        require(!observation.observedAt.isAfter(now)) { "Observation cannot be in the future" }
        require(observation.qualifiedSuccess == (observation.failureClass == null)) {
            "Observation must be a qualified success or a classified failure"
        }
        if (observation.observedAt < now.minus(EVIDENCE_WINDOW)) return state
        if (state.manuallyDisabled || state.status == HealthStatus.ARCHIVED) return state
        if (state.lastObservationAt?.let { observation.observedAt <= it } == true) return state
        if (!observation.qualifiedSuccess && observation.failureClass != FailureClass.OFFERING) return state
        if (state.failures.any { it.incidentId == observation.incidentId }) return state

        val at = observation.observedAt
        if (state.status == HealthStatus.QUARANTINED && state.nextCheckAt?.let { at < it } == true) return state
        if (observation.qualifiedSuccess) {
            return when (state.status) {
                HealthStatus.QUARANTINED -> {
                    state.copy(
                        status = HealthStatus.PROBATION,
                        failures = emptyList(),
                        lastQualifiedAt = at,
                        probationStartedAt = at,
                        nextCheckAt = at.plus(PROBATION),
                        lastObservationAt = at,
                    )
                }

                HealthStatus.PROBATION -> {
                    val began = checkNotNull(state.probationStartedAt)
                    if (at < began.plus(PROBATION)) {
                        state.copy(lastObservationAt = at)
                    } else {
                        qualified(state, at)
                    }
                }

                else -> {
                    qualified(state, at)
                }
            }
        }
        if (state.status == HealthStatus.QUARANTINED || state.status == HealthStatus.PROBATION) {
            val attempts = (state.quarantineAttempts + 1).coerceAtMost(3)
            val delay =
                when (attempts) {
                    1 -> Duration.ofHours(1)
                    2 -> Duration.ofHours(6)
                    else -> Duration.ofDays(1)
                }
            return state.copy(
                status = HealthStatus.QUARANTINED,
                probationStartedAt = null,
                nextCheckAt = at.plus(delay),
                quarantineAttempts = attempts,
                lastObservationAt = at,
            )
        }
        val window =
            (state.failures + observation)
                .filter { it.observedAt >= now.minus(EVIDENCE_WINDOW) }
                .distinctBy { it.incidentId }
                .sortedBy { it.observedAt }
        // Retain the oldest strike and recent incidents, preserving the qualifying time span.
        val failures = if (window.size <= 128) window else listOf(window.first()) + window.takeLast(127)
        val quarantine =
            failures.size >= 3 &&
                Duration.between(failures.first().observedAt, failures.last().observedAt) >= STRIKE_SPAN
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

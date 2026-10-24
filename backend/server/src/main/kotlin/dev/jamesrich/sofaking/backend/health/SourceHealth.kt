package dev.jamesrich.sofaking.backend.health

import java.time.Instant

enum class HealthStatus { UNVERIFIED, HEALTHY, SUSPECT, QUARANTINED, PROBATION, ARCHIVED }
enum class FailureClass { OFFERING, PROVIDER, AUTH, RATE_LIMIT, LOCAL, DEVICE }
data class Observation(
    val incidentId: String,
    val observedAt: Instant,
    val qualifiedSuccess: Boolean,
    val failureClass: FailureClass? = null,
)
data class HealthState(
    val status: HealthStatus = HealthStatus.UNVERIFIED,
    val manuallyDisabled: Boolean = false,
    val failures: List<Observation> = emptyList(),
    val lastQualifiedAt: Instant? = null,
    val probationStartedAt: Instant? = null,
    val nextCheckAt: Instant? = null,
    val quarantineAttempts: Int = 0,
    val lastObservationAt: Instant? = null,
)

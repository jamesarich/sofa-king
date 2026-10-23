package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.health.FailureClass
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.health.Observation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable internal data class StoredObservation(
    val incidentId: String,
    val observedAt: String,
    val qualifiedSuccess: Boolean,
    val failureClass: String?,
) {
    fun domain() =
        Observation(incidentId, Instant.parse(observedAt), qualifiedSuccess, failureClass?.let(FailureClass::valueOf))
}

@Serializable internal data class StoredHealth(
    val status: String,
    val manuallyDisabled: Boolean,
    val failures: List<StoredObservation>,
    val lastQualifiedAt: String?,
    val probationStartedAt: String?,
    val nextCheckAt: String?,
    val quarantineAttempts: Int,
    val lastObservationAt: String?,
) {
    fun domain() = HealthState(
        HealthStatus.valueOf(status),
        manuallyDisabled,
        failures.map { it.domain() },
        lastQualifiedAt?.let(Instant::parse),
        probationStartedAt?.let(Instant::parse),
        nextCheckAt?.let(Instant::parse),
        quarantineAttempts,
        lastObservationAt?.let(Instant::parse),
    )
}
internal fun Observation.stored() =
    StoredObservation(incidentId, observedAt.toString(), qualifiedSuccess, failureClass?.name)
internal fun HealthState.encode(): String = Json.encodeToString(
    StoredHealth(
        status.name,
        manuallyDisabled,
        failures.map { it.stored() },
        lastQualifiedAt?.toString(),
        probationStartedAt?.toString(),
        nextCheckAt?.toString(),
        quarantineAttempts,
        lastObservationAt?.toString(),
    ),
)
internal fun decodeHealth(json: String): HealthState = Json.decodeFromString<StoredHealth>(json).domain()

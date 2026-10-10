package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.core.Table

internal object Metadata : Table("metadata") {
    val key = text("key")
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}
internal object OfferingHealth : Table("offering_health") {
    val offeringId = text("offering_id")
    val stateJson = text("state_json")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(offeringId)
}
internal object ProbeAttempts : Table("probe_attempt") {
    val offeringId = text("offering_id")
    val attemptedAt = text("attempted_at")
    override val primaryKey = PrimaryKey(offeringId)
}
internal object OfferingProtocols : Table("offering_protocol") {
    val offeringId = text("offering_id")
    val protocol = text("protocol")
    val observedAt = text("observed_at")
    override val primaryKey = PrimaryKey(offeringId)
}
internal object HealthObservations : Table("health_observation") {
    val offeringId = text("offering_id").references(OfferingHealth.offeringId)
    val incidentId = text("incident_id")
    val observedAt = text("observed_at")
    val observationJson = text("observation_json")
    override val primaryKey = PrimaryKey(offeringId, incidentId)
}

package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.core.ReferenceOption
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
internal object LineupEntries : Table("lineup_entry") {
    val id = text("id")
    val position = integer("position")
    val number = text("number")
    val name = text("name")
    val groupName = text("group_name").nullable()
    val logo = text("logo").nullable()
    val enabled = bool("enabled")
    val guideId = text("guide_id").nullable()
    val guideChannel = text("guide_channel").nullable()
    val updatedAt = text("updated_at")
    val createdBy = text("created_by")
    val autoDisabledAt = text("auto_disabled_at").nullable()
    val autoDisabledReason = text("auto_disabled_reason").nullable()
    override val primaryKey = PrimaryKey(id)
}
internal object LineupOfferings : Table("lineup_offering") {
    val entryId = text("entry_id").references(LineupEntries.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val offeringId = text("offering_id")
    override val primaryKey = PrimaryKey(entryId, position)
}
internal object GuideImports : Table("guide_import") {
    val guideId = text("guide_id")
    val importedAt = text("imported_at")
    override val primaryKey = PrimaryKey(guideId)
}
internal object GuideChannels : Table("guide_channel") {
    val guideId = text("guide_id")
    val channelId = text("channel_id")
    val displayName = text("display_name")
    val icon = text("icon").nullable()
    override val primaryKey = PrimaryKey(guideId, channelId)
}
internal object LogoCacheRows : Table("logo_cache") {
    val key = text("key")
    val sourceUrl = text("source_url")
    val contentType = text("content_type").nullable()
    val size = long("size")
    val contentHash = text("content_hash").nullable()
    val fetchedAt = text("fetched_at").nullable()
    val failedAt = text("failed_at").nullable()
    val failures = integer("failures")
    override val primaryKey = PrimaryKey(key)
}
internal object PolicyChanges : Table("policy_change") {
    val id = long("id").autoIncrement()
    val appliedAt = text("applied_at")
    val entryId = text("entry_id").nullable()
    val changeJson = text("change_json")
    override val primaryKey = PrimaryKey(id)
}
internal object ProviderStates : Table("provider_state") {
    val providerId = text("provider_id")
    val key = text("key")
    val value = text("value")
    val expiresAt = text("expires_at").nullable()
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(providerId, key)
}
internal object GuideProgrammes : Table("guide_programme") {
    val guideId = text("guide_id")
    val channelId = text("channel_id")
    val startsAt = long("starts_at")
    val endsAt = long("ends_at")
    val title = text("title")
    val description = text("description").nullable()
    /** The programme's metadata beyond title and description as compact JSON; null when the source gave none. */
    val detailsJson = text("details_json").nullable()
    override val primaryKey = PrimaryKey(guideId, channelId, startsAt, endsAt, title)
}
/** One row per station key a lineup policy run remembers: availability and the pins' last offerings, as JSON. */
internal object StationAvailabilityRows : Table("station_availability") {
    val key = text("key")
    val availabilityJson = text("availability_json")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(key)
}

/** Overview items the operator ignored until they change: the item's key and the fingerprint of its text when ignored. */
internal object AttentionIgnores : Table("attention_ignore") {
    val itemKey = text("item_key")
    val fingerprint = text("fingerprint")
    val ignoredAt = text("ignored_at")
    override val primaryKey = PrimaryKey(itemKey)
}
internal object RuntimeSettings : Table("runtime_setting") {
    val key = text("key")
    val value = text("value")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(key)
}

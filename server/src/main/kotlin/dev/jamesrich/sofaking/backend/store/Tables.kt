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
    /** Comma-separated field names the household set; automation leaves them alone. */
    val pinned = text("pinned")
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
internal object CurationRules : Table("curation_rule") {
    val id = text("id")
    val kind = text("kind")
    val position = integer("position")
    val ruleJson = text("rule_json")
    override val primaryKey = PrimaryKey(id)
}
/** Since when an entry has had no eligible source; absent while it has one. */
internal object CurationEntryStates : Table("curation_entry_state") {
    val entryId = text("entry_id")
    val unavailableSince = text("unavailable_since")
    override val primaryKey = PrimaryKey(entryId)
}
/** Feed channels an adopt rule has created an entry for; never adopted twice. */
internal object CurationAdopted : Table("curation_adopted") {
    val channelId = text("channel_id")
    val entryId = text("entry_id")
    val adoptedAt = text("adopted_at")
    override val primaryKey = PrimaryKey(channelId)
}
internal object CurationChanges : Table("curation_change") {
    val id = long("id").autoIncrement()
    val appliedAt = text("applied_at")
    val entryId = text("entry_id").nullable()
    val actionJson = text("action_json")
    override val primaryKey = PrimaryKey(id)
}
internal object GuideProgrammes : Table("guide_programme") {
    val guideId = text("guide_id")
    val channelId = text("channel_id")
    val startsAt = long("starts_at")
    val endsAt = long("ends_at")
    val title = text("title")
    val description = text("description").nullable()
    override val primaryKey = PrimaryKey(guideId, channelId, startsAt, endsAt, title)
}

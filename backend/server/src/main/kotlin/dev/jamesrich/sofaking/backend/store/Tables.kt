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

    /**
     * The generation of the guide's channel and program rows that readers see; an import
     * writes the next one beside it.
     */
    val generation = long("generation").default(0)
    override val primaryKey = PrimaryKey(guideId)
}
internal object GuideChannels : Table("guide_channel") {
    val guideId = text("guide_id")
    val generation = long("generation").default(0)
    val channelId = text("channel_id")
    val displayName = text("display_name")
    val icon = text("icon").nullable()
    override val primaryKey = PrimaryKey(guideId, generation, channelId)
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
internal object GuidePrograms : Table("guide_program") {
    val guideId = text("guide_id")
    val generation = long("generation").default(0)
    val channelId = text("channel_id")
    val startsAt = long("starts_at")
    val endsAt = long("ends_at")
    val title = text("title")
    val description = text("description").nullable()

    /** The program's metadata beyond title and description as compact JSON; null when the source gave none. */
    val detailsJson = text("details_json").nullable()
    override val primaryKey = PrimaryKey(guideId, generation, channelId, startsAt, endsAt, title)
}

/** One row per station key a lineup policy run remembers: availability and the pins' last offerings, as JSON. */
internal object StationAvailabilityRows : Table("station_availability") {
    val key = text("key")
    val availabilityJson = text("availability_json")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(key)
}

/**
 * Overview items the operator ignored until they change: the item's key and the fingerprint of its text when ignored.
 */
internal object AttentionIgnores : Table("attention_ignore") {
    val itemKey = text("item_key")
    val fingerprint = text("fingerprint")
    val ignoredAt = text("ignored_at")
    override val primaryKey = PrimaryKey(itemKey)
}

/**
 * One signed-in portal browser: the SHA-256 of its cookie's session ID, never the ID itself,
 * its CSRF token and its expiry.
 */
internal object AdminSessionRows : Table("admin_session") {
    val key = text("key")
    val csrf = text("csrf")
    val expiresAt = text("expires_at")
    override val primaryKey = PrimaryKey(key)
}
internal object RuntimeSettings : Table("runtime_setting") {
    val key = text("key")
    val value = text("value")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(key)
}

/**
 * One row per client that paired or saved preferences: its label, when it was seen, and its preferences
 * document as JSON once it saved one.
 */
internal object Viewers : Table("viewer") {
    val clientId = text("client_id")
    val name = text("name")
    val platform = text("platform")
    val firstSeenAt = text("first_seen_at")
    val lastSeenAt = text("last_seen_at")
    val preferencesJson = text("preferences_json").nullable()
    val updatedAt = text("updated_at").nullable()
    override val primaryKey = PrimaryKey(clientId)
}

/** One media library: a folder under the media directory and how its files are named. */
internal object Libraries : Table("library") {
    val id = text("id")
    val name = text("name")
    val kind = text("kind")
    val path = text("path")
    val createdAt = text("created_at")
    val scannedAt = text("scanned_at").nullable()
    val scanOk = bool("scan_ok").nullable()
    val scanNote = text("scan_note").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * One movie, show, season, episode or video of a library; [path] is relative to the library's folder
 * and null for a show or season.
 */
internal object LibraryItems : Table("library_item") {
    val id = text("id")
    val libraryId = text("library_id")
    val kind = text("kind")
    val parentId = text("parent_id").nullable()
    val path = text("path").nullable()
    val title = text("title")
    val sortTitle = text("sort_title")
    val year = integer("year").nullable()
    val season = integer("season").nullable()
    val episode = integer("episode").nullable()
    val episodeEnd = integer("episode_end").nullable()
    val aired = text("aired").nullable()
    val overview = text("overview").nullable()
    val durationMs = long("duration_ms").nullable()
    val size = long("size").nullable()
    val modifiedAt = text("modified_at").nullable()
    val mediaJson = text("media_json").nullable()
    val detailsJson = text("details_json").nullable()
    val poster = text("poster").nullable()
    val backdrop = text("backdrop").nullable()
    val addedAt = text("added_at")
    val missingSince = text("missing_since").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** The household's resume point and watched flag for one item. */
internal object PlayStates : Table("play_state") {
    val itemId = text("item_id")
    val positionMs = long("position_ms")
    val durationMs = long("duration_ms").nullable()
    val watched = bool("watched")
    val updatedAt = text("updated_at")

    /** Taken off Continue watching until it is played again. */
    val dismissed = bool("dismissed").default(false)

    /** Whose state it is; every row is the household's until profiles exist. */
    val scope = text("scope").default(HOUSEHOLD)
    override val primaryKey = PrimaryKey(itemId)
}

/** What TMDB says about one item, and when it was last asked; [tmdbId] is null when no match was found. */
internal object LibraryMetadataRows : Table("library_metadata") {
    val itemId = text("item_id")
    val tmdbId = integer("tmdb_id").nullable()
    val checkedAt = text("checked_at")
    val factsJson = text("facts_json").nullable()
    val tvdbId = integer("tvdb_id").nullable()
    val imdbId = text("imdb_id").nullable()
    override val primaryKey = PrimaryKey(itemId)
}

/**
 * One episode's audio fingerprints and the intro and credits found from them. [fileKey] is the file's size and change
 * time when it was read, so a replaced file is read again; [fingerprint] is null when the file had no audio to read.
 * [tailFingerprint] covers the episode's end from [tailStartMs], which is set once the end has been read.
 */
internal object LibraryIntroRows : Table("library_intro") {
    val itemId = text("item_id")
    val fileKey = text("file_key")
    val fingerprint = binary("fingerprint").nullable()
    val introStartMs = long("intro_start_ms").nullable()
    val introEndMs = long("intro_end_ms").nullable()
    val readAt = text("read_at")
    val tailFingerprint = binary("tail_fingerprint").nullable()
    val tailStartMs = long("tail_start_ms").nullable()
    val creditsStartMs = long("credits_start_ms").nullable()
    val creditsEndMs = long("credits_end_ms").nullable()
    override val primaryKey = PrimaryKey(itemId)
}

internal object LibraryScrubRows : Table("library_scrub") {
    val itemId = text("item_id")
    val fileKey = text("file_key")
    val intervalMs = long("interval_ms")
    val tileWidth = integer("tile_width")
    val tileHeight = integer("tile_height")
    val columnCount = integer("columns")
    val rowCount = integer("rows")
    val sheets = integer("sheets")
    val frames = integer("frames")
    val madeAt = text("made_at")
    override val primaryKey = PrimaryKey(itemId)
}

internal object WatchlistEntries : Table("watchlist") {
    val id = text("id")

    /** Whose list it is; every row is the household's until profiles exist. */
    val scope = text("scope").default(HOUSEHOLD)
    val kind = text("kind")
    val itemId = text("item_id").nullable()
    val title = text("title")
    val channelId = text("channel_id").nullable()
    val addedAt = text("added_at")
    override val primaryKey = PrimaryKey(id)
}
internal object RecordingRules : Table("recording_rule") {
    val id = text("id")
    val title = text("title")
    val channelId = text("channel_id").nullable()
    val newOnly = bool("new_only")
    val keep = integer("keep")
    val startPadding = integer("start_padding_seconds")
    val endPadding = integer("end_padding_seconds")
    val priority = integer("priority")
    val createdAt = text("created_at")
    val matchKind = text("match_kind")
    val termsJson = text("terms_json")
    val categoriesJson = text("categories_json")
    override val primaryKey = PrimaryKey(id)
}
internal object Recordings : Table("recording") {
    val id = text("id")
    val ruleId = text("rule_id").nullable()
    val channelId = text("channel_id")
    val channelName = text("channel_name")
    val startsAt = long("starts_at")
    val endsAt = long("ends_at")
    val startPadding = integer("start_padding_seconds")
    val endPadding = integer("end_padding_seconds")
    val title = text("title")
    val episodeTitle = text("episode_title").nullable()
    val description = text("description").nullable()
    val season = integer("season").nullable()
    val episode = integer("episode").nullable()
    val movie = bool("movie")
    val year = integer("year").nullable()
    val image = text("image").nullable()
    val state = text("state")
    val reason = text("reason").nullable()
    val path = text("path").nullable()
    val bytes = long("bytes")
    val protected = bool("protected")
    val positionMs = long("position_ms")
    val watched = bool("watched")
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")
    val commercials = text("commercials").nullable()

    /** The breaks as `startMs-endMs` pairs joined by commas. */
    val breaks = text("breaks").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * What the app's live sessions and the ad listener found on each channel: how long it was played, how many ad breaks
 * were marked and how many learned, and when a marker was last seen on it ([markedAt]; null for a channel never seen
 * marked).
 */
internal object AdChannels : Table("ad_channel") {
    val channelId = text("channel_id")
    val providerId = text("provider_id")
    val watchedMs = long("watched_ms")
    val lastWatchedAt = text("last_watched_at").nullable()
    val breaks = integer("breaks")
    val learned = integer("learned")
    val markedAt = text("marked_at").nullable()
    val listenedMs = long("listened_ms")
    val lastListenedAt = text("last_listened_at").nullable()
    override val primaryKey = PrimaryKey(channelId)
}

/**
 * The ad audio the break library learned (see `BreakLibrary`): Chromaprint [points] as little-endian 32-bit words, the
 * provider and the channels and UTC days it was marked on (comma-separated), the break it was first heard in, and how
 * the matches since went.
 */
internal object AdBreakRows : Table("ad_break") {
    val id = text("id")
    val points = binary("points")
    val providerId = text("provider_id")
    val channels = text("channels")
    val days = text("days")
    val sourceBreak = text("source")
    val createdAt = text("created_at")
    val lastHitAt = text("last_hit_at").nullable()
    val hits = integer("hits")
    val falseHits = integer("false_hits")
    val blocked = bool("blocked")
    override val primaryKey = PrimaryKey(id)
}

/**
 * The tokens the server issued: one per paired device (`api`, with its [clientId]) and the ones the admin made for
 * dashboards (`read`) and hooks (`hook`). Only the SHA-256 of the secret is kept; [revokedAt] retires a row for good.
 */
internal object ApiTokenRows : Table("api_token") {
    val id = text("id")
    val label = text("label")
    val secretHash = text("secret_hash")
    val scope = text("scope")
    val clientId = text("client_id").nullable()
    val createdAt = text("created_at")
    val lastUsedAt = text("last_used_at").nullable()
    val revokedAt = text("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** The scope of household rows: the one value until profiles exist. */
internal const val HOUSEHOLD = "household"

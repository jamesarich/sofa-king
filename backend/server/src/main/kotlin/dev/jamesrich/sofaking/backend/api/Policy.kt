package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable

/**
 * The lineup policy: with the source configuration, everything that decides the stations, their IDs, numbers,
 * groups and sources. [providers] is ordered by priority; [stations] holds overrides keyed by post-alias station key.
 */
@Serializable data class LineupPolicy(
    val version: Int = 1,
    val names: PolicyNames = PolicyNames(),
    val providers: List<PolicyProvider> = emptyList(),
    val groups: PolicyGroups = PolicyGroups(),
    val numbering: PolicyNumbering = PolicyNumbering(),
    val health: PolicyHealth = PolicyHealth(),
    val maxSourcesPerStation: Int = 16,
    val stations: Map<String, StationOverride> = emptyMap(),
)

/**
 * [version] selects the name normalization (1 or 2); [aliases] map one normalized name onto another station's key.
 * [learned] holds the aliases runs found from matching schedules, applied after [aliases], which win on conflict;
 * [never] lists pairs of keys that never merge.
 */
@Serializable data class PolicyNames(
    val version: Int = 2,
    val aliases: Map<String, String> = emptyMap(),
    val learned: Map<String, String> = emptyMap(),
    val never: List<List<String>> = emptyList(),
)

/**
 * One provider entry. A candidate takes the first entry whose [id] is its provider and whose filters pass it.
 * No [include] admits everything. [enabled] is the default for stations this entry leads, and [group], when set, is
 * their group, so a provider that only fills in for other services never regroups a station it does not lead.
 */
@Serializable data class PolicyProvider(
    val id: String,
    val include: CandidateFilter? = null,
    val exclude: CandidateFilter? = null,
    val enabled: Boolean = true,
    val group: String? = null,
)

/**
 * Passes a candidate when any listed value matches: [groups] and [names] exactly and case-insensitively against the
 * raw group and name, the patterns as case-insensitive regexes found anywhere in them.
 */
@Serializable data class CandidateFilter(
    val groups: List<String> = emptyList(),
    val groupPatterns: List<String> = emptyList(),
    val names: List<String> = emptyList(),
    val namePatterns: List<String> = emptyList(),
)

/**
 * [map] is keyed by lowercase raw group, or `provider:raw group` for one provider's category. A station's sources
 * vote with their mapped categories; a category in [weak] votes only when no other category does. A station with no
 * vote takes [default], else its leading channel's raw group. With [builtIn], a category that neither [map] nor a
 * `rawGroup` pattern names is looked up in the group map the server ships, and the shipped weak list applies too.
 */
@Serializable data class PolicyGroups(
    val map: Map<String, String> = emptyMap(),
    val patterns: List<GroupPattern> = emptyList(),
    val default: String? = null,
    val weak: List<String> = emptyList(),
    val builtIn: Boolean = true,
)

/**
 * Assigns [group] ahead of any vote when [name] matches the station's name, or turns a source's raw group into a vote
 * for [group] when [rawGroup] matches it and the map does not name it (case-insensitive regexes).
 */
@Serializable data class GroupPattern(val name: String? = null, val rawGroup: String? = null, val group: String)

/**
 * New numbers come from [floor] upward, or the leading channel's own number with [keepProviderNumbers]. With
 * [groups] (group to first number), a station takes the next free number in its group's block of [groupSize],
 * then the floor once the block is full; provider numbers are not kept. [assigned] lists, sorted, the station keys
 * whose number the server wrote; those numbers are reclaimed once the station has been gone for keepMissingDays.
 */
@Serializable data class PolicyNumbering(
    val floor: Int = 1000,
    val keepProviderNumbers: Boolean = true,
    val groups: Map<String, Int> = emptyMap(),
    val groupSize: Int = 100,
    val assigned: List<String> = emptyList(),
    /** Moves a station whose key is in [assigned] into its group's block when its number lies outside it. */
    val followGroup: Boolean = false,
)

/**
 * [enableOn] is `healthy` or `eligible`. With [requireVerified], a station stays off until one of its sources has
 * qualified healthy once.
 */
@Serializable data class PolicyHealth(
    val disableAfterHours: Int = 24,
    val enableOn: String = "healthy",
    val keepMissingDays: Int = 7,
    val requireVerified: Boolean = true,
)

/**
 * Overrides for one station. [prefer] orders and [exclude] removes unpinned sources by provider ID. [gracenoteId] is
 * the station's Gracenote (TMS) station id, which the M3U output passes to Channels DVR for its guide.
 */
@Serializable data class StationOverride(
    val id: String? = null,
    val number: String? = null,
    val name: String? = null,
    val group: String? = null,
    val logo: String? = null,
    val guide: GuideMapping? = null,
    val gracenoteId: String? = null,
    val enabled: Boolean? = null,
    val required: Boolean = false,
    val sources: List<SourcePin> = emptyList(),
    val prefer: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
)

/** Joins a provider's channel to a station by [tvgId], or by [name] when either side has no `tvg-id`. */
@Serializable data class SourcePin(val provider: String, val tvgId: String? = null, val name: String? = null)

/** The loaded policy and its [revision], the SHA-256 of the file. */
@Serializable data class PolicyDocument(val revision: String, val path: String, val policy: LineupPolicy)

/**
 * One station a policy run produces. [disabledReason] is `policy`, `no-source`, `unverified`, `unavailable` or
 * `missing` when not [enabled]. [guideCandidates] are the guides that list one of its sources, in source order, when
 * no override sets the guide; [guide] is the first of them with programs ahead.
 */
@Serializable data class PolicyStation(
    val id: String,
    val key: String,
    val number: String,
    val name: String,
    val group: String? = null,
    val logo: String? = null,
    val guide: GuideMapping? = null,
    val enabled: Boolean = true,
    val required: Boolean = false,
    val offeringIds: List<String> = emptyList(),
    val disabledReason: String? = null,
    val guideCandidates: List<GuideMapping> = emptyList(),
    val gracenoteId: String? = null,
)

/**
 * A raw category no `groups.map` entry or pattern names, for the stations that fall to the default group or keep the
 * raw one. [category] is the lowercase key `groups.map` takes; [example] is the first station name in sorted order.
 */
@Serializable
data class PolicyUnmappedCategory(val provider: String, val category: String, val stations: Int, val example: String)

/** One field that differs between a current lineup entry and the station it maps to. */
@Serializable data class PolicyFieldChange(
    val key: String,
    val entryId: String,
    val field: String,
    val before: String? = null,
    val after: String? = null,
)

/** A current entry the policy would not produce. */
@Serializable data class PolicyRemoval(val key: String, val entryId: String, val name: String)

/**
 * Several current entries landing in one station ([entryIds] into [key]), or one entry spread over several stations.
 */
@Serializable data class PolicyRegrouping(val keys: List<String>, val entryIds: List<String>)

/** What a run would change against the current lineup; nothing is written. */
@Serializable data class PolicyPreview(
    val generatedAt: String,
    val revision: String,
    val stations: Int,
    val enabledStations: Int,
    val added: List<String> = emptyList(),
    val removed: List<PolicyRemoval> = emptyList(),
    val changes: List<PolicyFieldChange> = emptyList(),
    val merges: List<PolicyRegrouping> = emptyList(),
    val splits: List<PolicyRegrouping> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val findings: List<String> = emptyList(),
    val suggestedAliases: List<String> = emptyList(),
    /**
     * Aliases this run learned from matching schedules, `from` key to station key; an apply
     * writes them to `names.learned`.
     */
    val learned: Map<String, String> = emptyMap(),
    /** With HDHomeRun range devices: the devices the enabled stations need, and the devices configured. */
    val devicesNeeded: Int? = null,
    val devicesConfigured: Int? = null,
    val unmappedCategories: List<PolicyUnmappedCategory> = emptyList(),
)

/**
 * A feed channel as one run saw it. [outcome] is `taken`, `skipped` or `claimed` (pinned to [station], which is not
 * the key its name gives); [key] is the key its name normalizes to.
 */
@Serializable data class PolicyCandidate(
    val channelId: String,
    val providerId: String,
    val number: String,
    val name: String,
    val tvgId: String? = null,
    val key: String,
    val entryIndex: Int? = null,
    val outcome: String,
    val station: String? = null,
    val reason: String,
)

/** Every candidate that names [key] or joins its station, and the pins its override lists. */
@Serializable data class StationExplanation(
    val key: String,
    val station: PolicyStation? = null,
    val pins: List<SourcePin> = emptyList(),
    val candidates: List<PolicyCandidate> = emptyList(),
    val findings: List<String> = emptyList(),
)

/**
 * What one apply did. [assigned] holds the numbers this run gave stations without one and [learned] the aliases it
 * found from matching schedules; both are written back to the policy. With HDHomeRun range devices,
 * [devicesNeeded] is what the enabled stations need and [devicesConfigured] what is set up.
 */
@Serializable data class PolicyApplyResult(
    val appliedAt: String,
    val revision: String,
    val changed: Boolean,
    val stations: Int,
    val enabledStations: Int,
    val changes: Int = 0,
    val assigned: Map<String, String> = emptyMap(),
    val findings: List<String> = emptyList(),
    val suggestedAliases: List<String> = emptyList(),
    val learned: Map<String, String> = emptyMap(),
    val devicesNeeded: Int? = null,
    val devicesConfigured: Int? = null,
    val unmappedCategories: List<PolicyUnmappedCategory> = emptyList(),
)

/** A policy write and the apply that followed it. */
@Serializable data class PolicyWriteResult(val document: PolicyDocument, val apply: PolicyApplyResult)

/** Open findings of a run over the current feeds, and live station keys that probably name one station. */
@Serializable data class PolicyFindings(
    val generatedAt: String,
    val revision: String,
    val findings: List<String> = emptyList(),
    val suggestedAliases: List<String> = emptyList(),
    val unmappedCategories: List<PolicyUnmappedCategory> = emptyList(),
)

/**
 * One field a policy run changed on the lineup entry of station [key], with the run's cause as [reason]. [field] is
 * `station` with [before] null for a station that joined and [after] null for one that left.
 */
@Serializable data class PolicyChange(
    val entryId: String? = null,
    val entryName: String,
    val field: String? = null,
    val before: String? = null,
    val after: String? = null,
    val reason: String,
    val key: String? = null,
)

/** A logged [change], newest first in the change log. */
@Serializable data class PolicyChangeEntry(val id: Long, val appliedAt: String, val change: PolicyChange)

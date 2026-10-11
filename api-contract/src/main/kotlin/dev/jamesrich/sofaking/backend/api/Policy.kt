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

/** [version] selects the name normalization; [aliases] map one normalized name onto another station's key. */
@Serializable data class PolicyNames(val version: Int = 1, val aliases: Map<String, String> = emptyMap())

/**
 * One provider entry. A candidate takes the first entry whose [id] is its provider and whose filters pass it.
 * No [include] admits everything. [enabled] is the default for stations this entry leads.
 */
@Serializable data class PolicyProvider(
    val id: String, val include: CandidateFilter? = null, val exclude: CandidateFilter? = null, val enabled: Boolean = true,
)

/**
 * Passes a candidate when any listed value matches: [groups] and [names] exactly and case-insensitively against the
 * raw group and name, the patterns as case-insensitive regexes found anywhere in them.
 */
@Serializable data class CandidateFilter(
    val groups: List<String> = emptyList(), val groupPatterns: List<String> = emptyList(),
    val names: List<String> = emptyList(), val namePatterns: List<String> = emptyList(),
)

/** [map] is keyed by lowercase raw group. A station with no other group takes [default], else its raw group. */
@Serializable data class PolicyGroups(
    val map: Map<String, String> = emptyMap(), val patterns: List<GroupPattern> = emptyList(), val default: String? = null,
)

/** Assigns [group] when [name] matches the leading candidate's name or [rawGroup] its raw group (case-insensitive regexes). */
@Serializable data class GroupPattern(val name: String? = null, val rawGroup: String? = null, val group: String)

@Serializable data class PolicyNumbering(val floor: Int = 1000, val keepProviderNumbers: Boolean = true)

/** [enableOn] is `healthy` or `eligible`. */
@Serializable data class PolicyHealth(val disableAfterHours: Int = 24, val enableOn: String = "healthy", val keepMissingDays: Int = 7)

/** Overrides for one station. [prefer] orders and [exclude] removes unpinned sources by provider ID. */
@Serializable data class StationOverride(
    val id: String? = null, val number: String? = null, val name: String? = null, val group: String? = null,
    val logo: String? = null, val guide: GuideMapping? = null, val enabled: Boolean? = null, val required: Boolean = false,
    val sources: List<SourcePin> = emptyList(), val prefer: List<String> = emptyList(), val exclude: List<String> = emptyList(),
)

/** Joins a provider's channel to a station by [tvgId], or by [name] when either side has no `tvg-id`. */
@Serializable data class SourcePin(val provider: String, val tvgId: String? = null, val name: String? = null)

/** The loaded policy and its [revision], the SHA-256 of the file. */
@Serializable data class PolicyDocument(val revision: String, val path: String, val policy: LineupPolicy)

/** One station a policy run produces. [disabledReason] is `policy`, `unavailable`, `missing` or `no-source` when not [enabled]. */
@Serializable data class PolicyStation(
    val id: String, val key: String, val number: String, val name: String, val group: String? = null, val logo: String? = null,
    val guide: GuideMapping? = null, val enabled: Boolean = true, val required: Boolean = false,
    val offeringIds: List<String> = emptyList(), val disabledReason: String? = null,
)

/** One field that differs between a current lineup entry and the station it maps to. */
@Serializable data class PolicyFieldChange(val key: String, val entryId: String, val field: String, val before: String? = null, val after: String? = null)

/** A current entry the policy would not produce. */
@Serializable data class PolicyRemoval(val key: String, val entryId: String, val name: String)

/** Several current entries landing in one station ([entryIds] into [key]), or one entry spread over several stations. */
@Serializable data class PolicyRegrouping(val keys: List<String>, val entryIds: List<String>)

/** What a run would change against the current lineup; nothing is written. */
@Serializable data class PolicyPreview(
    val generatedAt: String, val revision: String, val stations: Int, val enabledStations: Int,
    val added: List<String> = emptyList(), val removed: List<PolicyRemoval> = emptyList(),
    val changes: List<PolicyFieldChange> = emptyList(), val merges: List<PolicyRegrouping> = emptyList(),
    val splits: List<PolicyRegrouping> = emptyList(), val counts: Map<String, Int> = emptyMap(), val findings: List<String> = emptyList(),
)

/**
 * A feed channel as one run saw it. [outcome] is `taken`, `skipped` or `claimed` (pinned to [station], which is not
 * the key its name gives); [key] is the key its name normalizes to.
 */
@Serializable data class PolicyCandidate(
    val channelId: String, val providerId: String, val number: String, val name: String, val tvgId: String? = null,
    val key: String, val entryIndex: Int? = null, val outcome: String, val station: String? = null, val reason: String,
)

/** Every candidate that names [key] or joins its station, and the pins its override lists. */
@Serializable data class StationExplanation(
    val key: String, val station: PolicyStation? = null, val pins: List<SourcePin> = emptyList(),
    val candidates: List<PolicyCandidate> = emptyList(), val findings: List<String> = emptyList(),
)

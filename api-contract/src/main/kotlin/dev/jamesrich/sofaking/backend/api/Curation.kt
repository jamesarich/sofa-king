package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * How automation curates the lineup. With [enabled] false every run is a preview. [enableOn] is `healthy` or
 * `eligible`; [equivalentEvidence] is `name-and-guide` or `name`; [retiredLogoHosts] are `host` or `host:port`;
 * [retiredGuides] are guide IDs whose mappings move to a guide that one of the entry's sources lists.
 */
@Serializable data class CurationSettings(
    val enabled: Boolean = false,
    val intervalSeconds: Long = 900,
    val disableAfterHours: Int = 24,
    val enableOn: String = "healthy",
    val equivalentEvidence: String = "name-and-guide",
    val numberFloor: Int = 1000,
    val retiredLogoHosts: List<String> = emptyList(),
    val defaultGroup: String? = null,
    val retiredGuides: List<String> = emptyList(),
)

/** Every field given must match. [group] and [name] are exact and case-insensitive; the patterns are regexes. */
@Serializable data class RuleMatch(
    val providerId: String? = null, val group: String? = null, val groupPattern: String? = null,
    val name: String? = null, val namePattern: String? = null, val tvgId: String? = null, val entryId: String? = null,
)
@Serializable data class RuleSet(
    val group: String? = null, val name: String? = null, val logo: String? = null, val enabled: Boolean? = null, val canonicalEntryId: String? = null,
)
/** [kind] is `group`, `correction`, `alias`, `equivalent` or `adopt`; rules of one kind apply in [position] order. */
@Serializable data class CurationRule(
    val id: String = "", val kind: String, val position: Int = 0, val enabled: Boolean = true,
    val match: RuleMatch = RuleMatch(), val set: RuleSet = RuleSet(), val note: String? = null,
)

/** One change automation would make or made. [kind] is `regroup`, `rename`, `relogo`, `clear-logo`, `attach`, `disable`, `enable` or `adopt`. */
@Serializable data class CurationAction(
    val kind: String, val entryId: String? = null, val entryName: String, val field: String? = null,
    val before: String? = null, val after: String? = null, val reason: String, val ruleId: String? = null,
    val offeringIds: List<String> = emptyList(),
)
@Serializable data class CurationPlan(
    val generatedAt: String, val enabled: Boolean, val actions: List<CurationAction>, val counts: Map<String, Int> = emptyMap(),
    val findings: List<String> = emptyList(),
)
@Serializable data class CurationChange(val id: Long, val appliedAt: String, val action: CurationAction)

/** The pipeline's data files, each optional; records keep their other fields, which are ignored. */
@Serializable data class CurationImport(
    val groupMap: Map<String, String> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    val equivalents: List<EquivalentRecord> = emptyList(),
    val sportsCorrections: List<CorrectionRecord> = emptyList(),
    val corrections: JsonElement? = null,
    val dryRun: Boolean = false,
)
@Serializable data class EquivalentRecord(val uuid: String? = null, val name: String? = null)
@Serializable data class CorrectionRecord(val id: Int? = null, val beforeName: String? = null, val afterName: String? = null)
/** [outcome] is `created`, `updated`, `exists`, `skipped` or `applied-by-import`. */
@Serializable data class CurationImportItem(val source: String, val item: String, val outcome: String, val ruleId: String? = null, val reason: String? = null)
@Serializable data class CurationImportReport(val dryRun: Boolean, val created: Int, val updated: Int, val existing: Int, val skipped: Int, val items: List<CurationImportItem>)

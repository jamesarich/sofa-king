package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable

/** Which XMLTV channel of which configured guide source carries a lineup entry's programmes. */
@Serializable data class GuideMapping(val guideId: String, val channelId: String)

@Serializable data class CandidateOffering(val id: String, val protocol: String, val status: String, val manuallyDisabled: Boolean = false)
@Serializable data class Candidate(
    val channelId: String, val providerId: String, val number: String, val name: String, val logo: String? = null,
    val group: String? = null, val tvgId: String? = null, val offerings: List<CandidateOffering> = emptyList(),
    val lineupEntryIds: List<String> = emptyList(),
)
@Serializable data class CandidatePage(val total: Int, val items: List<Candidate>)

@Serializable data class GuideStatus(
    val id: String, val lastSuccessAt: String? = null, val errorCode: String? = null, val channels: Int = 0, val programmes: Int = 0,
)
@Serializable data class GuideChannelInfo(val guideId: String, val channelId: String, val displayName: String, val icon: String? = null)

/** `status` is a health state, or `WITHDRAWN` when no configured feed currently offers the source. */
@Serializable data class LineupOfferingInfo(
    val id: String, val providerId: String? = null, val channelName: String? = null, val protocol: String? = null,
    val status: String, val manuallyDisabled: Boolean = false,
)
/**
 * [pinned] names the fields the household set (`number`, `name`, `group`, `logo`, `enabled`, `offerings`, `guide`), which
 * curation never changes. [createdBy] is `manual`, `import` or `rule:ID`. [autoDisabledAt] and [autoDisabledReason]
 * (`unavailable` or `alias`) are set while curation, not the household, holds the entry disabled.
 */
@Serializable data class LineupEntry(
    val id: String, val position: Int, val number: String, val name: String, val group: String? = null, val logo: String? = null,
    val enabled: Boolean = true, val guide: GuideMapping? = null, val offerings: List<LineupOfferingInfo> = emptyList(),
    val guideStatus: String = "unassigned", val pinned: List<String> = emptyList(), val createdBy: String = "manual",
    val autoDisabledAt: String? = null, val autoDisabledReason: String? = null,
)
/**
 * Either name a feed [channelId] to copy its number, name, logo, group and sources, or supply them explicitly. Every
 * field supplied is pinned; [enabled] defaults to true and is pinned only when sent.
 */
@Serializable data class CreateLineupEntry(
    val channelId: String? = null, val id: String? = null, val number: String? = null, val name: String? = null, val group: String? = null,
    val logo: String? = null, val enabled: Boolean? = null, val offerings: List<String>? = null, val guide: GuideMapping? = null,
    val position: Int? = null,
)
/**
 * Absent fields keep their value; the `clear*` flags remove optional values. Every field sent (and every field cleared)
 * is pinned; [pin] and [unpin] change pins without changing values.
 */
@Serializable data class UpdateLineupEntry(
    val number: String? = null, val name: String? = null, val group: String? = null, val logo: String? = null, val enabled: Boolean? = null,
    val offerings: List<String>? = null, val guide: GuideMapping? = null, val position: Int? = null,
    val clearGroup: Boolean = false, val clearLogo: Boolean = false, val clearGuide: Boolean = false,
    val pin: List<String> = emptyList(), val unpin: List<String> = emptyList(),
)
@Serializable data class ReorderLineup(val ids: List<String>)
@Serializable data class AdoptProvider(val providerId: String, val enabled: Boolean = true)
@Serializable data class AdoptResult(val added: Int, val skipped: Int)

/** The JSON that `scripts/dispatcharr-export.py` writes for one channel profile. */
@Serializable data class DispatcharrExport(
    val format: String, val profile: String? = null, val exportedAt: String? = null,
    val epgSources: List<DispatcharrEpgSource> = emptyList(), val channels: List<DispatcharrChannel>,
)
@Serializable data class DispatcharrEpgSource(val id: Int, val name: String, val url: String? = null)
@Serializable data class DispatcharrStream(val url: String, val name: String? = null, val provider: String? = null)
@Serializable data class DispatcharrChannel(
    val uuid: String? = null, val number: String, val name: String, val group: String? = null, val logo: String? = null,
    val tvgId: String? = null, val epgSourceId: Int? = null, val epgChannelId: String? = null, val enabled: Boolean = true,
    val streams: List<DispatcharrStream> = emptyList(),
)
/** [guideMap] maps a Dispatcharr EPG source id to a configured guide id when URL matching is not enough. */
@Serializable data class ImportLineup(
    val export: DispatcharrExport, val guideMap: Map<String, String> = emptyMap(), val dryRun: Boolean = false,
    val includeUnmatched: Boolean = false,
)
@Serializable data class ImportEntryReport(
    val number: String, val name: String, val uuid: String? = null, val action: String, val matchedStreams: Int,
    val unmatchedStreams: List<String> = emptyList(), val guide: String, val error: String? = null,
)
@Serializable data class ImportReport(
    val dryRun: Boolean, val created: Int, val updated: Int, val skipped: Int, val matchedStreams: Int, val unmatchedStreams: Int,
    val guideSources: Map<String, String?> = emptyMap(), val entries: List<ImportEntryReport> = emptyList(),
)

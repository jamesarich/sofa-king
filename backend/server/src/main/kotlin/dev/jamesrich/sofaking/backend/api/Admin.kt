package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which XMLTV channel of which configured guide source carries a lineup entry's programs. */
@Serializable data class GuideMapping(val guideId: String, val channelId: String)

@Serializable data class CandidateOffering(val id: String, val protocol: String, val status: String, val manuallyDisabled: Boolean = false)
@Serializable data class Candidate(
    val channelId: String, val providerId: String, val number: String, val name: String, val logo: String? = null,
    val group: String? = null, val tvgId: String? = null, val offerings: List<CandidateOffering> = emptyList(),
    val lineupEntryIds: List<String> = emptyList(),
)
@Serializable data class CandidatePage(val total: Int, val items: List<Candidate>)

@Serializable data class GuideStatus(
    val id: String, val lastSuccessAt: String? = null, val errorCode: String? = null, val channels: Int = 0, @SerialName("programmes") val programs: Int = 0,
)
@Serializable data class GuideChannelInfo(val guideId: String, val channelId: String, val displayName: String, val icon: String? = null)

/** `status` is a health state, or `WITHDRAWN` when no configured feed currently offers the source. */
@Serializable data class LineupOfferingInfo(
    val id: String, val providerId: String? = null, val channelName: String? = null, val protocol: String? = null,
    val status: String, val manuallyDisabled: Boolean = false,
)
/**
 * [createdBy] is `policy` for an entry a lineup policy built, else `manual`. [autoDisabledAt] and [autoDisabledReason]
 * (`unavailable`) are set while the policy holds the station off for want of an eligible source.
 */
@Serializable data class LineupEntry(
    val id: String, val position: Int, val number: String, val name: String, val group: String? = null, val logo: String? = null,
    val enabled: Boolean = true, val guide: GuideMapping? = null, val offerings: List<LineupOfferingInfo> = emptyList(),
    val guideStatus: String = "unassigned", val createdBy: String = "manual",
    val autoDisabledAt: String? = null, val autoDisabledReason: String? = null,
)
/**
 * Either name a feed [channelId] to copy its number, name, logo, group and sources, or supply them explicitly; fields
 * supplied override the copied ones. [enabled] defaults to true.
 */
@Serializable data class CreateLineupEntry(
    val channelId: String? = null, val id: String? = null, val number: String? = null, val name: String? = null, val group: String? = null,
    val logo: String? = null, val enabled: Boolean? = null, val offerings: List<String>? = null, val guide: GuideMapping? = null,
    val position: Int? = null,
)
/** Absent fields keep their value; the `clear*` flags remove optional values. */
@Serializable data class UpdateLineupEntry(
    val number: String? = null, val name: String? = null, val group: String? = null, val logo: String? = null, val enabled: Boolean? = null,
    val offerings: List<String>? = null, val guide: GuideMapping? = null, val position: Int? = null,
    val clearGroup: Boolean = false, val clearLogo: Boolean = false, val clearGuide: Boolean = false,
)
@Serializable data class ReorderLineup(val ids: List<String>)
@Serializable data class AdoptProvider(val providerId: String, val enabled: Boolean = true)
@Serializable data class AdoptResult(val added: Int, val skipped: Int)

/**
 * A client the server knows: it paired with a code or saved preferences. [preferences] is null until the client saves
 * some; [name] and [platform] are what the client called itself.
 */
@Serializable data class Viewer(
    val clientId: String, val name: String = "", val platform: String = "", val firstSeenAt: String, val lastSeenAt: String,
    val preferences: dev.jamesrich.sofaking.contract.ViewerPreferences? = null,
)

/** A pairing code the admin API issued: [code] is what the viewer types, valid until [expiresAt]. */
@Serializable data class PairingCode(val code: String, val expiresAt: String)

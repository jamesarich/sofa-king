package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Which XMLTV channel of which configured guide source carries a lineup entry's programs. */
@Serializable data class GuideMapping(val guideId: String, val channelId: String)

@Serializable data class CandidateOffering(
    val id: String,
    val protocol: String,
    val status: String,
    val manuallyDisabled: Boolean = false,
)

@Serializable data class Candidate(
    val channelId: String,
    val providerId: String,
    val number: String,
    val name: String,
    val logo: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    val offerings: List<CandidateOffering> = emptyList(),
    val lineupEntryIds: List<String> = emptyList(),
)

@Serializable data class CandidatePage(val total: Int, val items: List<Candidate>)

@Serializable data class GuideStatus(
    val id: String,
    val lastSuccessAt: String? = null,
    val errorCode: String? = null,
    val channels: Int = 0,
    val programs: Int = 0,
    /** Why the last import failed, URLs redacted; the portal shows it, and the API keeps to [errorCode]. */
    @Transient val error: String? = null,
)

@Serializable data class GuideChannelInfo(
    val guideId: String,
    val channelId: String,
    val displayName: String,
    val icon: String? = null,
)

/** `status` is a health state, or `WITHDRAWN` when no configured feed currently offers the source. */
@Serializable data class LineupOfferingInfo(
    val id: String,
    val providerId: String? = null,
    val channelName: String? = null,
    val protocol: String? = null,
    val status: String,
    val manuallyDisabled: Boolean = false,
)

/**
 * [createdBy] is `policy` for an entry a lineup policy built, else `manual`. [autoDisabledAt] and [autoDisabledReason]
 * (`unavailable`) are set while the policy holds the station off for want of an eligible source. [gracenoteId] is the
 * policy's Gracenote station id for the station.
 */
@Serializable data class LineupEntry(
    val id: String,
    val position: Int,
    val number: String,
    val name: String,
    val group: String? = null,
    val logo: String? = null,
    val enabled: Boolean = true,
    val guide: GuideMapping? = null,
    val offerings: List<LineupOfferingInfo> = emptyList(),
    val guideStatus: String = "unassigned",
    val createdBy: String = "manual",
    val autoDisabledAt: String? = null,
    val autoDisabledReason: String? = null,
    val gracenoteId: String? = null,
)

/**
 * Either name a feed [channelId] to copy its number, name, logo, group and sources, or supply them explicitly; fields
 * supplied override the copied ones. [enabled] defaults to true.
 */
@Serializable data class CreateLineupEntry(
    val channelId: String? = null,
    val id: String? = null,
    val number: String? = null,
    val name: String? = null,
    val group: String? = null,
    val logo: String? = null,
    val enabled: Boolean? = null,
    val offerings: List<String>? = null,
    val guide: GuideMapping? = null,
    val position: Int? = null,
)

/**
 * Absent fields keep their value; the `clear*` flags remove optional values. [gracenoteId] is written to the policy and
 * needs one.
 */
@Serializable data class UpdateLineupEntry(
    val number: String? = null,
    val name: String? = null,
    val group: String? = null,
    val logo: String? = null,
    val enabled: Boolean? = null,
    val offerings: List<String>? = null,
    val guide: GuideMapping? = null,
    val position: Int? = null,
    val clearGroup: Boolean = false,
    val clearLogo: Boolean = false,
    val clearGuide: Boolean = false,
    val gracenoteId: String? = null,
    val clearGracenoteId: Boolean = false,
)

@Serializable data class ReorderLineup(val ids: List<String>)

@Serializable data class AdoptProvider(val providerId: String, val enabled: Boolean = true)

@Serializable data class AdoptResult(val added: Int, val skipped: Int)

/**
 * A client the server knows: it paired with a code or saved preferences. [preferences] is null until the client saves
 * some; [name] and [platform] are what the client called itself.
 */
@Serializable data class Viewer(
    val clientId: String,
    val name: String = "",
    val platform: String = "",
    val firstSeenAt: String,
    val lastSeenAt: String,
    val preferences: dev.jamesrich.sofaking.contract.ViewerPreferences? = null,
    /** Whether the client holds a token of its own from pairing; false means it uses the shared API token. */
    val ownToken: Boolean = false,
)

/** A pairing code the admin API issued: [code] is what the viewer types, valid until [expiresAt]. */
@Serializable data class PairingCode(val code: String, val expiresAt: String)

/** What a token the server issued opens: the app's API, the read-only status, or the inbound hooks. */
@Serializable enum class TokenScope {
    @SerialName("api")
    API,

    @SerialName("read")
    READ,

    @SerialName("hook")
    HOOK,
}

/**
 * A token the server issued, without its secret: [clientId] names the device it was paired to, for an `api` token;
 * [lastUsedAt] is to the minute.
 */
@Serializable data class ApiTokenInfo(
    val id: String,
    val label: String,
    val scope: TokenScope,
    val clientId: String? = null,
    val createdAt: String,
    val lastUsedAt: String? = null,
)

/** Asks for a new `read` or `hook` token named [label]; `api` tokens come only from pairing. */
@Serializable data class CreateApiToken(val label: String, val scope: TokenScope)

/** A new token and its [secret], which the server shows this once and keeps only as a hash. */
@Serializable data class CreatedApiToken(val token: ApiTokenInfo, val secret: String)

/**
 * A named player output: its own `/output/{name}/playlist.m3u` and `/output/{name}/guide.xml` holding the lineup's
 * channels in one of [groups] (ignoring case) or listed in [channels] by number or entry ID.
 */
@Serializable data class NamedOutput(
    val name: String = "",
    val groups: List<String> = emptyList(),
    val channels: List<String> = emptyList(),
)

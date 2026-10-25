package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * One ended playback session, as `GET /api/v1/admin/history` lists it. [kind] is `live` or `vod`; a live session names
 * its [channelId] and [providerId], a VOD one its library [itemId] or [recordingId]. [clientId] is the paired device
 * whose token opened it, null for the shared token and for players outside the app, and [address] the player's
 * address. [mode] is the live session's intent (`watch`, `preview`), `external` or `recording`, or the VOD session's
 * `direct`, `remux` or `transcode`; [reason] is how it ended (`released`, `expired`, `evicted`, `stopped`). [bytes]
 * is what the relay sent, null for VOD.
 */
@Serializable data class HistoryEntry(
    val id: String,
    val kind: String,
    val clientId: String? = null,
    val address: String? = null,
    val channelId: String? = null,
    val itemId: String? = null,
    val recordingId: String? = null,
    val providerId: String? = null,
    val mode: String,
    val reason: String,
    val startedAt: String,
    val endedAt: String,
    val bytes: Long? = null,
)

/**
 * A playback session as it starts, the data of a `session.started` event; [HistoryEntry] has the same fields once it
 * ends. [mode] is the intent it opened with.
 */
@Serializable data class SessionStart(
    val id: String,
    val kind: String,
    val clientId: String? = null,
    val address: String? = null,
    val channelId: String? = null,
    val itemId: String? = null,
    val recordingId: String? = null,
    val providerId: String? = null,
    val mode: String,
    val startedAt: String,
)

/**
 * One event on `GET /api/v1/admin/events/stream`, sent as the SSE event named [type] with this object as its data.
 * [data] holds the event's fields: a session's, a recording's, or the attention items that came and went.
 */
@Serializable data class ServerEvent(val type: String, val at: String, val data: JsonObject)

/** What `GET /api/v1/admin/history` filters on; a null field matches every row. */
data class HistoryQuery(
    val since: Instant? = null,
    val kind: String? = null,
    /** A paired device's client id, or a player's address. */
    val client: String? = null,
    val limit: Int = 200,
)

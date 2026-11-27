package dev.jamesrich.sofaking.contract

import kotlinx.serialization.Serializable

/**
 * [viewers] says the server pairs clients (`/api/v1/pair`) and keeps their preferences
 * (`/api/v1/preferences/{clientId}`); [recordings] that it records (`/api/v1/recordings`, `/api/v1/recording-rules`).
 */
@Serializable data class Capabilities(
    val catalog: Boolean = false,
    val guide: Boolean = false,
    val playback: Boolean = false,
    val events: Boolean = false,
    val viewers: Boolean = false,
    val library: Boolean = false,
    val recordings: Boolean = false,
    /** `/api/v1/watchlist`, and the Watchlist shelf on a server with libraries. */
    val watchlist: Boolean = false,
    /** `GET /api/v1/guide/on-later`. */
    val onLater: Boolean = false,
    /** `GET`/`PUT /api/v1/household/preferences`, the favorites and hidden channels devices may share. */
    val household: Boolean = false,
    /** `/api/v1/multiview-sessions`: the server can composite channels into one picture now. */
    val multiview: Boolean = false,
    /** Music libraries, listed to a client that asks for them with [Wire.Header.LIBRARY_KINDS]. */
    val music: Boolean = false,
)

@Serializable data class Bootstrap(
    val serverId: String,
    val serverTime: String,
    val apiVersion: Int = 1,
    val capabilities: Capabilities = Capabilities(),
)

@Serializable data class ApiError(val code: String, val message: String, val retryable: Boolean = false)

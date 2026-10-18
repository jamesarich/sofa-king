package dev.jamesrich.sofaking.contract

import kotlinx.serialization.Serializable

/**
 * [viewers] says the server pairs clients (`/api/v1/pair`) and keeps their preferences (`/api/v1/preferences/{clientId}`);
 * [recordings] that it records (`/api/v1/recordings`, `/api/v1/recording-rules`).
 */
@Serializable data class Capabilities(
    val catalog: Boolean = false, val guide: Boolean = false, val playback: Boolean = false, val events: Boolean = false, val viewers: Boolean = false,
    val library: Boolean = false, val recordings: Boolean = false,
)
@Serializable data class Bootstrap(val serverId: String, val serverTime: String, val apiVersion: Int = 1, val capabilities: Capabilities = Capabilities())
@Serializable data class ApiError(val code: String, val message: String, val retryable: Boolean = false)

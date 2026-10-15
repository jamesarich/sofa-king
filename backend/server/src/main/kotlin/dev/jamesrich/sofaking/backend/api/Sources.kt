package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable

/**
 * A live session as the admin API lists it; [external] marks an M3U or HDHomeRun player, [busy] a media request in
 * flight, [client] the player's address and [bytes] what the server has sent it.
 */
@Serializable data class ActiveSession(
    val id: String, val channelId: String, val intent: String, val external: Boolean, val busy: Boolean,
    val providerId: String, val expiresAt: String,
    val startedAt: String = "", val client: String? = null, val bytes: Long = 0,
)
@Serializable data class ProviderStatus(
    val id: String, val lastSuccessAt: String? = null, val errorCode: String? = null, val channels: Int = 0,
    val sources: Int = 0, val skippedEntries: Int = 0, val skipReasons: String? = null, val notes: String? = null,
)
@Serializable data class SourceStatus(val id: String, val channelId: String, val providerId: String, val status: String, val manuallyDisabled: Boolean, val lastQualifiedAt: String? = null, val nextCheckAt: String? = null)
@Serializable data class UpdateSource(val manuallyDisabled: Boolean)

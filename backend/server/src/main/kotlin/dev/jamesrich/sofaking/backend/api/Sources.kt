package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A live session as the admin API lists it; [external] marks an M3U or HDHomeRun player, [busy] a media request in
 * flight, [client] the player's address and [bytes] what the server has sent it. [offeringId] is the source it plays;
 * [bitrateKbps] the rate its player was sent media at over the last 10 seconds or so; [stalls] the media requests the
 * upstream refused or dropped before anything was answered; [segmentsServed] the HLS segments relayed;
 * [sourceSwitches] the times it moved to another source, on a failure or by hand.
 */
@Serializable data class ActiveSession(
    val id: String,
    val channelId: String,
    val intent: String,
    val external: Boolean,
    val busy: Boolean,
    val providerId: String,
    val expiresAt: String,
    val startedAt: String = "",
    val client: String? = null,
    val bytes: Long = 0,
    val offeringId: String = "",
    val bitrateKbps: Int = 0,
    val stalls: Long = 0,
    val segmentsServed: Long = 0,
    val sourceSwitches: Int = 0,
)

@Serializable data class ProviderStatus(
    val id: String,
    val lastSuccessAt: String? = null,
    val errorCode: String? = null,
    val channels: Int = 0,
    val sources: Int = 0,
    val skippedEntries: Int = 0,
    val skipReasons: String? = null,
    val notes: String? = null,
    /** Why the last import failed, URLs redacted; the portal shows it, and the API keeps to [errorCode]. */
    @Transient val error: String? = null,
)

@Serializable data class SourceStatus(
    val id: String,
    val channelId: String,
    val providerId: String,
    val status: String,
    val manuallyDisabled: Boolean,
    val lastQualifiedAt: String? = null,
    val nextCheckAt: String? = null,
)

@Serializable data class UpdateSource(val manuallyDisabled: Boolean)

/** A probe request's answer: the source whose probe now waits at the front of the queue. */
@Serializable data class ProbeQueued(val sourceId: String)

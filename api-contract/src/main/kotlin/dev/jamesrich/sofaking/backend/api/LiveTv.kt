package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable

@Serializable data class Channel(
    val id: String, val displayNumber: String, val name: String, val logo: String? = null,
    val tags: List<String> = emptyList(), val legacyGuideId: String? = null,
    val guideStatus: String = "unassigned", val playbackEligible: Boolean = false,
)
@Serializable data class Catalog(val revision: String, val channels: List<Channel>)
@Serializable data class Programme(
    val id: String, val channelId: String, val startsAt: String, val endsAt: String,
    val title: String, val description: String? = null,
)
@Serializable data class GuideQuery(val channelIds: List<String>, val from: String, val to: String, val revision: String? = null)
@Serializable data class ChannelGuide(val channelId: String, val status: String, val programmes: List<Programme>)
@Serializable data class GuideWindow(val revision: String, val from: String, val to: String, val channels: List<ChannelGuide>)
@Serializable data class OpenPlayback(val channelId: String, val intent: String = "watch", val idempotencyKey: String)
@Serializable data class UpdatePlayback(val intent: String? = null)
@Serializable data class PlaybackDescriptor(val protocol: String, val mimeType: String, val url: String, val epoch: Int)
@Serializable data class PlaybackSession(val id: String, val channelId: String, val intent: String, val expiresAt: String, val descriptor: PlaybackDescriptor)
@Serializable data class ProviderStatus(
    val id: String, val lastSuccessAt: String? = null, val errorCode: String? = null, val channels: Int = 0,
    val sources: Int = 0, val skippedEntries: Int = 0, val skipReasons: String? = null,
)
@Serializable data class SourceStatus(val id: String, val channelId: String, val providerId: String, val status: String, val manuallyDisabled: Boolean, val lastQualifiedAt: String? = null, val nextCheckAt: String? = null)
@Serializable data class UpdateSource(val manuallyDisabled: Boolean)

package dev.jamesrich.sofaking.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The wire shapes of the Sofa King server's `/api/v1` resources; fields the app does not read are ignored. */
internal val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

@Serializable
internal data class CatalogDto(val revision: String, val channels: List<CatalogChannelDto>)

@Serializable
internal data class CatalogChannelDto(
    val id: String,
    val displayNumber: String,
    val name: String,
    val logo: String? = null,
    val tags: List<String> = emptyList(),
    val playbackEligible: Boolean = false,
)

@Serializable
internal data class GuideQueryDto(val channelIds: List<String>, val from: String, val to: String, val revision: String? = null)

@Serializable
internal data class GuideWindowDto(val revision: String, val channels: List<ChannelGuideDto>)

@Serializable
internal data class ChannelGuideDto(val channelId: String, val programmes: List<ProgrammeDto>)

@Serializable
internal data class ProgrammeDto(
    val channelId: String,
    val startsAt: String,
    val endsAt: String,
    val title: String,
    val description: String? = null,
)

@Serializable
internal data class OpenPlaybackDto(val channelId: String, val intent: String, val idempotencyKey: String)

@Serializable
internal data class UpdatePlaybackDto(val intent: String? = null)

@Serializable
internal data class PlaybackDescriptorDto(val protocol: String, val mimeType: String, val url: String, val epoch: Int)

@Serializable
internal data class PlaybackSessionDto(
    val id: String,
    val channelId: String,
    val intent: String,
    val expiresAt: String,
    val descriptor: PlaybackDescriptorDto,
)

@Serializable
internal data class ApiErrorDto(val code: String, val message: String, val retryable: Boolean = false)

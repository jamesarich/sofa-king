@file:OptIn(ExperimentalSerializationApi::class)

package dev.jamesrich.sofaking.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// A client built against these types talks to servers older and newer than itself: add a field with a default,
// give a field no client reads a default, and never rename or remove one.

@Serializable data class Channel(
    val id: String,
    val displayNumber: String,
    val name: String,
    val logo: String? = null,
    val tags: List<String> = emptyList(),
    val legacyGuideId: String? = null,
    val guideStatus: String = Wire.GuideStatus.AVAILABLE,
    val playbackEligible: Boolean = false,
)

@Serializable data class Catalog(val revision: String = "", val channels: List<Channel>)

/**
 * [episode] is a display label such as `S2 E5`; [rating] a content rating such as `TV-14`; [icon] an image URL
 * the guide source serves. The fields after [description] are null or false when the source has none.
 */
@Serializable data class Programme(
    val id: String = "",
    val channelId: String = "",
    val startsAt: String,
    val endsAt: String,
    val title: String,
    val description: String? = null,
    val subTitle: String? = null,
    val episode: String? = null,
    val isNew: Boolean = false,
    val rating: String? = null,
    val icon: String? = null,
    /** The guide's genres for the program as its source names them, such as `Movies` or `Sports`; older servers send none. */
    val categories: List<String> = emptyList(),
)

@Serializable
data class GuideQuery(val channelIds: List<String>, val from: String, val to: String, val revision: String? = null)

@Serializable data class ChannelGuide(val channelId: String, val status: String = "", val programmes: List<Programme>)

@Serializable data class GuideWindow(
    val revision: String,
    val from: String = "",
    val to: String = "",
    val channels: List<ChannelGuide>,
)

/** [intent] is always written, so a server's default never decides it. */
@Serializable data class OpenPlayback(
    val channelId: String,
    @EncodeDefault val intent: String = Wire.Intent.WATCH,
    val idempotencyKey: String,
)

@Serializable data class UpdatePlayback(val intent: String? = null)

@Serializable data class PlaybackDescriptor(val protocol: String, val mimeType: String, val url: String, val epoch: Int)

/** [providerId] names the source the server picked for the session, for the app's stream card; older servers leave it empty. */
@Serializable data class PlaybackSession(
    val id: String,
    val channelId: String,
    val intent: String,
    val expiresAt: String = "",
    val descriptor: PlaybackDescriptor,
    val providerId: String = "",
)

/**
 * Whether the session's player shows an ad, for a player that cannot read the playlist's [AdRun] marks: the server
 * places the picture from the last segment the player fetched and how far it has loaded ahead. [remainingMs] is how
 * long the break has left, when the playlist says.
 */
@Serializable data class AdBreakState(val inBreak: Boolean = false, val remainingMs: Long? = null)

package dev.jamesrich.sofaking.data

import kotlin.time.Instant

/** Models are immutable values; the UI module's stability configuration lets Compose skip rows and cells whose inputs are equal. */
data class Channel(
    /** The server's channel id: the key for guide data, favorites, caption timing and playback sessions. */
    val guideId: String,
    val number: Double,
    val name: String,
    val logo: String?,
    /** Every category the channel is filed under, never empty; a channel can be both Local and News. */
    val groups: List<String>,
    /** Why a channel without programmes has none; a server that does not say is taken as [GuideStatus.AVAILABLE]. */
    val guideStatus: GuideStatus = GuideStatus.AVAILABLE,
)

/** The server's guide state for one channel. */
enum class GuideStatus {
    /** No guide is mapped to the channel. */
    UNASSIGNED,
    /** A guide is mapped but lists nothing for the channel. */
    EMPTY,
    AVAILABLE,
    /** The mapped guide's data has run out. */
    STALE,
}

data class Programme(
    val channelId: String,
    val start: Instant,
    val stop: Instant,
    val title: String,
    val description: String?,
    /** The episode title. */
    val subTitle: String? = null,
    /** A display label such as `S2 E5`. */
    val episode: String? = null,
    /** A first airing. */
    val isNew: Boolean = false,
    /** A content rating such as `TV-14`. */
    val rating: String? = null,
    /** An image URL on the guide source's host. */
    val icon: String? = null,
)

data class Lineup(
    val channels: List<Channel>,
    /** Programmes per [Channel.guideId], sorted by start. */
    val guide: Map<String, List<Programme>>,
) {
    fun nowAndNext(channel: Channel, at: Instant): Pair<Programme?, Programme?> {
        val programmes = guide[channel.guideId].orEmpty()
        val index = programmes.indexOfFirst { it.start <= at && it.stop > at }
        if (index < 0) return null to programmes.firstOrNull { it.start > at }
        return programmes[index] to programmes.getOrNull(index + 1)
    }
}

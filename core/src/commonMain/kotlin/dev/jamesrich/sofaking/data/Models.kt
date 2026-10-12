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
)

data class Programme(
    val channelId: String,
    val start: Instant,
    val stop: Instant,
    val title: String,
    val description: String?,
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

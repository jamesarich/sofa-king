package dev.jamesrich.sofaking.data

import java.time.Instant

data class Channel(
    /** Matches the XMLTV `channel` id; Dispatcharr uses the channel number. */
    val guideId: String,
    val number: Double,
    val name: String,
    val logo: String?,
    val group: String,
    val streamUrl: String,
)

data class Programme(
    val channelId: String,
    val start: Instant,
    val stop: Instant,
    val title: String,
    val subtitle: String?,
    val description: String?,
)

data class Lineup(
    val channels: List<Channel>,
    /** Programmes per [Channel.guideId], sorted by start. */
    val guide: Map<String, List<Programme>>,
) {
    fun nowAndNext(channel: Channel, at: Instant): Pair<Programme?, Programme?> {
        val programmes = guide[channel.guideId].orEmpty()
        val index = programmes.indexOfFirst { !it.start.isAfter(at) && it.stop.isAfter(at) }
        if (index < 0) return null to programmes.firstOrNull { it.start.isAfter(at) }
        return programmes[index] to programmes.getOrNull(index + 1)
    }
}

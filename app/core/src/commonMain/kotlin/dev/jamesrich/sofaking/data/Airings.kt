package dev.jamesrich.sofaking.data

import kotlin.time.Instant

/** One airing of a programme, on the channel that shows it. */
data class Airing(val channel: Channel, val programme: Programme)

/**
 * The other airings of [programme] in the guide: the same title, ignoring case and spaces at either end, on any
 * channel, from the one on at [now] onwards, soonest first. The guide carries no series id, so a title shared by
 * different shows, such as a newscast, lists them all; each airing names its channel.
 */
fun Lineup.airingsOf(programme: Programme, now: Instant): List<Airing> {
    val title = programme.title.trim().lowercase().takeIf { it.isNotEmpty() } ?: return emptyList()
    return channels.distinctBy { it.guideId }
        .flatMap { channel ->
            guide[channel.guideId].orEmpty()
                .filter { it.stop > now && it.title.trim().lowercase() == title }
                .filterNot { it.channelId == programme.channelId && it.start == programme.start }
                .map { Airing(channel, it) }
        }
        .sortedWith(compareBy({ it.programme.start }, { it.channel.number }))
}

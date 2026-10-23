package dev.jamesrich.sofaking.data

import kotlin.time.Instant

/** One airing of a program, on the channel that shows it. */
data class Airing(val channel: Channel, val program: Program)

/**
 * The other airings of [program] in the guide: the same title, ignoring case and spaces at either end, on any
 * channel, from the one on at [now] onwards, soonest first. The guide carries no series id, so a title shared by
 * different shows, such as a newscast, lists them all; each airing names its channel.
 */
fun Lineup.airingsOf(program: Program, now: Instant): List<Airing> {
    val title = program.title.trim().lowercase().takeIf { it.isNotEmpty() } ?: return emptyList()
    return channels.distinctBy { it.guideId }
        .flatMap { channel ->
            guide[channel.guideId].orEmpty()
                .filter { it.stop > now && it.title.trim().lowercase() == title }
                .filterNot { it.channelId == program.channelId && it.start == program.start }
                .map { Airing(channel, it) }
        }
        .sortedWith(compareBy({ it.program.start }, { it.channel.number }))
}

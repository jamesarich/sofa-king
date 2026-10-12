package dev.jamesrich.sofaking.data

import java.time.Instant
import java.time.format.DateTimeParseException

/** The server's catalog and guide shapes as the app's [Channel] and [Programme] models. */
internal object SofaKingMapper {
    /** Channels the server can play, with a numeric display number, sorted by it; every tag is a group, [DEFAULT_GROUP] when there is none. */
    fun channels(catalog: CatalogDto): List<Channel> = catalog.channels.mapNotNull { dto ->
        if (!dto.playbackEligible) return@mapNotNull null
        val number = dto.displayNumber.trim().toDoubleOrNull() ?: return@mapNotNull null
        val groups = dto.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(DEFAULT_GROUP) }
        Channel(
            guideId = dto.id,
            number = number,
            name = dto.name,
            logo = dto.logo?.takeIf { it.isNotEmpty() },
            groups = groups,
        )
    }.sortedBy { it.number }

    /** Programmes per channel id, sorted by start; ones with unreadable times or no duration are dropped, as are channels left with none. */
    fun programmes(window: GuideWindowDto): Map<String, List<Programme>> = buildMap {
        for (channel in window.channels) {
            val programmes = channel.programmes.mapNotNull { toProgramme(channel.channelId, it) }.sortedBy { it.start }
            if (programmes.isNotEmpty()) put(channel.channelId, programmes)
        }
    }

    private fun toProgramme(channelId: String, dto: ProgrammeDto): Programme? {
        val start = parseInstant(dto.startsAt) ?: return null
        val stop = parseInstant(dto.endsAt) ?: return null
        if (!stop.isAfter(start)) return null
        return Programme(
            channelId = channelId,
            start = start,
            stop = stop,
            title = dto.title,
            subtitle = null,
            description = dto.description?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseInstant(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    const val DEFAULT_GROUP = "Other"
}

package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlin.time.Instant

/** The server's catalog and guide shapes as the app's [Channel] and [Program] models. */
internal object SofaKingMapper {
    /**
     * Channels the server can play, with a numeric display number, sorted by it; every tag is a group, [DEFAULT_GROUP]
     * when there is none. [resolveLogo] turns the server's logo URL into one the app can load.
     */
    fun channels(catalog: CatalogDto, resolveLogo: (String) -> String? = { it }): List<Channel> = catalog.channels.mapNotNull { dto ->
        if (!dto.playbackEligible) return@mapNotNull null
        val number = dto.displayNumber.trim().toDoubleOrNull() ?: return@mapNotNull null
        val groups = dto.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(DEFAULT_GROUP) }
        Channel(
            guideId = dto.id,
            number = number,
            name = dto.name,
            logo = dto.logo?.takeIf { it.isNotEmpty() }?.let(resolveLogo),
            groups = groups,
            guideStatus = guideStatus(dto.guideStatus),
        )
    }.sortedBy { it.number }

    /** Programs per channel id, sorted by start; ones with unreadable times or no duration are dropped, as are channels left with none. */
    fun programs(window: GuideWindowDto): Map<String, List<Program>> = buildMap {
        for (channel in window.channels) {
            val programs = channel.programs.mapNotNull { toProgram(channel.channelId, it) }.sortedBy { it.start }
            if (programs.isNotEmpty()) put(channel.channelId, programs)
        }
    }

    /** Channels the guide answered as gone from the lineup since the catalog was read. */
    fun removed(window: GuideWindowDto): Set<String> =
        window.channels.filter { it.status == Wire.GuideStatus.REMOVED }.mapTo(HashSet()) { it.channelId }

    private fun toProgram(channelId: String, dto: ProgramDto): Program? {
        val start = Instant.parseOrNull(dto.startsAt) ?: return null
        val stop = Instant.parseOrNull(dto.endsAt) ?: return null
        if (stop <= start) return null
        return Program(
            channelId = channelId,
            start = start,
            stop = stop,
            title = dto.title,
            description = dto.description.nonBlank(),
            subTitle = dto.subTitle.nonBlank(),
            episode = dto.episode.nonBlank(),
            isNew = dto.isNew,
            rating = dto.rating.nonBlank(),
            icon = dto.icon.nonBlank(),
            kinds = programKinds(dto.categories),
        )
    }

    private fun guideStatus(wire: String): GuideStatus = when (wire) {
        Wire.GuideStatus.UNASSIGNED -> GuideStatus.UNASSIGNED
        Wire.GuideStatus.EMPTY -> GuideStatus.EMPTY
        Wire.GuideStatus.STALE -> GuideStatus.STALE
        else -> GuideStatus.AVAILABLE
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    const val DEFAULT_GROUP = "Other"
}

package dev.jamesrich.sofaking.backend.virtual

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.GuideService
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.playback.ChannelTap
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import io.ktor.http.HttpStatusCode
import java.time.Instant

/** The lineup's live channels as a virtual channel's slots play them. */
interface LiveLineup {
    /** The enabled lineup entries a slot may play, by ID, with their labels; none plays a virtual channel. */
    fun choices(): Map<String, String>

    /** What the guide lists on entry [id] from [from] until [to]. */
    suspend fun programs(id: String, from: Instant, to: Instant): List<GuideProgram>

    /**
     * Runs [block] on entry [id]'s stream, read through the relay in a playback session of its own, which picks the
     * entry's healthiest source and holds a session slot while it runs.
     */
    suspend fun <T> reading(id: String, block: suspend (FfmpegSource) -> T): T
}

/** [LiveLineup] over the server's lineup and guides, read through [tap]. */
internal class LineupSlots(
    private val lineup: LineupService,
    private val guides: GuideService,
    private val feeds: FeedService,
    private val tap: ChannelTap,
) : LiveLineup {
    private val virtualProviders = feeds.configs.filter { it.service == VirtualClient.SERVICE }.map { it.id }.toSet()

    override fun choices(): Map<String, String> = lineup.entries()
        .filter { it.enabled && !playsVirtual(it) }
        .associate { it.id to "${it.number} ${it.name}" }

    override suspend fun programs(id: String, from: Instant, to: Instant): List<GuideProgram> {
        val mapping = lineup.entry(id)?.guide ?: return emptyList()
        return guides.programs(mapping.guideId, listOf(mapping.channelId), from, to)[mapping.channelId].orEmpty()
    }

    override suspend fun <T> reading(id: String, block: suspend (FfmpegSource) -> T): T {
        // A virtual channel playing a virtual channel could end up reading itself.
        if (id !in choices()) throw ApiFailure(HttpStatusCode.NotFound, "CHANNEL_NOT_FOUND", "No such live channel")
        return tap.reading(id, CLIENT, block)
    }

    private fun playsVirtual(entry: LineupEntryRecord): Boolean =
        entry.offeringIds.any { feeds.published.offering(it)?.providerId in virtualProviders }

    private companion object {
        const val CLIENT = "Virtual channel"
    }
}

/** The airing ID of live channel [entryId], as a schedule stores it in place of an item. */
internal fun liveAiringId(entryId: String): String = LIVE_PREFIX + entryId

/** The live channel an airing plays, or null when it plays an item or nothing. */
internal fun liveEntryOf(itemId: String?): String? =
    itemId?.takeIf { it.startsWith(LIVE_PREFIX) }?.removePrefix(LIVE_PREFIX)

private const val LIVE_PREFIX = "live:"

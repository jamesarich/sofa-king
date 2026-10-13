package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.store.BackendStore
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The app-facing view: lineup entries resolved against feed sources, guide programmes and source health. */
class CatalogService(
    val feeds: FeedService,
    val guides: GuideService,
    val lineup: LineupService,
    private val store: BackendStore,
    private val clock: Clock,
) {
    /** Lineup, feeds, guide coverage, health version and which guides are still current: everything the body depends on. */
    suspend fun catalogRevision(): String {
        val now = clock.instant()
        val coverage = guides.coverage()
        val current = buildString {
            for (entry in lineup.entries()) {
                val end = entry.guide?.let { coverage[it.guideId]?.get(it.channelId) }
                append(entry.id).append(':').append(end?.let { if (it > now) "available" else "stale" } ?: "none").append(',')
            }
        }
        return hash("${lineup.revision()}:${feeds.revision()}:${guides.revision()}:${store.healthVersion()}:${hash(current)}")
    }

    suspend fun catalog(revision: String? = null): Catalog {
        val stamped = revision ?: catalogRevision()
        val states = store.allHealth()
        val coverage = guides.coverage()
        val now = clock.instant()
        val channels = lineup.entries().filter { it.enabled }.map { entry -> channel(entry, states, coverage, now) }
        return Catalog(stamped, channels)
    }

    private fun channel(entry: LineupEntryRecord, states: Map<String, HealthState>, coverage: Map<String, Map<String, Instant>>, now: Instant): Channel {
        val offerings = entry.offeringIds.mapNotNull(feeds::offering)
        val end = entry.guide?.let { coverage[it.guideId]?.get(it.channelId) }
        return Channel(
            id = entry.id,
            displayNumber = entry.number,
            name = entry.name,
            logo = logoSource(entry),
            tags = listOfNotNull(entry.group),
            legacyGuideId = entry.guide?.channelId,
            guideStatus = when {
                entry.guide == null -> "unassigned"
                end == null -> "empty"
                end > now -> "available"
                else -> "stale"
            },
            playbackEligible = offerings.any { states[it.id]?.let(::eligible) == true },
        )
    }

    /**
     * The logo URL a channel shows: the entry's own, else the first of its sources' feed logos, else the mapped guide
     * channel's icon.
     */
    fun logoSource(entry: LineupEntryRecord): String? =
        entry.logo
            ?: entry.offeringIds.asSequence().mapNotNull(feeds::offering).mapNotNull { feeds.channel(it.channelId)?.logo }.firstOrNull()
            ?: entry.guide?.let { guides.channel(it.guideId, it.channelId)?.icon }

    /** The entry's sources in failover order; the session manager ranks them by health. */
    fun offeringsFor(channelId: String): List<Offering>? =
        lineup.entry(channelId)?.takeIf { it.enabled }?.offeringIds?.mapNotNull(feeds::offering)

    fun offering(id: String): Offering? = feeds.offering(id)

    suspend fun guide(query: GuideQuery): GuideWindow {
        if (query.channelIds.size !in 1..200 || query.channelIds.distinct().size != query.channelIds.size) {
            throw ApiFailure(400, "INVALID_QUERY", "Request 1 to 200 distinct channel IDs")
        }
        val from = try { Instant.parse(query.from) } catch (_: Exception) { throw ApiFailure(400, "INVALID_QUERY", "Invalid from timestamp") }
        val to = try { Instant.parse(query.to) } catch (_: Exception) { throw ApiFailure(400, "INVALID_QUERY", "Invalid to timestamp") }
        if (to <= from || Duration.between(from, to) > Duration.ofHours(24)) {
            throw ApiFailure(400, "INVALID_QUERY", "Guide window must be positive and at most 24 hours")
        }
        val entries = query.channelIds.map { id -> lineup.entry(id)?.takeIf { it.enabled } ?: throw ApiFailure(404, "CHANNEL_NOT_FOUND", "Unknown channel ID") }
        val revision = hash("${guides.revision()}:${lineup.revision()}")
        if (query.revision != null && query.revision != revision) throw ApiFailure(409, "RESYNC_REQUIRED", "Guide revision changed", true)
        val mapped = entries.mapNotNull { entry -> entry.guide?.let { entry to it } }
        val programmes = HashMap<String, List<GuideProgramme>>()
        for ((guideId, group) in mapped.groupBy { it.second.guideId }) {
            val rows = guides.programmes(guideId, group.map { it.second.channelId }, from, to)
            for ((entry, mapping) in group) programmes[entry.id] = rows[mapping.channelId].orEmpty()
        }
        return GuideWindow(
            revision,
            from.toString(),
            to.toString(),
            entries.map { entry ->
                val rows = programmes[entry.id].orEmpty().map { programme ->
                    val start = programme.startsAt.toString()
                    val end = programme.endsAt.toString()
                    Programme(
                        stableId("programme:${entry.id}:$start:$end:${programme.title}"), entry.id, start, end, programme.title, programme.description,
                        programme.subTitle, episodeLabel(programme.episodeNumbers), programme.isNew, programme.rating?.value, programme.icon,
                    )
                }
                ChannelGuide(entry.id, if (entry.guide == null) "unassigned" else if (rows.isEmpty()) "empty" else "available", rows)
            },
        )
    }
}

private val onscreenSeasonEpisode = Regex("[Ss](\\d{1,4})\\s*[Ee](\\d{1,5})")

/**
 * A display label for a programme's numbering: `S2 E5` from a zero-based `xmltv_ns` value or an `S02E05`-style
 * `onscreen` one, `E5` or `S2` when the source numbers only one, else the `onscreen` text itself.
 */
fun episodeLabel(numbers: List<EpisodeNumber>): String? {
    numbers.firstOrNull { it.system == "xmltv_ns" }?.value?.split('.')?.let { parts ->
        fun part(index: Int) = parts.getOrNull(index)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it in 0..99_999 }?.plus(1)
        val season = part(0)
        val episode = part(1)
        val label = listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString(" ")
        if (label.isNotEmpty()) return label
    }
    val onscreen = numbers.firstOrNull { it.system == "onscreen" }?.value ?: return null
    onscreenSeasonEpisode.matchEntire(onscreen.trim())?.let { match ->
        return "S${match.groupValues[1].toInt()} E${match.groupValues[2].toInt()}"
    }
    return onscreen.take(32)
}

fun eligible(state: HealthState): Boolean = !state.manuallyDisabled && state.status in setOf(HealthStatus.UNVERIFIED, HealthStatus.HEALTHY, HealthStatus.SUSPECT)

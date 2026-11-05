package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.catalog.ProgramRating
import dev.jamesrich.sofaking.backend.providers.ProviderChannel
import dev.jamesrich.sofaking.backend.providers.array
import dev.jamesrich.sofaking.backend.providers.seasonEpisode
import dev.jamesrich.sofaking.backend.providers.toGuideChannel
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant

/** A channel as listed: what the lineup shows, the variables its manifest keeps for it, and its item as read. */
internal class Listed(val channel: ProviderChannel, val vars: Map<String, String>, val raw: JsonElement) {
    val scope get() = ChannelScope(channel.id, vars)
}

/** A program item as a program on [channelId]; null when the title or times are missing or the end is not later. */
internal fun program(channelId: String, item: JsonElement, map: ManifestProgramMap): GuideProgram? {
    val title = map.title.text(item) ?: return null
    val start = map.start.instant(item) ?: return null
    val end = map.end.instant(item)?.takeIf { it > start } ?: return null
    return GuideProgram(
        channelId,
        start,
        end,
        title,
        map.description.text(item),
        subTitle = map.subTitle.text(item),
        icon = map.icon.text(item),
        categories = listOfNotNull(map.category.text(item)),
        rating = map.rating?.spec?.read(item) as? ProgramRating,
        episodeNumbers = seasonEpisode(map.season.text(item)?.toLongOrNull(), map.episode.text(item)?.toLongOrNull()),
    )
}

/**
 * The guide of a service whose one request lists the programs of many channels, each item naming its channel by
 * `guide.key`, matched to the channel's `keyVar` variable (its id when there is none); an item whose key no channel
 * has falls back to `fallbackKey`, read as a name with case and punctuation ignored. With `pastHours` a program that
 * ended longer ago is dropped. A request paged by `cursorPath` runs again from the first wanted channel no earlier
 * page covered, because the service orders its answer by station, not by the channel list; a station keeps the
 * programs of the first page that carried it.
 */
internal class KeyedGuide(private val fetcher: ManifestFetcher, private val loaded: Loaded) {
    suspend fun programs(guide: ManifestGuide, channels: List<Listed>, wanted: Set<String>): List<GuideProgram> {
        if (channels.none { it.channel.id in wanted }) return emptyList()
        val name = requireNotNull(guide.request)
        val match = KeyedMatch(guide, channels, wanted, fetcher.now())
        val paging = loaded.manifest.requests.getValue(name).paging
        val covered = paging?.cursorPath ?: return match.read(fetcher.items(loaded, name, null).orEmpty())
        val programs = ArrayList<GuideProgram>()
        val done = HashSet<String>()
        var from: Listed? = channels.firstOrNull()
        repeat(paging.maxPages) {
            val at = from ?: return@repeat
            val items = fetcher.items(loaded, name, null, PageCursor(at.channel.id, channels.size)).orEmpty()
            val fresh = items.filter { it.textAt(match.key) !in done }
            programs += match.read(fresh)
            fresh.mapNotNullTo(done) { it.textAt(covered) }
            match.keyOf(at)?.let(done::add)
            from = channels.firstOrNull { entry ->
                match.keyOf(entry).let { it != null && it in match.wanted && it !in done }
            }
        }
        return programs
    }
}

/** Which wanted channel each item of a keyed guide belongs to. */
private class KeyedMatch(private val guide: ManifestGuide, channels: List<Listed>, wanted: Set<String>, now: Instant) {
    val key = requireNotNull(guide.key)
    private val byKey = channels.filter { it.channel.id in wanted }.mapNotNull { e ->
        keyOf(e)?.let { it to e.channel.id }
    }.toMap()
    private val listed = channels.mapNotNull(::keyOf).toSet()
    private val byName = if (guide.fallbackKey == null) {
        emptyMap()
    } else {
        channels.filter { it.channel.id in wanted }.associate { normalized(it.channel.name) to it.channel.id }
    }
    private val earliest = guide.pastHours?.let { now.minus(Duration.ofHours(it)) }
    val wanted: Set<String> get() = byKey.keys

    fun keyOf(entry: Listed): String? = if (guide.keyVar == null) entry.channel.id else entry.vars[guide.keyVar]

    fun read(items: List<JsonElement>): List<GuideProgram> = items.mapNotNull { item ->
        val itemKey = item.textAt(key)
        val channelId = byKey[itemKey] ?: guide.fallbackKey?.takeIf { itemKey !in listed }
            ?.let { item.textAt(it) }?.let { byName[normalized(it)] }
        channelId?.let { program(it, item, guide.map) }?.takeIf { earliest == null || it.endsAt >= earliest }
    }

    private fun normalized(name: String) = name.lowercase().filter(Char::isLetterOrDigit)
}

/**
 * The guide of a service whose one request answers with a row per channel, each row naming its channel by
 * `guide.key` (matched as in a keyed guide) and carrying its programs at `guide.programs`. The guide offers only the
 * channels whose row has programs; `pastHours` drops programs that ended longer ago.
 */
internal class GroupedGuide(private val fetcher: ManifestFetcher, private val loaded: Loaded) {
    suspend fun parse(guide: ManifestGuide, channels: List<Listed>, wanted: Set<String>): ParsedGuide {
        val byKey = channels.associateBy { if (guide.keyVar == null) it.channel.id else it.vars[guide.keyVar] }
        val earliest = guide.pastHours?.let { fetcher.now().minus(Duration.ofHours(it)) }
        val rows = fetcher.items(loaded, requireNotNull(guide.request), null).orEmpty().mapNotNull { row ->
            val items = row.at(requireNotNull(guide.programs)).array
            byKey[row.textAt(requireNotNull(guide.key))]?.takeIf { items.isNotEmpty() }?.let { it to items }
        }
        val programs = rows.filter { (entry, _) -> entry.channel.id in wanted }.flatMap { (entry, items) ->
            items.mapNotNull { item ->
                program(entry.channel.id, item, guide.map)?.takeIf { earliest == null || it.endsAt >= earliest }
            }
        }
        val index = rows.map { (entry, _) -> entry.channel.toGuideChannel() }
        return ParsedGuide(index, programs)
    }
}

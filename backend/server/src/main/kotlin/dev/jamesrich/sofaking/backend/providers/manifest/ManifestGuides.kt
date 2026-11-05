package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ProgramRating
import dev.jamesrich.sofaking.backend.providers.ProviderChannel
import dev.jamesrich.sofaking.backend.providers.seasonEpisode
import kotlinx.serialization.json.JsonElement

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
 * `guide.key`, matched to the channel's `keyVar` variable (its id when there is none). A request paged by
 * `cursorPath` runs again from the first wanted channel no earlier page covered, because the service orders its
 * answer by station, not by the channel list; a station keeps the programs of the first page that carried it.
 */
internal class KeyedGuide(private val fetcher: ManifestFetcher, private val loaded: Loaded) {
    suspend fun programs(guide: ManifestGuide, channels: List<Listed>, wanted: Set<String>): List<GuideProgram> {
        if (channels.none { it.channel.id in wanted }) return emptyList()
        val name = requireNotNull(guide.request)
        val keyOf = { entry: Listed -> if (guide.keyVar == null) entry.channel.id else entry.vars[guide.keyVar] }
        val byKey = channels.filter { it.channel.id in wanted }.mapNotNull { entry ->
            keyOf(entry)?.let { it to entry.channel.id }
        }.toMap()
        val paging = loaded.manifest.requests.getValue(name).paging
        val covered = paging?.cursorPath
        val read = { items: List<JsonElement> ->
            items.mapNotNull { item ->
                byKey[item.textAt(requireNotNull(guide.key))]?.let { program(it, item, guide.map) }
            }
        }
        if (covered == null) return read(fetcher.items(loaded, name, null).orEmpty())
        val programs = ArrayList<GuideProgram>()
        val done = HashSet<String>()
        var from: Listed? = channels.firstOrNull()
        repeat(paging.maxPages) {
            val at = from ?: return@repeat
            val items = fetcher.items(loaded, name, null, PageCursor(at.channel.id, channels.size)).orEmpty()
            val fresh = items.filter { it.textAt(requireNotNull(guide.key)) !in done }
            programs += read(fresh)
            fresh.mapNotNullTo(done) { it.textAt(covered) }
            keyOf(at)?.let(done::add)
            from = channels.firstOrNull { entry -> keyOf(entry).let { it != null && it in byKey && it !in done } }
        }
        return programs
    }
}

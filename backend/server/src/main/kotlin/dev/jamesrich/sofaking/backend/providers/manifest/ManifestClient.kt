package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.catalog.ProgramRating
import dev.jamesrich.sofaking.backend.providers.Macros
import dev.jamesrich.sofaking.backend.providers.ProviderChannel
import dev.jamesrich.sofaking.backend.providers.ProviderClient
import dev.jamesrich.sofaking.backend.providers.ProviderContext
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.providers.array
import dev.jamesrich.sofaking.backend.providers.findListed
import dev.jamesrich.sofaking.backend.providers.long
import dev.jamesrich.sofaking.backend.providers.requireChannels
import dev.jamesrich.sofaking.backend.providers.toGuideChannel
import kotlinx.serialization.json.JsonElement
import java.time.Duration

/**
 * A provider run from its manifest. The manifest is read on every call, so a reload takes effect without rebuilding
 * the registry; the cache keys carry its content hash, so the shared fetches of the old one are not reused.
 */
class ManifestClient(
    private val context: ProviderContext,
    private val manifests: ProviderManifests,
    override val service: String,
) : ProviderClient {
    private val fetcher = ManifestFetcher(context, service)

    @Volatile private var listKey: String? = null

    override val protocol: String get() = manifests.current(service)?.manifest?.protocol ?: "hls"

    private fun loaded(): Loaded =
        manifests.current(service) ?: throw ProviderFailure("No manifest for $service", transient = false)

    override suspend fun channels(): List<ProviderChannel> = listed(loaded()).map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide? {
        val loaded = loaded()
        val guide = loaded.manifest.guide ?: return null
        val channels = listed(loaded)
        if (guide.mode == "grouped") return GroupedGuide(fetcher, loaded).parse(guide, channels, wanted)
        val programs = if (guide.mode == "keyed") {
            KeyedGuide(fetcher, loaded).programs(guide, channels, wanted)
        } else {
            channels.filter { it.channel.id in wanted }.flatMap { programs(loaded, guide, it) }
        }
        return ParsedGuide(channels.map { it.channel.toGuideChannel() }, programs)
    }

    private suspend fun programs(loaded: Loaded, guide: ManifestGuide, entry: Listed): List<GuideProgram> {
        val items = if (guide.mode == "inline") {
            entry.raw.at(guide.programs.orEmpty()).array
        } else {
            guide.requireVar?.let { name ->
                if (entry.vars[name].isNullOrEmpty()) {
                    throw ProviderFailure("$service lists no $name for ${entry.channel.id}")
                }
            }
            fetcher.items(loaded, requireNotNull(guide.request), entry.scope).orEmpty()
        }
        return items.mapNotNull { program(entry.channel.id, it, guide.map) }
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val loaded = loaded()
        val r = loaded.manifest.resolve
        val entry = context.findListed(key(loaded), r.missing) {
            listed(loaded).firstOrNull { it.channel.id == channelId }
        }
        val scope = fetcher.scope(loaded, entry.scope)
        var url = ManifestTemplate.render(r.url, scope)
        r.post?.let { url = fetcher.postedUrl(loaded, url, it, scope) }
        if (r.format.isNotEmpty()) {
            val args = r.format.map { ManifestTemplate.render(it, scope) }
            var next = 0
            url = Regex("%s").replace(url) { args.getOrElse(next++) { "" } }
        }
        if (r.macros.isNotEmpty() || r.post != null) {
            url = Macros.fill(url, r.macros.mapValues { ManifestTemplate.render(it.value, scope) })
        }
        val headers = r.headers.mapValues { ManifestTemplate.render(it.value, scope) }
        return ResolvedStream(url, headers, r.expiresSeconds?.let { context.clock.instant().plusSeconds(it) })
    }

    /** The cache key of the channel list under this manifest; the list of the one before it is dropped. */
    private fun key(loaded: Loaded): String = "channels:${loaded.hash}".also { key ->
        val old = listKey
        if (old != key) {
            listKey = key
            old?.let(context::invalidate)
        }
    }

    private suspend fun listed(loaded: Loaded): List<Listed> {
        val ttl = Duration.ofSeconds(loaded.manifest.cacheSeconds)
        return if (ttl.isZero) fetchListed(loaded) else context.cached(key(loaded), ttl) { fetchListed(loaded) }
    }

    private suspend fun fetchListed(loaded: Loaded): List<Listed> {
        val c = loaded.manifest.channels
        val items = c.static ?: requireNotNull(fetcher.items(loaded, requireNotNull(c.from), null))
        val raw = c.sortBy?.let { path -> items.sortedBy { it.at(path).long ?: Long.MAX_VALUE } } ?: items
        val joined = c.join?.let { join ->
            fetcher.items(loaded, join.request, null)?.mapNotNull { item -> item.textAt(join.key)?.let { it to item } }
                ?.toMap()
        }
        val listed = raw.mapNotNull { listing(loaded, c, it, joined) }
            .let { all -> if (c.distinctBy == "id") all.distinctBy { it.channel.id } else all }
        if (c.requireNonEmpty) requireChannels(listed)
        return listed
    }

    private suspend fun listing(
        loaded: Loaded,
        c: ManifestChannels,
        item: JsonElement,
        joined: Map<String, JsonElement>?,
    ): Listed? {
        if (!c.filter.all { it.passes(item) }) return null
        val id = c.map.id.text(item) ?: return null
        val name = c.map.name.text(item) ?: return null
        val vars = HashMap<String, String>()
        c.vars.forEach { (key, field) -> field.text(item)?.let { vars[key] = it } }
        var channel = ProviderChannel(
            id,
            name,
            c.map.number.text(item),
            c.map.logo.text(item),
            c.map.group.text(item),
            c.map.tags + c.map.tagsWhen.filter { it.condition.passes(item) }.map { it.tag },
        )
        val join = c.join
        if (join != null) {
            val on = ManifestTemplate.render(join.on, fetcher.scope(loaded, ChannelScope(id, vars)))
            joined?.get(on)?.let { match ->
                join.vars.forEach { (key, field) -> field.text(match)?.let { vars[key] = it } }
                channel = channel.joined(join.set, match)
            }
        }
        return Listed(channel, vars, item)
    }

    private fun ProviderChannel.joined(set: Map<String, Field>, match: JsonElement) = copy(
        name = set["name"].text(match) ?: name,
        number = set["number"].text(match) ?: number,
        logo = set["logo"].text(match) ?: logo,
        group = set["group"].text(match) ?: group,
    )
}

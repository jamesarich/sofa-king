package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import kotlinx.serialization.json.JsonElement
import java.time.Duration

/**
 * Tubi's live channels as tubitv.com/live serves them to a signed-out visitor: the page's `window.__data` state
 * names the US linear containers and their channel ids, and the public `oz/epg/programming` endpoint answers, per
 * batch of ids, each channel's title, images, HLS manifest (with a token valid a few days) and about two days of
 * programs. Channels that need a login or carry a license server are left out.
 */
class TubiClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "tubi"

    private val pageUrl = context.options["pageUrl"] ?: PAGE_URL
    private val epgUrl = context.options["epgUrl"] ?: EPG_URL

    private class Snapshot(val channels: List<ProviderChannel>, val programs: List<GuideProgram>)

    override suspend fun channels(): List<ProviderChannel> = snapshot().channels

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val snapshot = snapshot()
        return ParsedGuide(
            snapshot.channels.map { it.toGuideChannel() },
            snapshot.programs.filter {
                it.channelId in
                    wanted
            },
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        context.findListed(SNAPSHOT) { snapshot().channels.firstOrNull { it.id == channelId } }
        val row = rows(listOf(channelId)).firstOrNull { it.str("content_id") == channelId }
            ?: throw ProviderFailure("Tubi no longer answers for the channel", transient = false)
        val manifest = playable(row) ?: throw ProviderFailure("Tubi channel needs a login or DRM", transient = false)
        return ResolvedStream(manifest, STREAM_HEADERS, context.clock.instant().plus(URL_LIFETIME))
    }

    private suspend fun snapshot(): Snapshot = context.cached(SNAPSHOT, CACHED_FOR) { fetch() }

    private suspend fun fetch(): Snapshot {
        val html = context.http.get(pageUrl, PAGE_HEADERS).require("Tubi live page").text()
        val containers =
            jsonArrayAfter(html, LINEAR_KEY) ?: throw ProviderFailure("Tubi live page has no linear containers")
        val groups = HashMap<String, String>()
        val order = LinkedHashSet<String>()
        for (container in containers.filterNot { it.str("container_slug") in CURATED } +
            containers.filter { it.str("container_slug") in CURATED }) {
            val name = container.str("name")?.takeUnless { container.str("container_slug") in CURATED }
            for (id in container["contents"].array.mapNotNull { it.string }) {
                order += id
                if (name != null) groups.putIfAbsent(id, name)
            }
        }
        if (order.isEmpty()) throw ProviderFailure("Tubi live page lists no channels")
        val channels = ArrayList<ProviderChannel>()
        val programs = ArrayList<GuideProgram>()
        for (row in rows(order.toList())) {
            val channel = channel(row, groups)
            if (channel == null || channels.any { it.id == channel.id }) continue
            channels += channel
            row["programs"].array.mapNotNullTo(programs) {
                guideProgram(
                    channel.id,
                    it.str("start_time")?.let(::instantOrNull),
                    it.str("end_time")?.let(::instantOrNull),
                    it.str("title"),
                    it.str("description"),
                )
            }
        }
        requireChannels(channels)
        return Snapshot(channels, programs)
    }

    private suspend fun rows(ids: List<String>): List<JsonElement> = ids.chunked(BATCH).flatMap { batch ->
        context.http.get(
            "$epgUrl?content_id=${batch.joinToString(",")}",
            HEADERS,
        ).require("Tubi programming").json()["rows"].array
    }

    /** A playable channel in its group from [groups]; null when it is not playable or a field is missing. */
    private fun channel(row: JsonElement, groups: Map<String, String>): ProviderChannel? {
        val id = row.str("content_id") ?: return null
        val name = row.str("title") ?: return null
        if (playable(row) == null) return null
        return ProviderChannel(
            id,
            name,
            null,
            httpOrNull(row["images"]["thumbnail"][0].string),
            groups[id],
            listOf("us"),
        )
    }

    /** The clear HLS manifest, or null when the channel needs a login or a license server. */
    private fun playable(row: JsonElement): String? {
        if (row["needs_login"].truthy) return null
        val resource =
            row["video_resources"].array.firstOrNull {
                it.str("type")?.startsWith("hls") == true && it["license_server"] == null
            }
                ?: return null
        return httpOrNull(resource["manifest"].str("url"))
    }

    companion object {
        private const val PAGE_URL = "https://tubitv.com/live"
        private const val EPG_URL = "https://tubitv.com/oz/epg/programming"
        private const val LINEAR_KEY = "tubitv_us_linear"
        private const val SNAPSHOT = "snapshot"

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(30)
        private const val BATCH = 50
        private val URL_LIFETIME = Duration.ofHours(1)
        private val CURATED =
            setOf(
                "favorite_linear_channels",
                "recommended_linear_channels",
                "featured_channels",
                "recently_added_channels",
            )
        private val STREAM_HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Origin" to "https://tubitv.com",
            "Referer" to "https://tubitv.com/",
        )
        private val HEADERS = STREAM_HEADERS + ("Accept" to "application/json")
        private val PAGE_HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        )
    }
}

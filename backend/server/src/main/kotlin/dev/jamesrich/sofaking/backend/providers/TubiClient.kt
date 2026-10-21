package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonElement

/**
 * Tubi's live channels as tubitv.com/live serves them to a signed-out visitor: the page's `window.__data` state
 * names the US linear containers and their channel ids, and the public `oz/epg/programming` endpoint answers, per
 * batch of ids, each channel's title, images, HLS manifest (with a token valid a few days) and about two days of
 * programmes. Channels that need a login or carry a license server are left out.
 */
class TubiClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "tubi"

    private val pageUrl = context.options["pageUrl"] ?: PAGE_URL
    private val epgUrl = context.options["epgUrl"] ?: EPG_URL

    private class Snapshot(val channels: List<ProviderChannel>, val programmes: List<GuideProgramme>)

    override suspend fun channels(): List<ProviderChannel> = snapshot().channels

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val snapshot = snapshot()
        return ParsedGuide(snapshot.channels.map { GuideChannel(it.id, it.name, it.logo) }, snapshot.programmes.filter { it.channelId in wanted })
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        if (snapshot().channels.none { it.id == channelId }) {
            context.invalidate(SNAPSHOT)
            if (snapshot().channels.none { it.id == channelId }) throw ProviderFailure("Channel is no longer listed", transient = false)
        }
        val row = rows(listOf(channelId)).firstOrNull { it.str("content_id") == channelId }
            ?: throw ProviderFailure("Tubi no longer answers for the channel", transient = false)
        val manifest = playable(row) ?: throw ProviderFailure("Tubi channel needs a login or DRM", transient = false)
        return ResolvedStream(manifest, STREAM_HEADERS, context.clock.instant().plus(URL_LIFETIME))
    }

    private suspend fun snapshot(): Snapshot = context.cached(SNAPSHOT, Duration.ofMinutes(30)) { fetch() }

    private suspend fun fetch(): Snapshot {
        val html = context.http.get(pageUrl, PAGE_HEADERS).require("Tubi live page").text()
        val containers = jsonArrayAfter(html, LINEAR_KEY) ?: throw ProviderFailure("Tubi live page has no linear containers")
        val groups = HashMap<String, String>()
        val order = LinkedHashSet<String>()
        for (container in containers.filterNot { it.str("container_slug") in CURATED } + containers.filter { it.str("container_slug") in CURATED }) {
            val name = container.str("name")?.takeUnless { container.str("container_slug") in CURATED }
            for (id in container["contents"].array.mapNotNull { it.string }) {
                order += id
                if (name != null) groups.putIfAbsent(id, name)
            }
        }
        if (order.isEmpty()) throw ProviderFailure("Tubi live page lists no channels")
        val channels = ArrayList<ProviderChannel>()
        val programmes = ArrayList<GuideProgramme>()
        for (row in rows(order.toList())) {
            val id = row.str("content_id") ?: continue
            val name = row.str("title") ?: continue
            if (playable(row) == null || channels.any { it.id == id }) continue
            channels += ProviderChannel(id, name, null, httpOrNull(row["images"]["thumbnail"][0].string), groups[id], listOf("us"))
            for (programme in row["programs"].array) {
                val start = programme.str("start_time")?.let(::instantOrNull) ?: continue
                val end = programme.str("end_time")?.let(::instantOrNull) ?: continue
                val title = programme.str("title") ?: continue
                if (end > start) programmes += GuideProgramme(id, start, end, title, programme.str("description"))
            }
        }
        if (channels.isEmpty()) throw ProviderFailure("Channel list is empty")
        return Snapshot(channels, programmes)
    }

    private suspend fun rows(ids: List<String>): List<JsonElement> = ids.chunked(BATCH).flatMap { batch ->
        context.http.get("$epgUrl?content_id=${batch.joinToString(",")}", HEADERS).require("Tubi programming").json()["rows"].array
    }

    /** The clear HLS manifest, or null when the channel needs a login or a license server. */
    private fun playable(row: JsonElement): String? {
        if (row["needs_login"].truthy) return null
        val resource = row["video_resources"].array.firstOrNull { it.str("type")?.startsWith("hls") == true && it["license_server"] == null } ?: return null
        return httpOrNull(resource["manifest"].str("url"))
    }

    companion object {
        private const val PAGE_URL = "https://tubitv.com/live"
        private const val EPG_URL = "https://tubitv.com/oz/epg/programming"
        private const val LINEAR_KEY = "tubitv_us_linear"
        private const val SNAPSHOT = "snapshot"
        private const val BATCH = 50
        private val URL_LIFETIME = Duration.ofHours(1)
        private val CURATED = setOf("favorite_linear_channels", "recommended_linear_channels", "featured_channels", "recently_added_channels")
        private val STREAM_HEADERS = mapOf("User-Agent" to BROWSER_USER_AGENT, "Origin" to "https://tubitv.com", "Referer" to "https://tubitv.com/")
        private val HEADERS = STREAM_HEADERS + ("Accept" to "application/json")
        private val PAGE_HEADERS = mapOf("User-Agent" to BROWSER_USER_AGENT, "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8")
    }
}

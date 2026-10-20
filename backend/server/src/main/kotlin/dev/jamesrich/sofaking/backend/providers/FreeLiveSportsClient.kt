package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import java.time.Duration
import java.time.Instant

/**
 * FreeLiveSports.tv (an Unreel/POWR site): one public catalog document with every channel, its HLS template
 * (`[DEVICE_ID]`, `[CB]` and privacy macros) and the embedded schedule. The catalog path carries the site's
 * public key segment, which its web client sends in the clear; the bare path answers 403.
 */
class FreeLiveSportsClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "freelivesports"

    private val catalogUrl = context.options["catalogUrl"] ?: CATALOG_URL

    private class Listed(val channel: ProviderChannel, val template: String, val programmes: List<GuideProgramme>)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        return ParsedGuide(channels.map { GuideChannel(it.channel.id, it.channel.name, it.channel.logo) }, channels.filter { it.channel.id in wanted }.flatMap { it.programmes })
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val template = listed().firstOrNull { it.channel.id == channelId }?.template
            ?: run { context.invalidate(CATALOG); listed().firstOrNull { it.channel.id == channelId }?.template }
            ?: throw ProviderFailure("Channel is no longer listed", transient = false)
        val now = context.clock.instant()
        val url = Macros.fill(template, MACROS + mapOf("DEVICE_ID" to context.deviceId(), "CB" to now.epochSecond.toString(), "UA" to BROWSER_USER_AGENT))
        return ResolvedStream(url, HEADERS, now.plus(URL_LIFETIME))
    }

    private suspend fun listed(): List<Listed> = context.cached(CATALOG, Duration.ofMinutes(10)) { fetchCatalog() }

    private suspend fun fetchCatalog(): List<Listed> {
        val payload = context.http.get(catalogUrl, HEADERS).require("FreeLiveSports catalog").json()
        val rows = (payload as? kotlinx.serialization.json.JsonArray)?.toList() ?: payload["channels"].array
        val result = LinkedHashMap<String, Listed>()
        for (row in rows.sortedBy { it["channelNumber"].long ?: Long.MAX_VALUE }) {
            val id = row.str("_id") ?: row.str("id") ?: continue
            val name = row.str("name") ?: continue
            val template = row.str("url") ?: continue
            if (result.containsKey(id)) continue
            val programmes = row["epg"]["entries"].array.mapNotNull { entry ->
                val start = entry.str("start")?.let(::instantOrNull) ?: return@mapNotNull null
                val end = entry.str("stop")?.let(::instantOrNull) ?: return@mapNotNull null
                val title = entry.str("title") ?: return@mapNotNull null
                if (end <= start) null else GuideProgramme(id, start, end, title, entry.str("description"))
            }
            result[id] = Listed(ProviderChannel(id, name, row["channelNumber"].long?.toString(), httpOrNull(row.str("thumbnail")), "Sports", listOf("us")), template, programmes)
        }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result.values.toList()
    }


    companion object {
        private const val CATALOG_URL = "https://epg.unreel.me/v2/sites/freelivesports/live-channels/public/081f73704b56aaceb6b459804761ec54?__site=freelivesports&__source=web"
        private const val CATALOG = "catalog"
        private val URL_LIFETIME = Duration.ofHours(1)
        private val HEADERS = mapOf("User-Agent" to BROWSER_USER_AGENT, "Referer" to "https://www.freelivesports.tv/", "Origin" to "https://www.freelivesports.tv")
        private val MACROS = mapOf("DEVICE_MODEL" to "web", "REF" to "https://www.freelivesports.tv/", "LAT" to "0", "GDPR" to "0")
    }
}

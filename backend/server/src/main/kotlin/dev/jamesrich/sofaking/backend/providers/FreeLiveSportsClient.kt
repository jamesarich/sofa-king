package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import kotlinx.serialization.json.JsonElement
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

    private class Listed(val channel: ProviderChannel, val template: String, val programs: List<GuideProgram>)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        return ParsedGuide(
            channels.map {
                GuideChannel(it.channel.id, it.channel.name, it.channel.logo)
            },
            channels.filter {
                it.channel.id in
                    wanted
            }.flatMap { it.programs },
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val template = context.findListed(CATALOG) { listed().firstOrNull { it.channel.id == channelId }?.template }
        val now = context.clock.instant()
        val url = Macros.fill(
            template,
            MACROS + mapOf(
                "DEVICE_ID" to context.deviceId(),
                "CB" to now.epochSecond.toString(),
                "UA" to BROWSER_USER_AGENT,
            ),
        )
        return ResolvedStream(url, HEADERS, now.plus(URL_LIFETIME))
    }

    private suspend fun listed(): List<Listed> = context.cached(CATALOG, CACHED_FOR) { fetchCatalog() }

    private suspend fun fetchCatalog(): List<Listed> {
        val payload = context.http.get(catalogUrl, HEADERS).require("FreeLiveSports catalog").json()
        val rows = (payload as? kotlinx.serialization.json.JsonArray)?.toList() ?: payload["channels"].array
        val result = rows.sortedBy { it["channelNumber"].long ?: Long.MAX_VALUE }
            .mapNotNull(::listing)
            .distinctBy { it.channel.id }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result
    }

    /** A channel with its embedded schedule; null when a field is missing. */
    private fun listing(row: JsonElement): Listed? {
        val id = row.str("_id") ?: row.str("id") ?: return null
        val name = row.str("name") ?: return null
        val template = row.str("url") ?: return null
        val programs = row["epg"]["entries"].array.mapNotNull {
            guideProgram(
                id,
                it.str("start")?.let(::instantOrNull),
                it.str("stop")?.let(::instantOrNull),
                it.str("title"),
                it.str("description"),
            )
        }
        return Listed(
            ProviderChannel(
                id,
                name,
                row["channelNumber"].long?.toString(),
                httpOrNull(row.str("thumbnail")),
                "Sports",
                listOf("us"),
            ),
            template,
            programs,
        )
    }

    companion object {
        private const val CATALOG_URL = "https://epg.unreel.me/v2/sites/freelivesports/live-channels/public/" +
            "081f73704b56aaceb6b459804761ec54?__site=freelivesports&__source=web"
        private const val CATALOG = "catalog"

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(10)
        private val URL_LIFETIME = Duration.ofHours(1)
        private val HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Referer" to "https://www.freelivesports.tv/",
            "Origin" to "https://www.freelivesports.tv",
        )
        private val MACROS = mapOf(
            "DEVICE_MODEL" to "web",
            "REF" to "https://www.freelivesports.tv/",
            "LAT" to "0",
            "GDPR" to "0",
        )
    }
}

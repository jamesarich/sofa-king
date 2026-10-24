package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant

/**
 * Bally Sports' free channels, read from the Next.js payload of any channel page: the React Server Components
 * chunks carry `channelData` (name, uuid, logo, order, public CDN URL) and the programs as `epgData` (`epgItems`
 * since October 2026). Streams are static HLS on `*.channels.ballys.tv`.
 */
class BallySportsClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "ballysports"

    private val pageUrl = context.options["pageUrl"] ?: PAGE_URL

    private class Page(val channels: List<Pair<ProviderChannel, String>>, val programs: List<GuideProgram>)

    override suspend fun channels(): List<ProviderChannel> = page().channels.map { it.first }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val page = page()
        return ParsedGuide(
            page.channels.map {
                GuideChannel(it.first.id, it.first.name, it.first.logo)
            },
            page.programs.filter {
                it.channelId in
                    wanted
            },
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val url = context.findListed(PAGE) { page().channels.firstOrNull { it.first.id == channelId }?.second }
        return ResolvedStream(url, mapOf("User-Agent" to BROWSER_USER_AGENT))
    }

    private suspend fun page(): Page = context.cached(PAGE, CACHED_FOR) { fetchPage() }

    private suspend fun fetchPage(): Page {
        val html = context.http.get(pageUrl, HEADERS).require("Bally Sports page").text()
        val (channelData, epgData) = parse(html)
        if (channelData.isEmpty()) throw ProviderFailure("Bally Sports page carries no channelData")
        val channels = channelData.mapNotNull { entry ->
            val id = entry.str("channelUuid") ?: return@mapNotNull null
            val name = entry.str("channelName") ?: return@mapNotNull null
            val url = entry.str("public_cdn_url")?.takeIf { CDN.matches(it) } ?: return@mapNotNull null
            ProviderChannel(
                id,
                name,
                entry["order"].long?.toString(),
                httpOrNull(entry.str("logo")),
                "Sports",
                listOf("us"),
            ) to
                url
        }.distinctBy { it.first.id }
        val programs = epgData.mapNotNull { event ->
            val channelId = event.str("channelUuid") ?: return@mapNotNull null
            val start = event.str("since")?.let(::instantOrNull) ?: return@mapNotNull null
            val end = event.str("till")?.let(::instantOrNull) ?: return@mapNotNull null
            val title = event.str("title") ?: return@mapNotNull null
            if (end <= start) null else GuideProgram(channelId, start, end, title, event.str("comment"))
        }
        return Page(channels, programs)
    }

    companion object {
        private const val PAGE_URL = "https://www.ballysports.com/channels/milb"
        private const val PAGE = "page"

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(10)
        private val HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )
        private val CDN = Regex("""https://[a-z0-9]+\.channels\.ballys\.tv/.+""")
        private val PUSH = Regex("""self\.__next_f\.push\((\[.*?])\)""", RegexOption.DOT_MATCHES_ALL)
        private val CHUNK_KEYS = listOf("channelData", "epgData", "epgItems")
        private val DATE = Regex("\"\\\$D([^\"]+)\"")

        /**
         * The `channelData` and `epgData` (or `epgItems`) arrays from the page's RSC chunks;
         * each may sit in a different chunk.
         */
        internal fun parse(html: String): Pair<List<JsonElement>, List<JsonElement>> {
            var channels: List<JsonElement> = emptyList()
            var epg: List<JsonElement> = emptyList()
            for (cleaned in PUSH.findAll(html).mapNotNull { chunk(it.groupValues[1]) }) {
                if (channels.isEmpty()) channels = jsonArrayAfter(cleaned, "channelData").orEmpty()
                // The page named the array `epgData` until October 2026 and `epgItems` since.
                if (epg.isEmpty()) {
                    epg =
                        jsonArrayAfter(cleaned, "epgData") ?: jsonArrayAfter(cleaned, "epgItems").orEmpty()
                }
                if (channels.isNotEmpty() && epg.isNotEmpty()) break
            }
            return channels to epg
        }

        /** A pushed RSC chunk that holds the channels or the guide, as plain JSON; null for any other push. */
        private fun chunk(push: String): String? {
            val pushed = runCatching { lenientJson.parseToJsonElement(push) }.getOrNull() as? JsonArray ?: return null
            val chunk = pushed.takeIf { it.size >= 2 && it[0].long == 1L }?.getOrNull(1).string ?: return null
            if (CHUNK_KEYS.none { chunk.contains(it) }) return null
            // RSC encodes dates as "$D2026-..." and undefined as "$undefined"; plain JSON wants neither.
            return DATE.replace(chunk) { "\"${it.groupValues[1]}\"" }.replace("\"\$undefined\"", "null")
        }
    }
}

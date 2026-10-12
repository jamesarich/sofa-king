package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.catalog.percentEncode
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive

/**
 * Xumo Play as play.xumo.com uses it: the market's channel list, the guide in six-hour EPG blocks paged by channel,
 * and per resolve the channel's `broadcast` document, whose `ssaiStreamUrl` (or else the live asset's HLS source) is
 * an ad-stitched template with `[IFA]`-style macros. Live channels whose call sign ends in `-DRM` or `DRM-CMS` are
 * the Widevine ones and are left out.
 */
class XumoClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "xumo"

    private val apiBase = context.options["apiBase"] ?: API_BASE
    private val market = context.options["market"] ?: MARKET

    override suspend fun channels(): List<ProviderChannel> = listed()

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { GuideChannel(it.id, it.name, it.logo) }
        val ids = channels.map { it.id }.filter { it in wanted }.toSet()
        if (ids.isEmpty()) return ParsedGuide(index, emptyList())
        val now = context.clock.instant().atOffset(ZoneOffset.UTC)
        val firstBlock = now.withHour(now.hour / 6 * 6).withMinute(0).withSecond(0).withNano(0)
        val programmes = LinkedHashMap<Pair<String, Instant>, GuideProgramme>()
        // Blocks not yet published answer 400; the guide then ends where the service's does.
        for (step in 0 until GUIDE_BLOCKS) {
            val block = firstBlock.plusHours(6L * step)
            var offset = 0
            do {
                val url = "$apiBase/v2/epg/$market/${DATE.format(block)}/${block.hour / 6}.json?f=asset.title&f=asset.descriptions&limit=$PAGE&offset=$offset"
                val fetched = context.http.get(url, HEADERS)
                if (fetched.status == 400) break
                val page = fetched.require("Xumo guide").json()
                val rows = page["channels"].array
                for (row in rows) {
                    val id = row.str("channelId") ?: continue
                    if (id !in ids) continue
                    for (slot in row["schedule"].array) {
                        val start = slot.str("start")?.let(::instant) ?: continue
                        val end = slot.str("end")?.let(::instant) ?: continue
                        val assetId = slot.str("assetId") ?: continue
                        val asset = page["assets"][assetId]
                        val title = asset.str("title") ?: continue
                        if (end > start) programmes.putIfAbsent(id to start, programme(id, start, end, title, asset["descriptions"].let { it.str("medium") ?: it.str("small") }, assetId))
                    }
                }
                offset += PAGE
            } while (rows.size == PAGE && offset < (page["totalChannels"].long ?: 0) && offset < MAX_OFFSET)
        }
        return ParsedGuide(index, programmes.values.toList())
    }

    /**
     * Xumo's asset ids are Gracenote TMS ids (`EP000191553073`): two letters for the kind, eight digits for the
     * series and four for the episode, which XMLTV carries as `dd_progid` `EP00019155.3073`. `MV` is a movie and
     * `SP` a sports event.
     */
    private fun programme(id: String, start: Instant, end: Instant, title: String, description: String?, assetId: String): GuideProgramme {
        val tms = TMS_ID.matchEntire(assetId) ?: return GuideProgramme(id, start, end, title, description)
        val (kind, series, episode) = tms.destructured
        return GuideProgramme(
            id, start, end, title, description,
            episodeNumbers = listOf(EpisodeNumber("dd_progid", "$kind$series.$episode")),
            categories = listOfNotNull(KINDS[kind]),
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        if (listed().none { it.id == channelId }) {
            context.invalidate(CHANNELS)
            if (listed().none { it.id == channelId }) throw ProviderFailure("Channel is no longer listed", transient = false)
        }
        val hour = context.clock.instant().atOffset(ZoneOffset.UTC).hour
        val broadcast = context.http.get("$apiBase/v2/channels/channel/$channelId/broadcast.json?hour=$hour", HEADERS).require("Xumo broadcast").json()
        val template = broadcast.str("ssaiStreamUrl") ?: broadcast["ssaiStreamUrls"][0].str("url") ?: assetSource(broadcast)
            ?: throw ProviderFailure("Xumo channel has no HLS source", transient = false)
        val deviceId = context.deviceId()
        val values = mapOf(
            "PLATFORM" to "web", "APP_VERSION" to "1.0.0", "app_bundle" to "play.xumo.com", "content_language" to "en", "IS_LAT" to "0",
            "IFA" to deviceId, "IFA_TYPE" to "dpid", "DEVICE_ID" to deviceId.replace("-", ""), "SESSION_ID" to UUID.randomUUID().toString(),
            "CCPA_Value" to "1---", "OS" to "web", "timestamp" to context.clock.instant().toEpochMilli().toString(),
        )
        // A few asset sources carry raw spaces or non-ASCII in their ad parameters.
        val url = unwrapped(percentEncode(Macros.fill(template, values)))
        return ResolvedStream(url, STREAM_HEADERS, context.clock.instant().plus(URL_LIFETIME))
    }

    /** The live asset's first HLS source, for channels whose broadcast names no stitched stream. */
    private suspend fun assetSource(broadcast: kotlinx.serialization.json.JsonElement): String? {
        val assets = broadcast["assets"].array
        val id = (assets.firstOrNull { it["live"].truthy } ?: assets.firstOrNull()).str("id") ?: return null
        val asset = context.http.get("$apiBase/v2/assets/asset/$id.json?f=providers", HEADERS).require("Xumo asset").json()
        return asset["providers"].array.flatMap { it["sources"].array }
            .firstOrNull { (it.str("produces")?.contains("mpegurl", ignoreCase = true) == true) || it.str("uri")?.substringBefore('?')?.endsWith(".m3u8") == true }
            ?.str("uri")
    }

    /** A few channels point at an API that answers the real playlist URL as a JSON string; follow that one hop. */
    private suspend fun unwrapped(url: String): String {
        if (URI(url).path.orEmpty().lowercase().endsWith(".m3u8")) return url
        val answer = context.http.get(url, STREAM_HEADERS, limit = 8192)
        if (!answer.ok || answer.contentType?.contains("json") != true) return url
        val target = (runCatching { answer.json() }.getOrNull() as? JsonPrimitive)?.string ?: return url
        return httpOrNull(target) ?: url
    }

    private suspend fun listed(): List<ProviderChannel> = context.cached(CHANNELS, Duration.ofMinutes(30)) { fetchChannels() }

    private suspend fun fetchChannels(): List<ProviderChannel> {
        val deviceId = context.deviceId()
        val payload = context.http.get("$apiBase/v2/proxy/channels/list/$market.json?sort=hybrid&geoId=$GEO&deviceId=$deviceId&ifaId=$deviceId", HEADERS)
            .require("Xumo channel list").json()
        val result = LinkedHashMap<String, ProviderChannel>()
        for (item in payload["channel"]["item"].array) {
            val id = item["guid"].str("value") ?: continue
            val name = item.str("title") ?: continue
            val callSign = item.str("callsign").orEmpty()
            if (item["properties"].str("is_live") != "true" || DRM_SUFFIXES.any { callSign.endsWith(it) } || result.containsKey(id)) continue
            result[id] = ProviderChannel(id, name, item["number"].long?.toString(), "$LOGO_BASE/$id/248x140.png?type=color_onBlack", item["genre"][0].str("value"), listOf("us"))
        }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result.values.toList()
    }

    private fun instant(value: String): Instant? = try { Instant.from(STAMP.parse(value)) } catch (_: Exception) { null }

    companion object {
        private val TMS_ID = Regex("(EP|SH|MV|SP)(\\d{8})(\\d{4})")
        private val KINDS = mapOf("MV" to "Movie", "SP" to "Sports")
        private const val API_BASE = "https://valencia-app-mds.xumo.com"
        private const val LOGO_BASE = "https://image.xumo.com/v1/channels/channel"
        private const val MARKET = "10006"
        private const val GEO = "2f08a9b3"
        private const val CHANNELS = "channels"
        private const val PAGE = 50
        private const val MAX_OFFSET = 1000
        private const val GUIDE_BLOCKS = 3
        private val DRM_SUFFIXES = listOf("-DRM", "DRM-CMS")
        private val DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
        private val STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxx")
        private val URL_LIFETIME = Duration.ofHours(1)
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        private val STREAM_HEADERS = mapOf("User-Agent" to USER_AGENT, "Origin" to "https://play.xumo.com", "Referer" to "https://play.xumo.com/")
        private val HEADERS = STREAM_HEADERS + ("Accept" to "application/json, text/plain, */*")
    }
}

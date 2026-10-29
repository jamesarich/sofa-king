package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.catalog.percentEncode
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

private val STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxx")

private fun instant(value: String): Instant? = try {
    Instant.from(STAMP.parse(value))
} catch (_: Exception) {
    null
}

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
        val index = channels.map { it.toGuideChannel() }
        val ids = channels.map { it.id }.filter { it in wanted }.toSet()
        if (ids.isEmpty()) return ParsedGuide(index, emptyList())
        val now = context.clock.instant().atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS)
        val firstBlock = now.withHour(now.hour / BLOCK_HOURS * BLOCK_HOURS)
        val programs = LinkedHashMap<Pair<String, Instant>, GuideProgram>()
        for (step in 0 until GUIDE_BLOCKS) {
            for (program in block(firstBlock.plusHours(BLOCK_HOURS * step.toLong()), ids)) {
                programs.putIfAbsent(program.channelId to program.startsAt, program)
            }
        }
        return ParsedGuide(index, programs.values.toList())
    }

    /**
     * The programs on [ids] in the EPG block that starts at [block], paged by channel. A block not yet published
     * answers 400 and has none; the guide then ends where the service's does.
     */
    private suspend fun block(block: OffsetDateTime, ids: Set<String>): List<GuideProgram> {
        val programs = mutableListOf<GuideProgram>()
        var offset = 0
        do {
            val url = "$apiBase/v2/epg/$market/${DATE.format(block)}/${block.hour / BLOCK_HOURS}.json?f=asset.title&" +
                "f=asset.descriptions&limit=$PAGE&offset=$offset"
            val fetched = context.http.get(url, HEADERS)
            if (fetched.status == HttpStatusCode.BadRequest.value) break
            val page = fetched.require("Xumo guide").json()
            val rows = page["channels"].array
            for (row in rows) {
                val id = row.str("channelId")?.takeIf { it in ids } ?: continue
                row["schedule"].array.mapNotNullTo(programs) { slot(id, it, page["assets"]) }
            }
            offset += PAGE
        } while (rows.size == PAGE && offset < (page["totalChannels"].long ?: 0) && offset < MAX_OFFSET)
        return programs
    }

    /** One schedule slot on channel [id], with its asset from [assets]; null when a field is missing. */
    private fun slot(id: String, slot: JsonElement, assets: JsonElement?): GuideProgram? {
        val start = slot.str("start")?.let(::instant) ?: return null
        val end = slot.str("end")?.let(::instant)?.takeIf { it > start } ?: return null
        val assetId = slot.str("assetId") ?: return null
        val title = assets[assetId].str("title") ?: return null
        val descriptions = assets[assetId]["descriptions"]
        return program(id, start, end, title, descriptions.str("medium") ?: descriptions.str("small"), assetId)
    }

    /**
     * Xumo's asset ids are Gracenote TMS ids (`EP000191553073`): two letters for the kind, eight digits for the
     * series and four for the episode, which XMLTV carries as `dd_progid` `EP00019155.3073`. `MV` is a movie and
     * `SP` a sports event.
     */
    private fun program(
        id: String,
        start: Instant,
        end: Instant,
        title: String,
        description: String?,
        assetId: String,
    ): GuideProgram {
        val tms = TMS_ID.matchEntire(assetId) ?: return GuideProgram(id, start, end, title, description)
        val (kind, series, episode) = tms.destructured
        return GuideProgram(
            id,
            start,
            end,
            title,
            description,
            episodeNumbers = listOf(EpisodeNumber("dd_progid", "$kind$series.$episode")),
            categories = listOfNotNull(KINDS[kind]),
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        context.findListed(CHANNELS) { listed().firstOrNull { it.id == channelId } }
        val hour = context.clock.instant().atOffset(ZoneOffset.UTC).hour
        val broadcast = context.http.get(
            "$apiBase/v2/channels/channel/$channelId/broadcast.json?hour=$hour",
            HEADERS,
        ).require("Xumo broadcast").json()
        val template =
            broadcast.str("ssaiStreamUrl") ?: broadcast["ssaiStreamUrls"][0].str("url") ?: assetSource(broadcast)
                ?: throw ProviderFailure("Xumo channel has no HLS source", transient = false)
        val deviceId = context.deviceId()
        val values = mapOf(
            "PLATFORM" to "web", "APP_VERSION" to "1.0.0", "app_bundle" to "play.xumo.com", "content_language" to "en",
            "IS_LAT" to "0",
            "IFA" to deviceId, "IFA_TYPE" to "dpid", "DEVICE_ID" to deviceId.replace("-", ""),
            "SESSION_ID" to UUID.randomUUID().toString(),
            "CCPA_Value" to "1---", "OS" to "web", "timestamp" to context.clock.instant().toEpochMilli().toString(),
        )
        // A few asset sources carry raw spaces or non-ASCII in their ad parameters.
        val url = unwrapped(percentEncode(Macros.fill(template, values)))
        return ResolvedStream(url, STREAM_HEADERS, context.clock.instant().plus(URL_LIFETIME))
    }

    /** The live asset's first HLS source, for channels whose broadcast names no stitched stream. */
    private suspend fun assetSource(broadcast: JsonElement): String? {
        val assets = broadcast["assets"].array
        val id = (assets.firstOrNull { it["live"].truthy } ?: assets.firstOrNull()).str("id") ?: return null
        val asset = context.http.get(
            "$apiBase/v2/assets/asset/$id.json?f=providers",
            HEADERS,
        ).require("Xumo asset").json()
        return asset["providers"].array.flatMap { it["sources"].array }
            .firstOrNull {
                (it.str("produces")?.contains("mpegurl", ignoreCase = true) == true) ||
                    it.str("uri")?.substringBefore('?')?.endsWith(".m3u8") == true
            }
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

    private suspend fun listed(): List<ProviderChannel> = context.cached(CHANNELS, CACHED_FOR) {
        fetchChannels()
    }

    private suspend fun fetchChannels(): List<ProviderChannel> {
        val deviceId = context.deviceId()
        val payload = context.http.get(
            "$apiBase/v2/proxy/channels/list/$market.json?sort=hybrid&geoId=$GEO&deviceId=$deviceId&ifaId=$deviceId",
            HEADERS,
        )
            .require("Xumo channel list").json()
        val result = payload["channel"]["item"].array.mapNotNull(::channel).distinctBy { it.id }
        requireChannels(result)
        return result
    }

    /** A listed live channel; null for a Widevine one or when a field is missing. */
    private fun channel(item: JsonElement): ProviderChannel? {
        val id = item["guid"].str("value") ?: return null
        val name = item.str("title") ?: return null
        val callSign = item.str("callsign").orEmpty()
        if (item["properties"].str("is_live") != "true" || DRM_SUFFIXES.any { callSign.endsWith(it) }) return null
        return ProviderChannel(
            id,
            name,
            item["number"].long?.toString(),
            "$LOGO_BASE/$id/248x140.png?type=color_onBlack",
            item["genre"][0].str("value"),
            listOf("us"),
        )
    }

    companion object {
        private val TMS_ID = Regex("(EP|SH|MV|SP)(\\d{8})(\\d{4})")
        private val KINDS = mapOf("MV" to "Movie", "SP" to "Sports")
        private const val API_BASE = "https://valencia-app-mds.xumo.com"
        private const val LOGO_BASE = "https://image.xumo.com/v1/channels/channel"
        private const val MARKET = "10006"
        private const val GEO = "2f08a9b3"
        private const val CHANNELS = "channels"

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(30)
        private const val PAGE = 50
        private const val MAX_OFFSET = 1000
        private const val GUIDE_BLOCKS = 3
        private const val BLOCK_HOURS = 6
        private val DRM_SUFFIXES = listOf("-DRM", "DRM-CMS")
        private val DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
        private val URL_LIFETIME = Duration.ofHours(1)
        private val STREAM_HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Origin" to "https://play.xumo.com",
            "Referer" to "https://play.xumo.com/",
        )
        private val HEADERS = STREAM_HEADERS + ("Accept" to "application/json, text/plain, */*")
    }
}

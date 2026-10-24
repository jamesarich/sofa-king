package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Whale TV+ (rlaxx) as its web client uses it: a ~24-hour access token from the public web key, the category
 * channel list, a 7-day EPG in batches of channel ids, and Ottera or Amagi ad-stitched HLS whose URL template
 * takes a device id, a session id and a cache buster. The `region` option picks another country's lineup, which the
 * service answers by its `countryCode` alone.
 */
class WhaleClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "whale"

    /** The `apiBase` option points a test at a fixture; nothing else changes it. */
    private val apiBase = context.options["apiBase"] ?: API_BASE
    private val authUrl = "$apiBase/v1/auth/access"
    private val channelsUrl = "$apiBase/device/browser/v1/category/channels"
    private val epgUrl = "$apiBase/device/browser/v1/epg"
    private val region = RegionOptions(context.options).region
    private val country = region.uppercase()

    private class Listed(val channel: ProviderChannel, val template: String)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { GuideChannel(it.channel.id, it.channel.name, it.channel.logo) }
        val ids = channels.map { it.channel.id }.filter { it in wanted }
        val now = context.clock.instant()
        val programs = ArrayList<GuideProgram>()
        for (batch in ids.chunked(EPG_BATCH)) {
            val url = "$epgUrl?channelIds=${batch.joinToString(",")}&startTime=${now.toEpochMilli()}&" +
                "endTime=${now.plus(EPG_HORIZON).toEpochMilli()}&langCode=en&countryCode=$country"
            val data = authorized { token -> context.http.get(url, HEADERS + ("token" to token)) }.json()["data"].array
            for (row in data) {
                val channelId = row.str("chlId") ?: continue
                row["ptList"].array.mapNotNullTo(programs) {
                    guideProgram(
                        channelId,
                        it["prgStm"].long?.let(Instant::ofEpochMilli),
                        it["prgEtm"].long?.let(Instant::ofEpochMilli),
                        it.str("prgTitle"),
                        it.str("prgDesc"),
                    )
                }
            }
        }
        return ParsedGuide(index, programs)
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val template = context.findListed(CHANNELS) { listed().firstOrNull { it.channel.id == channelId }?.template }
        val now = context.clock.instant()
        val url = Macros.fill(
            template,
            mapOf(
                "did" to context.deviceId(), "session_id" to UUID.randomUUID().toString(),
                "cachebuster" to now.toEpochMilli().toString(),
                "dnt" to "0", "lmt" to "0", "content_language" to "en", "device_language" to "en",
                "country_2" to country, "livestream" to "1",
            ),
        )
        return ResolvedStream(url, STREAM_HEADERS, now.plus(URL_LIFETIME))
    }

    /** The channel list, shared between the import, the guide and resolves within ten minutes. */
    private suspend fun listed(): List<Listed> = context.cached(CHANNELS, CACHED_FOR) { fetchChannels() }

    private suspend fun fetchChannels(): List<Listed> {
        val payload = authorized { token ->
            context.http.get(
                "$channelsUrl?langCode=en&countryCode=$country",
                HEADERS + ("token" to token),
            )
        }.json()
        val categories = payload["data"].array
        val result = LinkedHashMap<String, Listed>()
        // Every channel also sits in the "All" bucket; the themed buckets name its group,
        // "All" only catches stragglers.
        for (category in categories.filterNot { it.str("ctgName") in UNGROUPED } +
            categories.filter { it.str("ctgName") in UNGROUPED }) {
            val group = category.str("ctgName")?.takeUnless { it in UNGROUPED }
            for (channel in category["channels"].array.mapNotNull { listing(it, group) }) {
                result.putIfAbsent(channel.channel.id, channel)
            }
        }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result.values.toList()
    }

    /** A channel in [group]; null when a field is missing. */
    private fun listing(channel: JsonElement, group: String?): Listed? {
        val id = channel.str("chlId") ?: return null
        val name = channel.str("chlName") ?: return null
        val template = channel.str("chlUrl") ?: return null
        val logo = channel.str("imageIdentifier")?.let { "$LOGO_BASE/${it}_white.png" }
        return Listed(ProviderChannel(id, name, channel.str("chlNum"), logo, group, listOf(region)), template)
    }

    /** Runs [request] with the current token; a 401 or 403 drops the stored token and tries once with a new one. */
    private suspend fun authorized(request: suspend (String) -> Fetched): Fetched {
        val first = request(token())
        if (!first.refused) return first.require("Whale API")
        context.clearState(TOKEN)
        return request(token()).require("Whale API")
    }

    private suspend fun token(): String {
        context.state(TOKEN)?.let { return it }
        val answer = context.http.get(
            "$authUrl?uuid=1&apiToken=$API_KEY&langCode=en",
            HEADERS,
        ).require("Whale auth").json()
        val token = (answer["data"] ?: answer).str("token") ?: throw ProviderFailure("Whale auth returned no token")
        context.saveState(TOKEN, token, context.clock.instant().plus(TOKEN_LIFETIME))
        return token
    }

    companion object {
        private const val API_BASE = "https://rlaxx.zeasn.tv/livetv/api"
        private const val LOGO_BASE = "https://d3b6luslimvglo.cloudfront.net/images/79/rlaxximages/channels-rescaled/" +
            "icon-white"

        /** The key the public web client at watch.whaletvplus.com sends; it identifies the client, not a user. */
        private const val API_KEY = "4ef13b5f3d2744e3b0a569feb8dde298"
        private const val CHANNELS = "channels"

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(10)
        private const val TOKEN = "token"
        private const val EPG_BATCH = 10
        private val EPG_HORIZON = Duration.ofDays(7)
        private val TOKEN_LIFETIME = Duration.ofHours(23)
        private val URL_LIFETIME = Duration.ofHours(1)
        private val UNGROUPED = setOf("All", "Featured all other countries")
        private val STREAM_HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Origin" to "https://watch.whaletvplus.com",
            "Referer" to "https://watch.whaletvplus.com/",
        )
        private val HEADERS = STREAM_HEADERS + ("Accept" to "application/json, text/plain, */*")
    }
}

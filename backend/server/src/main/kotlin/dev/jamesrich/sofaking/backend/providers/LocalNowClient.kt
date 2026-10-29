package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant

/**
 * Local Now as localnow.com uses it: the page's runtime config carries a public app token and the data-store host,
 * the `live/epg` document lists every channel for a DMA and market with its next few programs, and `video/play`
 * answers each channel's ad-stitched HLS master. Channels that are not `unlocked` or that
 * carry a DRM config are left out.
 */
class LocalNowClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "localnow"

    private val pageUrl = context.options["pageUrl"] ?: PAGE_URL
    private val dma = context.options["dma"]
    private val market = context.options["market"]

    private class Listed(val channel: ProviderChannel, val slug: String?)

    private class Snapshot(val channels: List<Listed>, val programs: List<GuideProgram>)

    private class Runtime(val host: String, val token: String)

    override suspend fun channels(): List<ProviderChannel> = snapshot().channels.map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val snapshot = snapshot()
        return ParsedGuide(
            snapshot.channels.map {
                it.channel.toGuideChannel()
            },
            snapshot.programs.filter {
                it.channelId in
                    wanted
            },
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val listed = context.findListed(SNAPSHOT) { snapshot().channels.firstOrNull { it.channel.id == channelId } }
        val page = URLEncoder.encode("https://localnow.com/channels/${listed.slug ?: ""}", Charsets.UTF_8)
        val query = "page_url=$page&device_devicetype=desktop_web&app_version=0.0.0&app_bundle=web.localnow&" +
            "ccpa_us_privacy=1YNY"
        val play = authorized { runtime ->
            context.http.get("${runtime.host}/video/play/$channelId/1920/1080?$query", headers(runtime))
        }.json()
        if (needsDrm(play["drm_config"])) throw ProviderFailure("Local Now channel needs DRM", transient = false)
        val url =
            play.str("video_m3u8") ?: play.str("session_m3u8") ?: throw ProviderFailure("Local Now answered no stream")
        return ResolvedStream(url, STREAM_HEADERS, context.clock.instant().plus(URL_LIFETIME))
    }

    private suspend fun snapshot(): Snapshot = context.cached(SNAPSHOT, CACHED_FOR) { fetch() }

    private suspend fun fetch(): Snapshot {
        val query = listOfNotNull(
            dma?.let {
                "dma=${URLEncoder.encode(it, Charsets.UTF_8)}"
            },
            market?.let { "market=${URLEncoder.encode(it, Charsets.UTF_8)}" },
        )
            .joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        val payload = authorized { runtime ->
            context.http.get("${runtime.host}/live/epg/US/website$query", headers(runtime))
        }.json()
        val channels = LinkedHashMap<String, Listed>()
        val programs = ArrayList<GuideProgram>()
        for (channel in payload["channels"].array) {
            val listed = listing(channel)
            if (listed == null || channels.containsKey(listed.channel.id)) continue
            val id = listed.channel.id
            channels[id] = listed
            channel["program"].array.mapNotNullTo(programs) {
                guideProgram(
                    id,
                    it["starts_at"].long?.let(Instant::ofEpochSecond),
                    it["ends_at"].long?.let(Instant::ofEpochSecond),
                    it.str("program_title"),
                    it.str("program_description"),
                )
            }
        }
        requireChannels(channels)
        return Snapshot(channels.values.toList(), programs)
    }

    /** An unlocked channel; null for a locked one or when a field is missing. */
    private fun listing(channel: JsonElement): Listed? {
        val id = channel.str("video_id") ?: channel.str("_id") ?: return null
        val name = channel.str("name") ?: return null
        if (!channel["subscription_access"]["unlocked"].truthy) return null
        return Listed(
            ProviderChannel(
                id,
                name,
                channel.str("channel_number"),
                httpOrNull(channel.str("logo")),
                channel["genres"][0].string,
                listOf("us"),
            ),
            channel.str("slug"),
        )
    }

    /** Runs [request] with the page's runtime config; a 401 or 403 reads the page again and tries once more. */
    private suspend fun authorized(request: suspend (Runtime) -> Fetched): Fetched {
        val first = request(runtime())
        if (!first.refused) return first.require("Local Now API")
        context.clearState(RUNTIME)
        return request(runtime()).require("Local Now API")
    }

    private suspend fun runtime(): Runtime {
        context.state(RUNTIME)?.split('\n')?.takeIf { it.size == 2 }?.let { return Runtime(it[0], it[1]) }
        val html = context.http.get(pageUrl, PAGE_HEADERS).require("Local Now page").text()
        val config = nextData(html, "Local Now page")["runtimeConfig"]
        val host =
            config.str("DSP_API_URL")?.let { if (it.startsWith("http")) it else "https://$it" }
                ?: throw ProviderFailure("Local Now runtime config has no API host")
        val token =
            config.str("DSP_TOKEN")?.let { runCatching { lenientJson.parseToJsonElement(it).str("token") }.getOrNull() }
                ?: throw ProviderFailure("Local Now runtime config has no token")
        context.saveState(
            RUNTIME,
            "${context.options["apiBase"] ?: host}\n$token",
            context.clock.instant().plus(RUNTIME_LIFETIME),
        )
        return Runtime(context.options["apiBase"] ?: host, token)
    }

    /** The API writes no DRM as null or the string `None`; anything else names a DRM system. */
    private fun needsDrm(config: JsonElement?): Boolean = when (config) {
        null -> false
        is JsonPrimitive -> config.string.let { it != null && it != "None" }
        is JsonObject -> config.isNotEmpty()
        is JsonArray -> config.isNotEmpty()
    }

    private fun headers(runtime: Runtime) =
        STREAM_HEADERS + mapOf("Accept" to "application/json, text/plain, */*", "x-access-token" to runtime.token)

    companion object {
        private const val PAGE_URL = "https://localnow.com/"
        private const val SNAPSHOT = "snapshot"

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(15)
        private const val RUNTIME = "runtime"
        private val RUNTIME_LIFETIME = Duration.ofHours(12)
        private val URL_LIFETIME = Duration.ofHours(1)
        private val STREAM_HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Origin" to "https://localnow.com",
            "Referer" to "https://localnow.com/",
        )
        private val PAGE_HEADERS = STREAM_HEADERS + ("Accept" to "text/html,application/xhtml+xml,*/*;q=0.8")
    }
}

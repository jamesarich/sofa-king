package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant

/**
 * The CW's free channels as cwtv.com's web player reads them: one public EPG feed that lists every channel with a
 * clear HLS URL and its coming programs. Channels flagged DRM or not HLS are left out. The listing, the guide and
 * resolves share one fetch.
 */
class CwClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "cw"

    private val feedBase = context.options["feedBase"] ?: FEED_BASE

    private class Listed(val channel: ProviderChannel, val url: String, val programs: List<JsonElement>)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { GuideChannel(it.channel.id, it.channel.name, it.channel.logo) }
        val programs = channels.filter { it.channel.id in wanted }.flatMap { channel ->
            channel.programs.mapNotNull { program(channel.channel.id, it) }
        }
        return ParsedGuide(index, programs)
    }

    /** A program of [channelId]; null when a field is missing or it is the feed's "To Be Announced" filler. */
    private fun program(channelId: String, program: JsonElement): GuideProgram? {
        val title = program.str("title")?.takeUnless { it.equals(FILLER, ignoreCase = true) } ?: return null
        val start = program["program_start_ts"].long?.let(Instant::ofEpochSecond) ?: return null
        val end = program["program_end_ts"].long?.let(Instant::ofEpochSecond)?.takeIf { it > start } ?: return null
        return GuideProgram(
            channelId,
            start,
            end,
            title,
            program.str("description"),
            subTitle = program.str("subtitle")?.takeIf { it != title },
            icon = httpOrNull(program.str("details_image_url")),
            categories = listOfNotNull(program.str("category")),
            rating = usRating(program.str("ratings")),
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val url = context.findListed(CHANNELS) { listed().firstOrNull { it.channel.id == channelId }?.url }
        return ResolvedStream(url)
    }

    private suspend fun listed(): List<Listed> = context.cached(CHANNELS, CACHED_FOR) { fetchChannels() }

    private suspend fun fetchChannels(): List<Listed> {
        // The version only keys the CDN's copy; the feed answers the same for any number, so a quarter-hour bucket
        // keeps requests cacheable without pinning a stale one.
        val version = context.clock.instant().epochSecond / VERSION_BUCKET_SECONDS
        val result = ArrayList<Listed>()
        var page = 1
        var pages = 1
        while (page <= pages && page <= MAX_PAGES) {
            val payload = context.http.get(
                "$feedBase/page_$page/pagesize_$PAGE_SIZE/device_web/apiversion_24/cacheversion_$version",
                HEADERS,
            ).require("CW feed").json()
            payload["channels"].array.mapNotNullTo(result, ::listing)
            val size = payload["page_size"].long ?: PAGE_SIZE.toLong()
            val count = payload["channel_count"].long ?: 0
            if (page == 1 && size > 0 && count > size) pages = ((count + size - 1) / size).toInt()
            page++
        }
        val channels = result.distinctBy { it.channel.id }
        if (channels.isEmpty()) throw ProviderFailure("Channel list is empty")
        return channels
    }

    /** A channel with a clear HLS stream; null for a DRM or other-protocol one or when a field is missing. */
    private fun listing(channel: JsonElement): Listed? {
        val id = channel.str("slug") ?: return null
        if (channel["has_drm"].let { it.truthy || (it.long ?: 0L) != 0L }) return null
        if ((channel.str("stream_type") ?: "hls") != "hls") return null
        val url = httpOrNull(channel.str("stream_url")) ?: return null
        val group = (channel.str("content_category") ?: channel.str("genre"))?.substringBefore(',')?.trim()
            ?.takeIf { it.isNotEmpty() }
        return Listed(
            ProviderChannel(
                id,
                channel.str("title") ?: id,
                logo = httpOrNull(channel.str("thumbnail_url")) ?: httpOrNull(channel.str("icon_unfocused_url")),
                group = group,
                tags = listOf("us"),
            ),
            url,
            channel["programs"].array,
        )
    }

    companion object {
        private const val FEED_BASE = "https://data.cwtv.com/feed/app-2/landing/epg"
        private const val CHANNELS = "channels"
        private const val FILLER = "To Be Announced"
        private const val PAGE_SIZE = 75
        private const val MAX_PAGES = 4
        private const val VERSION_BUCKET_SECONDS = 900L

        /** How long the feed is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(10)
        private val HEADERS = mapOf("User-Agent" to BROWSER_USER_AGENT, "Accept" to "application/json")
    }
}

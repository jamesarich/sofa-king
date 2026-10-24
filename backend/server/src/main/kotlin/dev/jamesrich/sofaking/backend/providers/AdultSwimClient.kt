package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import java.time.Duration
import java.time.Instant

/**
 * Adult Swim's marathon streams: the streams page's Next.js state lists them with a video id and a marathon id,
 * the video API names each one's stable `/live/` HLS asset, and the marathon schedule API carries the guide.
 */
class AdultSwimClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "adultswim"

    private val siteBase = context.options["siteBase"] ?: SITE_BASE

    private class Listed(val channel: ProviderChannel, val videoId: String, val marathonId: String)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { GuideChannel(it.channel.id, it.channel.name, it.channel.logo) }
        val programs = ArrayList<GuideProgram>()
        for (channel in channels.filter { it.channel.id in wanted }) {
            val rows = context.http.get(
                "$siteBase/api/schedule/marathons/${channel.marathonId}",
                HEADERS,
            ).require("Adult Swim schedule").json()["data"].array
            for (row in rows) {
                val start =
                    row.str("startTime")?.let(::instantOrNull) ?: row["time"].long?.let(Instant::ofEpochMilli)
                        ?: continue
                val seconds = row["duration"].double ?: continue
                if (seconds <= 0) continue
                val episode = row.str("name") ?: row.str("episodeName")
                val series = row.str("seriesName")
                val title = series ?: episode ?: channel.channel.name
                programs += GuideProgram(
                    channel.channel.id,
                    start,
                    start.plusMillis((seconds * 1000).toLong()),
                    title,
                    subTitle = if (series != null) episode else null,
                )
            }
        }
        return ParsedGuide(index, programs)
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val listed = listed().firstOrNull { it.channel.id == channelId }
            ?: run {
                context.invalidate(STREAMS)
                listed().firstOrNull { it.channel.id == channelId }
            }
            ?: throw ProviderFailure("Channel is no longer listed", transient = false)
        val url = context.cached("asset:${listed.videoId}", Duration.ofHours(6)) { asset(listed.videoId) }
        return ResolvedStream(
            url,
            mapOf("User-Agent" to BROWSER_USER_AGENT),
            context.clock.instant().plus(Duration.ofHours(6)),
        )
    }

    private suspend fun listed(): List<Listed> = context.cached(STREAMS, Duration.ofMinutes(10)) { fetchStreams() }

    private suspend fun fetchStreams(): List<Listed> {
        val html = context.http.get(
            "$siteBase/streams",
            HEADERS + ("Accept" to "text/html"),
        ).require("Adult Swim streams page").text()
        val state = nextData(html, "Adult Swim streams page")["props"]["__REDUX_STATE__"]
        val streams = state["streams"].array
        if (streams.isEmpty()) throw ProviderFailure("Adult Swim page carries no streams")
        val result = LinkedHashMap<String, Listed>()
        streams.forEachIndexed { index, row ->
            val slug = row.str("id") ?: return@forEachIndexed
            val title = row.str("title") ?: return@forEachIndexed
            val videoId = row.str("stream") ?: return@forEachIndexed
            val marathonId = row.str("vod_to_live_id") ?: return@forEachIndexed
            if (result.containsKey(slug)) return@forEachIndexed
            result[slug] =
                Listed(
                    ProviderChannel(
                        slug,
                        title,
                        (index + 1).toString(),
                        httpOrNull(row.str("poster")),
                        "Comedy",
                        listOf("us"),
                    ),
                    videoId,
                    marathonId,
                )
        }
        return result.values.toList()
    }

    /** The video's `/live/` HLS asset; the API answers for the video id the page lists. */
    private suspend fun asset(videoId: String): String {
        val url = "$siteBase/api/shows/v1/videos/$videoId?fields=title,type,duration,collection_title,poster,stream," +
            "title_id,auth,media_id,launch_date,auth_launch_date,disable_ads,season_number,episode_number"
        val assets = context.http.get(
            url,
            HEADERS,
        ).require("Adult Swim video").json()["data"]["video"]["stream"]["assets"].array
        val urls = assets.mapNotNull { asset ->
            val candidate = asset.str("url") ?: return@mapNotNull null
            val mime = asset.str("mime_type")?.lowercase()
            val hls = candidate.substringBefore('?').endsWith(".m3u8") || mime in HLS_MIME_TYPES
            candidate.takeIf { hls && httpOrNull(it) != null }
        }
        return urls.firstOrNull { it.contains("/live/") } ?: urls.firstOrNull()
            ?: throw ProviderFailure("Adult Swim video has no HLS asset", transient = false)
    }

    companion object {
        private val HLS_MIME_TYPES = setOf("application/x-mpegurl", "application/vnd.apple.mpegurl")
        private const val SITE_BASE = "https://www.adultswim.com"
        private const val STREAMS = "streams"
        private val HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to SITE_BASE,
            "Referer" to "$SITE_BASE/streams/",
        )
    }
}

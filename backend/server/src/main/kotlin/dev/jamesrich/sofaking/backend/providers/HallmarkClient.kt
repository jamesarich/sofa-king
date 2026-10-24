package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Hallmark+'s four free linear channels: static clear HLS on Akamai, a channel catalog that names each channel's
 * current content id by its stable `channelIdentifier`, and a per-channel schedule API for the guide.
 */
class HallmarkClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "hallmark"

    private val apiBase = context.options["apiBase"] ?: API_BASE
    private val streamBase = context.options["streamBase"] ?: "https://crwn-%s.akamaized.net/hls/playlist.m3u8"

    private class Known(val id: String, val identifier: String, val name: String, val number: String, val host: String)

    override suspend fun channels(): List<ProviderChannel> {
        val logos = runCatchingCancellable { catalog() }.getOrElse { emptyMap() }
        return KNOWN.map {
            ProviderChannel(it.id, it.name, it.number, logos[it.identifier]?.second, "Movies", listOf("us"))
        }
    }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val catalog = runCatchingCancellable { catalog() }.getOrElse { emptyMap() }
        val index = KNOWN.map { GuideChannel(it.id, it.name, catalog[it.identifier]?.second) }
        val now = context.clock.instant()
        val from = now.minus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val programs = ArrayList<GuideProgram>()
        for (channel in KNOWN.filter { it.id in wanted }) {
            val contentId =
                catalog[channel.identifier]?.first
                    ?: throw ProviderFailure("Hallmark catalog does not list ${channel.identifier}")
            val url = "$apiBase/channels/$contentId/schedule?from=${TIMESTAMP.format(from)}&" +
                "until=${TIMESTAMP.format(from.plus(GUIDE_HORIZON))}&locale=en"
            for (item in context.http.get(url, HEADERS).require("Hallmark schedule").json()["data"].array) {
                val start = item["schedule"].str("startsAt")?.let(::instantOrNull) ?: continue
                val end = item["schedule"].str("endsAt")?.let(::instantOrNull) ?: continue
                val title = item["display"]["epg"].str("title") ?: item.str("title") ?: continue
                if (end <= start) continue
                programs +=
                    GuideProgram(
                        channel.id,
                        start,
                        end,
                        title,
                        item.str("description") ?: item["display"]["epg"].str("description"),
                    )
            }
        }
        return ParsedGuide(index, programs)
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val known =
            KNOWN.firstOrNull { it.id == channelId }
                ?: throw ProviderFailure("Unknown Hallmark channel", transient = false)
        return ResolvedStream(streamBase.format(known.host), mapOf("User-Agent" to USER_AGENT))
    }

    /** `channelIdentifier` to the catalog's current content id and logo, cached for an hour. */
    private suspend fun catalog(): Map<String, Pair<String, String?>> = context.cached(CATALOG, Duration.ofHours(1)) {
        val items = context.http.get("$apiBase/channels", HEADERS).require("Hallmark catalog").json()["data"].array
        val result = HashMap<String, Pair<String, String?>>()
        for (item in items) {
            val identifier = item["extras"].str("channelIdentifier") ?: continue
            val id = item.str("id") ?: continue
            result[identifier] = id to httpOrNull(item["images"]["logo"]["landscape"][0].str("url"))
        }
        result
    }

    companion object {
        private const val API_BASE = "https://www.hallmarkplus.com/api/core/catalog"
        private const val CATALOG = "catalog"
        private val GUIDE_HORIZON = Duration.ofHours(48)
        private val TIMESTAMP = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
        ).withZone(java.time.ZoneOffset.UTC)
        private const val USER_AGENT = "Mozilla/5.0"
        private val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://www.hallmarkplus.com",
            "Referer" to "https://www.hallmarkplus.com/",
        )

        /**
         * The channels and their CDN hosts; the catalog's content ids have been renamed before, so
         * only the identifier is trusted.
         */
        private val KNOWN = listOf(
            Known("hallmark_hits", "HLIVE_HMH", "Non-Stop Hallmark Hits", "9001", "hits"),
            Known("hallmark_romcoms", "HLIVE_ROMCOM", "Non-Stop Rom-Coms", "9002", "nsrc"),
            Known("hallmark_christmas", "HLIVE_NSC", "Non-Stop Christmas", "9003", "christmas"),
            Known("hallmark_mysteries", "HLIVE_MSM", "Non-Stop Mysteries", "9004", "mysteries"),
        )
    }
}

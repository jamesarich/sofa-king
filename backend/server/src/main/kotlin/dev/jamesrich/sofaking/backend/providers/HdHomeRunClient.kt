package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.FeedParser
import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.time.Duration
import kotlin.random.Random

/**
 * An HDHomeRun tuner on the LAN, read the way Plex and the HDHomeRun app read it: `discover.json` for the device
 * and its `DeviceAuth`, `lineup.json` for the over-the-air channels, and each channel's own MPEG-TS stream URL on
 * the device (`/auto/vN.N`), which the relay shares between players. The guide is SiliconDust's XMLTV for the
 * device's lineup, three days without an account, fetched about once a day as its terms ask and remapped onto the
 * lineup's channel numbers. Channels the device marks DRM are left out.
 */
class HdHomeRunClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "hdhomerun"
    override val protocol: String get() = "mpegts"

    private val base = requireNotNull(
        context.options["url"]?.trimEnd('/')?.takeIf {
            it.startsWith("http://") ||
                it.startsWith("https://")
        },
    ) {
        "The hdhomerun service needs options.url, the tuner's address such as http://192.168.1.50"
    }
    private val transcode = context.options["transcode"]?.takeIf { it.matches(Regex("[a-z0-9]{1,32}")) }
    private val guideApi = context.options["guideApi"] ?: GUIDE_API

    /** Guide downloads spread over the day, as the guide service asks, so every install picks its own interval once. */
    private val guideTtl: Duration = GUIDE_TTL_MIN.plusMinutes(Random.nextLong(0, GUIDE_TTL_SPREAD.toMinutes()))

    private class Listed(val channel: ProviderChannel, val url: String)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { GuideChannel(it.channel.id, it.channel.name, it.channel.logo) }
        val parsed = context.cached(GUIDE, guideTtl) { fetchGuide() }
        val byNumber = channels.associate { it.channel.id to it.channel }
        val remap = HashMap<String, String>()
        for (channel in parsed.channels) {
            val number = byNumber.keys.firstOrNull { number ->
                channel.id == number || channel.id.endsWith("-$number") || channel.id.endsWith("_$number") ||
                    channel.id.endsWith(":$number") ||
                    channel.displayName == number ||
                    channel.displayName.equals(byNumber.getValue(number).name, ignoreCase = true)
            } ?: continue
            remap.putIfAbsent(channel.id, number)
        }
        val now = context.clock.instant()
        val programs = parsed.programs.mapNotNull { program ->
            val number = remap[program.channelId] ?: return@mapNotNull null
            if (number !in wanted || program.endsAt < now) null else program.copy(channelId = number)
        }
        return ParsedGuide(index, programs)
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val url = listed().firstOrNull { it.channel.id == channelId }?.url
            ?: run {
                context.invalidate(LINEUP)
                listed().firstOrNull { it.channel.id == channelId }?.url
            }
            ?: throw ProviderFailure("Channel is no longer in the tuner lineup", transient = false)
        val stream = if (transcode == null) url else url + (if ('?' in url) "&" else "?") + "transcode=$transcode"
        return ResolvedStream(stream)
    }

    private suspend fun listed(): List<Listed> = context.cached(LINEUP, CACHED_FOR) { fetchLineup() }

    private suspend fun fetchLineup(): List<Listed> {
        val rows = context.http.get("$base/lineup.json").require("HDHomeRun lineup").json().array
        val result = LinkedHashMap<String, Listed>()
        for (row in rows) {
            val number = row.str("GuideNumber") ?: continue
            val name = row.str("GuideName") ?: number
            val url = httpOrNull(row.str("URL")) ?: continue
            val tags = row.str("Tags")?.lowercase()?.split(',')?.map { it.trim() }.orEmpty()
            if (row["DRM"].long == 1L || "drm" in tags || result.containsKey(number)) continue
            result[number] = Listed(ProviderChannel(number, name, number, null, "Local", listOf("ota")), url)
        }
        if (result.isEmpty()) throw ProviderFailure("Tuner lineup is empty: scan for channels on the device")
        return result.values.toList()
    }

    private suspend fun fetchGuide(): ParsedGuide {
        val discover = context.http.get("$base/discover.json").require("HDHomeRun discover").json()
        val auth =
            discover.str("DeviceAuth")
                ?: throw ProviderFailure("Tuner reports no DeviceAuth, so its guide cannot be fetched")
        val fetched = context.http.get(
            "$guideApi?DeviceAuth=${URLEncoder.encode(auth, Charsets.UTF_8)}",
            GUIDE_HEADERS,
        ).require("HDHomeRun guide")
        return runCatchingCancellable {
            ByteArrayInputStream(fetched.body).use { FeedParser.guide(it, guideApi) { true } }
        }.getOrElse { throw it as? ProviderFailure ?: ProviderFailure("HDHomeRun guide is not XMLTV", cause = it) }
    }

    companion object {
        private const val GUIDE_API = "https://api.hdhomerun.com/api/xmltv"
        private const val LINEUP = "lineup"

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(55)
        private const val GUIDE = "guide"
        private val GUIDE_TTL_MIN = Duration.ofHours(20)
        private val GUIDE_TTL_SPREAD = Duration.ofHours(8)
        private val GUIDE_HEADERS = mapOf("Accept-Encoding" to "gzip")
    }
}

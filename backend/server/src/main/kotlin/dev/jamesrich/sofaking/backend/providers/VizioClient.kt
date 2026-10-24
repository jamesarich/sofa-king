package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Vizio WatchFree+ as the SmartCast app reads it: an open channel list whose clear HLS URLs carry `{ADID}`-style
 * ad macros (percent-encoded in the payload), and a 24-hour airings feed keyed by station id. Channels with a
 * `tokenUrl` need a device entitlement and are left out.
 */
class VizioClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "vizio"

    private val apiBase = context.options["apiBase"] ?: API_BASE

    private class Listed(val channel: ProviderChannel, val template: String, val airingsKey: String?)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { GuideChannel(it.channel.id, it.channel.name, it.channel.logo) }
        if (channels.none { it.channel.id in wanted }) return ParsedGuide(index, emptyList())
        val byStation = channels.filter { it.airingsKey != null && it.channel.id in wanted }.associate {
            it.airingsKey!! to
                it.channel.id
        }
        val now = context.clock.instant()
        val url = "$apiBase/airings/?start=${TIMESTAMP.format(now)}&end=${TIMESTAMP.format(now.plus(EPG_HORIZON))}&" +
            "startChannel=${channels.first().channel.id}&channelCount=${channels.size}"
        val airings = context.http.get(url, HEADERS).require("Vizio airings").json()["airings"].array
        return ParsedGuide(index, airings.mapNotNull { airing(it, byStation) })
    }

    /** An airing on a wanted channel, found by its station in [byStation]; null when a field is missing. */
    private fun airing(airing: JsonElement, byStation: Map<String, String>): GuideProgram? {
        val channelId = airing.str("stationId")?.let(byStation::get) ?: return null
        val title = airing.str("title") ?: return null
        val start = airing.str("timeStart")?.let(::instant) ?: return null
        val end = airing.str("timeEnd")?.let(::instant)?.takeIf { it > start } ?: return null
        return GuideProgram(
            channelId,
            start,
            end,
            title,
            airing.str("description"),
            subTitle = airing.str("subTitle")?.takeIf { it != title },
            episodeNumbers = seasonEpisode(airing["seasonNumber"].long, airing["episodeNumber"].long),
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val template = context.findListed(CHANNELS) { listed().firstOrNull { it.channel.id == channelId }?.template }
        val url = Macros.fill(template, MACROS + ("ADID" to context.deviceId()))
        return ResolvedStream(url, HEADERS, context.clock.instant().plus(URL_LIFETIME))
    }

    private suspend fun listed(): List<Listed> = context.cached(CHANNELS, CACHED_FOR) { fetchChannels() }

    private suspend fun fetchChannels(): List<Listed> {
        val payload = context.http.get("$apiBase/channels", HEADERS).require("Vizio channels").json()
        val result = payload["channels"].array.mapNotNull(::listing).distinctBy { it.channel.id }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result
    }

    /** A listed channel with a plain stream template; null for a tokenized one or when a field is missing. */
    private fun listing(channel: JsonElement): Listed? {
        val id = channel.str("channelId") ?: return null
        val name = channel.str("channelName") ?: return null
        if (channel.str("tokenUrl") != null) return null
        val template = (channel["channelUrls"][0].string ?: channel.str("channelUrl"))
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return null
        val logo =
            httpOrNull(channel.str("channelIcon")) ?: httpOrNull(channel.str("portraitIcon"))
                ?: httpOrNull(channel.str("bwIcon"))
        // FEATURED slots keep their id while the program in them rotates; the tag tells
        // curation apart from a station.
        val tags = listOf("us") + if (id.uppercase().startsWith("FEATURED")) listOf("featured") else emptyList()
        return Listed(
            ProviderChannel(
                id,
                name,
                channel["channelNumber"].long?.toString(),
                logo,
                channel.str("category")?.lowercase()?.replaceFirstChar(Char::uppercase),
                tags,
            ),
            template,
            channel.str("airingsKey"),
        )
    }

    private fun instant(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(value).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val API_BASE = "https://watchfreeplus-epg-prod.smartcasttv.com/api"
        private const val CHANNELS = "channels"

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(10)
        private val EPG_HORIZON = Duration.ofHours(24)
        private val URL_LIFETIME = Duration.ofHours(1)
        private val TIMESTAMP = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
        ).withZone(java.time.ZoneOffset.UTC)
        private val HEADERS = mapOf("User-Agent" to "okhttp/4.12.0", "Accept" to "application/json")

        /** The values the anonymous SmartCast mobile client sends; `ADID` is this install's device id. */
        private val MACROS = mapOf(
            "USPRIVACY" to "1---", "IFATYPE" to "aaid", "LMT" to "0", "TARGETOPT" to "False", "APP_NAME" to "VIZIO",
            "APP_BUNDLE" to "com.vizio.vue.launcher",
            "APP_STORE_URL" to "https://play.google.com/store/apps/details?id=com.vizio.vue.launcher",
            "DOMAIN" to "https://www.vizio.com", "DNT" to "0", "COPPA" to "0", "DEVICE_MAKE" to "Google",
            "WIDTH" to "1080", "HEIGHT" to "1920",
            "DEVICE_MODEL" to "Pixel 7", "APP_VERSION" to "5.0.0", "DEVICE_TYPE" to "mobile", "SKIPPABLE" to "1",
            "IS_LAT" to "0", "GDPR" to "0",
        )
    }
}

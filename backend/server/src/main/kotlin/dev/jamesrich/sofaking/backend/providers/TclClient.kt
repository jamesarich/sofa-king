package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * TCL TV+ as tcltv.plus uses it: the `livetab` category list, then one `programlist/by/category` request per
 * category, which carries every channel with its ad-stitched HLS template and its programmes for the requested
 * window, so the channel list and the guide come from the same requests. A resolve has the service's
 * `format-stream-url` fill the template, as its own client does. Channels with `drm` are left out.
 */
class TclClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "tcl"

    private val apiBase = context.options["apiBase"] ?: API_BASE

    private class Listed(val channel: ProviderChannel, val template: String, val source: String?)

    private class Snapshot(val channels: List<Listed>, val programmes: List<GuideProgramme>)

    override suspend fun channels(): List<ProviderChannel> = snapshot().channels.map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val snapshot = snapshot()
        return ParsedGuide(
            snapshot.channels.map {
                GuideChannel(it.channel.id, it.channel.name, it.channel.logo)
            },
            snapshot.programmes.filter {
                it.channelId in
                    wanted
            },
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val listed = snapshot().channels.firstOrNull { it.channel.id == channelId }
            ?: run {
                context.invalidate(SNAPSHOT)
                snapshot().channels.firstOrNull { it.channel.id == channelId }
            }
            ?: throw ProviderFailure("Channel is no longer listed", transient = false)
        val deviceId = context.deviceId()
        val request = buildJsonObject {
            put("type", "channel")
            put("bundle_id", channelId)
            put("device_id", deviceId)
            put("source", listed.source)
            put("stream_url", listed.template)
        }
        // The service fills its own macros and percent-encodes literal brackets such as Amagi's `device[did]`,
        // which a bracket macro would otherwise eat; only the brace forms it leaves are filled here.
        val formatted =
            context.http.post(
                "$apiBase/api/metadata/v1/format-stream-url?country_code=US&app_version=3.2.7",
                HEADERS,
                request.toString(),
            )
                .require("TCL stream format").json().str("stream_url")
                ?: throw ProviderFailure("TCL answered no stream URL")
        val values = mapOf(
            "IFA" to deviceId, "DID" to deviceId, "DEVICE_ID" to deviceId, "US_PRIVACY" to "1---",
            "UsPrivacy" to "1---",
            "LMT" to "0", "DNT" to "0", "GDPR" to "0", "COPPA" to "0", "PLAYER_WIDTH" to "1920",
            "PLAYER_HEIGHT" to "1080",
            "playerWidth" to "1920", "playerHeight" to "1080", "LANGUAGE" to "en",
        )
        return ResolvedStream(
            Macros.fill(formatted, values),
            STREAM_HEADERS,
            context.clock.instant().plus(URL_LIFETIME),
        )
    }

    private suspend fun snapshot(): Snapshot = context.cached(SNAPSHOT, Duration.ofMinutes(55)) { fetch() }

    private suspend fun fetch(): Snapshot {
        val deviceId = URLEncoder.encode(context.deviceId(), Charsets.UTF_8)
        val common = "userId=$deviceId&device_type=web&device_model=web&device_id=$deviceId&app_version=1.0&" +
            "country_code=US&state_code=OH"
        val lines = context.http.get(
            "$apiBase/api/metadata/v2/livetab?$common",
            HEADERS,
        ).require("TCL categories").json()["lines"].array
            .filter { it.str("type") != "recommend" && it.str("id") != null }
        val start = context.clock.instant().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.HOURS)
        val window = "start=$start&end=${start.plus(GUIDE_HORIZON)}"
        val channels = LinkedHashMap<String, Listed>()
        val programmes = LinkedHashMap<Pair<String, Instant>, GuideProgramme>()
        // Curated rows are read last, so a channel's group is the first themed row that lists it.
        for (line in lines.filterNot { it.str("name") in CURATED } + lines.filter { it.str("name") in CURATED }) {
            val group = line.str("name")?.takeUnless { it in CURATED }
            val payload = context.http.get(
                "$apiBase/api/metadata/v1/epg/programlist/by/category?$common&category_id=${line.str("id")}&$window",
                HEADERS,
            )
                .require("TCL category").json()
            for (channel in payload["channels"].array) {
                val id = channel.str("bundle_id") ?: channel.str("id") ?: continue
                val name = channel.str("name") ?: continue
                val template = channel.str("media") ?: continue
                if (channel["drm"] != null || channels.containsKey(id)) continue
                val logo = (channel.str("logo_color") ?: channel.str("logo_white"))?.let {
                    if (it.startsWith("/")) "$IMAGE_BASE$it" else it
                }
                channels[id] =
                    Listed(
                        ProviderChannel(id, name, null, httpOrNull(logo), group, listOf("us")),
                        template,
                        channel.str("source"),
                    )
                for (programme in channel["programs"].array) {
                    val from = programme.str("start")?.let(::instantOrNull) ?: continue
                    val until = programme.str("end")?.let(::instantOrNull) ?: continue
                    val title = programme.str("title") ?: continue
                    if (until > from) programmes.putIfAbsent(id to from, GuideProgramme(id, from, until, title))
                }
            }
        }
        if (channels.isEmpty()) throw ProviderFailure("Channel list is empty")
        return Snapshot(channels.values.toList(), programmes.values.toList())
    }

    companion object {
        private const val API_BASE = "https://gateway-prod.ideonow.com"
        private const val IMAGE_BASE = "https://tcl-channel-cdn.ideonow.com"
        private const val SNAPSHOT = "snapshot"
        private val GUIDE_HORIZON = Duration.ofHours(12)
        private val URL_LIFETIME = Duration.ofHours(1)
        private val CURATED = setOf("TCL TOP 15", "Featured", "Bingeable", "TV Favorites")
        private val STREAM_HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Origin" to "https://tcltv.plus",
            "Referer" to "https://tcltv.plus/",
        )
        private val HEADERS = STREAM_HEADERS + ("Accept" to "application/json, text/plain, */*")
    }
}

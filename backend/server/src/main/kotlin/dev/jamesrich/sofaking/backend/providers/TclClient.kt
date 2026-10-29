package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * TCL TV+ as tcltv.plus uses it: the `livetab` category list, then one `programlist/by/category` request per
 * category, which carries every channel with its ad-stitched HLS template and its programs for the requested
 * window, so the channel list and the guide come from the same requests. A resolve has the service's
 * `format-stream-url` fill the template, as its own client does. Channels with `drm` are left out.
 */
class TclClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "tcl"

    private val apiBase = context.options["apiBase"] ?: API_BASE

    private class Listed(val channel: ProviderChannel, val template: String, val source: String?)

    private class Snapshot(val channels: List<Listed>, val programs: List<GuideProgram>)

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

    private suspend fun snapshot(): Snapshot = context.cached(SNAPSHOT, CACHED_FOR) { fetch() }

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
        val programs = LinkedHashMap<Pair<String, Instant>, GuideProgram>()
        // Curated rows are read last, so a channel's group is the first themed row that lists it.
        for (line in lines.filterNot { it.str("name") in CURATED } + lines.filter { it.str("name") in CURATED }) {
            val group = line.str("name")?.takeUnless { it in CURATED }
            val payload = context.http.get(
                "$apiBase/api/metadata/v1/epg/programlist/by/category?$common&category_id=${line.str("id")}&$window",
                HEADERS,
            )
                .require("TCL category").json()
            for (channel in payload["channels"].array) {
                val listed = listing(channel, group)
                if (listed == null || channels.containsKey(listed.channel.id)) continue
                val id = listed.channel.id
                channels[id] = listed
                for (item in channel["programs"].array) {
                    val program = guideProgram(
                        id,
                        item.str("start")?.let(::instantOrNull),
                        item.str("end")?.let(::instantOrNull),
                        item.str("title"),
                    )
                    if (program != null) programs.putIfAbsent(id to program.startsAt, program)
                }
            }
        }
        requireChannels(channels)
        return Snapshot(channels.values.toList(), programs.values.toList())
    }

    /** A clear channel in [group]; null for a DRM one or when a field is missing. */
    private fun listing(channel: JsonElement, group: String?): Listed? {
        val id = channel.str("bundle_id") ?: channel.str("id") ?: return null
        val name = channel.str("name") ?: return null
        val template = channel.str("media") ?: return null
        if (channel["drm"] != null) return null
        val logo = (channel.str("logo_color") ?: channel.str("logo_white"))?.let {
            if (it.startsWith("/")) "$IMAGE_BASE$it" else it
        }
        return Listed(
            ProviderChannel(id, name, null, httpOrNull(logo), group, listOf("us")),
            template,
            channel.str("source"),
        )
    }

    companion object {
        private const val API_BASE = "https://gateway-prod.ideonow.com"
        private const val IMAGE_BASE = "https://tcl-channel-cdn.ideonow.com"
        private const val SNAPSHOT = "snapshot"

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(55)
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

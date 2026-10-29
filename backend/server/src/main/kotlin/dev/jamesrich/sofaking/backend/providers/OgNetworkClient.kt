package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import kotlinx.serialization.json.JsonElement
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * OG Network as its web app plays it: a channel list from the site's own `catalog.json`, then Sotal Cloud's SDP
 * with the web app's public client id. An anonymous `authorize` answers a token that is sent as `x-auth-token`
 * and kept in state; a resolve reads the asset's `hls-live` resource and opens a playback session whose clear
 * HLS URL carries a ticket good for about ten minutes, so it is made per tune. The guide is the one `epg-events`
 * call for every channel, mapped back to assets by `assets?$expand=epg`. A few channels serve playlists with no
 * segments; a resolve refuses those rather than hand the player a stream that cannot start.
 */
class OgNetworkClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "ognetwork"

    private val catalogUrl = context.options["catalogUrl"] ?: CATALOG_URL
    private val apiBase = context.options["apiBase"] ?: API_BASE

    override suspend fun channels(): List<ProviderChannel> = listed()

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { it.toGuideChannel() }
        val targets = channels.map { it.id }.filter { it in wanted }.toSet()
        if (targets.isEmpty()) return ParsedGuide(index, emptyList())
        val byEpg = authorized("OG Network assets") { token ->
            context.http.get("$apiBase/assets?\$expand=epg", headers(token))
        }.json()["data"].array.mapNotNull { asset ->
            val id = asset.long("id")?.toString()?.takeIf { it in targets } ?: return@mapNotNull null
            asset["epg"].long("id")?.let { it to id }
        }.toMap()
        val rows = authorized("OG Network guide") { token ->
            context.http.get("$apiBase/epg-events?\$expand=channel", headers(token))
        }.json()["data"].array
        return ParsedGuide(index, programs(rows, byEpg, context.clock.instant()))
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        context.findListed(CHANNELS) { listed().firstOrNull { it.id == channelId } }
        val asset = authorized("OG Network asset") { token ->
            context.http.get("$apiBase/assets/${urlEncoded(channelId)}?\$expand=resources", headers(token))
        }.json()["data"]
        val live = asset["resources"].array.firstOrNull { it.str("type") == "hls-live" }?.long("id")
        val session = authorized("OG Network playback") { token ->
            context.http.post(
                "$apiBase/assets/${urlEncoded(channelId)}/resources/${live ?: noStream()}/playback-sessions",
                headers(token),
                "{}",
            )
        }.json()["data"]
        val url = httpOrNull(session.str("playback_url")) ?: throw ProviderFailure("Playback session has no URL")
        requireSegments(url)
        return ResolvedStream(url, STREAM_HEADERS, context.clock.instant().plus(TICKET_LIFETIME))
    }

    private fun noStream(): Nothing = throw ProviderFailure("Channel has no live stream", transient = false)

    /**
     * Fails permanently when the stream's first playlist definitely lists no segments; a probe that fails or finds a
     * playlist of another shape lets the URL through for the player to try.
     */
    private suspend fun requireSegments(url: String) {
        val empty = runCatchingCancellable {
            val master = context.http.get(url, STREAM_HEADERS)
            val text = master.text()
            val variant = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            if (!master.ok) {
                false
            } else if (variant == null) {
                "#EXTINF" !in text && "#EXT-X-STREAM-INF" in text
            } else {
                val media = context.http.get(URI(master.url).resolve(variant).toString(), STREAM_HEADERS)
                media.ok && "#EXTINF" !in media.text()
            }
        }.getOrDefault(false)
        if (empty) throw ProviderFailure("Channel is serving an empty playlist", transient = false)
    }

    private suspend fun listed(): List<ProviderChannel> = context.cached(CHANNELS, CACHED_FOR) { fetchChannels() }

    private suspend fun fetchChannels(): List<ProviderChannel> {
        val payload = context.http.get("$catalogUrl?t=${context.clock.instant().toEpochMilli()}", STREAM_HEADERS)
            .require("OG Network catalog", gone = emptySet()).json()
        val result = payload["channels"].array.mapNotNull(::listing).distinctBy { it.id }
        requireChannels(result)
        return result
    }

    /** Runs [request] with the stored token; a refused one is dropped and the request tried once more afresh. */
    private suspend fun authorized(what: String, request: suspend (String) -> Fetched): Fetched {
        val first = request(token())
        if (!first.refused) return first.require(what)
        context.clearState(TOKEN)
        return request(token()).require(what)
    }

    private suspend fun token(): String {
        context.state(TOKEN)?.let { return it }
        val deviceId = context.deviceId().replace("-", "").take(DEVICE_ID_LENGTH)
        val query = "client_id=${urlEncoded(CLIENT_ID)}&grant_type=client_credentials&device_id=$deviceId"
        val token = context.http.post("$apiBase/authorize?$query", STREAM_HEADERS, "{}")
            .require("OG Network authorize").json()["data"].string
            ?: throw ProviderFailure("Authorize answered no token")
        context.saveState(TOKEN, token, context.clock.instant().plus(TOKEN_LIFETIME))
        return token
    }

    private fun headers(token: String) = STREAM_HEADERS + mapOf("Accept" to "application/json", "x-auth-token" to token)

    companion object {
        private const val CATALOG_URL = "https://play.theognetwork.net/catalog.json"
        private const val API_BASE = "https://discovery-ams.sotalcloud.com/sdp/v2"

        /** The web app's public client id. */
        private const val CLIENT_ID = "cloud:ognetwork_ottweb_device"
        private const val CHANNELS = "channels"
        private const val TOKEN = "token"
        private const val DEVICE_ID_LENGTH = 8

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(30)

        /** The token carries no expiry; a new one is taken now and then, and on any refusal. */
        private val TOKEN_LIFETIME = Duration.ofHours(6)

        /** A playback ticket lasts about ten minutes; resolves are cached until just short of that. */
        private val TICKET_LIFETIME = Duration.ofMinutes(9)
        private val STREAM_HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Origin" to "https://play.theognetwork.net",
            "Referer" to "https://play.theognetwork.net/",
        )
    }
}

private val PLACEHOLDERS = setOf("Movie", "Show", "TBD")

private fun JsonElement?.long(key: String): Long? = this[key].long

/** A catalog entry of type `channel`, grouped by its first genre after `Live TV`; null when a field is missing. */
private fun listing(entry: JsonElement): ProviderChannel? {
    if (entry.str("type") != "channel") return null
    val id = entry.long("assetId")?.toString() ?: return null
    val title = entry.str("title") ?: return null
    val group = entry["genres"].array.mapNotNull { it.string }.firstOrNull { it != "Live TV" }
    return ProviderChannel(id, title, null, httpOrNull(entry.str("posterImg")), group)
}

/** One guide row kept for the channel it airs on; only rows still ahead of the clock are kept. */
private class OgAiring(val id: Long, val channelId: String, val start: Instant, val end: Instant, val row: JsonElement)

/** The row as an [OgAiring] on a channel [byEpg] maps, ending after [now]; null when a field is missing. */
private fun airing(row: JsonElement, byEpg: Map<Long, String>, now: Instant): OgAiring? {
    val channelId = byEpg[row["channel"].long("id")] ?: return null
    val start = row.str("start_time")?.let(::instantOrNull) ?: return null
    val end = row.str("end_time")?.let(::instantOrNull)?.takeIf { it > now } ?: return null
    return OgAiring(row.long("id") ?: 0, channelId, start, end, row)
}

/**
 * The airings in [rows] still on or ahead of [now] on the channels [byEpg] maps. A channel can carry two syncs'
 * schedules interleaved, so on a shared start the newer row (higher id) wins and each program ends by the next
 * one's start.
 */
private fun programs(rows: List<JsonElement>, byEpg: Map<Long, String>, now: Instant): List<GuideProgram> {
    val newest = HashMap<Pair<String, Instant>, OgAiring>()
    for (airing in rows.mapNotNull { airing(it, byEpg, now) }) {
        newest.merge(airing.channelId to airing.start, airing) { old, new -> if (new.id >= old.id) new else old }
    }
    return newest.values.groupBy { it.channelId }.values.flatMap { airings ->
        val ordered = airings.sortedBy { it.start }
        ordered.mapIndexedNotNull { at, airing ->
            val end = minOf(airing.end, ordered.getOrNull(at + 1)?.start ?: airing.end)
            val blurb = (airing.row.str("description") ?: airing.row.str("episode_description"))
                ?.takeUnless { it in PLACEHOLDERS }
            guideProgram(airing.channelId, airing.start, end, airing.row.str("name"), blurb)
        }
    }
}

package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Pluto TV as pluto.tv uses it: `boot/v4/start` with the install's device id as client id answers a session JWT,
 * the stitcher host and its query parameters; the channels service lists channels and categories and serves the
 * guide as timelines; a resolve is the channel's stitcher path with those parameters and the JWT. Channels entitled
 * only to registered users are left out. Pluto picks the lineup by the caller's address, so another country's lineup
 * takes the `region` and `forwardedFor` options ([RegionOptions]).
 */
class PlutoClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "pluto"

    private val bootUrl = context.options["bootUrl"] ?: BOOT_URL
    private val regional = RegionOptions(context.options)

    private class Listed(val channel: ProviderChannel, val path: String)

    private class Session(val token: String, val channels: String, val stitcher: String, val params: String)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { GuideChannel(it.channel.id, it.channel.name, it.channel.logo) }
        val start = context.clock.instant().truncatedTo(ChronoUnit.HOURS)
        val programs = ArrayList<GuideProgram>()
        for (batch in channels.map { it.channel.id }.filter { it in wanted }.chunked(GUIDE_BATCH)) {
            val data = authorized { session ->
                context.http.get(
                    "${session.channels}/v2/guide/timelines?start=$start&channelIds=${batch.joinToString(",")}&" +
                        "duration=$GUIDE_MINUTES",
                    headers(session),
                )
            }.json()["data"].array
            for (row in data) {
                val id = row.str("channelId") ?: continue
                for (item in row["timelines"].array) {
                    val from = item.str("start")?.let(::instantOrNull) ?: continue
                    val until = item.str("stop")?.let(::instantOrNull) ?: continue
                    val title = item.str("title") ?: continue
                    if (until > from) programs += program(id, from, until, title, item["episode"])
                }
            }
        }
        return ParsedGuide(index, programs)
    }

    /**
     * A timeline entry and its `episode`: the episode name, season and number, genres, rating and
     * poster; a `film` series is a movie.
     */
    private fun program(id: String, from: Instant, until: Instant, title: String, episode: JsonElement?): GuideProgram {
        val movie = episode["series"].str("type") == "film"
        return GuideProgram(
            id, from, until, title, episode.str("description"),
            subTitle = episode.str("name")?.takeIf { !movie && !it.equals(title, ignoreCase = true) },
            episodeNumbers = if (movie) emptyList() else seasonEpisode(episode["season"].long, episode["number"].long),
            icon = httpOrNull(episode["poster"].str("path")),
            categories = listOfNotNull(if (movie) "Movie" else null, episode.str("genre"), episode.str("subGenre")),
            rating = usRating(episode.str("rating")?.takeUnless { it.equals("Not Rated", ignoreCase = true) }),
        )
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val path = listed().firstOrNull { it.channel.id == channelId }?.path
            ?: run {
                context.invalidate(CHANNELS)
                listed().firstOrNull { it.channel.id == channelId }?.path
            }
            ?: throw ProviderFailure("Channel is no longer listed", transient = false)
        val session = session()
        val url = "${session.stitcher}/v2$path?${session.params}&jwt=${session.token}&masterJWTPassthrough=true&" +
            "includeExtendedEvents=true"
        return ResolvedStream(url, streamHeaders(), context.clock.instant().plus(URL_LIFETIME))
    }

    private suspend fun listed(): List<Listed> = context.cached(CHANNELS, Duration.ofMinutes(30)) { fetchChannels() }

    private suspend fun fetchChannels(): List<Listed> {
        val payload = authorized { session ->
            context.http.get(
                "${session.channels}/v2/guide/channels?channelIds=&offset=0&limit=1000&sort=number:asc",
                headers(session),
            )
        }.json()
        // A failed fetch keeps the last categories, since a lost group moves a station's number.
        val categories = context.lastGood(
            CATEGORIES,
            runCatchingCancellable {
                authorized { session ->
                    context.http.get("${session.channels}/v2/guide/categories?offset=0&limit=1000", headers(session))
                }.json()["data"].array
                    .mapNotNull { category ->
                        category.str("id")?.let { id -> category.str("name")?.let { id to it } }
                    }.toMap()
            },
        )
        val result = LinkedHashMap<String, Listed>()
        for (channel in payload["data"].array) {
            val id = channel.str("id") ?: continue
            val name = channel.str("name") ?: continue
            val path = channel["stitched"].str("path") ?: continue
            if (channel["entitlements"].array.isNotEmpty() || result.containsKey(id)) continue
            val logo = channel["images"].array.firstOrNull { it.str("type") == "colorLogoPNG" }.str("url")
            val group = channel["categoryIDs"].array.firstNotNullOfOrNull { categories[it.string] }
            result[id] =
                Listed(ProviderChannel(id, name, channel.str("number"), httpOrNull(logo), group, regional.tags), path)
        }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result.values.toList()
    }

    /** Runs [request] in the stored session; a 401 or 403 boots a new one and tries once more. */
    private suspend fun authorized(request: suspend (Session) -> Fetched): Fetched {
        val first = request(session())
        if (first.status != 401 && first.status != 403) return first.require("Pluto API")
        context.clearState(SESSION)
        return request(session()).require("Pluto API")
    }

    private suspend fun session(): Session {
        context.state(SESSION)?.let { stored ->
            val value = runCatching { lenientJson.parseToJsonElement(stored) }.getOrNull()
            val token = value.str("token")
            val channels = value.str("channels")
            val stitcher = value.str("stitcher")
            val params = value.str("params")
            if (token != null && channels != null && stitcher != null && params != null) {
                return Session(token, channels, stitcher, params)
            }
        }
        val now = context.clock.instant()
        val clientId = URLEncoder.encode(context.deviceId(), Charsets.UTF_8)
        val query =
            "appName=web&appVersion=$APP_VERSION&deviceVersion=122.0.0&deviceModel=web&deviceMake=chrome&" +
                "deviceType=web" +
                "&clientModelNumber=1.0.0&serverSideAds=false&drmCapabilities=&blockingMode=&notificationVersion=1&" +
                "appLaunchCount=&lastAppLaunchDate=&clientID=$clientId&" +
                "clientTime=${URLEncoder.encode(now.truncatedTo(ChronoUnit.SECONDS).toString(), Charsets.UTF_8)}"
        val boot = context.http.get(
            "$bootUrl?$query",
            streamHeaders() + ("Accept" to "application/json"),
        ).require("Pluto boot").json()
        val session = Session(
            boot.str("sessionToken") ?: throw ProviderFailure("Pluto boot answered no session"),
            boot["servers"].str("channels") ?: CHANNELS_BASE,
            boot["servers"].str("stitcher") ?: throw ProviderFailure("Pluto boot answered no stitcher"),
            boot.str("stitcherParams") ?: throw ProviderFailure("Pluto boot answered no stitcher parameters"),
        )
        val stored =
            buildJsonObject {
                put("token", session.token)
                put("channels", session.channels)
                put("stitcher", session.stitcher)
                put("params", session.params)
            }
        context.saveState(SESSION, stored.toString(), now.plus(SESSION_LIFETIME))
        return session
    }

    private fun headers(session: Session) =
        streamHeaders() + mapOf("Accept" to "application/json", "Authorization" to "Bearer ${session.token}")

    /** What every request sends: the web client's headers, and with `forwardedFor` the region's address. */
    private fun streamHeaders() = STREAM_HEADERS + regional.headers

    companion object {
        private const val BOOT_URL = "https://boot.pluto.tv/v4/start"
        private const val CHANNELS_BASE = "https://service-channels.clusters.pluto.tv"
        private const val APP_VERSION = "8.0.0-111b2b9dc00bd0bea9030b30662159ed9e7c8bc6"
        private const val CHANNELS = "channels"
        private const val SESSION = "session"
        private const val CATEGORIES = "categories"
        private const val GUIDE_BATCH = 25
        private const val GUIDE_MINUTES = 720
        private val SESSION_LIFETIME = Duration.ofHours(4)
        private val URL_LIFETIME = Duration.ofHours(1)
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"
        private val STREAM_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to "https://pluto.tv",
            "Referer" to "https://pluto.tv/",
        )
    }
}

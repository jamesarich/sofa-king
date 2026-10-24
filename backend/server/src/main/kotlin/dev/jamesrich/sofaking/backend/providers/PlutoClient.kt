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
import java.time.temporal.ChronoUnit

private class Session(val token: String, val channels: String, val stitcher: String, val params: String)

/** The session saved as JSON, or null when any part of it is missing. */
private fun storedSession(value: JsonElement?): Session? {
    val token = value.str("token") ?: return null
    val channels = value.str("channels") ?: return null
    val stitcher = value.str("stitcher") ?: return null
    val params = value.str("params") ?: return null
    return Session(token, channels, stitcher, params)
}

/** A part of the session the boot answer must hold. */
private fun booted(value: String?, what: String): String =
    value ?: throw ProviderFailure("Pluto boot answered no $what")

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
                row["timelines"].array.mapNotNullTo(programs) { program(id, it) }
            }
        }
        return ParsedGuide(index, programs)
    }

    /**
     * A timeline entry and its `episode`: the episode name, season and number, genres, rating and
     * poster; a `film` series is a movie. Null when a field is missing.
     */
    private fun program(id: String, item: JsonElement): GuideProgram? {
        val from = item.str("start")?.let(::instantOrNull) ?: return null
        val until = item.str("stop")?.let(::instantOrNull)?.takeIf { it > from } ?: return null
        val title = item.str("title") ?: return null
        val episode = item["episode"]
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
        val path = context.findListed(CHANNELS) { listed().firstOrNull { it.channel.id == channelId }?.path }
        val session = session()
        val url = "${session.stitcher}/v2$path?${session.params}&jwt=${session.token}&masterJWTPassthrough=true&" +
            "includeExtendedEvents=true"
        return ResolvedStream(url, streamHeaders(), context.clock.instant().plus(URL_LIFETIME))
    }

    private suspend fun listed(): List<Listed> = context.cached(CHANNELS, CACHED_FOR) { fetchChannels() }

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
        val result = payload["data"].array.mapNotNull { listing(it, categories) }.distinctBy { it.channel.id }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result
    }

    /** A free channel, in its first category named in [categories]; null when a field is missing. */
    private fun listing(channel: JsonElement, categories: Map<String, String>): Listed? {
        val id = channel.str("id") ?: return null
        val name = channel.str("name") ?: return null
        val path = channel["stitched"].str("path") ?: return null
        if (channel["entitlements"].array.isNotEmpty()) return null
        val logo = channel["images"].array.firstOrNull { it.str("type") == "colorLogoPNG" }.str("url")
        val group = channel["categoryIDs"].array.firstNotNullOfOrNull { categories[it.string] }
        return Listed(ProviderChannel(id, name, channel.str("number"), httpOrNull(logo), group, regional.tags), path)
    }

    /** Runs [request] in the stored session; a 401 or 403 boots a new one and tries once more. */
    private suspend fun authorized(request: suspend (Session) -> Fetched): Fetched {
        val first = request(session())
        if (!first.refused) return first.require("Pluto API")
        context.clearState(SESSION)
        return request(session()).require("Pluto API")
    }

    private suspend fun session(): Session {
        context.state(SESSION)?.let { stored ->
            storedSession(runCatching { lenientJson.parseToJsonElement(stored) }.getOrNull())?.let { return it }
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
            booted(boot.str("sessionToken"), "session"),
            boot["servers"].str("channels") ?: CHANNELS_BASE,
            booted(boot["servers"].str("stitcher"), "stitcher"),
            booted(boot.str("stitcherParams"), "stitcher parameters"),
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

        /** How long the listing is kept before it is fetched again. */
        private val CACHED_FOR: Duration = Duration.ofMinutes(30)
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

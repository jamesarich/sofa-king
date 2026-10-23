package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.GuideChannel
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Plex's free live channels as watch.plex.tv uses them: an anonymous plex.tv token, the `plex` lineup on
 * epg.provider.plex.tv with a genre listing per guide filter, the per-channel `grid` for the guide, and a
 * `library/parts` master served directly with the token in its query.
 */
class PlexClient(private val context: ProviderContext) : ProviderClient {
    override val service: String get() = "plex"

    private val apiBase = context.options["apiBase"] ?: API_BASE
    private val authUrl = context.options["authUrl"] ?: AUTH_URL
    private val gridSpacingMillis = context.options["gridSpacingMillis"]?.toLongOrNull() ?: GRID_SPACING_MILLIS
    private val rateLimitWaitMillis = context.options["rateLimitWaitMillis"]?.toLongOrNull() ?: RATE_LIMIT_WAIT_MILLIS

    private class Listed(val channel: ProviderChannel, val partKey: String)

    override suspend fun channels(): List<ProviderChannel> = listed().map { it.channel }

    override suspend fun guide(wanted: Set<String>): ParsedGuide {
        val channels = listed()
        val index = channels.map { GuideChannel(it.channel.id, it.channel.name, it.channel.logo) }
        val today = context.clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()
        val programmes = ArrayList<GuideProgramme>()
        var first = true
        for (id in channels.map { it.channel.id }.filter { it in wanted }) {
            for (date in listOf(today, today.plusDays(1))) {
                // The grid answers 429 to a burst of a few hundred channels, so requests are spaced and a 429 waits.
                if (!first) delay(gridSpacingMillis)
                first = false
                val payload = authorized(patient = true) { token ->
                    context.http.get(
                        "$apiBase/grid?channelGridKey=$id&date=$date&X-Plex-Token=${encode(token)}",
                        headers(token) + ("X-Plex-Provider-Version" to "7.2"),
                    )
                }.json()
                for (item in payload["MediaContainer"]["Metadata"].array) {
                    val series = item.str("grandparentTitle")
                    val title = series ?: item.str("title") ?: continue
                    val episode = item.str("type") == "episode"
                    val movie = item.str("type") == "movie"
                    for (media in item["Media"].array) {
                        val start = media["beginsAt"].long ?: continue
                        val end = media["endsAt"].long ?: continue
                        if (end <= start) continue
                        programmes += GuideProgramme(
                            id, Instant.ofEpochSecond(start), Instant.ofEpochSecond(end), title, item.str("summary"),
                            subTitle = if (series != null) item.str("title") else null,
                            episodeNumbers = if (episode) {
                                seasonEpisode(
                                    item["parentIndex"].long,
                                    item["index"].long,
                                )
                            } else {
                                emptyList()
                            },
                            icon = httpOrNull(item.str("thumb") ?: item.str("grandparentThumb")),
                            categories =
                            listOfNotNull(if (movie) "Movie" else null) +
                                item["Genre"].array.mapNotNull { it.str("tag") },
                            rating = usRating(item.str("contentRating")),
                            isNew = media["premiere"].truthy,
                            date = item.str("originallyAvailableAt") ?: item.str("year"),
                        )
                    }
                }
            }
        }
        return ParsedGuide(index, programmes.distinctBy { Triple(it.channelId, it.startsAt, it.title) })
    }

    override suspend fun resolve(channelId: String): ResolvedStream {
        val key = listed().firstOrNull { it.channel.id == channelId }?.partKey
            ?: run {
                context.invalidate(CHANNELS)
                listed().firstOrNull { it.channel.id == channelId }?.partKey
            }
            ?: throw ProviderFailure("Channel is no longer listed", transient = false)
        var url = ""
        val answer = signedIn { token ->
            url = "$apiBase$key?includeAllStreams=1&X-Plex-Product=${encode(PRODUCT)}&X-Plex-Token=${encode(token)}"
            context.http.get(url, STREAM_HEADERS, limit = MANIFEST_LIMIT, follow = false)
        }
        // Some parts redirect to their CDN master. A part Plex answers 400 for is not served to anyone;
        // asking again never changes that.
        val target = if (answer.status in 300..399 && answer.location != null) {
            val location = try {
                URI(answer.url).resolve(answer.location).toString()
            } catch (_: IllegalArgumentException) {
                throw ProviderFailure("Invalid Plex redirect", transient = false)
            }
            httpOrNull(location) ?: throw ProviderFailure("Plex redirect is not HTTP", transient = false)
        } else {
            answer.require("Plex API", GONE_STATUSES + 400)
            if (!answer.text().trimStart().startsWith("#EXTM3U")) throw ProviderFailure("Plex part is not a playlist")
            url
        }
        return ResolvedStream(target, STREAM_HEADERS, context.clock.instant().plus(URL_LIFETIME))
    }

    private suspend fun listed(): List<Listed> = context.cached(CHANNELS, Duration.ofMinutes(30)) { fetchChannels() }

    private suspend fun fetchChannels(): List<Listed> {
        val payload = authorized { token ->
            context.http.get("$apiBase/lineups/plex/channels?X-Plex-Token=${encode(token)}", headers(token))
        }.json()
        // A failed fetch keeps the last genres, since a lost group moves a station's number.
        val groups = context.lastGood(GROUPS, runCatchingCancellable { genres() })
        val result = LinkedHashMap<String, Listed>()
        for (channel in payload["MediaContainer"]["Channel"].array) {
            val media = channel["Media"][0]
            if (media["drm"].truthy) continue
            val id = channel.str("gridKey") ?: continue
            val name = channel.str("title") ?: continue
            val key = media["Part"][0].str("key") ?: continue
            if (result.containsKey(id)) continue
            result[id] =
                Listed(ProviderChannel(id, name, null, httpOrNull(channel.str("thumb")), groups[id], listOf("us")), key)
        }
        if (result.isEmpty()) throw ProviderFailure("Channel list is empty")
        return result.values.toList()
    }

    /** Grid key to the first themed genre that lists it; the catch-all genres only name what nothing else does. */
    private suspend fun genres(): Map<String, String> {
        val root = authorized { token ->
            context.http.get("$apiBase/?X-Plex-Token=${encode(token)}", headers(token))
        }.json()
        val filters = root["MediaProvider"]["Feature"].array.flatMap { it["GridChannelFilter"].array }
            .mapNotNull { filter -> filter.str("identifier")?.let { it to (filter.str("title") ?: it) } }
        val result = HashMap<String, String>()
        for ((identifier, title) in filters.filterNot { it.first in CATCH_ALL } +
            filters.filter { it.first in CATCH_ALL }) {
            // About twenty listings in a row, which Plex answers with 429 when they come too fast.
            val listing = authorized(patient = true) { token ->
                context.http.get(
                    "$apiBase/lineups/plex/channels?genre=${encode(identifier)}&X-Plex-Token=${encode(token)}",
                    headers(token),
                )
            }.json()
            for (channel in listing["MediaContainer"]["Channel"].array) {
                val id = channel.str("gridKey") ?: continue
                result.putIfAbsent(id, title)
            }
        }
        return result
    }

    /**
     * Runs [request] with the stored anonymous token; a 401 or 403 replaces it and tries once more. A [patient]
     * caller, never a playback resolve, waits out a 429 with doubling waits before giving up.
     */
    private suspend fun authorized(
        gone: Set<Int> = GONE_STATUSES,
        patient: Boolean = false,
        request: suspend (String) -> Fetched,
    ): Fetched {
        var wait = rateLimitWaitMillis
        repeat(if (patient) RATE_LIMIT_RETRIES else 0) {
            val answer = signedIn(request)
            if (answer.status != 429) return answer.require("Plex API", gone)
            delay(wait)
            wait *= 2
        }
        return signedIn(request).require("Plex API", gone)
    }

    private suspend fun signedIn(request: suspend (String) -> Fetched): Fetched {
        val first = request(token())
        if (first.status != 401 && first.status != 403) return first
        context.clearState(TOKEN)
        return request(token())
    }

    private suspend fun token(): String {
        context.state(TOKEN)?.let { return it }
        val answer = context.http.post(
            authUrl,
            BASE_HEADERS + ("X-Plex-Client-Identifier" to context.deviceId()) + ("Accept" to "application/json"),
            body = "",
        )
            .require("Plex anonymous auth").json()
        val token = answer.str("authToken") ?: throw ProviderFailure("Plex anonymous auth returned no token")
        context.saveState(TOKEN, token, null)
        return token
    }

    private suspend fun headers(token: String): Map<String, String> = BASE_HEADERS +
        mapOf(
            "Accept" to "application/json",
            "X-Plex-Token" to token,
            "X-Plex-Client-Identifier" to context.deviceId(),
        )

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        private const val API_BASE = "https://epg.provider.plex.tv"
        private const val AUTH_URL = "https://plex.tv/api/v2/users/anonymous"
        private const val PRODUCT = "Plex Mediaverse"
        private const val CHANNELS = "channels"
        private const val TOKEN = "token"
        private const val GROUPS = "genre_groups"
        private const val MANIFEST_LIMIT = 1024 * 1024
        private const val GRID_SPACING_MILLIS = 250L
        private const val RATE_LIMIT_WAIT_MILLIS = 15_000L
        private const val RATE_LIMIT_RETRIES = 3
        private val URL_LIFETIME = Duration.ofHours(1)
        private val CATCH_ALL = setOf("bingeworthy", "global")
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/145.0.0.0 Safari/537.36"
        private val STREAM_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to "https://watch.plex.tv",
            "Referer" to "https://watch.plex.tv/",
        )
        private val BASE_HEADERS =
            STREAM_HEADERS +
                mapOf("X-Plex-Product" to PRODUCT, "X-Plex-Platform" to "Chrome", "X-Plex-Device" to "Linux")
    }
}

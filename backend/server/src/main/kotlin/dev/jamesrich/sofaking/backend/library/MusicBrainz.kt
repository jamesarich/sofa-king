package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.upstream.REDIRECT_STATUSES
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.time.Duration

/**
 * A failed MusicBrainz call; [code] is a short class for job notes (`MUSICBRAINZ_RATE_LIMITED`,
 * `MUSICBRAINZ_HTTP_500`), with no URL.
 */
class MusicBrainzFailure(val code: String) : RuntimeException(code)

private val musicBrainzJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable internal data class MbArtistRef(val id: String = "", val name: String = "")

@Serializable internal data class MbArtistCredit(val name: String = "", val artist: MbArtistRef? = null)

@Serializable internal data class MbGenre(val name: String = "", val count: Int = 0)

@Serializable internal data class MbReleaseGroup(
    val id: String,
    val title: String = "",
    val score: Int = 0,
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val genres: List<MbGenre> = emptyList(),
)

@Serializable internal data class MbReleaseGroups(
    @SerialName("release-groups") val releaseGroups: List<MbReleaseGroup> = emptyList(),
)

@Serializable internal data class MbRelease(
    val id: String,
    @SerialName(
        "release-group",
    ) val releaseGroup: MbReleaseGroup? = null,
)

@Serializable internal data class MbAlias(val name: String = "")

@Serializable internal data class MbUrl(val resource: String = "")

/** A link from an artist: to a page ([url]) or to another [artist], which `backward` points from them to it. */
@Serializable internal data class MbRelation(
    val type: String = "",
    val url: MbUrl? = null,
    val direction: String = "",
    val artist: MbArtistRef? = null,
    val ended: Boolean = false,
)

@Serializable internal data class MbArea(val name: String = "")

/** When an artist was born or formed ([begin], as `1991` or `1991-05-22`). */
@Serializable internal data class MbLifeSpan(val begin: String? = null)

@Serializable internal data class MbArtist(
    val id: String,
    val name: String = "",
    val score: Int = 0,
    val aliases: List<MbAlias> = emptyList(),
    val genres: List<MbGenre> = emptyList(),
    val relations: List<MbRelation> = emptyList(),
    val area: MbArea? = null,
    @SerialName("life-span") val lifeSpan: MbLifeSpan? = null,
)

@Serializable internal data class MbArtists(val artists: List<MbArtist> = emptyList())

@Serializable internal data class WdSitelink(val title: String = "")

@Serializable internal data class WdEntity(val sitelinks: Map<String, WdSitelink> = emptyMap())

@Serializable internal data class WdEntities(val entities: Map<String, WdEntity> = emptyMap())

@Serializable internal data class WpImage(val source: String = "")

/** A Wikipedia page's summary: its first paragraph as plain text, and the picture at its top. */
@Serializable internal data class WpSummary(
    val type: String = "",
    val extract: String? = null,
    @SerialName("originalimage") val image: WpImage? = null,
)

/** Where the front covers of MusicBrainz releases are kept. */
internal const val COVER_ART_ARCHIVE = "https://coverartarchive.org"

/** The User-Agent MusicBrainz and Wikimedia ask a client to send: who it is, its [version] and where it comes from. */
internal fun sofaKingAgent(version: String) = "SofaKing/$version ( https://github.com/jamesarich/sofa-king )"

/** MusicBrainz asks for one request a second from each client; a little more leaves room for clock jitter. */
internal val MUSICBRAINZ_PAUSE: Duration = Duration.ofMillis(1_100)

/**
 * The first wait after MusicBrainz throttles a call, doubled for each try after it. MusicBrainz throttles a busy
 * address or a busy service with a 503 well under one call a second, so a call waits and asks again before it fails.
 */
internal val MUSICBRAINZ_BACKOFF: Duration = Duration.ofSeconds(2)

/** Tries a throttled MusicBrainz call gets after its first, waiting 2, 4, 8 and 16 seconds. */
private const val MUSICBRAINZ_RETRIES = 4

/** The longest wait a Retry-After from MusicBrainz is kept to. */
private const val RETRY_AFTER_MAX_SECONDS = 60L

/**
 * The few MusicBrainz web service calls a Music library needs, and the Cover Art Archive, Wikidata and Wikipedia ones
 * for covers and artists' pictures and biographies, one at a time and MusicBrainz's at most one a second as it asks,
 * under a User-Agent naming the server's [version] and where it comes from, as MusicBrainz and Wikimedia require.
 */
internal class MusicBrainzClient(
    private val http: HttpClient,
    version: String,
    private val base: String = "https://musicbrainz.org/ws/2",
    private val coverArtArchive: String = COVER_ART_ARCHIVE,
    private val wikidata: String = "https://www.wikidata.org/w/api.php",
    private val wikipedia: String = "https://en.wikipedia.org/api/rest_v1",
    private val pause: Duration = MUSICBRAINZ_PAUSE,
    private val backoff: Duration = MUSICBRAINZ_BACKOFF,
) {
    private val userAgent = sofaKingAgent(version)
    private val gate = Mutex()
    private var lastAt = 0L

    /** The release groups called [title] by [artist], best first, with MusicBrainz's score out of 100. */
    suspend fun searchReleaseGroups(title: String, artist: String): List<MbReleaseGroup> = mb<MbReleaseGroups>(
        "/release-group",
        "query" to "releasegroup:${quoted(title)} AND artist:${quoted(artist)}",
        "limit" to "5",
    ).releaseGroups

    /** The release group [id] with its artists and genres; null when MusicBrainz has no such group. */
    suspend fun releaseGroup(id: String): MbReleaseGroup? =
        orNull { mb("/release-group/$id", "inc" to "artist-credits genres") }

    /** The release [id] with its release group; null when MusicBrainz has no such release. */
    suspend fun release(id: String): MbRelease? = orNull { mb("/release/$id", "inc" to "release-groups") }

    /** The artists called [name], best first. */
    suspend fun searchArtists(name: String): List<MbArtist> =
        mb<MbArtists>("/artist", "query" to "artist:${quoted(name)}", "limit" to "5").artists

    /** The artist [id] with its aliases, genres, links and members; null when MusicBrainz has no such artist. */
    suspend fun artist(id: String): MbArtist? =
        orNull { mb("/artist/$id", "inc" to "aliases genres url-rels artist-rels") }

    /**
     * Whether the Cover Art Archive holds a front cover for the release group [id]: it answers with a redirect to the
     * image, or 404 for none.
     */
    suspend fun hasCover(id: String): Boolean {
        val status = request(
            "$coverArtArchive/release-group/$id/front-250",
            emptyArray(),
            "COVERART",
            json = false,
        ).status
        return when {
            status == HttpStatusCode.OK || status.value in REDIRECT_STATUSES -> true

            status == HttpStatusCode.NotFound -> false

            status == HttpStatusCode.ServiceUnavailable || status == HttpStatusCode.TooManyRequests ->
                throw MusicBrainzFailure("COVERART_RATE_LIMITED")

            else -> throw MusicBrainzFailure("COVERART_HTTP_${status.value}")
        }
    }

    /** The English Wikipedia page the Wikidata item [id] (`Q…`) links to; null when it links none. */
    suspend fun wikipediaTitle(id: String): String? = orNull {
        get<WdEntities>(
            "WIKIDATA",
            wikidata,
            "action" to "wbgetentities",
            "ids" to id,
            "props" to "sitelinks",
            "sitefilter" to "enwiki",
            "format" to "json",
        )
    }?.entities?.get(id)?.sitelinks?.get("enwiki")?.title?.ifEmpty { null }

    /** The summary of the English Wikipedia page [title]; null when there is no such page. */
    suspend fun wikipediaSummary(title: String): WpSummary? = orNull {
        val page = URLEncoder.encode(title.replace(' ', '_'), Charsets.UTF_8).replace("+", "%20")
        get("WIKIPEDIA", "$wikipedia/page/summary/$page")
    }

    private suspend inline fun <reified T> mb(path: String, vararg query: Pair<String, String>): T =
        get("MUSICBRAINZ", base + path, *query, "fmt" to "json")

    /** [url] decoded as [T]; a failure's code starts with [service]. */
    private suspend inline fun <reified T> get(service: String, url: String, vararg query: Pair<String, String>): T {
        val response = request(url, query, service)
        failureOf(response.status)?.let { throw MusicBrainzFailure(it.replace("MUSICBRAINZ", service)) }
        return runCatchingCancellable { musicBrainzJson.decodeFromString<T>(response.bodyAsText()) }
            .getOrElse { throw MusicBrainzFailure("${service}_BAD_RESPONSE") }
    }

    /**
     * [url] of [service] asked, as JSON unless not [json], after MusicBrainz's pause since its last call. A throttled
     * MusicBrainz call is asked again, after a wait that doubles each time (or as long as its Retry-After says, up to a
     * minute), and its last answer stands once the tries run out.
     */
    private suspend fun request(
        url: String,
        query: Array<out Pair<String, String>>,
        service: String,
        json: Boolean = true,
    ): HttpResponse {
        // Only MusicBrainz asks for a pause; the others take one call after another.
        val paced = service == "MUSICBRAINZ"

        suspend fun once(): HttpResponse = gate.withLock {
            val wait = pause.toMillis() - (System.currentTimeMillis() - lastAt)
            if (paced && wait > 0) delay(wait)
            try {
                runCatchingCancellable {
                    http.get(url) {
                        header(HttpHeaders.UserAgent, userAgent)
                        if (json) header(HttpHeaders.Accept, "application/json")
                        for ((name, value) in query) parameter(name, value)
                    }
                }.getOrElse { throw MusicBrainzFailure("${service}_UNREACHABLE (${it.javaClass.simpleName})") }
            } finally {
                if (paced) lastAt = System.currentTimeMillis()
            }
        }

        var response = once()
        var wait = backoff.toMillis()
        var tries = 0
        while (paced && tries < MUSICBRAINZ_RETRIES && failureOf(response.status) == "MUSICBRAINZ_RATE_LIMITED") {
            val asked = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                ?.coerceIn(0, RETRY_AFTER_MAX_SECONDS)?.times(MILLIS_PER_SECOND)
            delay(asked ?: wait)
            wait *= 2
            tries++
            response = once()
        }
        return response
    }
}

/** What [call] answers, or null for a 404. */
private suspend fun <T> orNull(call: suspend () -> T): T? = try {
    call()
} catch (e: MusicBrainzFailure) {
    if (e.code.endsWith("_HTTP_404")) null else throw e
}

private const val MILLIS_PER_SECOND = 1_000L

/** [text] as one Lucene phrase, its quotes and backslashes escaped. */
private fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** The failure code of a MusicBrainz answer with [status]; null for a success. MusicBrainz throttles with a 503. */
private fun failureOf(status: HttpStatusCode): String? = when (status) {
    HttpStatusCode.OK -> null
    HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests -> "MUSICBRAINZ_RATE_LIMITED"
    else -> "MUSICBRAINZ_HTTP_${status.value}"
}

package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.runCatchingCancellable
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

@Serializable internal data class MbRelation(val type: String = "", val url: MbUrl? = null)

@Serializable internal data class MbArtist(
    val id: String,
    val name: String = "",
    val score: Int = 0,
    val aliases: List<MbAlias> = emptyList(),
    val genres: List<MbGenre> = emptyList(),
    val relations: List<MbRelation> = emptyList(),
)

@Serializable internal data class MbArtists(val artists: List<MbArtist> = emptyList())

/** MusicBrainz asks for one request a second from each client; a little more leaves room for clock jitter. */
internal val MUSICBRAINZ_PAUSE: Duration = Duration.ofMillis(1_100)

/**
 * The few MusicBrainz web service calls a Music library needs, at most one a second as MusicBrainz asks, under a
 * User-Agent naming the server's [version] and where it comes from, as MusicBrainz requires.
 */
internal class MusicBrainzClient(
    private val http: HttpClient,
    version: String,
    private val base: String = "https://musicbrainz.org/ws/2",
    private val pause: Duration = MUSICBRAINZ_PAUSE,
) {
    private val userAgent = "SofaKing/$version ( https://github.com/jamesarich/sofa-king )"
    private val gate = Mutex()
    private var lastAt = 0L

    /** The release groups called [title] by [artist], best first, with MusicBrainz's score out of 100. */
    suspend fun searchReleaseGroups(title: String, artist: String): List<MbReleaseGroup> = get<MbReleaseGroups>(
        "/release-group",
        "query" to "releasegroup:${quoted(title)} AND artist:${quoted(artist)}",
        "limit" to "5",
    ).releaseGroups

    /** The release group [id] with its artists and genres; null when MusicBrainz has no such group. */
    suspend fun releaseGroup(id: String): MbReleaseGroup? =
        orNull { get("/release-group/$id", "inc" to "artist-credits genres") }

    /** The release [id] with its release group; null when MusicBrainz has no such release. */
    suspend fun release(id: String): MbRelease? = orNull { get("/release/$id", "inc" to "release-groups") }

    /** The artists called [name], best first. */
    suspend fun searchArtists(name: String): List<MbArtist> =
        get<MbArtists>("/artist", "query" to "artist:${quoted(name)}", "limit" to "5").artists

    /** The artist [id] with its aliases, genres and links; null when MusicBrainz has no such artist. */
    suspend fun artist(id: String): MbArtist? = orNull { get("/artist/$id", "inc" to "aliases genres url-rels") }

    private suspend fun <T> orNull(call: suspend () -> T): T? = try {
        call()
    } catch (e: MusicBrainzFailure) {
        if (e.code == "MUSICBRAINZ_HTTP_404") null else throw e
    }

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String>): T {
        val response = request(path, query)
        failureOf(response.status)?.let { throw MusicBrainzFailure(it) }
        return runCatchingCancellable { musicBrainzJson.decodeFromString<T>(response.bodyAsText()) }
            .getOrElse { throw MusicBrainzFailure("MUSICBRAINZ_BAD_RESPONSE") }
    }

    /** [path] asked as JSON, after the pause since the last call; one that never gets an answer is unreachable. */
    private suspend fun request(path: String, query: Array<out Pair<String, String>>): HttpResponse = gate.withLock {
        val wait = pause.toMillis() - (System.currentTimeMillis() - lastAt)
        if (wait > 0) delay(wait)
        try {
            runCatchingCancellable {
                http.get(base + path) {
                    header(HttpHeaders.UserAgent, userAgent)
                    header(HttpHeaders.Accept, "application/json")
                    parameter("fmt", "json")
                    for ((name, value) in query) parameter(name, value)
                }
            }.getOrElse { throw MusicBrainzFailure("MUSICBRAINZ_UNREACHABLE (${it.javaClass.simpleName})") }
        } finally {
            lastAt = System.currentTimeMillis()
        }
    }
}

/** [text] as one Lucene phrase, its quotes and backslashes escaped. */
private fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** The failure code of a MusicBrainz answer with [status]; null for a success. MusicBrainz throttles with a 503. */
private fun failureOf(status: HttpStatusCode): String? = when (status) {
    HttpStatusCode.OK -> null
    HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests -> "MUSICBRAINZ_RATE_LIMITED"
    else -> "MUSICBRAINZ_HTTP_${status.value}"
}

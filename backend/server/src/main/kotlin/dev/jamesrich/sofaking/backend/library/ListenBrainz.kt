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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The first status code of a failure on ListenBrainz's side. */
private const val HTTP_SERVER_ERROR = 500

/** How many of an artist's most played songs are kept. */
private const val POPULAR_KEPT = 25

/** How many of an artist's most similar artists are kept. */
private const val SIMILAR_KEPT = 50

/**
 * The similarity ListenBrainz's own artist pages use: artists played in the same listening sessions over about 20
 * years.
 */
private const val SIMILAR_ALGORITHM = "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_" +
    "filter_True_skip_30"

private val listenBrainzJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable internal data class LbRecording(
    @SerialName("recording_mbid") val recordingId: String? = null,
    @SerialName("recording_name") val title: String = "",
)

@Serializable internal data class LbSimilarArtist(@SerialName("artist_mbid") val artistId: String = "")

/** A song ListenBrainz's listeners play much: its MusicBrainz recording [id] and its [title]. */
@Serializable
data class PopularSong(val id: String? = null, val title: String)

/**
 * A failed ListenBrainz token check; [code] is a short class for the setup error (`LISTENBRAINZ_AUTH`,
 * `LISTENBRAINZ_HTTP_500`), with no token.
 */
class ListenBrainzFailure(val code: String) : RuntimeException(code)

@Serializable internal data class LbTokenCheck(val valid: Boolean = false)

/**
 * [ListenBrainz](https://listenbrainz.org)'s listening statistics: the artists played alongside an artist, which need
 * no key, and an artist's most played songs, which need the user [token] (null asks for no songs), by MusicBrainz id,
 * under a User-Agent naming the server's [version].
 */
internal class ListenBrainzClient(
    private val http: HttpClient,
    version: String,
    private val token: String? = null,
    private val api: String = "https://api.listenbrainz.org/1",
    private val labs: String = "https://labs.api.listenbrainz.org",
) {
    private val userAgent = sofaKingAgent(version)

    /** Whether the most played songs can be asked for, which takes a token. */
    val canAskSongs: Boolean get() = token != null

    /** Fails with [ListenBrainzFailure] unless ListenBrainz takes the token: `LISTENBRAINZ_AUTH` when it does not. */
    suspend fun check() {
        val response = runCatchingCancellable {
            http.get("$api/validate-token") {
                header(HttpHeaders.UserAgent, userAgent)
                token?.let { header(HttpHeaders.Authorization, "Token $it") }
            }
        }.getOrElse { throw ListenBrainzFailure("LISTENBRAINZ_UNREACHABLE (${it.javaClass.simpleName})") }
        val valid = runCatchingCancellable {
            response.status == HttpStatusCode.OK &&
                listenBrainzJson.decodeFromString<LbTokenCheck>(response.bodyAsText()).valid
        }.getOrDefault(false)
        if (valid) return
        throw ListenBrainzFailure(
            if (response.status.value <
                HTTP_SERVER_ERROR
            ) {
                "LISTENBRAINZ_AUTH"
            } else {
                "LISTENBRAINZ_HTTP_${response.status.value}"
            },
        )
    }

    /** The MusicBrainz artist [id]'s most played songs, most played first; empty when ListenBrainz knows none. */
    suspend fun popular(id: String): List<PopularSong> {
        val response = http.get("$api/popularity/top-recordings-for-artist/$id") {
            header(HttpHeaders.UserAgent, userAgent)
            token?.let { header(HttpHeaders.Authorization, "Token $it") }
        }
        val body = text(response) ?: return emptyList()
        return listenBrainzJson.decodeFromString<List<LbRecording>>(body)
            .filter { it.title.isNotBlank() }
            .map { PopularSong(it.recordingId, it.title) }
            .take(POPULAR_KEPT)
    }

    /** The MusicBrainz ids of the artists most played alongside the artist [id], most similar first. */
    suspend fun similar(id: String): List<String> {
        val response = http.get("$labs/similar-artists/json") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("artist_mbids", id)
            parameter("algorithm", SIMILAR_ALGORITHM)
        }
        val body = text(response) ?: return emptyList()
        return listenBrainzJson.decodeFromString<List<LbSimilarArtist>>(body)
            .map { it.artistId }
            .filter { it.isNotEmpty() && it != id }
            .distinct()
            .take(SIMILAR_KEPT)
    }

    /** [response]'s body; null for a 404 or 204, which mean ListenBrainz knows nothing of it. Fails for others. */
    private suspend fun text(response: HttpResponse): String? = when (response.status) {
        HttpStatusCode.OK -> response.bodyAsText()
        HttpStatusCode.NotFound, HttpStatusCode.NoContent -> null
        else -> error("LISTENBRAINZ_HTTP_${response.status.value}")
    }
}

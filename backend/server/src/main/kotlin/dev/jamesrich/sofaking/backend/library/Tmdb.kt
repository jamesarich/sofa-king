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
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `SOFA_TMDB_TOKEN` and `SOFA_TMDB_LANGUAGE`: TMDB lookups for movies and shows, in [language]
 * (an IETF tag such as `en-US`).
 */
data class TmdbConfig(val token: String, val language: String = "en-US") {
    /** The country whose certification is the content rating: `US` for `en-US`. */
    val region: String get() = language.substringAfter('-', "US").uppercase()

    override fun toString(): String = "TmdbConfig(token=****, language=$language)"
}

/**
 * A failed TMDB call; [code] is a short class for job notes (`TMDB_AUTH`, `TMDB_RATE_LIMITED`,
 * `TMDB_HTTP_500`), with no URL or token.
 */
class TmdbFailure(code: String) : ServiceFailure(code)

private val tmdbJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable internal data class TmdbPage<T>(val results: List<T> = emptyList())

@Serializable internal data class TmdbAuthentication(val success: Boolean = false)

@Serializable internal data class TmdbMovieResult(
    val id: Int,
    val title: String = "",
    @SerialName("original_title") val originalTitle: String = "",
    @SerialName("release_date") val releaseDate: String? = null,
    val popularity: Double = 0.0,
    val overview: String? = null,
)

@Serializable internal data class TmdbShowResult(
    val id: Int,
    val name: String = "",
    @SerialName("original_name") val originalName: String = "",
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double = 0.0,
    val overview: String? = null,
)

/** What `/find/{id}` answers for a TheTVDB or IMDb id. */
@Serializable internal data class TmdbFound(
    @SerialName("movie_results") val movies: List<TmdbMovieResult> = emptyList(),
    @SerialName("tv_results") val shows: List<TmdbShowResult> = emptyList(),
)

@Serializable internal data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
)

@Serializable internal data class TmdbGenre(val name: String = "")

@Serializable internal data class TmdbCompany(val name: String = "")

@Serializable internal data class TmdbPerson(val name: String = "", val job: String? = null)

@Serializable
internal data class TmdbCredits(val cast: List<TmdbPerson> = emptyList(), val crew: List<TmdbPerson> = emptyList())

@Serializable internal data class TmdbReleaseDate(val certification: String = "")

@Serializable internal data class TmdbCountryReleases(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val dates: List<TmdbReleaseDate> = emptyList(),
)

@Serializable internal data class TmdbReleases(val results: List<TmdbCountryReleases> = emptyList())

@Serializable internal data class TmdbContentRating(
    @SerialName(
        "iso_3166_1",
    ) val country: String = "",
    val rating: String = "",
)

@Serializable internal data class TmdbContentRatings(val results: List<TmdbContentRating> = emptyList())

@Serializable internal data class TmdbMovie(
    val id: Int,
    val title: String = "",
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("production_companies") val companies: List<TmdbCompany> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val credits: TmdbCredits? = null,
    @SerialName("release_dates") val releaseDates: TmdbReleases? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
)

@Serializable internal data class TmdbShow(
    val id: Int,
    val name: String = "",
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val networks: List<TmdbCompany> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val credits: TmdbCredits? = null,
    @SerialName("content_ratings") val contentRatings: TmdbContentRatings? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("last_air_date") val lastAirDate: String? = null,
    /** `Returning Series`, `Ended`, `Canceled` and the like. */
    val status: String? = null,
    @SerialName("number_of_seasons") val seasonCount: Int? = null,
    @SerialName("number_of_episodes") val episodeCount: Int? = null,
    @SerialName("created_by") val createdBy: List<TmdbPerson> = emptyList(),
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
)

@Serializable internal data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String = "",
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable internal data class TmdbSeason(
    val name: String = "",
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val episodes: List<TmdbEpisode> = emptyList(),
)

/**
 * The few TMDB v3 calls the library needs, over the server's HTTP client. [config]'s token is a v4 read access
 * token sent as a bearer, or a 32-character v3 key sent as `api_key`; it never appears in a log or an error.
 */
internal class TmdbClient(
    private val http: HttpClient,
    private val config: TmdbConfig,
    private val base: String = "https://api.themoviedb.org/3",
) {
    private val v3Key = config.token.length == 32 && config.token.all { it.isLetterOrDigit() }

    suspend fun searchMovies(title: String, year: Int?): List<TmdbMovieResult> = get<TmdbPage<TmdbMovieResult>>(
        "/search/movie",
        "query" to title,
        "year" to year?.toString(),
        "include_adult" to "false",
    ).results

    suspend fun searchShows(title: String, year: Int?): List<TmdbShowResult> = get<TmdbPage<TmdbShowResult>>(
        "/search/tv",
        "query" to title,
        "first_air_date_year" to year?.toString(),
        "include_adult" to "false",
    ).results

    suspend fun movie(id: Int): TmdbMovie = get("/movie/$id", "append_to_response" to "credits,release_dates")

    suspend fun show(id: Int): TmdbShow = get("/tv/$id", "append_to_response" to "credits,content_ratings,external_ids")

    /** The movies and shows TMDB files under the TheTVDB id [id] (`tvdb_id`) or IMDb id (`imdb_id`). */
    suspend fun find(id: String, source: String): TmdbFound = get("/find/$id", "external_source" to source)

    /** Asks TMDB whether it takes the token: fails with `TMDB_AUTH` when it refuses it. */
    suspend fun check() {
        get<TmdbAuthentication>("/authentication")
    }

    /** Null when TMDB has no such season. */
    suspend fun season(showId: Int, season: Int): TmdbSeason? = try {
        get("/tv/$showId/season/$season")
    } catch (e: TmdbFailure) {
        if (e.code == "TMDB_HTTP_404") null else throw e
    }

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String?>): T {
        val response = request(path, query)
        failureOf(response.status)?.let { throw TmdbFailure(it) }
        return runCatchingCancellable { tmdbJson.decodeFromString<T>(response.bodyAsText()) }
            .getOrElse { throw TmdbFailure("TMDB_BAD_RESPONSE") }
    }

    /** [path] asked with the token and the language; a request that never gets an answer is `TMDB_UNREACHABLE`. */
    private suspend fun request(path: String, query: Array<out Pair<String, String?>>): HttpResponse =
        runCatchingCancellable {
            http.get(base + path.split('/').joinToString("/") { it.encodeURLPathPart() }) {
                if (v3Key) {
                    parameter("api_key", config.token)
                } else {
                    header(HttpHeaders.Authorization, "Bearer ${config.token}")
                }
                header(HttpHeaders.Accept, "application/json")
                parameter("language", config.language)
                for ((name, value) in query) if (value != null) parameter(name, value)
            }
        }.getOrElse {
            // Only the class goes on: the transport's message can carry the URL, and with it a v3 key.
            throw TmdbFailure("TMDB_UNREACHABLE (${it.javaClass.simpleName})")
        }
}

/** The failure code of a TMDB answer with [status]; null for a success. */
private fun failureOf(status: HttpStatusCode): String? = when (status) {
    HttpStatusCode.OK -> null
    HttpStatusCode.Unauthorized -> "TMDB_AUTH"
    HttpStatusCode.TooManyRequests -> "TMDB_RATE_LIMITED"
    else -> "TMDB_HTTP_${status.value}"
}

/** The notice TMDB's terms ask every product that uses its API to show. */
const val TMDB_NOTICE = "This product uses the TMDB API but is not endorsed or certified by TMDB."

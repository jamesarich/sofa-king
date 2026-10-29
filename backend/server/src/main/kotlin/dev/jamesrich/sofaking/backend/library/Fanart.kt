package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A failed fanart.tv call; [code] is a short class for job notes (`FANART_AUTH`, `FANART_HTTP_500`), with no URL or
 * key.
 */
class FanartFailure(code: String) : ServiceFailure(code)

private val fanartJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Where fanart.tv keeps its images; a picture is stored as its path under it. */
internal const val FANART_ASSETS = "https://assets.fanart.tv/fanart/"

/**
 * An image path under [FANART_ASSETS]: up to three folders of plain lowercase characters, as in
 * `music/<id>/artistbackground/<file>`, and a file of plain characters, no `..`, ending in its type. Most images are
 * just a slug-named file (`autechre-4faa….jpg`).
 */
internal val FANART_PATH =
    Regex("""(?:[a-z0-9-]{1,40}/){0,3}[A-Za-z0-9_~%-][A-Za-z0-9._~%-]{0,200}\.(jpg|png)""")

/** Which reading of fanart.tv's image addresses stored facts carry; a lookup made under an older one is made again. */
internal const val FANART_REVISION = 2

/** An artist MusicBrainz knows, as asked of fanart.tv only to check a key. */
private const val CHECK_ARTIST = "f22942a1-6f70-4f48-866e-238cb2308fbd"

@Serializable internal data class FanartImage(val url: String = "", val likes: String = "0")

@Serializable internal data class FanartAlbum(@SerialName("albumcover") val covers: List<FanartImage> = emptyList())

@Serializable internal data class FanartArtistImages(
    @SerialName("artistthumb") val thumbs: List<FanartImage> = emptyList(),
    @SerialName("artistbackground") val backgrounds: List<FanartImage> = emptyList(),
    val albums: Map<String, FanartAlbum> = emptyMap(),
)

/** What fanart.tv holds for an artist: [picture] and [backdrop] are paths under [FANART_ASSETS]. */
internal data class FanartArtist(val picture: String?, val backdrop: String?, val covers: Map<String, String>)

/**
 * fanart.tv's music images, which people pick and vote for, by MusicBrainz id: an artist's square picture and wide
 * backdrop and their albums' covers. [key] is a project or personal API key, sent in a header and never in the URL,
 * under a User-Agent naming the server's [version].
 */
internal class FanartClient(
    private val http: HttpClient,
    private val key: String,
    version: String,
    private val base: String = "https://webservice.fanart.tv/v3",
) {
    private val userAgent = sofaKingAgent(version)

    /** What fanart.tv holds for the MusicBrainz artist [id]; null when it holds nothing. */
    suspend fun artist(id: String): FanartArtist? {
        val images = get("/music/$id") ?: return null
        return FanartArtist(
            best(images.thumbs),
            best(images.backgrounds),
            images.albums.mapNotNull { (group, album) -> best(album.covers)?.let { group to it } }.toMap(),
        )
    }

    /** Fails with [FanartFailure] unless fanart.tv takes the key: `FANART_AUTH` when it refuses it. */
    suspend fun check() {
        get("/music/$CHECK_ARTIST")
    }

    private suspend fun get(path: String): FanartArtistImages? {
        val response = runCatchingCancellable {
            http.get(base + path) {
                header("api-key", key)
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/json")
            }
        }.getOrElse { throw FanartFailure("FANART_UNREACHABLE (${it.javaClass.simpleName})") }
        val failure = when (response.status) {
            HttpStatusCode.OK -> null
            HttpStatusCode.NotFound -> return null
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> "FANART_AUTH"
            else -> "FANART_HTTP_${response.status.value}"
        }
        return runCatchingCancellable {
            if (failure != null) throw FanartFailure(failure)
            fanartJson.decodeFromString<FanartArtistImages>(response.bodyAsText())
        }.getOrElse { throw it as? FanartFailure ?: FanartFailure("FANART_BAD_RESPONSE") }
    }

    /** The path of the most liked of [images] that fanart.tv serves from its image host, if any. */
    private fun best(images: List<FanartImage>): String? = images
        .mapNotNull { image -> fanartPath(image.url)?.let { it to (image.likes.toIntOrNull() ?: 0) } }
        .maxByOrNull { it.second }?.first
}

/** The path under [FANART_ASSETS] of the image at [url], which fanart.tv may name over plain HTTP; null elsewhere. */
internal fun fanartPath(url: String): String? =
    (if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url)
        .takeIf { it.startsWith(FANART_ASSETS) }?.removePrefix(FANART_ASSETS)?.takeIf { FANART_PATH.matches(it) }

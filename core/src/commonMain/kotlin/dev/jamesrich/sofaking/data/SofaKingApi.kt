package dev.jamesrich.sofaking.data

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/** An error answer from the server: HTTP [status] with the API's [code], or `HTTP_<status>` when the body was not one. */
class SofaKingException(
    val status: Int,
    val code: String,
    message: String,
) : IOException(message)

/** The catalog as the server sent it, or [NotModified] when the ETag offered still matches. */
internal sealed interface CatalogResult {
    data class Changed(val catalog: CatalogDto, val etag: String?) : CatalogResult
    data object NotModified : CatalogResult
}

/**
 * The Sofa King server's JSON API under `/api/v1`. Every call carries the bearer [token] unless it is empty;
 * media URLs from a descriptor are resolved against the base and carry no token. Transport failures surface as
 * [IOException] on every platform, whatever the engine throws, so callers retry them the same way.
 */
class SofaKingApi(
    baseUrl: String,
    private val token: String,
    private val http: HttpClient = apiHttpClient,
) {
    private val base: Url = Url(baseUrl.trimEnd('/'))

    internal suspend fun catalog(etag: String?): CatalogResult = call {
        val response = http.get(endpoint("channels")) {
            authorize()
            if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
        }
        if (response.status.value == 304) return@call CatalogResult.NotModified
        CatalogResult.Changed(decode<CatalogDto>(response), response.headers[HttpHeaders.ETag])
    }

    internal suspend fun guide(query: GuideQueryDto): GuideWindowDto = call {
        decode(http.post(endpoint("guide", "query")) { json(wireJson.encodeToString(GuideQueryDto.serializer(), query)) })
    }

    internal suspend fun openSession(channelId: String, intent: String, idempotencyKey: String): PlaybackSessionDto = call {
        val body = wireJson.encodeToString(OpenPlaybackDto.serializer(), OpenPlaybackDto(channelId, intent, idempotencyKey))
        decode(http.post(endpoint("playback-sessions")) { json(body) })
    }

    internal suspend fun session(id: String): PlaybackSessionDto = call {
        decode(http.get(endpoint("playback-sessions", id)) { authorize() })
    }

    /** Renews the lease; [intent] changes preview to watch (or back) without replacing the stream. */
    internal suspend fun updateSession(id: String, intent: String?): PlaybackSessionDto = call {
        val body = wireJson.encodeToString(UpdatePlaybackDto.serializer(), UpdatePlaybackDto(intent))
        decode(http.patch(endpoint("playback-sessions", id)) { json(body) })
    }

    internal suspend fun releaseSession(id: String) = call {
        http.delete(endpoint("playback-sessions", id)) { authorize() }.checkSuccess()
    }

    /** The absolute media URL for a descriptor, whose [PlaybackDescriptorDto.url] is relative to the server. */
    internal fun mediaUrl(descriptor: PlaybackDescriptorDto): String = try {
        URLBuilder(base).takeFrom(descriptor.url).buildString()
    } catch (e: IllegalArgumentException) {
        throw IOException("Unusable media URL", e)
    }

    /**
     * A logo the server serves, under `/logos/`, on this client's base address: the server names its own host
     * from the request, which is wrong behind a proxy and plain HTTP under an HTTPS page. Other URLs pass through.
     */
    internal fun logoUrl(raw: String): String? {
        val url = try {
            Url(raw)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!url.encodedPath.startsWith("/logos/")) return raw
        return URLBuilder(base).apply { encodedPath = base.encodedPath.trimEnd('/') + url.encodedPath }.buildString()
    }

    private fun endpoint(vararg segments: String): Url =
        URLBuilder(base).appendPathSegments("api", "v1").appendPathSegments(*segments).build()

    private fun HttpRequestBuilder.authorize() {
        if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun HttpRequestBuilder.json(text: String) {
        authorize()
        setBody(TextContent(text, JSON))
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        response.checkSuccess()
        return try {
            wireJson.decodeFromString(response.bodyAsText())
        } catch (e: SerializationException) {
            throw IOException("Unreadable answer from the server", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unreadable answer from the server", e)
        }
    }

    private suspend fun HttpResponse.checkSuccess() {
        if (status.isSuccess()) return
        val code = status.value
        val error = try {
            wireJson.decodeFromString(ApiErrorDto.serializer(), bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ApiErrorDto("HTTP_$code", "Server returned HTTP $code")
        }
        throw SofaKingException(code, error.code, error.message)
    }

    /** Engines throw their own transport errors (Darwin's NSError, a browser's TypeError); every one becomes an [IOException]. */
    private suspend inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message ?: e.toString(), e)
    }

    private companion object {
        val JSON = ContentType.parse("application/json; charset=utf-8")
    }
}

/**
 * The MIME type to give the player. Media3 recognizes HLS only by its own constant, so the server's
 * `application/vnd.apple.mpegurl` is translated; anything else, including `video/mp2t`, is passed through.
 */
internal fun mediaMimeType(descriptor: PlaybackDescriptorDto): String =
    if (descriptor.protocol == "hls" || descriptor.mimeType.lowercase() in HLS_MIME_TYPES) HLS_MIME_TYPE else descriptor.mimeType

/** The HLS MIME type the players are handed; Media3 matches it exactly, and the other players compare it case-insensitively. */
const val HLS_MIME_TYPE = "application/x-mpegURL"
private val HLS_MIME_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl")

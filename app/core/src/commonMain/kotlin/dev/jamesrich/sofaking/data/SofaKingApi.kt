package dev.jamesrich.sofaking.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

/**
 * An error answer from the server: HTTP [status] with the API's [code], or `HTTP_<status>` when the body was not one.
 */
class SofaKingException(val status: Int, val code: String, message: String) : IOException(message) {
    /** [status] as Ktor names it, to compare with [HttpStatusCode]'s constants. */
    val statusCode: HttpStatusCode get() = HttpStatusCode.fromValue(status)
}

/** The catalog as the server sent it, or [NotModified] when the ETag offered still matches. */
internal sealed interface CatalogResult {
    data class Changed(val catalog: CatalogDto, val etag: String?) : CatalogResult
    data object NotModified : CatalogResult
}

/** A DELETE to [url] that frees a playback session, with the bearer [token] unless it is empty. */
class SessionRelease(val url: String, val token: String)

/**
 * The Sofa King server's JSON API under `/api/v1`. Every call carries the bearer [token] unless it is empty;
 * media URLs from a descriptor are resolved against the base and carry no token. Transport failures surface as
 * [IOException] on every platform, whatever the engine throws, so callers retry them the same way. Answers are
 * decoded on [background], off the caller's main thread. It holds its own client on [engine], the app's one pool
 * unless a test passes another; [close] it once its server is no longer used, which leaves the pool open.
 */
class SofaKingApi(
    baseUrl: String,
    private val token: String,
    engine: HttpClientEngine = sharedHttpEngine,
    internal val background: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val base: Url = Url(baseUrl.trimEnd('/'))

    // The base path ends in a slash so relative endpoints resolve under it. JSON stays with wireJson by hand: Ktor's
    // ContentNegotiation converter pulls OpenAPI schema, YAML and a kotlinx-datetime compatibility build into every
    // target for two lines of encoding.
    internal val http = httpClient(engine) {
        defaultRequest {
            url("$base/api/v1/")
            if (token.isNotEmpty()) bearerAuth(token)
        }
    }

    override fun close() = http.close()

    /** True once [close]d. */
    internal val closed: Boolean get() = !http.isActive

    /** The DELETE that frees the session [id], a library item's when [vod], for a closing page that sends it itself. */
    internal fun releaseRequest(id: String, vod: Boolean) =
        SessionRelease(url = "$base/api/v1/" + (if (vod) vodPath(id) else sessionPath(id)), token = token)

    /** The absolute media URL for a descriptor, whose [PlaybackDescriptorDto.url] is relative to the server. */
    internal fun mediaUrl(descriptor: PlaybackDescriptorDto): String =
        resolve(descriptor.url) ?: throw IOException("Unusable media URL")

    /** A channel logo resolved against the server address, as media URLs are; null when it cannot be resolved. */
    internal fun logoUrl(raw: String): String? = resolve(raw)

    /** [raw] against the server address; null when it does not parse, which Ktor reports as either exception. */
    private fun resolve(raw: String): String? = try {
        URLBuilder(base).takeFrom(raw).buildString()
    } catch (_: URLParserException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    internal inline fun <reified T> HttpRequestBuilder.json(body: T) {
        setBody(TextContent(wireJson.encodeToString(body), JSON))
    }

    internal suspend inline fun <reified T> HttpResponse.decode(): T {
        checkSuccess()
        val body = bodyAsText()
        return try {
            withContext(background) { wireJson.decodeFromString<T>(body) }
        } catch (e: SerializationException) {
            throw IOException("Unreadable answer from the server", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unreadable answer from the server", e)
        }
    }

    internal suspend fun HttpResponse.checkSuccess() {
        if (status.isSuccess()) return
        val code = status.value
        val error = try {
            val body = bodyAsText()
            withContext(background) { wireJson.decodeFromString(ApiErrorDto.serializer(), body) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ApiErrorDto("HTTP_$code", "Server returned HTTP $code")
        }
        throw SofaKingException(code, error.code, error.message)
    }

    /**
     * Engines throw their own transport errors (Darwin's NSError, a browser's TypeError);
     * every one becomes an [IOException].
     */
    internal suspend inline fun <T> call(block: () -> T): T = runCatchingCancellable(block).getOrElse {
        throw it as? IOException
            ?: IOException(it.message ?: it.toString(), it)
    }

    internal companion object {
        val JSON = ContentType.parse("application/json; charset=utf-8")
    }
}

/** The session's endpoint, relative to the API, with [id] encoded as one path segment. */
internal fun sessionPath(id: String) = "playback-sessions/" + id.encodeURLPathPart()

internal fun vodPath(id: String) = "vod-sessions/" + id.encodeURLPathPart()

/**
 * The MIME type to give the player. Media3 recognizes HLS only by its own constant, so the server's
 * `application/vnd.apple.mpegurl` is translated; anything else, including `video/mp2t`, is passed through.
 */
internal fun mediaMimeType(descriptor: PlaybackDescriptorDto): String = if (descriptor.protocol == "hls" ||
    descriptor.mimeType.lowercase() in HLS_MIME_TYPES
) {
    HLS_MIME_TYPE
} else {
    descriptor.mimeType
}

/**
 * The HLS MIME type the players are handed; Media3 matches it exactly, and the other players
 * compare it case-insensitively.
 */
const val HLS_MIME_TYPE = "application/x-mpegURL"
private val HLS_MIME_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl")

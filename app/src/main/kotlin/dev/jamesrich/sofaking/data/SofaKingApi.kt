package dev.jamesrich.sofaking.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An error answer from the server: HTTP [status] with the API's [code], or `HTTP_<status>` when the body was not one. */
class SofaKingException(
    val status: Int,
    val code: String,
    message: String,
    val retryable: Boolean,
) : IOException(message)

/** The catalog as the server sent it, or [NotModified] when the ETag offered still matches. */
internal sealed interface CatalogResult {
    data class Changed(val catalog: CatalogDto, val etag: String?) : CatalogResult
    data object NotModified : CatalogResult
}

/**
 * The Sofa King server's JSON API under `/api/v1`. Every call carries the bearer [token] unless it is empty;
 * media URLs from a descriptor are resolved against the base and carry no token.
 */
class SofaKingApi(
    baseUrl: String,
    private val token: String,
    http: OkHttpClient = sharedHttp,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val http: OkHttpClient = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    internal suspend fun catalog(etag: String?): CatalogResult {
        val request = request("channels").apply { if (etag != null) header("If-None-Match", etag) }.get().build()
        return http.fetch(request, ioDispatcher) { response ->
            if (response.code == 304) return@fetch CatalogResult.NotModified
            CatalogResult.Changed(decode<CatalogDto>(response), response.header("ETag"))
        }
    }

    internal suspend fun guide(query: GuideQueryDto): GuideWindowDto =
        execute(request("guide", "query").post(json(wireJson.encodeToString(GuideQueryDto.serializer(), query))).build())

    internal suspend fun openSession(channelId: String, intent: String, idempotencyKey: String): PlaybackSessionDto {
        val body = wireJson.encodeToString(OpenPlaybackDto.serializer(), OpenPlaybackDto(channelId, intent, idempotencyKey))
        return execute(request("playback-sessions").post(json(body)).build())
    }

    internal suspend fun session(id: String): PlaybackSessionDto = execute(request("playback-sessions", id).get().build())

    /** Renews the lease; [intent] changes preview to watch (or back) without replacing the stream. */
    internal suspend fun updateSession(id: String, intent: String?): PlaybackSessionDto {
        val body = wireJson.encodeToString(UpdatePlaybackDto.serializer(), UpdatePlaybackDto(intent))
        return execute(request("playback-sessions", id).patch(json(body)).build())
    }

    internal suspend fun releaseSession(id: String) {
        http.fetch(request("playback-sessions", id).delete().build(), ioDispatcher) { response -> response.checkSuccess() }
    }

    /** The absolute media URL for a descriptor, whose [PlaybackDescriptorDto.url] is relative to the server. */
    internal fun mediaUrl(descriptor: PlaybackDescriptorDto): String =
        base.resolve(descriptor.url)?.toString() ?: throw IOException("Unusable media URL")

    private fun request(vararg segments: String): Request.Builder {
        val url = base.newBuilder().addPathSegments("api/v1").apply { segments.forEach(::addPathSegment) }.build()
        return Request.Builder().url(url).apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
    }

    private suspend inline fun <reified T> execute(request: Request): T = http.fetch(request, ioDispatcher) { decode<T>(it) }

    private inline fun <reified T> decode(response: Response): T {
        response.checkSuccess()
        return try {
            wireJson.decodeFromString(response.body.string())
        } catch (e: SerializationException) {
            throw IOException("Unreadable answer from the server", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unreadable answer from the server", e)
        }
    }

    private fun Response.checkSuccess() {
        if (isSuccessful) return
        val text = body.string()
        val error = try {
            wireJson.decodeFromString(ApiErrorDto.serializer(), text)
        } catch (_: Exception) {
            ApiErrorDto("HTTP_$code", "Server returned HTTP $code", retryable = code >= 500)
        }
        throw SofaKingException(code, error.code, error.message, error.retryable)
    }

    private fun json(text: String) = text.toRequestBody(JSON)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * The MIME type to give the player. Media3 recognizes HLS only by its own constant, so the server's
 * `application/vnd.apple.mpegurl` is translated; anything else, including `video/mp2t`, is passed through.
 */
internal fun mediaMimeType(descriptor: PlaybackDescriptorDto): String =
    if (descriptor.protocol == "hls" || descriptor.mimeType.lowercase() in HLS_MIME_TYPES) MEDIA3_HLS_MIME_TYPE else descriptor.mimeType

internal const val MEDIA3_HLS_MIME_TYPE = "application/x-mpegURL"
private val HLS_MIME_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl")

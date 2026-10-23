package dev.jamesrich.sofaking.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

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
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val base: Url = Url(baseUrl.trimEnd('/'))

    // The base path ends in a slash so relative endpoints resolve under it. JSON stays with wireJson by hand: Ktor's
    // ContentNegotiation converter pulls OpenAPI schema, YAML and a kotlinx-datetime compatibility build into every
    // target for two lines of encoding.
    private val http = httpClient(engine) {
        defaultRequest {
            url("$base/api/v1/")
            if (token.isNotEmpty()) bearerAuth(token)
        }
    }

    override fun close() = http.close()

    /** True once [close]d. */
    internal val closed: Boolean get() = !http.isActive

    internal suspend fun catalog(etag: String?): CatalogResult = call {
        val response = http.get("channels") {
            if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
        }
        if (response.status.value == 304) return@call CatalogResult.NotModified
        CatalogResult.Changed(response.decode(), response.headers[HttpHeaders.ETag])
    }

    internal suspend fun guide(query: GuideQueryDto): GuideWindowDto = call {
        http.post("guide/query") { json(query) }.decode()
    }

    internal suspend fun openSession(channelId: String, intent: String, idempotencyKey: String): PlaybackSessionDto = call {
        http.post("playback-sessions") { json(OpenPlaybackDto(channelId, intent, idempotencyKey)) }.decode()
    }

    internal suspend fun session(id: String): PlaybackSessionDto = call {
        http.get(sessionPath(id)).decode()
    }

    /** Renews the lease; [intent] changes preview to watch (or back) without replacing the stream. */
    internal suspend fun updateSession(id: String, intent: String?): PlaybackSessionDto = call {
        http.patch(sessionPath(id)) { json(UpdatePlaybackDto(intent)) }.decode()
    }

    /** Whether the session's player shows an ad, for an engine that cannot read the playlist's marks; [aheadMs] is what it loaded past the picture. */
    internal suspend fun adBreak(id: String, aheadMs: Long): AdBreakStateDto = call {
        http.get(sessionPath(id) + "/ad-break") { parameter("aheadMs", aheadMs) }.decode()
    }

    internal suspend fun releaseSession(id: String) = call {
        http.delete(sessionPath(id)).checkSuccess()
    }

    /** Claims a pairing code; the client built for this has no token yet. A wrong or spent code is a [SofaKingException] with [Wire.ErrorCode.PAIRING_INVALID]. */
    internal suspend fun pair(claim: PairingClaimDto): PairingResultDto = call {
        http.post("pair") { json(claim) }.decode()
    }

    /** The preferences the server keeps for [clientId], or null when it has none (or is too old to keep any). */
    internal suspend fun preferences(clientId: String): ViewerPreferencesDto? = call {
        val response = http.get("preferences/" + clientId.encodeURLPathPart())
        if (response.status.value == 404) return@call null
        response.decode()
    }

    /** Replaces the server's copy of [clientId]'s preferences; the answer carries the server's stamp. */
    internal suspend fun savePreferences(clientId: String, preferences: ViewerPreferencesDto): ViewerPreferencesDto = call {
        http.put("preferences/" + clientId.encodeURLPathPart()) { json(preferences) }.decode()
    }

    /**
     * One page of [libraryId]'s top-level items in [sort] order (`title`, `added`, `year`, `rating` or `watched`), only
     * those in [genre] when given, from [cursor].
     */
    internal suspend fun libraryItems(libraryId: String, sort: String, unwatched: Boolean, cursor: String?, limit: Int, genre: String? = null): LibraryPageDto = call {
        http.get("libraries/" + libraryId.encodeURLPathPart() + "/items") {
            url.parameters.append("sort", sort)
            url.parameters.append("limit", limit.toString())
            if (unwatched) url.parameters.append("unwatched", "true")
            if (genre != null) url.parameters.append("genre", genre)
            if (cursor != null) url.parameters.append("cursor", cursor)
        }.decode()
    }

    internal suspend fun libraryHome(): LibraryHomeDto = call {
        http.get("library/home").decode()
    }

    internal suspend fun librarySearch(query: String): LibraryPageDto = call {
        http.get("library/search") { url.parameters.append("q", query) }.decode()
    }

    internal suspend fun libraryItem(id: String): LibraryItemDetailDto = call {
        http.get(itemPath(id)).decode()
    }

    /**
     * Text subtitle track [index] of the item [id] as WebVTT. The server reads the whole file the first time, so the
     * request may take minutes.
     */
    internal suspend fun librarySubtitles(id: String, index: Int): String = call {
        http.get(itemPath(id) + "/subtitles/$index") {
            timeout {
                requestTimeoutMillis = SUBTITLE_TIMEOUT_MS
                socketTimeoutMillis = SUBTITLE_TIMEOUT_MS
            }
        }.let { response ->
            response.checkSuccess()
            response.bodyAsText()
        }
    }

    /** Saves where the household stopped in [id], or marks it (a show or season: every episode in it) watched or not. */
    internal suspend fun updatePlayState(id: String, update: UpdatePlayStateDto): LibraryItemDto = call {
        http.put(itemPath(id) + "/play-state") { json(update) }.decode()
    }

    internal suspend fun openVod(request: OpenVodPlaybackDto): VodSessionDto = call {
        http.post("vod-sessions") { json(request) }.decode()
    }

    /** Renews the lease, or with [startMs] restarts a converted stream there under a new epoch. */
    internal suspend fun updateVod(id: String, startMs: Long?): VodSessionDto = call {
        http.patch(vodPath(id)) { json(UpdateVodPlaybackDto(startMs)) }.decode()
    }

    internal suspend fun releaseVod(id: String) = call {
        http.delete(vodPath(id)).checkSuccess()
    }

    /** The server's id, time and [CapabilitiesDto]: what it offers beyond live TV. */
    internal suspend fun bootstrap(): BootstrapDto = call {
        http.get("bootstrap").decode()
    }

    /** Every recording but cancelled ones, soonest scheduled first. */
    internal suspend fun recordings(): RecordingListDto = call {
        http.get("recordings").decode()
    }

    /** Records the programme on [ScheduleRecordingDto.channelId] that starts at [ScheduleRecordingDto.startsAt]. */
    internal suspend fun scheduleRecording(request: ScheduleRecordingDto): RecordingDto = call {
        http.post("recordings") { json(request) }.decode()
    }

    /** Cancels a scheduled recording, stops a running one, or deletes a finished one and its file. */
    internal suspend fun deleteRecording(id: String) = call {
        http.delete("recordings/" + id.encodeURLPathPart()).checkSuccess()
    }

    /** Saves where the household is in a recording, whether it is watched, or whether cleanup keeps it. */
    internal suspend fun updateRecording(id: String, update: UpdateRecordingDto): RecordingDto = call {
        http.patch("recordings/" + id.encodeURLPathPart()) { json(update) }.decode()
    }

    internal suspend fun recordingRules(): RecordingRuleListDto = call {
        http.get("recording-rules").decode()
    }

    internal suspend fun createRecordingRule(request: CreateRecordingRuleDto): RecordingRuleDto = call {
        http.post("recording-rules") { json(request) }.decode()
    }

    /** Changes a series rule; the server schedules again from it. */
    internal suspend fun updateRecordingRule(id: String, update: UpdateRecordingRuleDto): RecordingRuleDto = call {
        http.patch("recording-rules/" + id.encodeURLPathPart()) { json(update) }.decode()
    }

    /** Deletes a series rule and the recordings it scheduled; the ones it already made stay. */
    internal suspend fun deleteRecordingRule(id: String) = call {
        http.delete("recording-rules/" + id.encodeURLPathPart()).checkSuccess()
    }

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

    /** The session's endpoint, relative to the API, with [id] encoded as one path segment. */
    private fun sessionPath(id: String) = "playback-sessions/" + id.encodeURLPathPart()

    private fun vodPath(id: String) = "vod-sessions/" + id.encodeURLPathPart()

    private fun itemPath(id: String) = "library/items/" + id.encodeURLPathPart()

    private inline fun <reified T> HttpRequestBuilder.json(body: T) {
        setBody(TextContent(wireJson.encodeToString(body), JSON))
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T {
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

    private suspend fun HttpResponse.checkSuccess() {
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

        /** How long the first read of a subtitle track may take, as the server reads the whole file. */
        const val SUBTITLE_TIMEOUT_MS = 5 * 60_000L
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

package dev.jamesrich.sofaking.backend.client

import dev.jamesrich.sofaking.backend.api.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class BackendException(val error: ApiError) : RuntimeException(error.message)
sealed interface PlaybackState {
    data class Ready(val session: PlaybackSession) : PlaybackState
    data class Unavailable(val code: String, val retryable: Boolean) : PlaybackState
    data object Closed : PlaybackState
}

/** Owns leases and cleanup; callers only observe state, promote and release the handle. */
class SofaBackendClient(baseUrl: String, private val token: String? = null, httpClient: HttpClient? = null) {
    private val base = URI(baseUrl.trimEnd('/') + "/").also { require(it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null) }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val ownsHttp = httpClient == null
    private val http = httpClient ?: HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5000 } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val handles = mutableSetOf<PlaybackHandle>()
    private var closed = false
    suspend fun bootstrap(): Bootstrap = get("/api/v1/bootstrap")
    suspend fun catalog(): Catalog = get("/api/v1/channels")
    suspend fun guide(query: GuideQuery): GuideWindow = decode(request("/api/v1/guide/query", HttpMethod.Post, json.encodeToString(query)))
    fun mediaUrl(descriptor: PlaybackDescriptor): String = base.resolve(descriptor.url).toString()
    suspend fun openPlayback(channelId: String, intent: String = "watch"): PlaybackHandle {
        mutex.withLock { check(!closed) { "Client is closed" } }
        val body = json.encodeToString(OpenPlayback(channelId, intent, UUID.randomUUID().toString()))
        // Parse inside a bounded non-cancellable block, so a lost UI request still learns which session to release.
        var session: PlaybackSession? = null
        try {
            val opened = withContext(NonCancellable) { withTimeout(12_000) { decode<PlaybackSession>(request("/api/v1/playback-sessions", HttpMethod.Post, body)).also { session = it } } }
            currentCoroutineContext().ensureActive()
            val handle = PlaybackHandle(opened)
            mutex.withLock { check(!closed) { "Client is closed" }; handles += handle }
            handle.start()
            return handle
        } catch (failure: Throwable) {
            session?.let { withContext(NonCancellable) { runCatching { release(it.id) } } }
            throw failure
        }
    }
    suspend fun close() {
        val active = mutex.withLock { closed = true; handles.toList() }
        withContext(NonCancellable) { active.forEach { it.close() } }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        if (ownsHttp) http.close()
    }
    inner class PlaybackHandle internal constructor(initial: PlaybackSession) {
        private val id = initial.id
        private val mutable = MutableStateFlow<PlaybackState>(PlaybackState.Ready(initial))
        val state: StateFlow<PlaybackState> = mutable.asStateFlow()
        private val lock = Mutex()
        private var lease: Job? = null
        private var released = false
        internal fun start() {
            lease = scope.launch {
                while (isActive) {
                    delay(30_000)
                    try {
                        val renewed = renew(null)
                        val current: PlaybackSession = get("/api/v1/playback-sessions/$id")
                        lock.withLock { if (!released) mutable.value = PlaybackState.Ready(current.copy(expiresAt = renewed.expiresAt)) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: BackendException) { mutable.value = PlaybackState.Unavailable(e.error.code, e.error.retryable); if (!e.error.retryable) break }
                    catch (_: Exception) { mutable.value = PlaybackState.Unavailable("NETWORK_UNAVAILABLE", true) }
                }
            }
        }
        suspend fun promoteToWatching(): PlaybackSession = lock.withLock {
            check(!released) { "Playback is closed" }
            renew("watch").also { mutable.value = PlaybackState.Ready(it) }
        }
        suspend fun close() {
            lock.withLock { if (released) return; released = true; mutable.value = PlaybackState.Closed }
            lease?.cancelAndJoin()
            try { withContext(NonCancellable) { release(id) } }
            finally { mutex.withLock { handles -= this } }
        }
        private suspend fun renew(intent: String?): PlaybackSession = decode(request("/api/v1/playback-sessions/$id", HttpMethod.Patch, json.encodeToString(UpdatePlayback(intent))))
    }
    private suspend fun release(id: String) { request("/api/v1/playback-sessions/$id", HttpMethod.Delete) }
    private suspend inline fun <reified T> get(path: String): T = decode(request(path, HttpMethod.Get))
    private inline fun <reified T> decode(response: String): T = json.decodeFromString(response)
    private suspend fun request(path: String, method: HttpMethod, body: String? = null): String {
        val response = http.request(base.resolve(path).toString()) {
            this.method = method
            token?.let { bearerAuth(it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<ApiError>(text) }.getOrElse { ApiError("HTTP_${response.status.value}", "Backend request failed", response.status.value >= 500) }
            throw BackendException(error)
        }
        return text
    }
}

package dev.jamesrich.sofaking.backend.client

import dev.jamesrich.sofaking.backend.api.ApiError
import dev.jamesrich.sofaking.backend.api.Bootstrap
import dev.jamesrich.sofaking.backend.api.Catalog
import dev.jamesrich.sofaking.backend.api.GuideQuery
import dev.jamesrich.sofaking.backend.api.GuideWindow
import dev.jamesrich.sofaking.backend.api.OpenPlayback
import dev.jamesrich.sofaking.backend.api.PlaybackDescriptor
import dev.jamesrich.sofaking.backend.api.PlaybackSession
import dev.jamesrich.sofaking.backend.api.UpdatePlayback
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

class BackendException(
    val error: ApiError,
) : RuntimeException(error.message)

sealed interface PlaybackState {
    data class Ready(
        val session: PlaybackSession,
    ) : PlaybackState

    data class Unavailable(
        val code: String,
        val retryable: Boolean,
    ) : PlaybackState

    data object Closed : PlaybackState
}

/** Owns leases and cleanup; callers only observe state, promote and release the handle. */
class SofaBackendClient(
    baseUrl: String,
    private val token: String? = null,
    httpClient: HttpClient? = null,
) {
    private val base =
        URI(baseUrl.trimEnd('/') + "/").also {
            require(
                it.scheme in
                    setOf(
                        "http",
                        "https",
                    ) && it.host != null && it.userInfo == null && it.rawQuery == null && it.rawFragment == null,
            )
        }
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    private val ownsHttp = httpClient == null
    private val http =
        httpClient ?: HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5000
            }
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val handles = mutableSetOf<PlaybackHandle>()
    private var closed = false

    suspend fun bootstrap(): Bootstrap = get("/api/v1/bootstrap")

    suspend fun catalog(): Catalog = get("/api/v1/channels")

    suspend fun guide(query: GuideQuery): GuideWindow = decode(request("/api/v1/guide/query", HttpMethod.Post, json.encodeToString(query)))

    fun mediaUrl(descriptor: PlaybackDescriptor): String = base.resolve(descriptor.url).toString()

    suspend fun openPlayback(
        channelId: String,
        intent: String = "watch",
    ): PlaybackHandle =
        mutex.withLock {
            check(!closed) { "Client is closed" }
            val body = json.encodeToString(OpenPlayback(channelId, intent, UUID.randomUUID().toString()))
            // Parse inside a bounded non-cancellable block, so a lost UI request still learns which session to release.
            var session: PlaybackSession? = null
            try {
                val opened =
                    withContext(NonCancellable) {
                        withTimeout(12_000) {
                            decode<PlaybackSession>(request("/api/v1/playback-sessions", HttpMethod.Post, body)).also {
                                session =
                                    it
                            }
                        }
                    }
                currentCoroutineContext().ensureActive()
                val handle = PlaybackHandle(opened)
                handles += handle
                handle.start()
                return@withLock handle
            } catch (failure: Throwable) {
                session?.let { withContext(NonCancellable) { runCatching { release(it.id) } } }
                throw failure
            }
        }

    suspend fun close(): Unit =
        withContext(NonCancellable) {
            val active =
                mutex.withLock {
                    closed = true
                    handles.toList()
                }
            active.forEach { runCatching { it.close() } }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            if (ownsHttp) http.close()
        }

    inner class PlaybackHandle internal constructor(
        initial: PlaybackSession,
    ) {
        private val id = initial.id
        private val mutable = MutableStateFlow<PlaybackState>(PlaybackState.Ready(initial))
        val state: StateFlow<PlaybackState> = mutable.asStateFlow()
        private val lock = Mutex()
        private var lease: Job? = null
        private var released = false

        internal fun start() {
            lease =
                scope.launch {
                    while (isActive) {
                        delay(30_000)
                        try {
                            val renewed = renew(null)
                            val current: PlaybackSession = get("/api/v1/playback-sessions/$id")
                            lock.withLock {
                                if (!released) {
                                    mutable.value =
                                        PlaybackState.Ready(
                                            current.copy(expiresAt = renewed.expiresAt),
                                        )
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: BackendException) {
                            lock.withLock {
                                if (!released) {
                                    mutable.value =
                                        PlaybackState.Unavailable(e.error.code, e.error.retryable)
                                }
                            }
                            if (!e.error.retryable) break
                        } catch (_: Exception) {
                            lock.withLock {
                                if (!released) {
                                    mutable.value =
                                        PlaybackState.Unavailable("NETWORK_UNAVAILABLE", true)
                                }
                            }
                        }
                    }
                }
        }

        suspend fun promoteToWatching(): PlaybackSession =
            lock.withLock {
                check(!released) { "Playback is closed" }
                renew("watch").also { mutable.value = PlaybackState.Ready(it) }
            }

        suspend fun close(): Unit =
            withContext(NonCancellable) {
                lock.withLock {
                    if (released) return@withContext
                    released = true
                    mutable.value = PlaybackState.Closed
                }
                lease?.cancelAndJoin()
                try {
                    withContext(NonCancellable) { release(id) }
                } finally {
                    mutex.withLock { handles -= this@PlaybackHandle }
                }
            }

        private suspend fun renew(intent: String?): PlaybackSession =
            decode(request("/api/v1/playback-sessions/$id", HttpMethod.Patch, json.encodeToString(UpdatePlayback(intent))))
    }

    private suspend fun release(id: String): Unit =
        withTimeout(12_000) {
            request("/api/v1/playback-sessions/$id", HttpMethod.Delete)
            Unit
        }

    private suspend inline fun <reified T> get(path: String): T = decode(request(path, HttpMethod.Get))

    private inline fun <reified T> decode(response: String): T = json.decodeFromString(response)

    private suspend fun request(
        path: String,
        method: HttpMethod,
        body: String? = null,
    ): String {
        val response =
            http.request(base.resolve(path).toString()) {
                this.method = method
                token?.let { bearerAuth(it) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error =
                runCatching { json.decodeFromString<ApiError>(text) }.getOrElse {
                    ApiError(
                        "HTTP_${response.status.value}",
                        "Backend request failed",
                        response.status.value >= 500,
                    )
                }
            throw BackendException(error)
        }
        return text
    }
}

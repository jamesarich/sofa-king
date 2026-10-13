package dev.jamesrich.sofaking.backend.client

import dev.jamesrich.sofaking.backend.api.ApiError
import dev.jamesrich.sofaking.backend.api.OpenPlayback
import dev.jamesrich.sofaking.backend.api.PlaybackDescriptor
import dev.jamesrich.sofaking.backend.api.PlaybackSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SofaBackendClientTest {
    @Test fun `UI cancellation after creation releases the late session response`() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val complete = CompletableDeferred<Unit>()
            val deletes = AtomicInteger()
            val engine =
                MockEngine { request ->
                    if (request.method == HttpMethod.Delete) {
                        deletes.incrementAndGet()
                        respond("", HttpStatusCode.NoContent)
                    } else {
                        started.complete(Unit)
                        complete.await()
                        respond(
                            Json.encodeToString(
                                PlaybackSession(
                                    "session",
                                    "channel",
                                    "preview",
                                    "2026-10-10T14:00:00Z",
                                    PlaybackDescriptor("hls", "application/vnd.apple.mpegurl", "/media/root.m3u8", 1),
                                ),
                            ),
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            HttpClient(engine).use { http ->
                val sdk = SofaBackendClient("http://backend.test", httpClient = http)
                try {
                    val ui = launch { sdk.openPlayback("channel", "preview") }
                    started.await()
                    ui.cancel()
                    complete.complete(Unit)
                    ui.join()
                    assertEquals(1, deletes.get())
                } finally {
                    sdk.close()
                }
            }
        }

    @Test fun `a retried create reuses one idempotency key and a client error is not retried`() =
        runBlocking<Unit> {
            val bodies = mutableListOf<String>()
            val deletes = AtomicInteger()
            val engine =
                MockEngine { request ->
                    when (request.method) {
                        HttpMethod.Delete -> {
                            deletes.incrementAndGet()
                            respond("", HttpStatusCode.NoContent)
                        }
                        else -> {
                            val body = String(request.body.toByteArray())
                            bodies += body
                            when (bodies.size) {
                                1 -> throw java.io.IOException("connection reset")
                                2 -> respond(Json.encodeToString(ApiError("INTERNAL_ERROR", "Request failed", true)), HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                                3 -> respond(
                                    Json.encodeToString(PlaybackSession("session", "channel", "preview", "2026-10-10T14:00:00Z", PlaybackDescriptor("hls", "application/vnd.apple.mpegurl", "/media/root.m3u8", 1))),
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                                else -> respond(Json.encodeToString(ApiError("CHANNEL_NOT_FOUND", "Unknown channel", false)), HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                            }
                        }
                    }
                }
            HttpClient(engine).use { http ->
                val sdk = SofaBackendClient("http://backend.test", httpClient = http, retryBackoffMillis = 0)
                val handle = sdk.openPlayback("channel", "preview")
                assertEquals(3, bodies.size)
                assertEquals(1, bodies.map { Json.decodeFromString<OpenPlayback>(it).idempotencyKey }.distinct().size)
                assertEquals("CHANNEL_NOT_FOUND", assertFailsWith<BackendException> { sdk.openPlayback("missing", "preview") }.error.code)
                assertEquals(4, bodies.size)
                handle.close()
                sdk.close()
                assertEquals(1, deletes.get())
            }
        }

    @Test fun `closing from a cancelled coroutine still releases a lease`() =
        runBlocking<Unit> {
            val deletes = AtomicInteger()
            val engine =
                MockEngine { request ->
                    if (request.method == HttpMethod.Delete) {
                        deletes.incrementAndGet()
                        respond("", HttpStatusCode.NoContent)
                    } else {
                        respond(
                            Json.encodeToString(
                                PlaybackSession(
                                    "session",
                                    "channel",
                                    "preview",
                                    "2026-10-10T14:00:00Z",
                                    PlaybackDescriptor("hls", "application/vnd.apple.mpegurl", "/media/root.m3u8", 1),
                                ),
                            ),
                        )
                    }
                }
            HttpClient(engine).use { http ->
                val sdk = SofaBackendClient("http://backend.test", httpClient = http)
                val handle = sdk.openPlayback("channel")
                launch {
                    currentCoroutineContext().cancel()
                    handle.close()
                    sdk.close()
                }.join()
                assertEquals(1, deletes.get())
                assertEquals(PlaybackState.Closed, handle.state.value)
            }
        }

    @Test fun `client close waits for pending creation before releasing it`() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val complete = CompletableDeferred<Unit>()
            val deletes = AtomicInteger()
            val engine =
                MockEngine { request ->
                    if (request.method == HttpMethod.Delete) {
                        deletes.incrementAndGet()
                        respond("", HttpStatusCode.NoContent)
                    } else {
                        started.complete(Unit)
                        complete.await()
                        respond(
                            Json.encodeToString(
                                PlaybackSession(
                                    "session",
                                    "channel",
                                    "preview",
                                    "2026-10-10T14:00:00Z",
                                    PlaybackDescriptor("hls", "application/vnd.apple.mpegurl", "/media/root.m3u8", 1),
                                ),
                            ),
                        )
                    }
                }
            HttpClient(engine).use { http ->
                val sdk = SofaBackendClient("http://backend.test", httpClient = http)
                val opening = async { sdk.openPlayback("channel") }
                started.await()
                val closing = launch { sdk.close() }
                yield()
                assertFalse(closing.isCompleted)
                complete.complete(Unit)
                val handle = opening.await()
                closing.join()
                assertEquals(1, deletes.get())
                assertEquals(PlaybackState.Closed, handle.state.value)
                assertFailsWith<IllegalStateException> { sdk.openPlayback("channel") }
            }
        }
}

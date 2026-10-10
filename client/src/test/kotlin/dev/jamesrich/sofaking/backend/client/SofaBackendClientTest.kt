package dev.jamesrich.sofaking.backend.client

import dev.jamesrich.sofaking.backend.api.PlaybackDescriptor
import dev.jamesrich.sofaking.backend.api.PlaybackSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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

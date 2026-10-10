package dev.jamesrich.sofaking.backend.client

import dev.jamesrich.sofaking.backend.api.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class SofaBackendClientTest {
    @Test fun `UI cancellation after creation releases the late session response`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val complete = CompletableDeferred<Unit>()
        val deletes = AtomicInteger()
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Delete) { deletes.incrementAndGet(); respond("", HttpStatusCode.NoContent) }
            else {
                started.complete(Unit); complete.await()
                respond(Json.encodeToString(PlaybackSession("session", "channel", "preview", "2026-10-10T14:00:00Z", PlaybackDescriptor("hls", "application/vnd.apple.mpegurl", "/media/root.m3u8", 1))), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        HttpClient(engine).use { http ->
            val sdk = SofaBackendClient("http://backend.test", httpClient = http)
            try {
                val ui = launch { sdk.openPlayback("channel", "preview") }
                started.await(); ui.cancel(); complete.complete(Unit); ui.join()
                assertEquals(1, deletes.get())
            } finally { sdk.close() }
        }
    }
}

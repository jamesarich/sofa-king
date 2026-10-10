package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.readHead
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.*

class RelayTest {
    @Test fun `two MPEG-TS sessions on one source share a single upstream connection`() = testApplication {
        val dir = Files.createTempDirectory("sofa-relay-ts-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(listOf(fixture.tsFeed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token), MutableClock())
            try {
                runtime.feeds.refresh(fixture.tsFeed)
                application { backendModule(store, MutableClock(), runtime, token) }
                val channel = Json.decodeFromString<Catalog>(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()).channels.single()
                val opened = listOf("one", "two").map { key ->
                    val response = client.post("/api/v1/playback-sessions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(OpenPlayback(channel.id, "watch", key))) }
                    Json.decodeFromString<PlaybackSession>(response.bodyAsText())
                }
                assertEquals("mpegts", opened.first().descriptor.protocol)
                val heads = withTimeout(20_000) {
                    opened.map { session ->
                        async {
                            client.prepareGet(session.descriptor.url).execute { response ->
                                assertEquals(HttpStatusCode.OK, response.status)
                                assertEquals("video/mp2t", response.headers[HttpHeaders.ContentType])
                                response.bodyAsChannel().readHead(188 * 3)
                            }
                        }
                    }.awaitAll()
                }
                for (head in heads) {
                    assertEquals(188 * 3, head.size)
                    for (packet in 0 until 3) assertEquals(0x47.toByte(), head[packet * 188])
                }
                assertEquals(1, fixture.tsConnections.get())
                for (session in opened) client.delete("/api/v1/playback-sessions/${session.id}") { bearerAuth(token) }
                withTimeout(20_000) { while (fixture.tsClosed.get() < 1) kotlinx.coroutines.delay(50) }
                assertEquals(0, runtime.relay.shared.active())
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}

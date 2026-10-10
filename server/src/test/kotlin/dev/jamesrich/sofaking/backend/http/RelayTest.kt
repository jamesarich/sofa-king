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
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true), MutableClock())
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

    @Test fun `LL-HLS directives reach the upstream playlist and a refused upstream answers 502`() = testApplication {
        val dir = Files.createTempDirectory("sofa-relay-hls-edges-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(listOf(fixture.feed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true), MutableClock())
            try {
                runtime.feeds.refresh(fixture.feed)
                application { backendModule(store, MutableClock(), runtime, token) }
                val channel = Json.decodeFromString<Catalog>(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()).channels.single()
                val session = Json.decodeFromString<PlaybackSession>(client.post("/api/v1/playback-sessions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(OpenPlayback(channel.id, "watch", "edges"))) }.bodyAsText())
                val variants = client.get(session.descriptor.url).bodyAsText().lineSequence().filter { it.startsWith("/media/") }.toList()
                assertEquals(2, variants.size)
                assertEquals(HttpStatusCode.OK, client.get(variants[0] + "?_HLS_msn=3&_HLS_part=1&_HLS_skip=YES&unrelated=1").status)
                assertEquals("_HLS_msn=3&_HLS_part=1&_HLS_skip=YES", fixture.lastMediaQuery)
                assertEquals(HttpStatusCode.OK, client.get(variants[0]).status)
                assertNull(fixture.lastMediaQuery)
                val refused = client.get(variants[1])
                assertEquals(HttpStatusCode.BadGateway, refused.status)
                assertEquals("UPSTREAM_REJECTED", Json.decodeFromString<ApiError>(refused.bodyAsText()).code)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `descriptor follows the detected protocol after a mismatched first fetch`() = testApplication {
        val dir = Files.createTempDirectory("sofa-relay-protocol-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val feeds = listOf(fixture.extensionlessFeed, fixture.mislabeledFeed)
            val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(feeds))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true), MutableClock())
            try {
                feeds.forEach { runtime.feeds.refresh(it) }
                application { backendModule(store, MutableClock(), runtime, token) }
                val catalog = Json.decodeFromString<Catalog>(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()).channels
                suspend fun open(channel: Channel, key: String): PlaybackSession =
                    Json.decodeFromString(client.post("/api/v1/playback-sessions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(OpenPlayback(channel.id, "preview", key))) }.bodyAsText())
                suspend fun session(id: String): PlaybackSession = Json.decodeFromString(client.get("/api/v1/playback-sessions/$id") { bearerAuth(token) }.bodyAsText())

                // Extensionless HLS imports as MPEG-TS; the first fetch finds a playlist.
                val extensionless = open(catalog.single { it.name == "Extensionless" }, "ext")
                assertEquals("mpegts", extensionless.descriptor.protocol); assertTrue(extensionless.descriptor.url.endsWith(".ts"))
                val mismatch = client.get(extensionless.descriptor.url)
                assertEquals(HttpStatusCode.Gone, mismatch.status)
                assertEquals("STREAM_CHANGED", Json.decodeFromString<ApiError>(mismatch.bodyAsText()).code)
                val corrected = session(extensionless.id)
                assertEquals(2, corrected.descriptor.epoch); assertEquals("hls", corrected.descriptor.protocol)
                assertEquals("application/vnd.apple.mpegurl", corrected.descriptor.mimeType); assertTrue(corrected.descriptor.url.endsWith(".m3u8"))
                val manifest = client.get(corrected.descriptor.url)
                assertEquals(HttpStatusCode.OK, manifest.status)
                assertTrue(manifest.headers[HttpHeaders.ContentType]!!.contains("mpegurl"))
                assertTrue(manifest.bodyAsText().lineSequence().any { it.startsWith("/media/sessions/${extensionless.id}/2/") })
                val later = open(catalog.single { it.name == "Extensionless" }, "ext-later")
                assertEquals("hls", later.descriptor.protocol); assertEquals(1, later.descriptor.epoch)

                // A .m3u8 path serving MPEG-TS goes the other way.
                val mislabeled = open(catalog.single { it.name == "Mislabeled" }, "fake")
                assertEquals("hls", mislabeled.descriptor.protocol)
                assertEquals(HttpStatusCode.Gone, client.get(mislabeled.descriptor.url).status)
                val ts = session(mislabeled.id)
                assertEquals("mpegts", ts.descriptor.protocol); assertEquals("video/mp2t", ts.descriptor.mimeType); assertTrue(ts.descriptor.url.endsWith(".ts"))
                val head = withTimeout(20_000) { client.prepareGet(ts.descriptor.url).execute { it.bodyAsChannel().readHead(188 * 3) } }
                assertEquals(0x47.toByte(), head[0]); assertEquals(0x47.toByte(), head[188])
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}

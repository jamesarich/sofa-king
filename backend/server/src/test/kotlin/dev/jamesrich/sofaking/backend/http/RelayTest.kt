package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.offeringProtocols
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.readHead
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.origin
import io.ktor.http.path
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayTest {
    @Test fun `a gzip-encoded media playlist and key reach the player as the bytes they encode`() = testApplication {
        val dir = Files.createTempDirectory("sofa-relay-gzip-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            fixture.gzipEncoded = true
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(listOf(fixture.feed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true),
                MutableClock(),
            )
            try {
                runtime.feeds.refresh(fixture.feed)
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token) }
                val channel = Json.decodeFromString<Catalog>(
                    client.get("/api/v1/channels") {
                        bearerAuth(token)
                    }.bodyAsText(),
                ).channels.single()
                val session = Json.decodeFromString<PlaybackSession>(
                    client.post("/api/v1/playback-sessions") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(OpenPlayback(channel.id, "watch", "gzip")))
                    }.bodyAsText(),
                )
                val variant = client.get(session.descriptor.url).bodyAsText().lineSequence().first {
                    it.startsWith("/media/")
                }
                val media = client.get(variant)
                assertEquals(HttpStatusCode.OK, media.status)
                val playlist = media.bodyAsText()
                assertTrue(
                    playlist.startsWith("#EXTM3U") &&
                        playlist.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"/media/sessions/"),
                    playlist,
                )
                val key = client.get(Regex("URI=\"([^\"]+)\"").find(playlist)!!.groupValues[1]) {
                    header(HttpHeaders.Range, "bytes=0-")
                }
                assertEquals(HttpStatusCode.OK, key.status)
                assertEquals(
                    "0123456789abcdef",
                    key.bodyAsText(),
                    "the key is inflated, not the gzip bytes the origin sent",
                )
                assertNull(key.headers[HttpHeaders.ContentEncoding])
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `two MPEG-TS sessions on one source share a single upstream connection`() = testApplication {
        val dir = Files.createTempDirectory("sofa-relay-ts-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(listOf(fixture.tsFeed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true),
                MutableClock(),
            )
            try {
                runtime.feeds.refresh(fixture.tsFeed)
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token) }
                val channel = Json.decodeFromString<Catalog>(
                    client.get("/api/v1/channels") {
                        bearerAuth(token)
                    }.bodyAsText(),
                ).channels.single()
                val opened = listOf("one", "two").map { key ->
                    val response = client.post("/api/v1/playback-sessions") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(OpenPlayback(channel.id, "watch", key)))
                    }
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
                pollUntil(20_000, 50) { fixture.tsClosed.get() >= 1 }
                assertEquals(0, runtime.relay.shared.active())
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a request waiting on a shared stream when its session is released answers 410, not 502`() = testApplication {
        val dir = Files.createTempDirectory("sofa-relay-release-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            fixture.tsStartDelayMs = 3_000
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(listOf(fixture.tsFeed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true),
                MutableClock(),
            )
            try {
                runtime.feeds.refresh(fixture.tsFeed)
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token) }
                val channel = Json.decodeFromString<Catalog>(
                    client.get("/api/v1/channels") {
                        bearerAuth(token)
                    }.bodyAsText(),
                ).channels.single()
                val session = Json.decodeFromString<PlaybackSession>(
                    client.post("/api/v1/playback-sessions") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(OpenPlayback(channel.id, "watch", "release")))
                    }.bodyAsText(),
                )
                val answered = coroutineScope {
                    val media = async { client.get(session.descriptor.url) }
                    // The player zaps away while the upstream has yet to answer.
                    pollUntil(20_000, 20) { fixture.tsConnections.get() >= 1 }
                    assertEquals(
                        HttpStatusCode.NoContent,
                        client.delete("/api/v1/playback-sessions/${session.id}") {
                            bearerAuth(token)
                        }.status,
                    )
                    withTimeout(20_000) { media.await() }
                }
                assertEquals(HttpStatusCode.Gone, answered.status)
                assertEquals(
                    Wire.ErrorCode.SESSION_EXPIRED,
                    Json.decodeFromString<ApiError>(answered.bodyAsText()).code,
                )
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a recording of an MPEG-TS channel someone watches reads the viewer's upstream connection`() = testApplication {
        val dir = Files.createTempDirectory("sofa-relay-record-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(listOf(fixture.tsFeed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true),
                MutableClock(),
            )
            try {
                runtime.feeds.refresh(fixture.tsFeed)
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token) }
                val channel = Json.decodeFromString<Catalog>(
                    client.get("/api/v1/channels") {
                        bearerAuth(token)
                    }.bodyAsText(),
                ).channels.single()
                val session = Json.decodeFromString<PlaybackSession>(
                    client.post("/api/v1/playback-sessions") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(OpenPlayback(channel.id, "watch", "viewer")))
                    }.bodyAsText(),
                )
                val recorder = dev.jamesrich.sofaking.backend.dvr.Recorder(
                    "ffmpeg",
                    runtime.sessions,
                    runtime.feeds,
                    java.time.Clock.systemUTC(),
                    runtime.loopback,
                    shared = runtime.relay::joinShared,
                )
                val file = dir.resolve("recording.ts")
                val (head, take) = withTimeout(30_000) {
                    client.prepareGet(session.descriptor.url).execute { response ->
                        val head = response.bodyAsChannel().readHead(188 * 3)
                        head to recorder.record(channel.id, file, java.time.Instant.now().plusSeconds(2))
                    }
                }
                assertEquals(0x47.toByte(), head[0])
                assertNull(take.lastFailure)
                val bytes = Files.readAllBytes(file)
                assertTrue(bytes.size >= 188 * 10 && bytes.size % 188 == 0, "recorded ${bytes.size} bytes")
                assertEquals(0x47.toByte(), bytes[0])
                assertEquals(1, fixture.tsConnections.get(), "the recording opened no second upstream connection")
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `LL-HLS directives reach the upstream playlist and a refused upstream answers 502`() = testApplication {
        val dir = Files.createTempDirectory("sofa-relay-hls-edges-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(listOf(fixture.feed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true),
                MutableClock(),
            )
            try {
                runtime.feeds.refresh(fixture.feed)
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token) }
                val channel = Json.decodeFromString<Catalog>(
                    client.get("/api/v1/channels") {
                        bearerAuth(token)
                    }.bodyAsText(),
                ).channels.single()
                val session = Json.decodeFromString<PlaybackSession>(
                    client.post("/api/v1/playback-sessions") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(OpenPlayback(channel.id, "watch", "edges")))
                    }.bodyAsText(),
                )
                val variants = client.get(session.descriptor.url).bodyAsText().lineSequence().filter {
                    it.startsWith("/media/")
                }.toList()
                assertEquals(2, variants.size)
                assertEquals(
                    HttpStatusCode.OK,
                    client.get(variants[0] + "?_HLS_msn=3&_HLS_part=1&_HLS_skip=YES&unrelated=1").status,
                )
                assertEquals("_HLS_msn=3&_HLS_part=1&_HLS_skip=YES", fixture.lastMediaQuery)
                assertEquals(HttpStatusCode.OK, client.get(variants[0]).status)
                assertNull(fixture.lastMediaQuery)
                val refused = client.get(variants[1])
                assertEquals(HttpStatusCode.BadGateway, refused.status)
                assertEquals("UPSTREAM_REJECTED", Json.decodeFromString<ApiError>(refused.bodyAsText()).code)
                assertEquals(1, runtime.sessions.snapshot().single().stalls, "the refusal counts as a stall")
                val segment = client.get(variants[0]).bodyAsText().lineSequence().first { it.startsWith("/media/") }
                assertEquals(HttpStatusCode.OK, client.get(segment).status)
                assertEquals(1, runtime.sessions.snapshot().single().segmentsServed, "playlists are not segments")
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `an extensionless HLS source turns its session into HLS after the first fetch`() = relay(
        { listOf(it.extensionlessFeed, it.mislabeledFeed) },
    ) {
        // Extensionless HLS imports as MPEG-TS; the first fetch finds a playlist.
        val extensionless = open("Extensionless", "ext")
        assertEquals("mpegts", extensionless.descriptor.protocol)
        assertTrue(extensionless.descriptor.url.endsWith(".ts"))
        assertStreamChanged(client.get(extensionless.descriptor.url))
        val corrected = session(extensionless.id)
        assertEquals(2, corrected.descriptor.epoch)
        assertEquals("hls", corrected.descriptor.protocol)
        assertEquals("application/vnd.apple.mpegurl", corrected.descriptor.mimeType)
        assertTrue(corrected.descriptor.url.endsWith(".m3u8"))
        val manifest = client.get(corrected.descriptor.url)
        assertEquals(HttpStatusCode.OK, manifest.status)
        assertTrue(manifest.headers[HttpHeaders.ContentType]!!.contains("mpegurl"))
        assertTrue(
            manifest.bodyAsText().lineSequence().any { it.startsWith("/media/sessions/${extensionless.id}/2/") },
        )
    }

    @Test fun `a later session on a corrected source opens as HLS`() = relay(
        { listOf(it.extensionlessFeed, it.mislabeledFeed) },
    ) {
        client.get(open("Extensionless", "ext").descriptor.url)
        val later = open("Extensionless", "ext-later")
        assertEquals("hls", later.descriptor.protocol)
        assertEquals(1, later.descriptor.epoch)
    }

    @Test fun `a m3u8 path serving MPEG-TS turns its session into MPEG-TS after the first fetch`() = relay(
        { listOf(it.extensionlessFeed, it.mislabeledFeed) },
    ) {
        val mislabeled = open("Mislabeled", "fake")
        assertEquals("hls", mislabeled.descriptor.protocol)
        assertEquals(HttpStatusCode.Gone, client.get(mislabeled.descriptor.url).status)
        val ts = session(mislabeled.id)
        assertEquals("mpegts", ts.descriptor.protocol)
        assertEquals("video/mp2t", ts.descriptor.mimeType)
        assertTrue(ts.descriptor.url.endsWith(".ts"))
        val head = withTimeout(20_000) {
            client.prepareGet(ts.descriptor.url).execute { it.bodyAsChannel().readHead(188 * 3) }
        }
        assertEquals(0x47.toByte(), head[0])
        assertEquals(0x47.toByte(), head[188])
    }

    @Test fun `a persisted protocol serves the first fetch of a new process without a 410`() = testApplication {
        val dir = Files.createTempDirectory("sofa-relay-persisted-protocol-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(listOf(fixture.extensionlessFeed)))
            val config = ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true)
            // A previous process observed the body; either tier records it the same way.
            openStore(dir).use { store ->
                val runtime = LiveRuntime.create(store, config, MutableClock())
                try {
                    runtime.feeds.refresh(fixture.extensionlessFeed)
                    runtime.feeds.recordProtocol(runtime.feeds.published.offerings.single().id, "hls")
                } finally {
                    runtime.close()
                }
            }
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, MutableClock())
            try {
                runtime.feeds.refresh(fixture.extensionlessFeed)
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token) }
                val channel = Json.decodeFromString<Catalog>(
                    client.get("/api/v1/channels") {
                        bearerAuth(token)
                    }.bodyAsText(),
                ).channels.single()
                val session = Json.decodeFromString<PlaybackSession>(
                    client.post("/api/v1/playback-sessions") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(OpenPlayback(channel.id, "preview", "persisted")))
                    }.bodyAsText(),
                )
                assertEquals("hls", session.descriptor.protocol)
                assertEquals(1, session.descriptor.epoch)
                assertEquals("application/vnd.apple.mpegurl", session.descriptor.mimeType)
                assertTrue(session.descriptor.url.endsWith(".m3u8"))
                val manifest = client.get(session.descriptor.url)
                assertEquals(HttpStatusCode.OK, manifest.status)
                assertTrue(manifest.headers[HttpHeaders.ContentType]!!.contains("mpegurl"))
                assertTrue(
                    manifest.bodyAsText().lineSequence().any {
                        it.startsWith("/media/sessions/${session.id}/1/")
                    },
                )
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `a chunked HLS playlist on an unprobed TS path answers 410 and is stored as HLS`() = relay(
        { listOf(it.proxyFeed) },
    ) {
        val offering = runtime.feeds.published.offerings.single()
        assertEquals("mpegts", offering.protocol)
        assertEquals(fixture.proxyStreamUrl, offering.url)
        val opened = open("Proxied", "proxy", intent = "watch")
        assertEquals("mpegts", opened.descriptor.protocol)
        assertTrue(opened.descriptor.url.endsWith(".ts"))
        assertStreamChanged(withTimeout(20_000) { client.get(opened.descriptor.url) })
        assertEquals(mapOf(offering.id to "hls"), store.offeringProtocols())
        // The relay releases the shared upstream after the 410 is sent, so the count settles just after the response.
        pollUntil(10_000) { runtime.relay.shared.active() == 0 }
    }

    @Test fun `the proxy path's corrected session plays its playlist and a later one opens as HLS`() = relay(
        { listOf(it.proxyFeed) },
    ) {
        val opened = open("Proxied", "proxy", intent = "watch")
        withTimeout(20_000) { client.get(opened.descriptor.url) }
        val corrected = session(opened.id)
        assertEquals(2, corrected.descriptor.epoch)
        assertEquals("hls", corrected.descriptor.protocol)
        assertTrue(corrected.descriptor.url.endsWith(".m3u8"))
        val playlist = client.get(corrected.descriptor.url)
        assertEquals(HttpStatusCode.OK, playlist.status)
        val lines = playlist.bodyAsText().lines()
        assertTrue(lines.contains("#EXT-X-MEDIA-SEQUENCE:7"))
        assertTrue(lines.any { it.startsWith("/media/sessions/${opened.id}/2/") && it.endsWith(".ts") })
        assertEquals(2, fixture.proxyRequests.get())
        val later = open("Proxied", "proxy-later", intent = "watch")
        assertEquals("hls", later.descriptor.protocol)
        assertEquals(1, later.descriptor.epoch)
    }

    private suspend fun assertStreamChanged(response: HttpResponse) {
        assertEquals(HttpStatusCode.Gone, response.status)
        assertEquals("STREAM_CHANGED", Json.decodeFromString<ApiError>(response.bodyAsText()).code)
    }

    /** A server over the fixture's [feeds], adopted. */
    private class Relay(
        val app: ApplicationTestBuilder,
        val fixture: Fixture,
        val store: BackendStore,
        val runtime: LiveRuntime,
    ) {
        val client get() = app.client

        /** Opens a session on the channel named [name]. */
        suspend fun open(name: String, key: String, intent: String = "preview"): PlaybackSession {
            val channel = Json.decodeFromString<Catalog>(
                client.get("/api/v1/channels") { bearerAuth(TOKEN) }.bodyAsText(),
            ).channels.single { it.name == name }
            return Json.decodeFromString(
                client.post("/api/v1/playback-sessions") {
                    bearerAuth(TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(OpenPlayback(channel.id, intent, key)))
                }.bodyAsText(),
            )
        }

        suspend fun session(id: String): PlaybackSession =
            Json.decodeFromString(client.get("/api/v1/playback-sessions/$id") { bearerAuth(TOKEN) }.bodyAsText())
    }

    private fun relay(feeds: (Fixture) -> List<FeedConfig>, block: suspend Relay.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-relay-")
        Fixture().use { fixture ->
            val configs = feeds(fixture)
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(configs))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                ServerConfig("127.0.0.1", 8080, dir, sources, TOKEN, allowLoopbackUpstreams = true),
                MutableClock(),
            )
            try {
                configs.forEach { runtime.feeds.refresh(it) }
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, TOKEN) }
                Relay(this, fixture, store, runtime).block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    private companion object {
        val TOKEN = "secret".repeat(8)
    }
}

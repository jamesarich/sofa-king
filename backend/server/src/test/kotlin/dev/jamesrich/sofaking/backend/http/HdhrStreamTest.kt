package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.readHead
import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.contract.Channel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The HDHomeRun stream on a real socket: FFmpeg remuxes live HLS to TS and dies with the client. */
class HdhrStreamTest {
    private val token = "secret".repeat(8)

    private class Stack(
        val runtime: LiveRuntime,
        val store: BackendStore,
        val server: RealServer,
        val channels: List<Channel>,
    ) : AutoCloseable {
        override fun close() {
            server.close()
            runBlocking { runtime.close() }
            store.close()
        }
    }

    private suspend fun stack(dir: Path, feeds: List<FeedConfig>, hdhr: HdhrConfig): Stack {
        val sources = dir.resolve("sources.json")
        Files.writeString(sources, Json.encodeToString(feeds))
        val store = openStore(dir)
        val runtime = LiveRuntime.create(
            store,
            ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, hdhr = hdhr),
            MutableClock(),
        )
        feeds.forEach { runtime.feeds.refresh(it) }
        runtime.adoptAll()
        val server = RealServer(store, MutableClock(), runtime, token, hdhr = hdhr)
        val channels = HttpClient(CIO).use { http ->
            Json.decodeFromString<Catalog>(
                http.get("${server.base}/api/v1/channels") {
                    bearerAuth(token)
                }.bodyAsText(),
            ).channels
        }
        return Stack(runtime, store, server, channels)
    }

    private fun ffmpegChildren(): Int = ProcessHandle.current().children().filter {
        it.info().command().orElse("").endsWith("ffmpeg")
    }.count().toInt()

    @Test fun `live HLS is remuxed to aligned MPEG-TS and the FFmpeg process dies when the client hangs up`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-hdhr-remux-")
            MediaFixture().use { media ->
                stack(dir, listOf(media.feed), HdhrConfig(tuners = 2)).use { stack ->
                    HttpClient(CIO).use { http ->
                        assertEquals(0, ffmpegChildren())
                        val packets = withTimeout(60_000) {
                            http.prepareGet("${stack.server.base}/auto/v2").execute { response ->
                                assertEquals(HttpStatusCode.OK, response.status)
                                assertEquals("video/mp2t", response.headers[HttpHeaders.ContentType])
                                val body = response.bodyAsChannel()
                                val early = body.readHead(188 * 20)
                                assertEquals(1, stack.runtime.remux.active())
                                assertEquals(1, stack.runtime.sessions.count())
                                assertEquals(1, ffmpegChildren())
                                // Longer than the player idle lease would matter for: the
                                // request itself holds the session.
                                delay(2500)
                                val later = body.readHead(188 * 5)
                                assertEquals(1, stack.runtime.sessions.count())
                                early + later
                            }
                        }
                        assertEquals(188 * 25, packets.size)
                        for (packet in 0 until 25) {
                            assertEquals(
                                0x47.toByte(),
                                packets[packet * 188],
                                "sync byte of packet $packet",
                            )
                        }
                        assertTrue(media.liveRequests.get() >= 1)
                        pollUntil(15_000, 50) {
                            stack.runtime.remux.active() == 0 && stack.runtime.sessions.count() == 0 &&
                                ffmpegChildren() == 0
                        }
                    }
                }
            }
            dir.toFile().deleteRecursively()
        }

    @Test fun `a source FFmpeg cannot open ends the response cleanly with the process reaped`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-hdhr-missing-")
        MediaFixture().use { media ->
            stack(dir, listOf(media.feed), HdhrConfig(tuners = 2)).use { stack ->
                HttpClient(CIO).use { http ->
                    val response = withTimeout(60_000) { http.get("${stack.server.base}/auto/v3") }
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals(0, response.bodyAsBytes().size)
                    pollUntil(15_000, 50) {
                        stack.runtime.remux.active() == 0 && stack.runtime.sessions.count() == 0 &&
                            ffmpegChildren() == 0
                    }
                    // The tuner is free again for a channel that works.
                    val head = withTimeout(60_000) {
                        http.prepareGet("${stack.server.base}/auto/v2").execute { it.bodyAsChannel().readHead(188 * 3) }
                    }
                    assertEquals(0x47.toByte(), head[0])
                }
            }
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `a second stream beyond the tuner count is refused the HDHomeRun way while the first plays`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-hdhr-tuners-")
            MediaFixture().use { media ->
                Fixture().use { fixture ->
                    stack(dir, listOf(media.feed, fixture.tsFeed), HdhrConfig(tuners = 1)).use { stack ->
                        HttpClient(CIO).use { http ->
                            withTimeout(60_000) {
                                http.prepareGet("${stack.server.base}/auto/v2").execute { response ->
                                    assertEquals(HttpStatusCode.OK, response.status)
                                    response.bodyAsChannel().readHead(188 * 3)
                                    val refused = http.get("${stack.server.base}/auto/v6")
                                    assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
                                    assertEquals("805 All Tuners In Use", refused.headers["X-HDHomeRun-Error"])
                                }
                            }
                            pollUntil(15_000, 50) {
                                stack.runtime.remux.active() == 0 && stack.runtime.sessions.count() == 0
                            }
                            // With the tuner free again, a TS source streams through the fan-out.
                            val head = withTimeout(20_000) {
                                http.prepareGet("${stack.server.base}/auto/v6").execute { response ->
                                    assertEquals(HttpStatusCode.OK, response.status)
                                    response.bodyAsChannel().readHead(188 * 3)
                                }
                            }
                            for (packet in 0 until 3) assertEquals(0x47.toByte(), head[packet * 188])
                            pollUntil(15_000, 50) { stack.runtime.sessions.count() == 0 }
                        }
                    }
                }
            }
            dir.toFile().deleteRecursively()
        }
}

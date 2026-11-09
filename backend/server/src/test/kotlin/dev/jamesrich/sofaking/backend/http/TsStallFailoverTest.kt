package dev.jamesrich.sofaking.backend.http

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.playback.graceMillisForTest
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TsStallFailoverTest {
    private val token = "secret".repeat(8)

    @Test fun `an MPEG-TS source that goes silent past the grace moves the session on and the old epoch gets a 410`() =
        runBlocking<Unit> { failsOver(sentFirst = 3) }

    @Test fun `an MPEG-TS source that never sends a byte moves the session on and the old epoch gets a 410`() =
        runBlocking<Unit> { failsOver(sentFirst = 0) }

    private suspend fun failsOver(sentFirst: Int) {
        val dir = Files.createTempDirectory("sofa-ts-stall-")
        val upstream = StallingUpstream(sentFirst)
        val base = upstream.base
        Files.writeString(
            dir.resolve("sources.json"),
            """{"providers":[
                    {"id":"stalls","playlistUrl":"$base/stalls-feed.m3u","probe":false},
                    {"id":"spare","playlistUrl":"$base/spare-feed.m3u","probe":false}]}""",
        )
        val sources = dir.resolve("sources.json")
        val hdhr = HdhrConfig(enabled = false)
        val config =
            ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, hdhr = hdhr)
        val store = openStore(dir)
        val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
        graceMillisForTest = GRACE_MS
        var server: RealServer? = null
        try {
            for (feed in runtime.feeds.configs) runtime.feeds.refresh(feed)
            runtime.adoptAll()
            val offerings = runtime.feeds.published.offerings
            val stalls = offerings.single { it.url.endsWith("/stalls.ts") }
            val spare = offerings.single { it.url.endsWith("/spare.ts") }
            val entry = runtime.lineup.entries().single { stalls.id in it.offeringIds }
            runtime.lineup.update(entry.id, UpdateLineupEntry(offerings = listOf(stalls.id, spare.id)))
            server = RealServer(store, MutableClock(), runtime, token, hdhr = hdhr)

            HttpClient(CIO).use { http ->
                val session = Json { ignoreUnknownKeys = true }.decodeFromString<PlaybackSession>(
                    http.post("${server.base}/api/v1/playback-sessions") {
                        bearerAuth(token)
                        header(Wire.Header.PLAYBACK_PROTOCOLS, "hls, mpegts")
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(OpenPlayback(entry.id, "watch", "stall")))
                    }.bodyAsText(),
                )
                assertEquals("mpegts", session.descriptor.protocol)
                val url = server.base + session.descriptor.url
                val response = withTimeout(30_000) { http.get(url) }
                val played = response.bodyAsBytes()
                assertFirstResponse(response.status, played, sentFirst)
                assertEquals(1, runtime.sessions.snapshot().single().sourceSwitches, "the stall moved the session")
                val stale = http.get(url)
                assertEquals(HttpStatusCode.Gone, stale.status)
                assertTrue(stale.bodyAsText().contains("STREAM_CHANGED"))
            }
        } finally {
            graceMillisForTest = null
            server?.close()
            runtime.close()
            store.close()
            upstream.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun assertFirstResponse(status: HttpStatusCode, played: ByteArray, sentFirst: Int) {
        if (sentFirst > 0) {
            assertEquals(HttpStatusCode.OK, status, played.decodeToString())
            assertTrue(played.size >= sentFirst * 188, "the stream played before it went silent: ${played.size}")
        } else {
            assertEquals(HttpStatusCode.Gone, status, played.decodeToString())
            assertTrue(played.decodeToString().contains("STREAM_CHANGED"))
        }
    }

    /** Two MPEG-TS sources, `stalls` and `spare`: [sentFirst] packets each, then silence on an open connection. */
    private class StallingUpstream(private val sentFirst: Int) : AutoCloseable {
        private val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                if (path.endsWith(".m3u")) {
                    val id = path.removePrefix("/").removeSuffix("-feed.m3u")
                    val body = "#EXTM3U\n#EXTINF:-1 tvg-id=\"ts\" tvg-chno=\"1\",TS\n$base/$id.ts\n".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } else {
                    exchange.responseHeaders.set("Content-Type", "video/mp2t")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { out ->
                        for (n in 1..sentFirst) out.write(Fixture.tsPacket(n))
                        out.flush()
                        release.await()
                    }
                }
            }
            server.start()
        }

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private companion object {
        const val GRACE_MS = 1_500L
    }
}

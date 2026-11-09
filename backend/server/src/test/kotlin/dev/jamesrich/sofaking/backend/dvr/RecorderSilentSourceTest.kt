package dev.jamesrich.sofaking.backend.dvr

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.playback.FanoutConfig
import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecorderSilentSourceTest {
    @Test fun `a recording whose first MPEG-TS source sends nothing carries on from the channel's next source`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-silent-rec-")
            val upstream = SilentThenSpare()
            Files.writeString(
                dir.resolve("sources.json"),
                """{"providers":[
                    {"id":"silent","playlistUrl":"${upstream.base}/silent-feed.m3u","probe":false},
                    {"id":"spare","playlistUrl":"${upstream.base}/spare-feed.m3u","probe":false}]}""",
            )
            val config =
                ServerConfig("127.0.0.1", 8080, dir, dir.resolve("sources.json"), allowLoopbackUpstreams = true)
            val store = openStore(dir)
            val fanout = FanoutConfig(graceMillis = 1_000)
            val runtime = LiveRuntime.create(store, config, Clock.systemUTC(), probes = false, fanout = fanout)
            try {
                for (feed in runtime.feeds.configs) runtime.feeds.refresh(feed)
                runtime.adoptAll()
                val offerings = runtime.feeds.published.offerings
                val silent = offerings.single { it.url.endsWith("/silent.ts") }
                val spare = offerings.single { it.url.endsWith("/spare.ts") }
                val entry = runtime.lineup.entries().single { silent.id in it.offeringIds }
                runtime.lineup.update(entry.id, UpdateLineupEntry(offerings = listOf(silent.id, spare.id)))
                val recorder = Recorder(
                    "ffmpeg",
                    runtime.sessions,
                    runtime.feeds,
                    Clock.systemUTC(),
                    runtime.loopback,
                    Duration.ofMillis(200),
                    shared = runtime.relay::joinShared,
                )
                val take = recorder.record(entry.id, dir.resolve("recording.ts"), Instant.now().plusSeconds(5))
                assertTrue(take.bytes >= 3 * 188, "the spare's packets were recorded: ${take.bytes}")
                assertEquals(1, upstream.silentRequests.get(), "the silent source was not opened again by a retry")
            } finally {
                upstream.close()
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }

    /**
     * Two MPEG-TS sources: `silent` accepts the connection and sends nothing; `spare` sends a packet every 100 ms, so
     * it never stalls and the recording needs no third source.
     */
    private class SilentThenSpare : AutoCloseable {
        private val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val silentRequests = AtomicInteger()
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
                    if (path == "/silent.ts") silentRequests.incrementAndGet()
                    exchange.responseHeaders.set("Content-Type", "video/mp2t")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { out -> stream(path, out) }
                }
            }
            server.start()
        }

        private fun stream(path: String, out: java.io.OutputStream) {
            out.flush()
            var n = 0
            while (path == "/spare.ts" && !release.await(100, TimeUnit.MILLISECONDS)) {
                out.write(Fixture.tsPacket(n++))
                out.flush()
            }
            if (path != "/spare.ts") release.await()
        }

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

package dev.jamesrich.sofaking.backend.http

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.AdDateRange
import dev.jamesrich.sofaking.contract.AdRun
import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.PlaybackSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the app's live playlists carry that other players' do not: dates, a hold-back and the ad marks. */
class AdMarksHttpTest {
    private val token = "secret".repeat(8)
    private val output = "players-" + "x".repeat(32)

    /** A live window of ten six second segments with a 30 second cue-out before the fourth and a cue-in before the ninth. */
    private class Upstream(private val segments: String) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        val base = "http://127.0.0.1:${server.address.port}"
        val feed = FeedConfig("ads", "$base/feed.m3u", probe = false)

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                val text = when (exchange.requestURI.path) {
                    "/feed.m3u" -> "#EXTM3U\n#EXTINF:-1 tvg-id=\"ads\" tvg-chno=\"1\",Ads\n$base/live.m3u8\n"
                    "/live.m3u8" -> playlist()
                    else -> null
                }
                if (text == null) {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                } else {
                    val bytes = text.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
        }

        private fun playlist() = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:100\n")
            for (index in 0 until WINDOW) {
                if (index == CUE_OUT) append("#EXT-X-CUE-OUT:30\n")
                if (index == CUE_IN) append("#EXT-X-CUE-IN\n")
                append("#EXTINF:6,\n$segments/seg${index % SEGMENT_FILES}.ts\n")
            }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test fun `the app's playlist carries dates, a hold-back and the ad marks, and an output's carries none`() =
        testApplication {
            val dir = Files.createTempDirectory("sofa-ad-marks-")
            MediaFixture().use { media ->
                Upstream(media.base).use { upstream ->
                    Files.writeString(dir.resolve("sources.json"), Json.encodeToString(listOf(upstream.feed)))
                    val outputs = OutputConfig(output, 3)
                    val config = ServerConfig(
                        "127.0.0.1",
                        8080,
                        dir,
                        dir.resolve("sources.json"),
                        token,
                        hdhr = HdhrConfig(enabled = false),
                        updateImage = null,
                        allowLoopbackUpstreams = true,
                        outputs = outputs,
                    )
                    val store = openStore(dir)
                    val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
                    try {
                        runtime.feeds.refresh(upstream.feed)
                        runtime.adoptAll()
                        application {
                            backendModule(store, MutableClock(), runtime, token, outputs = outputs, config = config)
                        }
                        val channel = Json { ignoreUnknownKeys = true }.decodeFromString<Catalog>(
                            client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText(),
                        ).channels.single().id

                        val session = Json { ignoreUnknownKeys = true }.decodeFromString<PlaybackSession>(
                            client.post("/api/v1/playback-sessions") {
                                bearerAuth(token)
                                contentType(ContentType.Application.Json)
                                setBody(Json.encodeToString(OpenPlayback(channel, "watch", "marks")))
                            }.bodyAsText(),
                        )
                        val response = client.get(session.descriptor.url)
                        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                        val lines = response.bodyAsText().lines()
                        assertTrue(lines.any { it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") }, "dated: $lines")
                        assertTrue(lines.contains("#EXT-X-SERVER-CONTROL:HOLD-BACK=36"), "held back: $lines")
                        assertEquals(listOf(AdRun(103, 107, 30_000)), lines.mapNotNull(AdRun::parse))
                        val range = lines.mapNotNull(AdDateRange::parse).single()
                        assertEquals("sofa-ad-103", range.id)
                        assertEquals(30_000L, range.durationMs)
                        assertTrue(
                            lines.any {
                                it.startsWith("#EXT-X-DATERANGE:") &&
                                    "CLASS=\"${AdDateRange.CLASS}\"" in it
                            },
                        )

                        val plain = client.get("/output/stream/$channel.m3u8?token=$output").bodyAsText()
                        assertTrue(plain.contains("#EXTINF"), plain)
                        for (tag in listOf("PROGRAM-DATE-TIME", "SERVER-CONTROL", "SOFA-AD", "DATERANGE")) {
                            assertTrue("#EXT-X-$tag" !in plain, "an output's playlist has no $tag: $plain")
                        }
                    } finally {
                        runtime.close()
                        store.close()
                        dir.toFile().deleteRecursively()
                    }
                }
            }
        }

    private companion object {
        const val WINDOW = 10
        const val CUE_OUT = 3
        const val CUE_IN = 8
        const val SEGMENT_FILES = 4
    }
}

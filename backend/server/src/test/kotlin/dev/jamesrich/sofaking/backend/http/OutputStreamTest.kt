package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.readHead
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Player streams over a real socket: response lifetimes, hang-ups and an FFmpeg client following the redirect. */
class OutputStreamTest {
    private val token = "secret".repeat(8)
    private val output = "players-" + "x".repeat(32)
    private val outputs = OutputConfig(output, 3)

    private class Stack(val runtime: LiveRuntime, val store: BackendStore, val server: RealServer, val channels: List<Channel>) : AutoCloseable {
        override fun close() { server.close(); runBlocking { runtime.close() }; store.close() }
    }

    private suspend fun stack(dir: Path, feeds: List<FeedConfig>, maxSessions: Int = 4): Stack {
        val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(feeds))
        val store = openStore(dir)
        val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, maxSessions = maxSessions, allowLoopbackUpstreams = true, outputs = outputs), MutableClock())
        feeds.forEach { runtime.feeds.refresh(it) }; runtime.adoptAll()
        val server = RealServer(store, MutableClock(), runtime, token, outputs)
        val channels = HttpClient(CIO).use { http -> Json.decodeFromString<Catalog>(http.get("${server.base}/api/v1/channels") { bearerAuth(token) }.bodyAsText()).channels }
        return Stack(runtime, store, server, channels)
    }

    @Test fun `a TS stream URL fans out until the player hangs up and then releases the session`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-output-ts-")
        Fixture().use { fixture ->
            stack(dir, listOf(fixture.tsFeed)).use { stack ->
                HttpClient(CIO).use { http ->
                    val ts = stack.channels.single()
                    val packets = withTimeout(30_000) {
                        http.prepareGet("${stack.server.base}/output/stream/${ts.id}.ts?token=$output").execute { response ->
                            assertEquals(HttpStatusCode.OK, response.status)
                            assertEquals("video/mp2t", response.headers[HttpHeaders.ContentType])
                            val body = response.bodyAsChannel()
                            val early = body.readHead(188 * 3)
                            // Longer than the fan-out's linger: the request itself is what holds the session and the upstream.
                            delay(2500)
                            val later = body.readHead(188 * 3)
                            assertEquals(1, stack.runtime.sessions.count())
                            assertEquals(1, fixture.tsConnections.get()); assertEquals(0, fixture.tsClosed.get())
                            early + later
                        }
                    }
                    assertEquals(188 * 6, packets.size)
                    for (packet in 0 until 6) assertEquals(0x47.toByte(), packets[packet * 188])
                    withTimeout(20_000) { while (stack.runtime.sessions.count() > 0) delay(50) }
                    withTimeout(20_000) { while (fixture.tsClosed.get() < 1) delay(50) }
                    assertEquals(0, stack.runtime.relay.shared.active())
                }
            }
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `a TS player holds the last slot while an idle HLS player session is replaced by a zap`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-output-slots-")
        Fixture().use { fixture ->
            stack(dir, listOf(fixture.feed, fixture.tsFeed), maxSessions = 1).use { stack ->
                HttpClient(CIO) { followRedirects = false }.use { http ->
                    val hls = stack.channels.single { it.displayNumber == "5" }
                    val ts = stack.channels.single { it.displayNumber == "6" }
                    val idle = http.get("${stack.server.base}/output/stream/${hls.id}.m3u8?token=$output").headers[HttpHeaders.Location]!!
                    assertEquals(HttpStatusCode.OK, http.get(idle).status)
                    withTimeout(30_000) {
                        http.prepareGet("${stack.server.base}/output/stream/${ts.id}.ts?token=$output").execute { response ->
                            assertEquals(HttpStatusCode.OK, response.status)
                            response.bodyAsChannel().readHead(188 * 3)
                            // The zap took the only slot from the idle HLS session; while TS streams, nothing else fits.
                            assertEquals(HttpStatusCode.Gone, http.get(idle).status)
                            val refused = http.get("${stack.server.base}/output/stream/${hls.id}.m3u8?token=$output")
                            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
                            assertEquals("CAPACITY_EXHAUSTED", Json.decodeFromString<ApiError>(refused.bodyAsText()).code)
                        }
                    }
                    withTimeout(20_000) { while (stack.runtime.sessions.count() > 0) delay(50) }
                    assertEquals(HttpStatusCode.Found, http.get("${stack.server.base}/output/stream/${hls.id}.m3u8?token=$output").status)
                }
            }
        }
        dir.toFile().deleteRecursively()
    }

    @Test fun `FFmpeg follows the HLS stream redirect and decodes video through the session`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-output-ffmpeg-")
        MediaFixture().use { media ->
            stack(dir, listOf(media.feed)).use { stack ->
                val vod = stack.channels.single { it.displayNumber == "1" }
                val url = "${stack.server.base}/output/stream/${vod.id}.m3u8?token=$output"
                val process = ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-i", url, "-t", "4", "-map", "0:v:0", "-progress", "pipe:1", "-f", "null", "-")
                    .redirectError(dir.resolve("ffmpeg.err").toFile()).start()
                val progress = process.inputStream.bufferedReader().readText()
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "ffmpeg did not finish")
                assertEquals(0, process.exitValue(), Files.readString(dir.resolve("ffmpeg.err")))
                val frames = progress.lines().filter { it.startsWith("frame=") }.map { it.removePrefix("frame=").trim().toInt() }
                assertTrue(progress.contains("progress=end") && frames.isNotEmpty() && frames.last() >= 45, progress)
                assertEquals(1, stack.runtime.sessions.count())
            }
        }
        dir.toFile().deleteRecursively()
    }
}

package dev.jamesrich.sofaking.backend.http

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.jamesrich.sofaking.backend.AppStreams
import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.playback.PackagedStreams
import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An MPEG-TS channel for an app session: the server packages it as HLS unless the client asks for the TS. */
class PackagedStreamsTest {
    private val token = "secret".repeat(8)

    /** Runs [block] on a server whose one channel, `TS`, is an MPEG-TS source, with [appStreams] set. */
    private fun withTsChannel(
        appStreams: AppStreams = AppStreams.HLS,
        block: suspend ApplicationTestBuilder.(channel: String, runtime: LiveRuntime) -> Unit,
    ) = testApplication {
        val dir = Files.createTempDirectory("sofa-packaged-")
        MediaFixture().use { media ->
            Files.writeString(
                dir.resolve("sources.json"),
                """{"providers":[{"id":"media-ts","playlistUrl":"${media.tsFeed.playlistUrl}","probe":false}]}""",
            )
            val config = ServerConfig(
                "127.0.0.1",
                8080,
                dir,
                dir.resolve("sources.json"),
                token,
                hdhr = HdhrConfig(enabled = false),
                updateImage = null,
                allowLoopbackUpstreams = true,
                appStreams = appStreams,
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
            try {
                runtime.feeds.refresh(media.tsFeed)
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token, config = config) }
                block(runtime.lineup.entries().single { it.name == "TS" }.id, runtime)
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `a player that names no protocols gets an MPEG-TS channel as HLS, and one that plays both gets the TS`() =
        withTsChannel { channel, _ ->
            val plain = open(channel, "plain", null)
            assertEquals("hls", plain.descriptor.protocol, "a player that names no protocols gets HLS")
            val both = open(channel, "both", "hls, mpegts")
            assertEquals("mpegts", both.descriptor.protocol, "one that plays both asks for the source's own")
            client.delete("/api/v1/playback-sessions/${plain.id}") { bearerAuth(token) }
            client.delete("/api/v1/playback-sessions/${both.id}") { bearerAuth(token) }
        }

    @Test fun `the packaged playlist is dated and held back, its segments are copied, and a release stops FFmpeg`() =
        withTsChannel { channel, runtime ->
            val session = open(channel, "hls", "hls")
            assertEquals("hls", session.descriptor.protocol)
            assertEquals("application/vnd.apple.mpegurl", session.descriptor.mimeType)
            assertTrue(session.descriptor.url.endsWith(".m3u8"), session.descriptor.url)
            val response = client.get(session.descriptor.url)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("application/vnd.apple.mpegurl", response.headers[HttpHeaders.ContentType])
            val playlist = response.bodyAsText()
            val segments = playlist.lines().filter { it.isNotBlank() && !it.startsWith('#') }
            assertTrue(segments.isNotEmpty(), "a client other than AVPlayer is served from one segment")
            assertTrue(playlist.contains("#EXT-X-PROGRAM-DATE-TIME:"), "the packaged playlist is dated: $playlist")
            assertTrue(segments.all { it.startsWith("/media/sessions/${session.id}/") }, playlist)
            val segment = client.get(segments.first())
            assertEquals(HttpStatusCode.OK, segment.status)
            assertEquals("video/mp2t", segment.headers[HttpHeaders.ContentType])
            assertEquals(0x47.toByte(), segment.bodyAsBytes().first(), "a segment is MPEG-TS")
            assertEquals(1, runtime.relay.packaged)

            // AVFoundation gets a bare media playlist when the stream carries no captions to declare, and starts
            // three segments from the end, so its first playlist waits for them.
            val apple = client.get(session.descriptor.url) { header(HttpHeaders.UserAgent, "AppleCoreMedia/1.0.0") }
            assertTrue(apple.bodyAsText().lines().none { it.startsWith("#EXT-X-STREAM-INF") })

            // The window grows until it holds a hold-back, and the dates stay with it.
            var latest = ""
            pollUntil(30_000, 500) {
                latest = client.get(session.descriptor.url).bodyAsText()
                latest.contains("#EXT-X-SERVER-CONTROL:HOLD-BACK=")
            }
            assertTrue(latest.contains("#EXT-X-PROGRAM-DATE-TIME:"), latest)
            // The value follows the window: no more than it leaves behind three target durations, and within one
            // target duration of that (or of the 36 s cap) after the refresh that wrote it.
            fun window(playlist: String) =
                Regex("#EXTINF:([0-9.]+)").findAll(playlist).sumOf { it.groupValues[1].toDouble() }
            fun checkHoldBack(playlist: String) {
                val holdBack = Regex("HOLD-BACK=(\\d+)").find(playlist)!!.groupValues[1].toDouble()
                val target = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(playlist)!!.groupValues[1].toDouble()
                val room = minOf(36.0, window(playlist) - 3 * target)
                assertTrue(holdBack <= room + target, "hold-back $holdBack beyond room $room: $playlist")
                assertTrue(holdBack >= room - 2 * target, "hold-back $holdBack stuck below room $room: $playlist")
            }
            checkHoldBack(latest)
            // Once the window has grown well past the first value's room, the value has grown with it.
            pollUntil(40_000, 500) {
                latest = client.get(session.descriptor.url).bodyAsText()
                window(latest) >= 30.0
            }
            checkHoldBack(latest)

            client.delete("/api/v1/playback-sessions/${session.id}") { bearerAuth(token) }
            pollUntil(10_000, 100) { runtime.relay.packaged == 0 }
            assertEquals(0, runtime.relay.packaged, "releasing the session stops its FFmpeg")
        }

    @Test fun `the first playlist for a player other than AVPlayer comes within three seconds`() =
        withTsChannel { channel, _ ->
            val session = open(channel, "fast", "hls")
            val started = System.nanoTime()
            val playlist = client.get(session.descriptor.url).bodyAsText()
            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue(seconds < 3.0, "the first playlist took $seconds s: $playlist")
            client.delete("/api/v1/playback-sessions/${session.id}") { bearerAuth(token) }
        }

    @Test fun `an FFmpeg that fails on the short probe is started again with the default probe`() {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val root = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME)
        root.addAppender(events)
        // A probe too small to see the codec parameters makes FFmpeg exit before its first playlist.
        PackagedStreams.probeLimitsForTest = 2_000 to 100_000
        try {
            withTsChannel { channel, _ ->
                val session = open(channel, "retry", "hls")
                val response = client.get(session.descriptor.url)
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertTrue(response.bodyAsText().contains("#EXTINF:"), "the retry serves a playlist")
                client.delete("/api/v1/playback-sessions/${session.id}") { bearerAuth(token) }
            }
        } finally {
            PackagedStreams.probeLimitsForTest = null
            root.detachAppender(events)
        }
        assertTrue(
            events.list.any { it.formattedMessage.contains("packager retried with the default probe") },
            "the retry is logged",
        )
    }

    @Test fun `SOFA_APP_STREAMS source gives a player that names no protocols the TS, and HLS on request`() =
        withTsChannel(AppStreams.SOURCE) { channel, _ ->
            assertEquals("mpegts", open(channel, "plain", null).descriptor.protocol)
            assertEquals("hls", open(channel, "hls", "hls").descriptor.protocol)
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.open(
        channel: String,
        key: String,
        protocols: String?,
    ): PlaybackSession = Json { ignoreUnknownKeys = true }.decodeFromString(
        client.post("/api/v1/playback-sessions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            protocols?.let { header(Wire.Header.PLAYBACK_PROTOCOLS, it) }
            setBody(Json.encodeToString(OpenPlayback(channel, "watch", key)))
        }.bodyAsText(),
    )
}

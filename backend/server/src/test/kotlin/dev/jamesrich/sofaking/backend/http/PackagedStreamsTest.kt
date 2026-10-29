package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An MPEG-TS channel for a player that plays HLS only: the server packages it, and others still get the TS. */
class PackagedStreamsTest {
    private val token = "secret".repeat(8)

    @Test fun `a player that plays HLS only gets an MPEG-TS channel as an HLS playlist of copied segments`() =
        testApplication {
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
                )
                val store = openStore(dir)
                val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
                try {
                    runtime.feeds.refresh(media.tsFeed)
                    runtime.adoptAll()
                    application { backendModule(store, MutableClock(), runtime, token, config = config) }
                    val channel = runtime.lineup.entries().single { it.name == "TS" }.id

                    val plain = open(channel, "plain", null)
                    assertEquals("mpegts", plain.descriptor.protocol, "a player that names no protocols gets the TS")
                    val both = open(channel, "both", "hls, mpegts")
                    assertEquals("mpegts", both.descriptor.protocol, "one that plays both gets the TS too")
                    client.delete("/api/v1/playback-sessions/${plain.id}") { bearerAuth(token) }
                    client.delete("/api/v1/playback-sessions/${both.id}") { bearerAuth(token) }

                    val session = open(channel, "hls", "hls")
                    assertEquals("hls", session.descriptor.protocol)
                    assertEquals("application/vnd.apple.mpegurl", session.descriptor.mimeType)
                    assertTrue(session.descriptor.url.endsWith(".m3u8"), session.descriptor.url)
                    val response = client.get(session.descriptor.url)
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    assertEquals("application/vnd.apple.mpegurl", response.headers[HttpHeaders.ContentType])
                    val playlist = response.bodyAsText()
                    val segments = playlist.lines().filter { it.isNotBlank() && !it.startsWith('#') }
                    assertTrue(segments.size >= 3, "the first playlist lists three segments: $playlist")
                    assertTrue(segments.all { it.startsWith("/media/sessions/${session.id}/") }, playlist)
                    val segment = client.get(segments.first())
                    assertEquals(HttpStatusCode.OK, segment.status)
                    assertEquals("video/mp2t", segment.headers[HttpHeaders.ContentType])
                    assertEquals(0x47.toByte(), segment.bodyAsBytes().first(), "a segment is MPEG-TS")
                    assertEquals(1, runtime.relay.packaged)

                    // AVFoundation gets a bare media playlist when the stream carries no captions to declare.
                    val apple = client.get(session.descriptor.url) {
                        header(HttpHeaders.UserAgent, "AppleCoreMedia/1.0.0")
                    }
                    assertTrue(apple.bodyAsText().lines().none { it.startsWith("#EXT-X-STREAM-INF") })

                    client.delete("/api/v1/playback-sessions/${session.id}") { bearerAuth(token) }
                    pollUntil(10_000, 100) { runtime.relay.packaged == 0 }
                    assertEquals(0, runtime.relay.packaged, "releasing the session stops its FFmpeg")
                } finally {
                    runtime.close()
                    store.close()
                    dir.toFile().deleteRecursively()
                }
            }
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

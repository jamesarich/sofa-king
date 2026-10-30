package dev.jamesrich.sofaking.backend.dvr

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.playback.PlaylistMarkers
import dev.jamesrich.sofaking.backend.playback.remuxCommand
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.ffmpegProcess
import dev.jamesrich.sofaking.contract.RecordingBreak
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What FFmpeg's reads through the loopback relay show the recorder of a marked playlist. */
class RecordingMarkersTest {
    /** Six two-second segments of the fixture's media, the third and fourth between a cue out and a cue in. */
    private fun marked(media: MediaFixture) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n")
        for (n in 0 until 6) {
            if (n == 2) append("#EXT-X-CUE-OUT:4\n")
            if (n == 4) append("#EXT-X-CUE-IN\n")
            if (n > 0 && n % media.segments == 0) append("#EXT-X-DISCONTINUITY\n")
            append("#EXTINF:2.000,\n${media.base}/seg${n % media.segments}.ts?n=$n\n")
        }
        append("#EXT-X-ENDLIST\n")
    }

    @Test fun `the breaks a playlist marks are the offsets of the media FFmpeg read from it`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-markers-")
        Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val config = ServerConfig("127.0.0.1", 8080, dir, dir.resolve("sources.json"), allowLoopbackUpstreams = true)
        val runtime = LiveRuntime.create(openStore(dir), config, Clock.systemUTC(), probes = false)
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            MediaFixture().use { media ->
                val body = marked(media).toByteArray()
                upstream.createContext("/marked.m3u8") { exchange ->
                    exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                upstream.start()
                val markers = PlaylistMarkers()
                markers.begin()
                val stream = ResolvedStream("http://127.0.0.1:${upstream.address.port}/marked.m3u8", emptyMap())
                val written = runtime.loopback.reading(stream, "hls", markers = markers) { source ->
                    val process = ffmpegProcess(remuxCommand("ffmpeg", source))
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    val bytes = process.inputStream.readBytes().size
                    process.waitFor()
                    bytes
                }
                assertTrue(written > 188 * 50, "FFmpeg wrote $written bytes")
                assertEquals(listOf(RecordingBreak(4_000, 8_000)), markers.breaks())
            }
        } finally {
            upstream.stop(0)
            runtime.close()
            runtime.store.close()
            dir.toFile().deleteRecursively()
        }
    }
}

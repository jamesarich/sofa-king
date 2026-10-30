package dev.jamesrich.sofaking.backend.dvr

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A live HLS source that renumbers its playlist, as Pluto does after a lapse, recorded through the real recorder and
 * loopback relay. The recording must hold about as much media as the window lasted, not only what came before the
 * renumbering.
 */
// Each case records a real window of up to 44 s, past the default minute once fixtures and FFprobe are counted.
@Timeout(2, unit = TimeUnit.MINUTES)
class RecorderLapseTest {
    /**
     * A live playlist of two-second segments that advances with the clock: numbered from 100 for [renumberAt]
     * seconds, then, after [lapse] seconds of an empty playlist that ends the stream, numbered from 5.
     */
    private class Pluto(
        private val media: MediaFixture,
        private val renumberAt: Int,
        private val lapse: Int,
        /** Whether the playlist stops adding segments at [renumberAt] instead. */
        private val frozen: Boolean = false,
    ) {
        private var started = 0L

        fun playlist(): String = synchronized(this) {
            if (started == 0L) started = System.nanoTime()
            val t = (System.nanoTime() - started) / 1_000_000_000.0
            val head = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n"
            when {
                t < renumberAt -> head + window(100, (t / 2).toInt(), 0)
                frozen -> head + window(100, renumberAt / 2, 0)
                t < renumberAt + lapse -> head + "#EXT-X-MEDIA-SEQUENCE:100\n#EXT-X-ENDLIST\n"
                else -> head + window(5, ((t - renumberAt - lapse) / 2).toInt(), 1)
            }
        }

        private fun window(first: Int, step: Int, round: Int): String = buildString {
            val newest = first + step
            val oldest = maxOf(first, newest - 3)
            append("#EXT-X-MEDIA-SEQUENCE:$oldest\n")
            for (n in oldest..newest) {
                if (n == first && round > 0) append("#EXT-X-DISCONTINUITY\n")
                append("#EXTINF:2.000,\n${media.base}/seg${n % media.segments}.ts?r=$round&n=$n\n")
            }
        }
    }

    /** What the recorder took in [windowSeconds] from the source, and the seconds of video the file holds. */
    private data class Result(val take: RecordedTake, val seconds: Double)

    private fun record(
        renumberAt: Int,
        lapse: Int,
        windowSeconds: Long,
        frozen: Boolean = false,
        stallAfter: Duration? = null,
    ): Result = runBlocking {
        val dir = Files.createTempDirectory("sofa-lapse-")
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        try {
            MediaFixture().use { media ->
                val pluto = Pluto(media, renumberAt, lapse, frozen)
                upstream.executor = executor
                upstream.createContext("/") { exchange -> serve(exchange, pluto, upstream.address.port) }
                upstream.start()
                val base = "http://127.0.0.1:${upstream.address.port}"
                val feed = FeedConfig("pluto", "$base/feed.m3u", probe = false)
                val sources = dir.resolve("sources.json")
                Files.writeString(sources, Json.encodeToString(listOf(feed)))
                val config = ServerConfig("127.0.0.1", 8080, dir, sources, allowLoopbackUpstreams = true)
                val runtime = LiveRuntime.create(openStore(dir), config, Clock.systemUTC(), probes = false)
                try {
                    runtime.feeds.refresh(feed)
                    runtime.adoptAll()
                    val channel = runtime.lineup.entries().single().id
                    val file = dir.resolve("recording.ts")
                    val take = recorder(runtime, stallAfter)
                        .record(channel, file, Instant.now().plusSeconds(windowSeconds))
                    Result(take, mediaSeconds(file))
                } finally {
                    runtime.close()
                    runtime.store.close()
                }
            }
        } finally {
            upstream.stop(0)
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }

    private fun recorder(runtime: LiveRuntime, stallAfter: Duration?): Recorder {
        val clock = Clock.systemUTC()
        val retry = Duration.ofMillis(200)
        return if (stallAfter == null) {
            Recorder("ffmpeg", runtime.sessions, runtime.feeds, clock, runtime.loopback, retry)
        } else {
            Recorder("ffmpeg", runtime.sessions, runtime.feeds, clock, runtime.loopback, retry, stallAfter = stallAfter)
        }
    }

    private fun serve(exchange: HttpExchange, pluto: Pluto, port: Int) {
        val path = exchange.requestURI.path
        val body = when (path) {
            "/feed.m3u" ->
                "#EXTM3U\n#EXTINF:-1 tvg-id=\"pluto\" tvg-chno=\"1\",Pluto\nhttp://127.0.0.1:$port/live.m3u8\n"
                    .toByteArray()

            "/live.m3u8" -> pluto.playlist().toByteArray()

            else -> null
        }
        if (body == null) {
            exchange.sendResponseHeaders(404, -1)
        } else {
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        exchange.close()
    }

    /** The seconds of video the file holds, counted as packets at the fixture's 15 frames per second. */
    private fun mediaSeconds(file: Path): Double {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets",
            "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", file.toString(),
        ).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(30, TimeUnit.SECONDS)
        // FFprobe prints a line per program as well as per stream.
        return (out.lineSequence().firstNotNullOfOrNull { it.trim().toIntOrNull() } ?: 0) / FRAME_RATE
    }

    @Test
    fun `a source that lapses and comes back renumbered is recorded for the whole window`() {
        val result = record(renumberAt = 8, lapse = 6, windowSeconds = 44)
        assertTrue(result.seconds >= 44 * 0.6, "the recording holds ${result.seconds} s of a 44 s window")
    }

    @Test
    fun `a source that renumbers without a lapse is recorded for the whole window`() {
        val result = record(renumberAt = 8, lapse = 0, windowSeconds = 44)
        assertTrue(result.seconds >= 44 * 0.6, "the recording holds ${result.seconds} s of a 44 s window")
    }

    @Test
    fun `a source that stops adding segments is reopened and the recording says why`() {
        val result =
            record(renumberAt = 8, lapse = 0, windowSeconds = 30, frozen = true, stallAfter = Duration.ofSeconds(5))
        // FFmpeg 6.1 gives up on a frozen playlist with an error; newer versions wait until the watchdog stops them.
        val reason = result.take.lastFailure
        assertTrue(reason == "SOURCE_STALLED" || reason == "IOException", "the recording's reason was $reason")
        assertTrue(result.seconds > 0, "the media before the stall is kept")
    }

    private companion object {
        const val FRAME_RATE = 15.0
    }
}

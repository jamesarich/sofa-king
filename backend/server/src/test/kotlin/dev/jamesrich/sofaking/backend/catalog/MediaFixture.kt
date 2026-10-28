package dev.jamesrich.sofaking.backend.catalog

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real H.264/AAC HLS generated once with FFmpeg: two-second segments served as a VOD playlist (`/vod.m3u8`) and
 * as an endless live playlist (`/live.m3u8`) whose window advances one segment every two seconds and wraps with
 * a discontinuity, so a reader blocks waiting for the next segment the way it does on a real channel.
 */
class MediaFixture : AutoCloseable {
    private val dir: Path = Files.createTempDirectory("sofa-media-fixture-")
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()
    private val startedAt = System.nanoTime()
    val base = "http://127.0.0.1:${server.address.port}"

    /** Channel 1 plays the VOD playlist, channel 2 the live one, channel 3 a playlist that answers 404. */
    val feed = FeedConfig("media", "$base/feed.m3u", probe = false)

    /** A second provider whose one channel is a live playlist that never adds a segment, for stall failover. */
    val frozenFeed = FeedConfig("media-frozen", "$base/frozen-feed.m3u", probe = false)

    /** One channel, TS, that streams the clip's segments over and over as raw MPEG-TS, the first four at once. */
    val tsFeed = FeedConfig("media-ts", "$base/ts-feed.m3u", probe = false)
    val segments: Int
    val liveRequests = AtomicInteger()

    init {
        generate()
        segments =
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().matches(Regex("seg\\d+\\.ts")) }.count().toInt()
            }
        require(segments >= 4) { "FFmpeg produced $segments segments" }
        server.executor = executor
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val (type, bytes) = when {
                path == "/feed.m3u" ->
                    "audio/x-mpegurl" to (
                        "#EXTM3U\n#EXTINF:-1 tvg-id=\"vod\" tvg-chno=\"1\",VOD\n$base/vod.m3u8\n#EXTINF:-1 " +
                            "tvg-id=\"live\" tvg-chno=\"2\",Live\n$base/live.m3u8\n" +
                            "#EXTINF:-1 tvg-id=\"missing\" tvg-chno=\"3\",Missing\n$base/missing.m3u8\n"
                        ).toByteArray()

                path == "/frozen-feed.m3u" ->
                    "audio/x-mpegurl" to
                        "#EXTM3U\n#EXTINF:-1 tvg-id=\"frozen\" tvg-chno=\"4\",Frozen\n$base/frozen.m3u8\n".toByteArray()

                path == "/frozen.m3u8" ->
                    "application/vnd.apple.mpegurl" to (
                        "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                            "#EXTINF:2.000000,\nseg0.ts\n#EXTINF:2.000000,\nseg1.ts\n"
                        ).toByteArray()

                path == "/ts-feed.m3u" ->
                    "audio/x-mpegurl" to
                        "#EXTM3U\n#EXTINF:-1 tvg-id=\"ts\" tvg-chno=\"5\",TS\n$base/live.ts\n".toByteArray()

                path == "/live.ts" -> {
                    streamTs(exchange)
                    return@createContext
                }

                path == "/vod.m3u8" -> "application/vnd.apple.mpegurl" to Files.readAllBytes(dir.resolve("vod.m3u8"))

                path == "/live.m3u8" -> {
                    liveRequests.incrementAndGet()
                    "application/vnd.apple.mpegurl" to
                        livePlaylist().toByteArray()
                }

                path.matches(Regex("/seg\\d+\\.ts")) ->
                    "video/mp2t" to
                        Files.readAllBytes(dir.resolve(path.removePrefix("/")))

                else -> null to ByteArray(0)
            }
            if (type == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            } else {
                exchange.responseHeaders.set("Content-Type", type)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
    }

    /** The segments end to end, again and again: four at once, then one every two seconds, until the client leaves. */
    private fun streamTs(exchange: com.sun.net.httpserver.HttpExchange) {
        exchange.responseHeaders.set("Content-Type", "video/mp2t")
        exchange.sendResponseHeaders(200, 0)
        try {
            exchange.responseBody.use { out ->
                var index = 0
                while (true) {
                    out.write(Files.readAllBytes(dir.resolve("seg${index % segments}.ts")))
                    out.flush()
                    if (++index >= 4) Thread.sleep(2_000)
                }
            }
        } catch (_: java.io.IOException) {
            // The client left.
        } catch (_: InterruptedException) {
            // The fixture closed.
        }
    }

    private fun generate() {
        fun run(codec: List<String>): Boolean {
            val command = listOf(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=48000",
                "-t", "24", "-pix_fmt", "yuv420p", "-g", "30",
            ) + codec +
                listOf(
                    "-c:a", "aac", "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", "-hls_playlist_type", "vod",
                    "-hls_segment_filename", "seg%d.ts", "vod.m3u8",
                )
            val process = ProcessBuilder(
                command,
            ).directory(
                dir.toFile(),
            ).redirectErrorStream(true).redirectOutput(dir.resolve("ffmpeg.log").toFile()).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            return process.exitValue() == 0
        }
        check(run(listOf("-c:v", "libx264", "-preset", "ultrafast")) || run(listOf("-c:v", "mpeg2video"))) {
            "FFmpeg could not generate the HLS fixture: " + Files.readString(dir.resolve("ffmpeg.log"))
        }
    }

    /** The last four segments as of now; one more every two seconds since start. */
    private fun livePlaylist(): String {
        val sequence = ((System.nanoTime() - startedAt) / 2_000_000_000L).toInt()
        val first = maxOf(0, sequence - 3)
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$first\n")
            for (index in first..sequence) {
                if (index > 0 && index % segments == 0) append("#EXT-X-DISCONTINUITY\n")
                append("#EXTINF:2.000000,\nseg${index % segments}.ts\n")
            }
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        dir.toFile().deleteRecursively()
    }
}

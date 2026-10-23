package dev.jamesrich.sofaking.backend.playback

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.dvr.Recorder
import dev.jamesrich.sofaking.backend.health.ProbeConfig
import dev.jamesrich.sofaking.backend.health.ProbeWorker
import dev.jamesrich.sofaking.backend.http.RealServer
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.RELAY_CREDENTIAL_HEADER
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.readHead
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FFmpeg's reads on real sockets: the remux, the recorder and the probes' decode tier read every upstream through
 * the loopback relay, which checks each hop, carries the provider's headers and admits FFmpeg by a credential that
 * goes nowhere else.
 */
class LoopbackRelayTest {
    private val token = "secret".repeat(8)

    /** One request the upstream answered: its path and the headers that show who sent it. */
    private data class Seen(val path: String, val userAgent: String?, val providerKey: String?, val icy: String?)

    /**
     * The MediaFixture's playlists and segments served from [host], recording every request, plus playlists whose
     * redirect, variant, segments or key point at [targets] by name: `/redirect-trap.m3u8`,
     * `/key-metadata.m3u8` and so on.
     */
    private class Upstream(host: String, private val media: MediaFixture, private val targets: Map<String, String>) :
        AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(host, 0), 0)
        private val executor = Executors.newCachedThreadPool()
        val base = "http://$host:${server.address.port}"
        val port = server.address.port
        val seen = CopyOnWriteArrayList<Seen>()
        val feed = FeedConfig(
            "up",
            "$base/feed.m3u",
            probe = false,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "X-Provider-Key" to PROVIDER_SECRET,
            ),
        )

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                seen +=
                    Seen(
                        path,
                        exchange.requestHeaders.getFirst("User-Agent"),
                        exchange.requestHeaders.getFirst("X-Provider-Key"),
                        exchange.requestHeaders.getFirst("Icy-MetaData"),
                    )
                val shape = SHAPED.matchEntire(path)
                when {
                    path == "/feed.m3u" -> exchange.reply("audio/x-mpegurl", feedText().toByteArray())

                    path == "/stream.ts" -> exchange.reply(
                        "video/mp2t",
                        (0 until 4).map {
                            fetch(media.base, "/seg$it.ts")
                        }.reduce(ByteArray::plus),
                    )

                    shape != null -> {
                        val target = targets.getValue(shape.groupValues[2])
                        when (shape.groupValues[1]) {
                            "redirect" -> {
                                exchange.responseHeaders.set("Location", "$target/vod.m3u8")
                                exchange.sendResponseHeaders(302, -1)
                                exchange.close()
                            }

                            "variant" -> exchange.reply(
                                MPEGURL,
                                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\n$target/vod.m3u8\n".toByteArray(),
                            )

                            "segments" -> exchange.reply(
                                MPEGURL,
                                vod().replace(SEGMENT_LINE) {
                                    "$target/${it.value}"
                                }.toByteArray(),
                            )

                            else -> exchange.reply(
                                MPEGURL,
                                vod().replaceFirst(
                                    "#EXTM3U\n",
                                    "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"$target/key\"\n",
                                ).toByteArray(),
                            )
                        }
                    }

                    else -> exchange.proxy(media.base)
                }
            }
            server.start()
        }

        private fun vod(): String = String(fetch(media.base, "/vod.m3u8"))

        /**
         * Channel 1 the live playlist, 2 the VOD one, 3 MPEG-TS, 4 a playlist that answers 404,
         * and from 10 the shaped playlists.
         */
        private fun feedText(): String = buildString {
            append("#EXTM3U\n")
            append("#EXTINF:-1 tvg-id=\"live\" tvg-chno=\"1\",Live\n$base/live.m3u8\n")
            append("#EXTINF:-1 tvg-id=\"vod\" tvg-chno=\"2\",VOD\n$base/vod.m3u8\n")
            append("#EXTINF:-1 tvg-id=\"ts\" tvg-chno=\"3\",TS\n$base/stream.ts\n")
            append("#EXTINF:-1 tvg-id=\"missing\" tvg-chno=\"4\",Missing\n$base/missing.m3u8\n")
            for ((index, name) in shapes(
                targets.keys,
            ).withIndex()) {
                append(
                    "#EXTINF:-1 tvg-id=\"$name\" tvg-chno=\"${10 + index}\",$name\n$base/$name.m3u8\n",
                )
            }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    /** Answers every request the way the upstream would, counting each; nothing may ever reach it. */
    private class Trap(media: MediaFixture) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        val base = "http://127.0.0.1:${server.address.port}"
        val hits = AtomicInteger()

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                hits.incrementAndGet()
                if (exchange.requestURI.path == "/key") {
                    exchange.reply("application/octet-stream", ByteArray(16))
                } else {
                    exchange.proxy(media.base)
                }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private class Stack(val runtime: LiveRuntime, val server: RealServer, val ffmpeg: Path, val arguments: Path) :
        AutoCloseable {
        /** Every relay credential FFmpeg was started with so far. */
        fun credentials(): List<String> = if (Files.exists(arguments)) {
            CREDENTIAL.findAll(Files.readString(arguments)).map {
                it.groupValues[1]
            }.toList()
        } else {
            emptyList()
        }

        fun number(number: String): String = runtime.lineup.entries().single { it.number == number }.id

        fun offering(url: String): Offering = runtime.feeds.offerings().single { it.url == url }

        override fun close() {
            server.close()
            runBlocking { runtime.close() }
            runtime.store.close()
        }
    }

    /**
     * The server over [upstream], on a real socket with the HDHomeRun routes, and FFmpeg behind a wrapper that writes
     * its arguments to a file, one per line, before it runs.
     */
    private suspend fun stack(dir: Path, upstream: Upstream, allowLoopback: Boolean): Stack {
        val arguments = dir.resolve("ffmpeg-arguments.txt")
        val ffmpeg = dir.resolve("ffmpeg").also {
            Files.writeString(it, "#!/bin/sh\nprintf '%s\\n' \"\$@\" >> '$arguments'\nexec ffmpeg \"\$@\"\n")
            it.toFile().setExecutable(true)
        }
        val sources = dir.resolve("sources.json")
        Files.writeString(sources, Json.encodeToString(listOf(upstream.feed)))
        val store = openStore(dir)
        val hdhr = HdhrConfig(tuners = 2)
        val config =
            ServerConfig(
                "127.0.0.1",
                8080,
                dir,
                sources,
                token,
                allowLoopbackUpstreams = allowLoopback,
                hdhr = hdhr,
                ffmpeg = ffmpeg.toString(),
            )
        val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
        runtime.feeds.refresh(upstream.feed)
        runtime.adoptAll()
        return Stack(runtime, RealServer(store, MutableClock(), runtime, token, hdhr = hdhr), ffmpeg, arguments)
    }

    private fun Stack.probe(): ProbeWorker = ProbeWorker(
        runtime.feeds,
        runtime.store,
        MutableClock(),
        ffmpeg.toString(),
        config = ProbeConfig(decodeSeconds = 4, deadlineSeconds = 30),
        relay = runtime.loopback,
    )

    private fun Stack.recorder(): Recorder = Recorder(
        ffmpeg.toString(),
        runtime.sessions,
        runtime.feeds,
        Clock.systemUTC(),
        runtime.loopback,
        retryDelay = Duration.ofMillis(200),
    )

    /** What `/auto/v{number}` streamed, at most [limit] bytes. */
    private suspend fun HttpClient.remux(stack: Stack, number: String, limit: Int = 188 * 20): ByteArray =
        withTimeout(60_000) {
            prepareGet("${stack.server.base}/auto/v$number").execute { it.bodyAsChannel().readHead(limit) }
        }

    private suspend fun Stack.settled() = withTimeout(15_000) {
        while (runtime.remux.active() > 0 || runtime.sessions.count() > 0 || runtime.loopback.active() > 0) delay(50)
    }

    @Test
    fun `the remux, recorder and probe reach the upstream only through the loopback relay, with provider headers`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-loopback-reads-")
            try {
                MediaFixture().use { media ->
                    Upstream("127.0.0.1", media, emptyMap()).use { upstream ->
                        stack(dir, upstream, allowLoopback = true).use { stack ->
                            HttpClient(CIO).use { http ->
                                val live = http.remux(stack, "1")
                                assertEquals(188 * 20, live.size)
                                for (packet in 0 until 20) {
                                    assertEquals(
                                        0x47.toByte(),
                                        live[packet * 188],
                                        "sync byte of packet $packet",
                                    )
                                }
                                stack.settled()
                            }
                            // MPEG-TS over HTTP goes through the relay too: the recorder reads it with
                            // FFmpeg when no fan-out is given.
                            val file = dir.resolve("recording.ts")
                            val take = stack.recorder().record(stack.number("3"), file, Instant.now().plusSeconds(3))
                            assertTrue(take.bytes > 0, "recorded ${take.bytes} bytes, last failure ${take.lastFailure}")
                            assertEquals(0x47.toByte(), Files.newInputStream(file).use { it.read() }.toByte())
                            val worker = stack.probe()
                            val headers = upstream.feed.headers
                            assertNull(
                                worker.decode(stack.offering("${upstream.base}/vod.m3u8"), headers),
                                "the HLS source qualifies",
                            )
                            assertNull(
                                worker.decode(stack.offering("${upstream.base}/stream.ts"), headers),
                                "the MPEG-TS source qualifies",
                            )
                            stack.settled()

                            val media = upstream.seen.filter { it.path != "/feed.m3u" }
                            assertTrue(media.any { SEGMENT_PATH.matches(it.path) }, "segments were read: $media")
                            // FFmpeg sends Icy-MetaData and its own Lavf agent; the relay sends neither, and
                            // the provider's headers instead.
                            for (request in media) {
                                assertEquals(USER_AGENT, request.userAgent, request.toString())
                                assertEquals(PROVIDER_SECRET, request.providerKey, request.toString())
                                assertNull(request.icy, request.toString())
                            }
                            val arguments = Files.readString(stack.arguments)
                            val inputs = Regex("(?m)^-i\n(.*)$").findAll(arguments).map { it.groupValues[1] }.toList()
                            assertTrue(inputs.size >= 4, arguments)
                            for (input in inputs) {
                                assertTrue(
                                    input.matches(
                                        Regex(
                                            "http://127\\.0\\.0\\.1:\\d+/relay/[0-9a-f-]{36}/[0-9a-f-]{36}\\.(m3u8|ts)",
                                        ),
                                    ),
                                    input,
                                )
                            }
                            assertTrue(
                                ":${upstream.port}/" !in arguments,
                                "FFmpeg was never given the upstream's address",
                            )
                            assertTrue(
                                PROVIDER_SECRET !in arguments && USER_AGENT !in arguments,
                                "FFmpeg was never given the provider's headers",
                            )
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `a redirect, a variant, a segment or a key at a refused address fails instead of being fetched`() =
        runBlocking<Unit> {
            // The upstream must look like any LAN or public host, so loopback stays
            // refused; the trap listens on loopback.
            val outside = outsideAddress()
            assumeTrue(outside != null, "this machine has no address but loopback")
            val dir = Files.createTempDirectory("sofa-loopback-refused-")
            try {
                MediaFixture().use { media ->
                    Trap(media).use { trap ->
                        val targets = mapOf("trap" to trap.base, "metadata" to "http://169.254.169.254")
                        Upstream(outside!!, media, targets).use { upstream ->
                            stack(dir, upstream, allowLoopback = false).use { stack ->
                                val names = shapes(targets.keys)
                                val worker = stack.probe()
                                HttpClient(CIO).use { http ->
                                    // The same stack plays an allowed upstream, so each
                                    // failure below is the refused hop.
                                    assertEquals(188 * 3, http.remux(stack, "2", 188 * 3).size)
                                    stack.settled()
                                    for ((index, name) in names.withIndex()) {
                                        val number = "${10 + index}"
                                        val started = System.nanoTime()
                                        assertNotNull(
                                            worker.decode(
                                                stack.offering("${upstream.base}/$name.m3u8"),
                                                upstream.feed.headers,
                                            ),
                                            "$name: the probe fails",
                                        )
                                        // A connection to the address would wait out FFmpeg's 15-second read
                                        // timeout; a refused hop fails at once.
                                        assertTrue(
                                            System.nanoTime() - started < 10_000_000_000L,
                                            "$name: the probe failed within 10 s",
                                        )
                                        if (name.endsWith("-trap")) {
                                            assertEquals(
                                                0,
                                                http.remux(stack, number).size,
                                                "$name: the remux streams nothing",
                                            )
                                            stack.settled()
                                            val take = stack.recorder().record(
                                                stack.number(number),
                                                dir.resolve("$name.ts"),
                                                Instant.now().plusSeconds(2),
                                            )
                                            assertEquals(0L, take.bytes, "$name: the recording holds nothing")
                                        }
                                    }
                                }
                                stack.settled()
                                assertEquals(0, trap.hits.get(), "no request reached the refused address")
                            }
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `the relay credential is in no log line, session or job note, even when FFmpeg fails`() = runBlocking<Unit> {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val ours = context.getLogger("dev.jamesrich.sofaking")
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val level = ours.level
        root.addAppender(events)
        ours.level = Level.DEBUG
        val dir = Files.createTempDirectory("sofa-loopback-logs-")
        try {
            MediaFixture().use { media ->
                Upstream("127.0.0.1", media, emptyMap()).use { upstream ->
                    stack(dir, upstream, allowLoopback = true).use { stack ->
                        val notes = ArrayList<String>()
                        HttpClient(CIO).use { http ->
                            http.prepareGet("${stack.server.base}/auto/v1").execute { response ->
                                response.bodyAsChannel().readHead(188 * 3)
                                notes += stack.runtime.sessions.snapshot().toString()
                                notes += stack.runtime.jobs.snapshot().toString()
                            }
                            stack.settled()
                            // FFmpeg cannot open the source, exits with an error, and its last lines are logged.
                            assertEquals(0, http.remux(stack, "4").size)
                            stack.settled()
                        }
                        stack.probe().decode(stack.offering("${upstream.base}/missing.m3u8"), upstream.feed.headers)
                        stack.recorder().record(
                            stack.number("4"),
                            dir.resolve("missing.ts"),
                            Instant.now().plusSeconds(1),
                        )
                        stack.settled()
                        notes += stack.runtime.jobs.snapshot().toString()

                        val credentials = stack.credentials()
                        assertTrue(credentials.size >= 4, "FFmpeg ran with ${credentials.size} credentials")
                        assertEquals(credentials.size, credentials.toSet().size, "each FFmpeg run has its own")
                        val lines = events.list.map { event ->
                            event.formattedMessage + generateSequence(event.throwableProxy) {
                                it.cause
                            }.joinToString("") { " ${it.className}: ${it.message}" }
                        }
                        assertTrue(lines.any { "remux ended exit=" in it }, "the failed remux was logged")
                        for (credential in credentials) {
                            for (line in lines + notes) assertTrue(credential !in line, "a credential in: $line")
                        }
                    }
                }
            }
        } finally {
            root.detachAppender(events)
            ours.level = level
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `the loopback relay answers only a live lease's credential and is no route of the public server`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-loopback-guard-")
            try {
                MediaFixture().use { media ->
                    Upstream("127.0.0.1", media, emptyMap()).use { upstream ->
                        stack(dir, upstream, allowLoopback = true).use { stack ->
                            HttpClient(CIO).use { http ->
                                val stream = ResolvedStream("${upstream.base}/vod.m3u8", upstream.feed.headers)
                                val url = stack.runtime.loopback.reading(stream, "hls") { source ->
                                    assertEquals(
                                        HttpStatusCode.Unauthorized,
                                        http.get(source.url).status,
                                        "no credential",
                                    )
                                    assertEquals(
                                        HttpStatusCode.Unauthorized,
                                        http.get(source.url) {
                                            header(RELAY_CREDENTIAL_HEADER, source.credential.reversed())
                                        }.status,
                                        "a wrong credential",
                                    )
                                    val other = stack.runtime.loopback.reading(stream, "hls") { it.credential }
                                    assertEquals(
                                        HttpStatusCode.Unauthorized,
                                        http.get(source.url) {
                                            header(RELAY_CREDENTIAL_HEADER, other)
                                        }.status,
                                        "another lease's credential",
                                    )
                                    val playlist = http.get(source.url) {
                                        header(RELAY_CREDENTIAL_HEADER, source.credential)
                                    }
                                    assertEquals(HttpStatusCode.OK, playlist.status)
                                    val lease = URI(source.url).path.substringBeforeLast('/')
                                    val references = playlist.bodyAsText().lines().filter {
                                        it.isNotBlank() && !it.startsWith("#")
                                    }
                                    assertEquals(media.segments, references.size)
                                    for (reference in references) assertTrue(reference.startsWith("$lease/"), reference)
                                    val segment = http.get(
                                        "http://127.0.0.1:${URI(source.url).port}${references.first()}",
                                    ) {
                                        header(RELAY_CREDENTIAL_HEADER, source.credential)
                                    }
                                    assertEquals(0x47.toByte(), segment.readRawBytes().first())
                                    // The listener is on loopback alone.
                                    // A host firewall in stealth mode drops the attempt instead of
                                    // refusing it, so it times out instead.
                                    outsideAddress()?.let { address ->
                                        assertFailsWith<java.io.IOException> {
                                            Socket().use {
                                                it.connect(
                                                    java.net.InetSocketAddress(address, URI(source.url).port),
                                                    2_000,
                                                )
                                            }
                                        }
                                    }
                                    source.url to source.credential
                                }
                                assertEquals(
                                    HttpStatusCode.Unauthorized,
                                    http.get(url.first) {
                                        header(RELAY_CREDENTIAL_HEADER, url.second)
                                    }.status,
                                    "an ended lease",
                                )
                                assertEquals(
                                    HttpStatusCode.NotFound,
                                    http.get(stack.server.base + URI(url.first).path) {
                                        header(RELAY_CREDENTIAL_HEADER, url.second)
                                    }.status,
                                )
                            }
                            assertEquals(0, stack.runtime.loopback.active())
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    private companion object {
        const val USER_AGENT = "SofaTest/1"
        const val PROVIDER_SECRET = "provider-key-not-for-ffmpeg"
        const val MPEGURL = "application/vnd.apple.mpegurl"
        val SHAPED = Regex("/(redirect|variant|segments|key)-(\\w+)\\.m3u8")
        val SEGMENT_LINE = Regex("(?m)^seg\\d+\\.ts$")
        val SEGMENT_PATH = Regex("/seg\\d+\\.ts")
        val CREDENTIAL = Regex("$RELAY_CREDENTIAL_HEADER: ([A-Za-z0-9_-]+)")

        /** Each shape of a refused hop, at each target. */
        fun shapes(targets: Collection<String>): List<String> =
            listOf("redirect", "variant", "segments", "key").flatMap { shape ->
                targets.map { "$shape-$it" }
            }

        /**
         * An IPv4 address of this machine other than loopback or link-local, which the address policy
         * allows; null when there is none.
         */
        fun outsideAddress(): String? = NetworkInterface.networkInterfaces().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses() }.filter {
                it is Inet4Address && !it.isLinkLocalAddress && !it.isLoopbackAddress
            }
            .findFirst().orElse(null)?.hostAddress

        fun fetch(base: String, path: String): ByteArray = URI(base + path).toURL().readBytes()

        fun HttpExchange.reply(type: String, bytes: ByteArray) {
            responseHeaders.set("Content-Type", type)
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        /** Answers with what the media fixture has at the same path, or its 404. */
        fun HttpExchange.proxy(base: String) {
            val connection = URI(base + requestURI.path).toURL().openConnection() as java.net.HttpURLConnection
            if (connection.responseCode != 200) {
                sendResponseHeaders(connection.responseCode, -1)
                close()
                return
            }
            reply(connection.contentType ?: "application/octet-stream", connection.inputStream.use { it.readBytes() })
        }
    }
}

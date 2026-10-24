package dev.jamesrich.sofaking.backend.health

import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.lineupStack
import dev.jamesrich.sofaking.backend.playback.LoopbackRelay
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.playback.remuxCommand
import dev.jamesrich.sofaking.backend.store.health
import dev.jamesrich.sofaking.backend.store.offeringProtocols
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.contract.OpenPlayback
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The probe tiers: the HTTP check, the FFmpeg probes and recheck, and how each classifies what it sees. */
class ProbeTiersTest {
    @Test
    fun `FFmpeg 8 HTTP errors classify auth rate limit provider outage and local decoder errors`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-classification-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val worker =
                        ProbeWorker(
                            FeedService(emptyList(), store, http, MutableClock()),
                            store,
                            MutableClock(),
                            "unused",
                        )
                    assertEquals(
                        FailureClass.AUTH,
                        classifyFailure("Error opening input: Server returned 401 Unauthorized (authorization failed)"),
                    )
                    assertEquals(
                        FailureClass.AUTH,
                        classifyFailure("Error opening input: Server returned 403 Forbidden (access denied)"),
                    )
                    assertEquals(
                        FailureClass.RATE_LIMIT,
                        classifyFailure("Error opening input: Server returned 429 Too Many Requests"),
                    )
                    assertEquals(
                        FailureClass.PROVIDER,
                        classifyFailure("Error opening input: Server returned 5XX Server Error reply"),
                    )
                    assertEquals(FailureClass.PROVIDER, classifyFailure("HTTP error 503 Service Unavailable"))
                    assertEquals(
                        FailureClass.OFFERING,
                        classifyFailure("Error opening input: Server returned 404 Not Found"),
                    )
                    assertEquals(
                        FailureClass.OFFERING,
                        classifyFailure(
                            "Error opening input: Server returned 4XX Client Error, but not one of 40{0,1,3,4}",
                        ),
                    )
                    assertEquals(FailureClass.LOCAL, classifyFailure("Unknown decoder"))
                    assertEquals(FailureClass.OFFERING, classifyFailure("Invalid data found when processing input"))
                    assertEquals(
                        FailureClass.OFFERING,
                        classifyFailure("Stream ends prematurely at 206424, should be 18446744073709551615"),
                    )
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `fast HTTP tier passes stream signatures and classifies statuses by origin`() = runBlocking<Unit> {
        val ts = ByteArray(188 * 4) { if (it % 188 == 0) 0x47 else 0x11 }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/ts" -> respond(ts)
                "/m3u" -> respond("\uFEFF#EXTM3U\n#EXT-X-TARGETDURATION:4\n")
                "/html" -> respond("<html>not a stream</html>")
                "/502" -> respond("", HttpStatusCode.BadGateway)
                "/503" -> respond("", HttpStatusCode.ServiceUnavailable)
                "/404" -> respond("", HttpStatusCode.NotFound)
                "/410" -> respond("", HttpStatusCode.Gone)
                "/403" -> respond("", HttpStatusCode.Forbidden)
                "/429" -> respond("", HttpStatusCode.TooManyRequests)
                else -> error("unexpected")
            }
        }
        HttpClient(engine) { followRedirects = false }.use { http ->
            val tier = HttpQualifier(http, setOf("192.168.1.3:3000"))
            suspend fun check(url: String) = tier.check(Offering("o", "c", "p", url, "mpegts"), emptyMap()).failure
            assertEquals(
                HttpQualifier.Qualification(null, "mpegts"),
                tier.check(Offering("o", "c", "p", "http://192.168.1.3:3000/ts", "hls"), emptyMap()),
            )
            assertEquals(
                HttpQualifier.Qualification(null, "hls"),
                tier.check(Offering("o", "c", "p", "http://provider.test/m3u", "mpegts"), emptyMap()),
            )
            assertEquals(FailureClass.OFFERING, check("http://provider.test/html"))
            assertEquals(FailureClass.OFFERING, check("http://192.168.1.3:3000/502"))
            assertEquals(FailureClass.PROVIDER, check("http://provider.test/502"))
            assertEquals(FailureClass.PROVIDER, check("http://192.168.1.3:3000/503"))
            assertEquals(FailureClass.OFFERING, check("http://provider.test/404"))
            assertEquals(FailureClass.OFFERING, check("http://192.168.1.3:3000/410"))
            assertEquals(FailureClass.AUTH, check("http://provider.test/403"))
            assertEquals(FailureClass.RATE_LIMIT, check("http://provider.test/429"))
        }
        assertTrue(
            HttpQualifier.isRestreamOrigin("http://192.168.1.3:3000/api/ext/v1/dlhd/1", setOf("192.168.1.3:3000")),
        )
        assertTrue(HttpQualifier.isRestreamOrigin("http://192.168.1.3/x", setOf("192.168.1.3:80")))
        assertFalse(HttpQualifier.isRestreamOrigin("http://192.168.1.3:3001/x", setOf("192.168.1.3:3000")))
        assertTrue(HttpQualifier.isRestreamOrigin("https://proxy.lan:8443/x", setOf("proxy.lan")))
    }

    @Test fun `HTTP tier persists the detected protocol so sessions start with it before and after a restart`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-probe-protocol-")
            try {
                Fixture().use { fixture ->
                    HttpClient(CIO) { followRedirects = false }.use { http ->
                        val clock = MutableClock()
                        val config = fixture.extensionlessFeed.copy(probe = true)
                        val policy = UpstreamPolicy(allowLoopback = true)
                        val channelId = openStore(dir).use { store ->
                            val feeds = FeedService(listOf(config), store, http, clock, policy)
                            feeds.refresh(config)
                            val catalog = lineupStack(feeds, store, clock, http).catalog
                            val offering = feeds.published.offerings.single()
                            assertEquals("mpegts", offering.protocol)
                            // No FFmpeg here: the decode tier fails as LOCAL, which neither strikes nor
                            // hides the HTTP tier's detection.
                            val worker = ProbeWorker(feeds, store, clock, "unused", http)
                            assertTrue(worker.step())
                            assertEquals(mapOf(offering.id to "hls"), store.offeringProtocols())
                            assertEquals("hls", feeds.published.offering(offering.id)!!.protocol)
                            assertEquals(HealthStatus.UNVERIFIED, store.health(offering.id)!!.status)
                            val channelId = catalog.catalog().channels.single().id
                            val opened = SessionManager(
                                catalog,
                                store,
                                clock,
                            ).open(OpenPlayback(channelId, "preview", "k"))
                            assertEquals("hls", opened.descriptor.protocol)
                            assertEquals(1, opened.descriptor.epoch)
                            assertTrue(opened.descriptor.url.endsWith(".m3u8"))
                            channelId
                        }
                        openStore(dir).use { store ->
                            val feeds = FeedService(listOf(config), store, http, clock, policy)
                            feeds.initialize()
                            assertEquals("hls", feeds.published.offerings.single().protocol)
                            // A re-import assigns mpegts again; the persisted detection still wins.
                            feeds.refresh(config)
                            assertEquals("hls", feeds.published.offerings.single().protocol)
                            val catalog = lineupStack(feeds, store, clock, http, adopt = false).catalog
                            val opened = SessionManager(
                                catalog,
                                store,
                                clock,
                            ).open(OpenPlayback(channelId, "preview", "k"))
                            assertEquals("hls", opened.descriptor.protocol)
                            assertEquals(1, opened.descriptor.epoch)
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `a recheck passes on output time alone, since FFmpeg 6 prints no frame count when copying`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-probe-ffmpeg6-")
            try {
                // FFmpeg 6.1's -progress output for a 30-second stream copy: output time, no frame= line.
                val ffmpeg = dir.resolve("ffmpeg").also {
                    Files.writeString(
                        it,
                        "#!/bin/sh\nprintf 'out_time_us=-80000\\nout_time_us=29960000\\nprogress=end\\n'\n",
                    )
                    it.toFile().setExecutable(true)
                }
                HttpClient(CIO).use { http ->
                    openStore(dir.resolve("db")).use { store ->
                        val feeds = FeedService(emptyList(), store, http, MutableClock())
                        val relay = LoopbackRelay(http, UpstreamPolicy())
                        try {
                            val worker = ProbeWorker(feeds, store, MutableClock(), ffmpeg.toString(), relay = relay)
                            val offering = Offering("o", "c", "p", "http://example.test/live.m3u8", "hls")
                            assertNull(worker.decode(offering, emptyMap(), recheck = true))
                            assertEquals(
                                FailureClass.OFFERING,
                                worker.decode(offering, emptyMap(), recheck = false),
                                "a full decode still needs its frames",
                            )
                        } finally {
                            relay.close()
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a healthy recheck copies the video instead of decoding it, and both qualify real media`() = runBlocking<Unit> {
        val source = FfmpegSource("http://127.0.0.1:9/relay/lease/root.m3u8", "credential")
        val decodeArgs = decodeCommand("ffmpeg", source, "hls", 30, copy = false)
        val copyArgs = decodeCommand("ffmpeg", source, "hls", 30, copy = true)
        assertFalse("-c" in decodeArgs)
        assertEquals(decodeArgs.size + 2, copyArgs.size)
        assertEquals(
            listOf("-map", "0:v:0", "-c", "copy"),
            copyArgs.subList(
                copyArgs.indexOf("-map"),
                copyArgs.indexOf("-map") + 4,
            ),
        )

        val dir = Files.createTempDirectory("sofa-probe-copy-")
        try {
            MediaFixture().use { media ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val feeds = FeedService(emptyList(), store, http, MutableClock())
                        val relay = LoopbackRelay(http, UpstreamPolicy(allowLoopback = true))
                        try {
                            val worker =
                                ProbeWorker(
                                    feeds,
                                    store,
                                    MutableClock(),
                                    "ffmpeg",
                                    config = ProbeConfig(decodeSeconds = 4, deadlineSeconds = 30),
                                    relay = relay,
                                )
                            val vod = Offering("vod", "c", "media", "${media.base}/vod.m3u8", "hls")
                            assertNull(worker.decode(vod, emptyMap(), recheck = false))
                            assertNull(worker.decode(vod, emptyMap(), recheck = true))
                            assertEquals(
                                FailureClass.OFFERING,
                                worker.decode(vod.copy(url = "${media.base}/missing.m3u8"), emptyMap(), recheck = true),
                            )
                        } finally {
                            relay.close()
                        }
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `FFmpeg opens only the whitelisted protocols for probes and remuxes`() {
        val source = FfmpegSource("http://127.0.0.1:9/relay/lease/root.m3u8", "credential")
        for (command in listOf(
            decodeCommand("ffmpeg", source, "hls", 30, copy = false),
            remuxCommand("ffmpeg", source),
        )) {
            val at = command.indexOf("-protocol_whitelist")
            assertTrue(at in 1 until command.indexOf("-i"), "an input option: $command")
            assertEquals("http,tcp,crypto", command[at + 1])
        }
        MediaFixture().use { media ->
            var output = ""
            fun exit(url: String): Int {
                val process = ProcessBuilder(
                    decodeCommand("ffmpeg", FfmpegSource(url, "credential"), "mpegts", 1, copy = true),
                ).redirectErrorStream(true).start()
                output = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "FFmpeg finished")
                return process.exitValue()
            }
            assertEquals(0, exit("${media.base}/seg0.ts"), "HTTP is allowed")
            assertNotEquals(
                0,
                exit("https://${media.base.removePrefix("http://")}/seg0.ts"),
                "HTTPS is not: only the loopback relay is read",
            )
            assertTrue("not on whitelist" in output, output)
            assertNotEquals(0, exit("concat:${media.base}/seg0.ts|${media.base}/seg1.ts"), "concat is not")
            val local = Files.createTempFile("sofa-probe-local-", ".ts")
            try {
                Files.write(local, java.net.URI("${media.base}/seg0.ts").toURL().readBytes())
                assertNotEquals(0, exit("file:$local"), "a local file is not")
            } finally {
                Files.deleteIfExists(local)
            }
        }
    }
}

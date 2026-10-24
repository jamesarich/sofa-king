package dev.jamesrich.sofaking.backend.health

import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.FeedSnapshot
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.lineupStack
import dev.jamesrich.sofaking.backend.playback.LoopbackRelay
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.playback.remuxCommand
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.contract.OpenPlayback
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProbeWorkerTest {
    @Test fun `automatic independent failures quarantine then qualified probation restores eligibility`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-probe-policy-")
            try {
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val config = FeedConfig("p", "http://example.test/feed.m3u")
                        val channel = Channel("station", "5", "Station")
                        val offering = Offering("source", channel.id, "p", "http://example.test/live.m3u8", "hls")
                        store.saveSnapshot(FeedSnapshot("p", listOf(channel), listOf(offering), clock.now.toString()))
                        val feeds = FeedService(listOf(config), store, http, clock)
                        feeds.initialize()
                        val catalog = lineupStack(feeds, store, clock, http).catalog
                        var success = false
                        val worker =
                            ProbeWorker(feeds, store, clock, "unused") { _, _ ->
                                if (success) null else FailureClass.OFFERING
                            }
                        assertTrue(worker.step())
                        assertFalse(worker.step())
                        clock.now = clock.now.plusSeconds(300)
                        assertTrue(worker.step())
                        clock.now = clock.now.plusSeconds(300)
                        assertTrue(worker.step())
                        assertEquals(HealthStatus.QUARANTINED, store.health(offering.id)!!.status)
                        assertFalse(catalog.catalog().channels.single().playbackEligible)
                        clock.now = clock.now.plusSeconds(899)
                        assertFalse(worker.step())
                        clock.now = clock.now.plusSeconds(1)
                        success = true
                        assertTrue(worker.step())
                        assertEquals(HealthStatus.PROBATION, store.health(offering.id)!!.status)
                        clock.now = clock.now.plusSeconds(600)
                        assertTrue(worker.step())
                        assertEquals(HealthStatus.HEALTHY, store.health(offering.id)!!.status)
                        assertTrue(catalog.catalog().channels.single().playbackEligible)
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `provider faults do not strike and probe retry timing survives restart`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-restart-")
        try {
            HttpClient(CIO).use { http ->
                val clock = MutableClock()
                val config = FeedConfig("p", "http://example.test/feed.m3u")
                openStore(dir).use { store ->
                    store.saveSnapshot(
                        FeedSnapshot(
                            "p",
                            listOf(Channel("c", "1", "Station")),
                            listOf(Offering("s", "c", "p", "http://example.test/live.m3u8", "hls")),
                            clock.now.toString(),
                        ),
                    )
                    val feeds = FeedService(listOf(config), store, http, clock)
                    feeds.initialize()
                    val worker = ProbeWorker(feeds, store, clock, "unused") { _, _ -> FailureClass.PROVIDER }
                    assertTrue(worker.step())
                    assertTrue(store.health("s")!!.failures.isEmpty())
                }
                openStore(dir).use { store ->
                    val feeds = FeedService(listOf(config), store, http, clock)
                    feeds.initialize()
                    val worker = ProbeWorker(feeds, store, clock, "unused") { _, _ -> error("Should not run early") }
                    assertFalse(worker.step())
                    val disabled = store.setManuallyDisabled("s", true)
                    clock.now = clock.now.plusSeconds(3600)
                    assertFalse(worker.step())
                    assertTrue(disabled.manuallyDisabled)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a resolve that throws outside the provider contract is recorded against the source`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-resolve-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val clock = MutableClock()
                    store.saveSnapshot(
                        FeedSnapshot(
                            "p",
                            listOf(Channel("c", "1", "Station")),
                            listOf(Offering("s", "c", "p", "svc://xumo/1", "hls")),
                            clock.now.toString(),
                        ),
                    )
                    // Without a provider registry a native source's resolve throws
                    // IllegalStateException, not ProviderFailure.
                    val feeds = FeedService(listOf(FeedConfig("p", "http://example.test/feed.m3u")), store, http, clock)
                    feeds.initialize()
                    val worker =
                        ProbeWorker(feeds, store, clock, "unused") { _, _ -> error("the qualifier is never reached") }
                    assertTrue(worker.step())
                    assertEquals(1, store.health("s")!!.failures.size)
                    assertFalse(worker.step())
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

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

    @Test
    fun `suspect sources are rechecked before unverified ones and healthy rechecks come last`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-order-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val clock = MutableClock()
                    val config = FeedConfig("p", "http://example.test/feed.m3u")
                    val offerings = listOf("healthy", "unverified", "suspect").map {
                        Offering(it, "c", "p", "http://example.test/$it.ts", "mpegts")
                    }
                    store.saveSnapshot(
                        FeedSnapshot("p", listOf(Channel("c", "1", "Station")), offerings, clock.now.toString()),
                    )
                    store.recordObservation("healthy", Observation("ok", clock.now, true), clock.now)
                    store.recordObservation(
                        "suspect",
                        Observation("bad", clock.now, false, FailureClass.OFFERING),
                        clock.now,
                    )
                    val feeds = FeedService(listOf(config), store, http, clock)
                    feeds.initialize()
                    val probed = mutableListOf<String>()
                    val worker = ProbeWorker(feeds, store, clock, "unused") { offering, _ ->
                        probed += offering.id
                        null
                    }
                    clock.now = clock.now.plusSeconds(6 * 3600)
                    repeat(3) { assertTrue(worker.step()) }
                    assertFalse(worker.step())
                    assertEquals(listOf("suspect", "unverified", "healthy"), probed)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `workers probe different offerings concurrently and never the same one twice`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-parallel-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val clock = MutableClock()
                    val config = FeedConfig("p", "http://example.test/feed.m3u")
                    val offerings = (1..3).map { Offering("s$it", "c", "p", "http://example.test/$it.ts", "mpegts") }
                    store.saveSnapshot(
                        FeedSnapshot("p", listOf(Channel("c", "1", "Station")), offerings, clock.now.toString()),
                    )
                    val feeds = FeedService(listOf(config), store, http, clock)
                    feeds.initialize()
                    val started = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val inFlight = java.util.concurrent.atomic.AtomicInteger()
                    val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
                    val worker =
                        ProbeWorker(feeds, store, clock, "unused", config = ProbeConfig(parallelism = 3)) {
                                offering,
                                _,
                            ->
                            seen += offering.id
                            if (inFlight.incrementAndGet() == 3) started.complete(Unit)
                            release.await()
                            null
                        }
                    val running = launch { worker.run() }
                    withTimeout(10_000) { started.await() }
                    release.complete(Unit)
                    withTimeout(10_000) {
                        while (offerings.any { store.health(it.id)!!.status != HealthStatus.HEALTHY }) yield()
                    }
                    running.cancelAndJoin()
                    assertEquals(setOf("s1", "s2", "s3"), seen.toSet())
                    assertEquals(3, seen.size)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `health callback fires only when an observation changes the status`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-callback-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val clock = MutableClock()
                    val offering = Offering("source", "station", "p", "http://example.test/live.m3u8", "hls")
                    store.saveSnapshot(
                        FeedSnapshot(
                            "p",
                            listOf(Channel("station", "5", "Station")),
                            listOf(offering),
                            clock.now.toString(),
                        ),
                    )
                    val feeds = FeedService(listOf(FeedConfig("p", "http://example.test/feed.m3u")), store, http, clock)
                    feeds.initialize()
                    var success = false
                    val changed = mutableListOf<String>()
                    val observed = mutableListOf<String>()
                    val worker =
                        ProbeWorker(feeds, store, clock, "unused", onHealthChanged = { changed += it }, onObserved = {
                            observed +=
                                it
                        }) { _, _ ->
                            if (success) null else FailureClass.OFFERING
                        }
                    val statusChanges = mutableListOf<Boolean>()
                    suspend fun observe(afterSeconds: Long) {
                        clock.now = clock.now.plusSeconds(afterSeconds)
                        val statusBefore = store.health(offering.id)!!.status
                        val callbacksBefore = changed.size
                        assertTrue(worker.step())
                        val didChange = store.health(offering.id)!!.status != statusBefore
                        assertEquals(
                            if (didChange) 1 else 0,
                            changed.size - callbacksBefore,
                            "callback count for a step that changed=$didChange",
                        )
                        statusChanges += didChange
                    }
                    observe(0)
                    observe(300)
                    observe(300)
                    assertEquals(HealthStatus.QUARANTINED, store.health(offering.id)!!.status)
                    success = true
                    observe(900)
                    observe(600)
                    assertEquals(HealthStatus.HEALTHY, store.health(offering.id)!!.status)
                    observe(6 * 3600)
                    assertTrue(
                        statusChanges.any {
                            it
                        } && statusChanges.any { !it },
                        "the sequence must cover both an unchanged and a changed observation",
                    )
                    assertEquals(statusChanges.count { it }, changed.size)
                    assertEquals(List(statusChanges.size) { offering.id }, observed)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `under a policy the worker probes what stations reference by tier, whatever the probe flag`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-probe-targets-")
            try {
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val ids = listOf("a", "b", "c", "d", "e")
                        store.saveSnapshot(
                            FeedSnapshot(
                                "p",
                                ids.map {
                                    Channel(it, "1", it)
                                },
                                ids.map { Offering(it, it, "p", "http://example.test/$it.m3u8", "hls") },
                                clock.now.toString(),
                            ),
                        )
                        val feeds =
                            FeedService(
                                listOf(FeedConfig("p", "http://example.test/feed.m3u", probe = false)),
                                store,
                                http,
                                clock,
                            )
                        feeds.initialize()
                        store.recordObservation(
                            "a",
                            Observation("x", clock.now, false, FailureClass.OFFERING),
                            clock.now,
                        )
                        clock.now = clock.now.plusSeconds(600)

                        val probed = mutableListOf<String>()
                        val untargeted = ProbeWorker(feeds, store, clock, "unused") { offering, _ ->
                            probed += offering.id
                            null
                        }
                        assertFalse(untargeted.step(), "without a policy the probe flag holds")

                        // a is suspect, so health alone would take it first; the tiers come before health.
                        val plan = mapOf("a" to 2, "b" to 1, "c" to 0, "e" to 0)
                        val worker = ProbeWorker(feeds, store, clock, "unused", targets = { plan }) { offering, _ ->
                            probed +=
                                offering.id
                            null
                        }
                        while (worker.step()) { /* drain the plan */ }
                        assertEquals(
                            listOf("c", "e", "b", "a"),
                            probed,
                            "first sources of unverified stations, their other sources, then the rest; d is no " +
                                "station's",
                        )
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

    @Test fun `a provider with secret headers is probed, and its headers reach only the source's origin`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-probe-headers-")
            val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
            val engine = MockEngine { request ->
                seen += request.url.host to request.headers["X-Provider-Key"]
                if (request.url.host == "provider.test") {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        io.ktor.http.headersOf(io.ktor.http.HttpHeaders.Location, "http://cdn.test/live.ts"),
                    )
                } else {
                    respond("", HttpStatusCode.Forbidden)
                }
            }
            try {
                HttpClient(CIO).use { feedHttp ->
                    HttpClient(engine) { followRedirects = false }.use { http ->
                        openStore(dir).use { store ->
                            val clock = MutableClock()
                            val config =
                                FeedConfig(
                                    "p",
                                    "http://provider.test/feed.m3u",
                                    headers = mapOf("X-Provider-Key" to "secret"),
                                )
                            val offering = Offering("source", "c", "p", "http://provider.test/live.ts", "mpegts")
                            store.saveSnapshot(
                                FeedSnapshot(
                                    "p",
                                    listOf(Channel("c", "1", "Station")),
                                    listOf(offering),
                                    clock.now.toString(),
                                ),
                            )
                            val feeds = FeedService(listOf(config), store, feedHttp, clock)
                            feeds.initialize()
                            val worker = ProbeWorker(feeds, store, clock, "unused", http)
                            assertTrue(worker.step(), "the source is probed")
                            assertEquals(listOf("provider.test" to "secret", "cdn.test" to null), seen.toList())
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `probe now puts a source at the front of the queue whether or not it is due`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-now-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val clock = MutableClock()
                    val config = FeedConfig("p", "http://example.test/feed.m3u")
                    val channel = Channel("station", "5", "Station")
                    val offering = Offering("source", channel.id, "p", "http://example.test/live.m3u8", "hls")
                    store.saveSnapshot(FeedSnapshot("p", listOf(channel), listOf(offering), clock.now.toString()))
                    val feeds = FeedService(listOf(config), store, http, clock)
                    feeds.initialize()
                    val probed = mutableListOf<String>()
                    val worker = ProbeWorker(feeds, store, clock, "unused") { target, _ ->
                        probed += target.id
                        null
                    }
                    assertTrue(worker.step())
                    assertFalse(worker.step(), "a healthy source is not due again for hours")
                    assertFalse(worker.probeNow("nothing"), "an unknown source is refused")
                    assertTrue(worker.probeNow(offering.id))
                    assertTrue(worker.step(), "the asked-for probe runs at once")
                    assertEquals(listOf("source", "source"), probed)
                    assertFalse(worker.step())
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

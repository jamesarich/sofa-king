package dev.jamesrich.sofaking.backend.health

import dev.jamesrich.sofaking.backend.api.Channel
import dev.jamesrich.sofaking.backend.api.OpenPlayback
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class ProbeWorkerTest {
    @Test fun `automatic independent failures quarantine then qualified probation restores eligibility`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-policy-")
        try { HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock()
            val config = FeedConfig("p", "http://example.test/feed.m3u")
            val channel = Channel("station", "5", "Station")
            val offering = Offering("source", channel.id, "p", "http://example.test/live.m3u8", "hls")
            store.saveSnapshot(FeedSnapshot("p", listOf(channel), listOf(offering), emptyList(), clock.now.toString()))
            val feeds = FeedService(listOf(config), store, http, clock); feeds.initialize()
            val catalog = lineupStack(feeds, store, clock, http).catalog
            var success = false
            val worker = ProbeWorker(feeds, store, clock, "unused") { _, _ -> if (success) null else FailureClass.OFFERING }
            assertTrue(worker.step()); assertFalse(worker.step())
            clock.now = clock.now.plusSeconds(300); assertTrue(worker.step())
            clock.now = clock.now.plusSeconds(300); assertTrue(worker.step())
            assertEquals(HealthStatus.QUARANTINED, store.health(offering.id)!!.status)
            assertFalse(catalog.catalog().channels.single().playbackEligible)
            clock.now = clock.now.plusSeconds(899); assertFalse(worker.step())
            clock.now = clock.now.plusSeconds(1); success = true; assertTrue(worker.step())
            assertEquals(HealthStatus.PROBATION, store.health(offering.id)!!.status)
            clock.now = clock.now.plusSeconds(600); assertTrue(worker.step())
            assertEquals(HealthStatus.HEALTHY, store.health(offering.id)!!.status)
            assertTrue(catalog.catalog().channels.single().playbackEligible)
        } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `provider faults do not strike and probe retry timing survives restart`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-restart-")
        try { HttpClient(CIO).use { http ->
            val clock = MutableClock(); val config = FeedConfig("p", "http://example.test/feed.m3u")
            openStore(dir).use { store ->
                store.saveSnapshot(FeedSnapshot("p", listOf(Channel("c", "1", "Station")), listOf(Offering("s", "c", "p", "http://example.test/live.m3u8", "hls")), emptyList(), clock.now.toString()))
                val feeds = FeedService(listOf(config), store, http, clock); feeds.initialize()
                val worker = ProbeWorker(feeds, store, clock, "unused") { _, _ -> FailureClass.PROVIDER }
                assertTrue(worker.step()); assertTrue(store.health("s")!!.failures.isEmpty())
            }
            openStore(dir).use { store ->
                val feeds = FeedService(listOf(config), store, http, clock); feeds.initialize()
                val worker = ProbeWorker(feeds, store, clock, "unused") { _, _ -> error("Should not run early") }
                assertFalse(worker.step())
                val disabled = store.setManuallyDisabled("s", true)
                clock.now = clock.now.plusSeconds(3600); assertFalse(worker.step())
                assertTrue(disabled.manuallyDisabled)
            }
        } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `FFmpeg 8 HTTP errors classify auth rate limit provider outage and local decoder errors`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-classification-")
        try { HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val worker = ProbeWorker(FeedService(emptyList(), store, http, MutableClock()), store, MutableClock(), "unused")
            assertEquals(FailureClass.AUTH, worker.classify("Error opening input: Server returned 401 Unauthorized (authorization failed)"))
            assertEquals(FailureClass.AUTH, worker.classify("Error opening input: Server returned 403 Forbidden (access denied)"))
            assertEquals(FailureClass.RATE_LIMIT, worker.classify("Error opening input: Server returned 429 Too Many Requests"))
            assertEquals(FailureClass.PROVIDER, worker.classify("Error opening input: Server returned 5XX Server Error reply"))
            assertEquals(FailureClass.PROVIDER, worker.classify("HTTP error 503 Service Unavailable"))
            assertEquals(FailureClass.OFFERING, worker.classify("Error opening input: Server returned 404 Not Found"))
            assertEquals(FailureClass.OFFERING, worker.classify("Error opening input: Server returned 4XX Client Error, but not one of 40{0,1,3,4}"))
            assertEquals(FailureClass.LOCAL, worker.classify("Unknown decoder"))
            assertEquals(FailureClass.OFFERING, worker.classify("Invalid data found when processing input"))
            assertEquals(FailureClass.OFFERING, worker.classify("Stream ends prematurely at 206424, should be 18446744073709551615"))
        } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `fast HTTP tier passes stream signatures and classifies statuses by origin`() = runBlocking<Unit> {
        val ts = ByteArray(188 * 4) { if (it % 188 == 0) 0x47 else 0x11 }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/ts" -> respond(ts)
                "/m3u" -> respond("﻿#EXTM3U\n#EXT-X-TARGETDURATION:4\n")
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
            assertEquals(HttpQualifier.Qualification(null, "mpegts"), tier.check(Offering("o", "c", "p", "http://192.168.1.3:3000/ts", "hls"), emptyMap()))
            assertEquals(HttpQualifier.Qualification(null, "hls"), tier.check(Offering("o", "c", "p", "http://provider.test/m3u", "mpegts"), emptyMap()))
            assertEquals(FailureClass.OFFERING, check("http://provider.test/html"))
            assertEquals(FailureClass.OFFERING, check("http://192.168.1.3:3000/502"))
            assertEquals(FailureClass.PROVIDER, check("http://provider.test/502"))
            assertEquals(FailureClass.PROVIDER, check("http://192.168.1.3:3000/503"))
            assertEquals(FailureClass.OFFERING, check("http://provider.test/404"))
            assertEquals(FailureClass.OFFERING, check("http://192.168.1.3:3000/410"))
            assertEquals(FailureClass.AUTH, check("http://provider.test/403"))
            assertEquals(FailureClass.RATE_LIMIT, check("http://provider.test/429"))
        }
        assertTrue(HttpQualifier.isRestreamOrigin("http://192.168.1.3:3000/api/ext/v1/dlhd/1", setOf("192.168.1.3:3000")))
        assertTrue(HttpQualifier.isRestreamOrigin("http://192.168.1.3/x", setOf("192.168.1.3:80")))
        assertFalse(HttpQualifier.isRestreamOrigin("http://192.168.1.3:3001/x", setOf("192.168.1.3:3000")))
        assertTrue(HttpQualifier.isRestreamOrigin("https://proxy.lan:8443/x", setOf("proxy.lan")))
    }
    @Test fun `HTTP tier persists the detected protocol so sessions start with it before and after a restart`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-protocol-")
        try { Fixture().use { fixture -> HttpClient(CIO) { followRedirects = false }.use { http ->
            val clock = MutableClock()
            val config = fixture.extensionlessFeed.copy(probe = true)
            val policy = UpstreamPolicy(allowLoopback = true)
            val channelId = openStore(dir).use { store ->
                val feeds = FeedService(listOf(config), store, http, clock, policy); feeds.refresh(config)
                val catalog = lineupStack(feeds, store, clock, http).catalog
                val offering = feeds.offerings().single()
                assertEquals("mpegts", offering.protocol)
                // No FFmpeg here: the decode tier fails as LOCAL, which neither strikes nor hides the HTTP tier's detection.
                val worker = ProbeWorker(feeds, store, clock, "unused", http)
                assertTrue(worker.step())
                assertEquals(mapOf(offering.id to "hls"), store.offeringProtocols())
                assertEquals("hls", feeds.offering(offering.id)!!.protocol)
                assertEquals(HealthStatus.UNVERIFIED, store.health(offering.id)!!.status)
                val channelId = catalog.catalog().channels.single().id
                val opened = SessionManager(catalog, store, clock).open(OpenPlayback(channelId, "preview", "k"))
                assertEquals("hls", opened.descriptor.protocol); assertEquals(1, opened.descriptor.epoch); assertTrue(opened.descriptor.url.endsWith(".m3u8"))
                channelId
            }
            openStore(dir).use { store ->
                val feeds = FeedService(listOf(config), store, http, clock, policy); feeds.initialize()
                assertEquals("hls", feeds.offerings().single().protocol)
                // A re-import assigns mpegts again; the persisted detection still wins.
                feeds.refresh(config)
                assertEquals("hls", feeds.offerings().single().protocol)
                val catalog = lineupStack(feeds, store, clock, http, adopt = false).catalog
                val opened = SessionManager(catalog, store, clock).open(OpenPlayback(channelId, "preview", "k"))
                assertEquals("hls", opened.descriptor.protocol); assertEquals(1, opened.descriptor.epoch)
            }
        } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `suspect sources are rechecked before unverified ones and healthy rechecks come last`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-order-")
        try { HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val config = FeedConfig("p", "http://example.test/feed.m3u")
            val offerings = listOf("healthy", "unverified", "suspect").map { Offering(it, "c", "p", "http://example.test/$it.ts", "mpegts") }
            store.saveSnapshot(FeedSnapshot("p", listOf(Channel("c", "1", "Station")), offerings, emptyList(), clock.now.toString()))
            store.recordObservation("healthy", Observation("ok", clock.now, true), clock.now)
            store.recordObservation("suspect", Observation("bad", clock.now, false, FailureClass.OFFERING), clock.now)
            val feeds = FeedService(listOf(config), store, http, clock); feeds.initialize()
            val probed = mutableListOf<String>()
            val worker = ProbeWorker(feeds, store, clock, "unused") { offering, _ -> probed += offering.id; null }
            clock.now = clock.now.plusSeconds(6 * 3600)
            repeat(3) { assertTrue(worker.step()) }
            assertFalse(worker.step())
            assertEquals(listOf("suspect", "unverified", "healthy"), probed)
        } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `workers probe different offerings concurrently and never the same one twice`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-parallel-")
        try { HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val config = FeedConfig("p", "http://example.test/feed.m3u")
            val offerings = (1..3).map { Offering("s$it", "c", "p", "http://example.test/$it.ts", "mpegts") }
            store.saveSnapshot(FeedSnapshot("p", listOf(Channel("c", "1", "Station")), offerings, emptyList(), clock.now.toString()))
            val feeds = FeedService(listOf(config), store, http, clock); feeds.initialize()
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val inFlight = java.util.concurrent.atomic.AtomicInteger(); val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
            val worker = ProbeWorker(feeds, store, clock, "unused", config = ProbeConfig(parallelism = 3)) { offering, _ ->
                seen += offering.id
                if (inFlight.incrementAndGet() == 3) started.complete(Unit)
                release.await(); null
            }
            val running = launch { worker.run() }
            withTimeout(10_000) { started.await() }
            release.complete(Unit)
            withTimeout(10_000) { while (offerings.any { store.health(it.id)!!.status != HealthStatus.HEALTHY }) yield() }
            running.cancelAndJoin()
            assertEquals(setOf("s1", "s2", "s3"), seen.toSet()); assertEquals(3, seen.size)
        } } } finally { dir.toFile().deleteRecursively() }
    }
}

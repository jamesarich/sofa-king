package dev.jamesrich.sofaking.backend.health

import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.FeedSnapshot
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.lineupStack
import dev.jamesrich.sofaking.backend.store.health
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.recordObservation
import dev.jamesrich.sofaking.backend.store.saveSnapshot
import dev.jamesrich.sofaking.backend.store.setManuallyDisabled
import dev.jamesrich.sofaking.contract.Channel
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

    @Test fun `a refusal strikes once the provider's other sources qualify, but not while all are refused`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-probe-refused-")
            try {
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        store.saveSnapshot(
                            FeedSnapshot(
                                "p",
                                listOf(Channel("a", "1", "Blocked"), Channel("b", "2", "Open")),
                                listOf(
                                    Offering("blocked", "a", "p", "http://example.test/a.m3u8", "hls"),
                                    Offering("open", "b", "p", "http://example.test/b.m3u8", "hls"),
                                ),
                                clock.now.toString(),
                            ),
                        )
                        val feeds =
                            FeedService(listOf(FeedConfig("p", "http://example.test/feed.m3u")), store, http, clock)
                        feeds.initialize()
                        var outage = true
                        val worker = ProbeWorker(feeds, store, clock, "unused") { offering, _ ->
                            if (offering.id == "open" && !outage) null else FailureClass.AUTH
                        }
                        repeat(2) {
                            while (worker.step()) Unit
                            clock.now = clock.now.plusSeconds(300)
                        }
                        assertTrue(store.health("blocked")!!.failures.isEmpty(), "a credential outage strikes nothing")
                        assertTrue(store.health("open")!!.failures.isEmpty())
                        outage = false
                        repeat(2) {
                            while (worker.step()) Unit
                            clock.now = clock.now.plusSeconds(300)
                        }
                        assertEquals(HealthStatus.HEALTHY, store.health("open")!!.status)
                        assertEquals(HealthStatus.SUSPECT, store.health("blocked")!!.status)
                        assertEquals(FailureClass.OFFERING, store.health("blocked")!!.failures.single().failureClass)
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

    private suspend fun claimOrder(healthyFirst: String?): List<String> {
        val dir = Files.createTempDirectory("sofa-probe-first-pass-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val clock = MutableClock()
                    for (provider in listOf("a", "b")) {
                        val offerings = (1..4).map {
                            Offering("$provider$it", "$provider-c$it", provider, "http://x.test/$provider$it", "hls")
                        }
                        val channels = (1..4).map { Channel("$provider-c$it", "$it", "Station $it") }
                        store.saveSnapshot(FeedSnapshot(provider, channels, offerings, clock.now.toString()))
                    }
                    val feeds = FeedService(
                        listOf(FeedConfig("a", "http://x.test/a.m3u"), FeedConfig("b", "http://x.test/b.m3u")),
                        store,
                        http,
                        clock,
                    )
                    feeds.initialize()
                    if (healthyFirst != null) {
                        store.recordObservation(healthyFirst, Observation("seed", clock.now, true, null), clock.now)
                    }
                    val order = mutableListOf<String>()
                    val worker = ProbeWorker(
                        feeds,
                        store,
                        clock,
                        "unused",
                        config = ProbeConfig(firstPassPerProvider = 2),
                    ) { offering, _ ->
                        order += offering.id
                        null
                    }
                    while (worker.step()) Unit
                    return order
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a first pass claims the first sources of every provider with no healthy source before the rest`() =
        runBlocking<Unit> {
            assertEquals(listOf("a1", "b1", "a2", "b2", "a3", "a4", "b3", "b4"), claimOrder(null))
        }

    @Test fun `a provider that already has a healthy source gets no first pass`() = runBlocking<Unit> {
        assertEquals(listOf("b1", "b2", "a1", "a2", "a3", "b3", "b4"), claimOrder("a4"))
    }
}

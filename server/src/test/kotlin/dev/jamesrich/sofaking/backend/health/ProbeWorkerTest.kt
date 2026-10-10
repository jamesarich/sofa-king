package dev.jamesrich.sofaking.backend.health

import dev.jamesrich.sofaking.backend.api.Channel
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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
            var success = false
            val worker = ProbeWorker(feeds, store, clock, "unused") { _, _ -> if (success) null else FailureClass.OFFERING }
            assertTrue(worker.step()); assertFalse(worker.step())
            clock.now = clock.now.plusSeconds(300); assertTrue(worker.step())
            clock.now = clock.now.plusSeconds(300); assertTrue(worker.step())
            assertEquals(HealthStatus.QUARANTINED, store.health(offering.id)!!.status)
            assertFalse(feeds.catalog().channels.single().playbackEligible)
            clock.now = clock.now.plusSeconds(899); assertFalse(worker.step())
            clock.now = clock.now.plusSeconds(1); success = true; assertTrue(worker.step())
            assertEquals(HealthStatus.PROBATION, store.health(offering.id)!!.status)
            clock.now = clock.now.plusSeconds(600); assertTrue(worker.step())
            assertEquals(HealthStatus.HEALTHY, store.health(offering.id)!!.status)
            assertTrue(feeds.catalog().channels.single().playbackEligible)
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
    @Test fun `HTTP auth rate limit provider outage and local decoder errors are distinct`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-probe-classification-")
        try { HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val worker = ProbeWorker(FeedService(emptyList(), store, http, MutableClock()), store, MutableClock(), "unused")
            assertEquals(FailureClass.AUTH, worker.classify("HTTP error 401 Unauthorized"))
            assertEquals(FailureClass.AUTH, worker.classify("Server returned 403 Forbidden"))
            assertEquals(FailureClass.RATE_LIMIT, worker.classify("HTTP error 429 Too Many Requests"))
            assertEquals(FailureClass.PROVIDER, worker.classify("HTTP error 503 Service Unavailable"))
            assertEquals(FailureClass.LOCAL, worker.classify("Unknown decoder"))
            assertEquals(FailureClass.OFFERING, worker.classify("Invalid data found when processing input"))
        } } } finally { dir.toFile().deleteRecursively() }
    }
}

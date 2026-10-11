package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.*
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import kotlin.test.*

class SessionManagerTest {
    @Test fun `idempotency promotion capacity cancellation and lease expiry are owned`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-session-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true)); feeds.refresh(fixture.feed)
            val catalog = lineupStack(feeds, store, clock, http).catalog
            val sessions = SessionManager(catalog, store, clock, 1)
            val request = OpenPlayback(catalog.catalog().channels.single().id, "preview", "one")
            val opened = sessions.open(request)
            assertEquals(opened, sessions.open(request))
            assertEquals("IDEMPOTENCY_CONFLICT", assertFailsWith<ApiFailure> { sessions.open(request.copy(intent = "watch")) }.code)
            val promoted = sessions.update(opened.id, UpdatePlayback("watch"))
            assertEquals("watch", promoted.intent); assertEquals(opened.descriptor, promoted.descriptor)
            val listed = sessions.snapshot().single()
            assertEquals(opened.id, listed.id); assertEquals("watch", listed.intent); assertEquals("fixture", listed.providerId)
            assertFalse(listed.external); assertFalse(listed.busy); assertEquals(promoted.expiresAt, listed.expiresAt)
            assertEquals("CAPACITY_EXHAUSTED", assertFailsWith<ApiFailure> { sessions.open(request.copy(idempotencyKey = "two")) }.code)
            val mediaJob = Job()
            sessions.acquire(opened.id, 1, opened.descriptor.url.substringAfterLast('/'), mediaJob)
            sessions.release(opened.id)
            assertTrue(mediaJob.isCancelled)
            sessions.release(opened.id)
            assertEquals("SESSION_EXPIRED", assertFailsWith<ApiFailure> { sessions.open(request) }.code)
            val second = sessions.open(request.copy(idempotencyKey = "two"))
            clock.now = clock.now.plusSeconds(91); sessions.expire()
            assertEquals("SESSION_EXPIRED", assertFailsWith<ApiFailure> { sessions.get(second.id) }.code)
            assertTrue(sessions.snapshot().isEmpty(), "an expired session is not listed")
            sessions.close()
        } } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `at capacity the oldest preview is evicted and previews hold shorter leases than watches`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-evict-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true)); feeds.refresh(fixture.feed)
            val catalog = lineupStack(feeds, store, clock, http).catalog
            val channel = catalog.catalog().channels.single().id
            val sessions = SessionManager(catalog, store, clock, 2)
            val first = sessions.open(OpenPlayback(channel, "preview", "first"))
            assertEquals(clock.now.plusSeconds(45).toString(), first.expiresAt)
            val watching = sessions.open(OpenPlayback(channel, "watch", "watching"))
            assertEquals(clock.now.plusSeconds(90).toString(), watching.expiresAt)
            val firstMedia = Job(); sessions.acquire(first.id, 1, first.descriptor.url.substringAfterLast('/'), firstMedia)
            val third = sessions.open(OpenPlayback(channel, "preview", "third"))
            assertTrue(firstMedia.isCancelled)
            assertEquals("SESSION_EXPIRED", assertFailsWith<ApiFailure> { sessions.get(first.id) }.code)
            assertEquals(watching.id, sessions.get(watching.id).id); assertEquals(third.id, sessions.get(third.id).id)
            val promoted = sessions.update(third.id, UpdatePlayback("watch"))
            assertEquals(clock.now.plusSeconds(90).toString(), promoted.expiresAt)
            assertEquals("CAPACITY_EXHAUSTED", assertFailsWith<ApiFailure> { sessions.open(OpenPlayback(channel, "preview", "fourth")) }.code)
            clock.now = clock.now.plusSeconds(46); sessions.expire()
            assertEquals(watching.id, sessions.get(watching.id).id)
            sessions.close()
        } } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `manual disable immediately blocks media and cancels its upstream request`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-disable-play-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true)); feeds.refresh(fixture.feed)
            val catalog = lineupStack(feeds, store, clock, http).catalog
            val sessions = SessionManager(catalog, store, clock)
            val session = sessions.open(OpenPlayback(catalog.catalog().channels.single().id, idempotencyKey = "disable"))
            val token = session.descriptor.url.substringAfterLast('/')
            val job = Job(); sessions.acquire(session.id, 1, token, job)
            store.setManuallyDisabled(feeds.offerings().single().id, true)
            sessions.healthChanged(feeds.offerings().single().id)
            assertTrue(job.isCancelled)
            assertEquals("SOURCE_UNAVAILABLE", assertFailsWith<ApiFailure> { sessions.get(session.id) }.code)
            assertTrue(job.isCancelled)
            assertFailsWith<ApiFailure> { sessions.acquire(session.id, 1, token, Job()) }
            sessions.close()
        } } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `selection prefers healthy then unverified then suspect and never quarantined`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-select-")
        try { HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val config = FeedConfig("p", "http://example.test/feed.m3u", probe = false)
            val offerings = listOf("suspect", "unverified", "healthy", "quarantined").map { Offering(it, "c", "p", "http://example.test/$it.ts", "mpegts") }
            store.saveSnapshot(FeedSnapshot("p", listOf(Channel("c", "1", "Station")), offerings, emptyList(), clock.now.toString()))
            store.recordObservation("healthy", Observation("ok", clock.now, true), clock.now)
            store.recordObservation("suspect", Observation("bad", clock.now, false, FailureClass.OFFERING), clock.now)
            for (minute in listOf(0L, 5L, 10L)) {
                val at = clock.now.plusSeconds(minute * 60)
                store.recordObservation("quarantined", Observation("q-$minute", at, false, FailureClass.OFFERING), at)
            }
            val feeds = FeedService(listOf(config), store, http, clock); feeds.initialize()
            val catalog = lineupStack(feeds, store, clock, http).catalog
            val channel = catalog.catalog().channels.single().id
            val sessions = SessionManager(catalog, store, clock)
            suspend fun selected(key: String): String {
                val token = sessions.open(OpenPlayback(channel, "preview", key)).descriptor.url.substringAfterLast('/')
                return sessions.acquire(sessions.open(OpenPlayback(channel, "preview", key)).id, 1, token, Job()).offering.id
            }
            assertEquals("healthy", selected("one"))
            store.setManuallyDisabled("healthy", true)
            assertEquals("unverified", selected("two"))
            store.setManuallyDisabled("unverified", true)
            assertEquals("suspect", selected("three"))
            store.setManuallyDisabled("suspect", true)
            assertEquals("SOURCE_UNAVAILABLE", assertFailsWith<ApiFailure> { selected("four") }.code)
            sessions.close()
        } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `re-acquiring a live MPEG-TS stream cancels the previous media request but HLS requests coexist`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-reacquire-")
        try { HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val config = FeedConfig("p", "http://example.test/feed.m3u", probe = false)
            val channels = listOf(Channel("ts", "1", "TS"), Channel("hls", "2", "HLS"))
            val offerings = listOf(Offering("ts-source", "ts", "p", "http://example.test/live.ts", "mpegts"), Offering("hls-source", "hls", "p", "http://example.test/live.m3u8", "hls"))
            store.saveSnapshot(FeedSnapshot("p", channels, offerings, emptyList(), clock.now.toString()))
            val feeds = FeedService(listOf(config), store, http, clock); feeds.initialize()
            val catalog = lineupStack(feeds, store, clock, http).catalog
            val sessions = SessionManager(catalog, store, clock)
            val ids = catalog.catalog().channels.associate { it.name to it.id }
            val ts = sessions.open(OpenPlayback(ids.getValue("TS"), "watch", "ts"))
            val tsToken = ts.descriptor.url.substringAfterLast('/')
            val first = Job(); val second = Job()
            assertTrue(sessions.acquire(ts.id, 1, tsToken, first).shared)
            sessions.acquire(ts.id, 1, tsToken, second)
            assertTrue(first.isCancelled); assertFalse(second.isCancelled)
            val hls = sessions.open(OpenPlayback(ids.getValue("HLS"), "watch", "hls"))
            val hlsToken = hls.descriptor.url.substringAfterLast('/')
            val playlist = Job(); val segment = Job()
            assertFalse(sessions.acquire(hls.id, 1, hlsToken, playlist).shared)
            sessions.acquire(hls.id, 1, hlsToken, segment)
            assertFalse(playlist.isCancelled); assertFalse(segment.isCancelled)
            sessions.close()
        } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `playlist tokens survive the segment LRU and lookups are bidirectional`() {
        val session = LiveSession("s", OpenPlayback("c", "watch", "k"), Offering("o", "c", "p", "http://example.test/resolve?id=1", "hls"), Instant.MAX)
        val root = session.descriptor().descriptor.url.substringAfterLast('/')
        assertTrue(root.endsWith(".m3u8"))
        val variant = session.resource("http://example.test/v/index.m3u8")
        val first = session.resource("http://example.test/v/seg0.ts")
        assertEquals(first, session.resource("http://example.test/v/seg0.ts"))
        for (index in 1..4200) session.resource("http://example.test/v/seg$index.ts")
        assertEquals("http://example.test/resolve?id=1", session.resources[root])
        assertEquals("http://example.test/v/index.m3u8", session.resources[variant])
        assertNull(session.resources[first])
        assertEquals(4096 + 2, session.resources.size)
        assertEquals("http://example.test/v/seg4200.ts", session.resources[session.resource("http://example.test/v/seg4200.ts")])
        session.resources.clear()
        assertEquals(0, session.resources.size)
    }
    @Test fun `quarantined source blocks new playback without deleting channel`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-quarantine-play-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true)); feeds.refresh(fixture.feed)
            val offering = feeds.offerings().single()
            for (minute in listOf(0L, 5L, 10L)) {
                val at = clock.now.plusSeconds(minute * 60)
                store.recordObservation(offering.id, Observation("failure-$minute", at, false, FailureClass.OFFERING), at)
            }
            val catalog = lineupStack(feeds, store, clock, http).catalog
            val channel = catalog.catalog().channels.single()
            assertFalse(channel.playbackEligible)
            assertEquals("SOURCE_UNAVAILABLE", assertFailsWith<ApiFailure> { SessionManager(catalog, store, clock).open(OpenPlayback(channel.id, idempotencyKey = "blocked")) }.code)
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}

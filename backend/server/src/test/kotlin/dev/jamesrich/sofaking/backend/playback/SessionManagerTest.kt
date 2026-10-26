package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.api.HistoryQuery
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.CatalogService
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.FeedSnapshot
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.lineupStack
import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.health.FailureClass
import dev.jamesrich.sofaking.backend.health.Observation
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.recordObservation
import dev.jamesrich.sofaking.backend.store.saveSnapshot
import dev.jamesrich.sofaking.backend.store.setManuallyDisabled
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.UpdatePlayback
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionManagerTest {
    @Test fun `idempotency promotion capacity cancellation and lease expiry are owned`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-session-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val feeds =
                            FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.refresh(fixture.feed)
                        val catalog = lineupStack(feeds, store, clock, http).catalog
                        val sessions = SessionManager(catalog, store, clock, 1)
                        val request = OpenPlayback(catalog.catalog().channels.single().id, "preview", "one")
                        val opened = sessions.open(request)
                        assertEquals(opened, sessions.open(request))
                        assertEquals(
                            "IDEMPOTENCY_CONFLICT",
                            assertFailsWith<ApiFailure> {
                                sessions.open(request.copy(intent = "watch"))
                            }.code,
                        )
                        val promoted = sessions.update(opened.id, UpdatePlayback("watch"))
                        assertEquals("watch", promoted.intent)
                        assertEquals(opened.descriptor, promoted.descriptor)
                        val listed = sessions.snapshot().single()
                        assertEquals(opened.id, listed.id)
                        assertEquals("watch", listed.intent)
                        assertEquals("fixture", listed.providerId)
                        assertFalse(listed.external)
                        assertFalse(listed.busy)
                        assertEquals(promoted.expiresAt, listed.expiresAt)
                        assertEquals(
                            "CAPACITY_EXHAUSTED",
                            assertFailsWith<ApiFailure> {
                                sessions.open(request.copy(idempotencyKey = "two"))
                            }.code,
                        )
                        val mediaJob = Job()
                        sessions.media.acquire(opened.id, 1, opened.descriptor.url.substringAfterLast('/'), mediaJob)
                        sessions.release(opened.id)
                        assertTrue(mediaJob.isCancelled)
                        sessions.release(opened.id)
                        assertEquals("SESSION_EXPIRED", assertFailsWith<ApiFailure> { sessions.open(request) }.code)
                        val second = sessions.open(request.copy(idempotencyKey = "two"))
                        clock.now = clock.now.plusSeconds(91)
                        sessions.expire()
                        assertEquals("SESSION_EXPIRED", assertFailsWith<ApiFailure> { sessions.get(second.id) }.code)
                        assertTrue(sessions.snapshot().isEmpty(), "an expired session is not listed")
                        sessions.close()
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a channel the listener cannot use passes its turn to the next in line`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-listenable-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val feeds =
                            FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.refresh(fixture.feed)
                        val catalog = lineupStack(feeds, store, clock, http).catalog
                        val channel = catalog.catalog().channels.single().id
                        val sessions = SessionManager(catalog, store, clock)
                        assertEquals(
                            listOf(channel),
                            sessions.sources.listenable(listOf("missing", channel), 1) {
                                true
                            }.keys.toList(),
                            "an unknown channel passes",
                        )
                        assertTrue(
                            sessions.sources.listenable(listOf(channel), 1) {
                                false
                            }.isEmpty(),
                            "a refused source passes",
                        )
                        assertTrue(sessions.sources.listenable(listOf(channel), 0) { true }.isEmpty())
                        sessions.close()
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `previews hold shorter leases than watches`() = withChannel("sofa-leases-") {
            catalog,
            store,
            clock,
            channel,
        ->
        val sessions = SessionManager(catalog, store, clock, 2)
        assertEquals(
            clock.now.plusSeconds(45).toString(),
            sessions.open(OpenPlayback(channel, "preview", "p")).expiresAt,
        )
        val watching = sessions.open(OpenPlayback(channel, "watch", "w"))
        assertEquals(clock.now.plusSeconds(90).toString(), watching.expiresAt)
        sessions.close()
    }

    @Test fun `at capacity the oldest preview is evicted`() = withChannel("sofa-evict-") {
            catalog,
            store,
            clock,
            channel,
        ->
        val sessions = SessionManager(catalog, store, clock, 2)
        val first = sessions.open(OpenPlayback(channel, "preview", "first"))
        val watching = sessions.open(OpenPlayback(channel, "watch", "watching"))
        val firstMedia = Job()
        sessions.media.acquire(first.id, 1, first.descriptor.url.substringAfterLast('/'), firstMedia)
        assertEquals(
            Wire.ErrorCode.CHANNEL_NOT_FOUND,
            assertFailsWith<ApiFailure> { sessions.open(OpenPlayback("missing", "watch", "missing")) }.code,
        )
        assertEquals(
            Wire.ErrorCode.CHANNEL_NOT_FOUND,
            assertFailsWith<ApiFailure> { sessions.external.open("missing") }.code,
        )
        assertFalse(firstMedia.isCancelled, "a channel that cannot play evicts nobody")
        val third = sessions.open(OpenPlayback(channel, "preview", "third"))
        assertTrue(firstMedia.isCancelled)
        assertEquals("SESSION_EXPIRED", assertFailsWith<ApiFailure> { sessions.get(first.id) }.code)
        assertEquals(watching.id, sessions.get(watching.id).id)
        assertEquals(third.id, sessions.get(third.id).id)
        val promoted = sessions.update(third.id, UpdatePlayback("watch"))
        assertEquals(clock.now.plusSeconds(90).toString(), promoted.expiresAt)
        assertEquals(
            "CAPACITY_EXHAUSTED",
            assertFailsWith<ApiFailure> { sessions.open(OpenPlayback(channel, "preview", "fourth")) }.code,
        )
        clock.now = clock.now.plusSeconds(46)
        sessions.expire()
        assertEquals(watching.id, sessions.get(watching.id).id)
        sessions.close()
    }

    @Test fun `each ended session is a history row and an evicted preview says so`() = withChannel("sofa-history-") {
            catalog,
            store,
            clock,
            channel,
        ->
        val history = SessionHistory(store, ServerEvents(clock), clock)
        val sessions = SessionManager(catalog, store, clock, 2)
        sessions.history = history
        val tv = PlaybackClient("192.0.2.5", "tv-1")
        val preview = sessions.open(OpenPlayback(channel, "preview", "preview"), tv)
        val watching = sessions.open(OpenPlayback(channel, "watch", "watching"))
        val third = sessions.open(OpenPlayback(channel, "preview", "third"), tv)
        sessions.update(third.id, UpdatePlayback("watch"))
        sessions.release(watching.id)
        clock.now = clock.now.plusSeconds(91)
        sessions.expire()
        val player = sessions.external.open(channel, "192.0.2.9")
        sessions.close()
        val rows = history.list(HistoryQuery()).associateBy { it.id }
        assertEquals(4, rows.size)
        val evicted = rows.getValue(preview.id)
        assertEquals(HistoryReason.EVICTED, evicted.reason)
        assertEquals(HistoryKind.LIVE, evicted.kind)
        assertEquals("tv-1", evicted.clientId)
        assertEquals("192.0.2.5", evicted.address)
        assertEquals(channel, evicted.channelId)
        assertEquals("fixture", evicted.providerId)
        assertEquals("preview", evicted.mode)
        assertEquals(HistoryReason.RELEASED, rows.getValue(watching.id).reason)
        assertNull(rows.getValue(watching.id).clientId)
        val expired = rows.getValue(third.id)
        assertEquals(HistoryReason.EXPIRED, expired.reason)
        assertEquals("watch", expired.mode, "the mode it ended in")
        assertEquals(
            Instant.parse(expired.startedAt).plusSeconds(91),
            Instant.parse(expired.endedAt),
        )
        val external = rows.getValue(player.id)
        assertEquals(HistoryReason.STOPPED, external.reason)
        assertEquals("external", external.mode)
        assertEquals("192.0.2.9", external.address)
        assertEquals(listOf(third.id, preview.id), history.list(HistoryQuery(client = "tv-1")).map { it.id })
        assertEquals(listOf(player.id), history.list(HistoryQuery(client = "192.0.2.9")).map { it.id })
    }

    @Test fun `manual disable immediately blocks media and cancels its upstream request`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-disable-play-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val feeds =
                            FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.refresh(fixture.feed)
                        val catalog = lineupStack(feeds, store, clock, http).catalog
                        val sessions = SessionManager(catalog, store, clock)
                        val session = sessions.open(
                            OpenPlayback(catalog.catalog().channels.single().id, idempotencyKey = "disable"),
                        )
                        val token = session.descriptor.url.substringAfterLast('/')
                        val job = Job()
                        sessions.media.acquire(session.id, 1, token, job)
                        store.setManuallyDisabled(feeds.published.offerings.single().id, true)
                        sessions.healthChanged(feeds.published.offerings.single().id)
                        assertTrue(job.isCancelled)
                        assertEquals(
                            "SOURCE_UNAVAILABLE",
                            assertFailsWith<ApiFailure> {
                                sessions.get(session.id)
                            }.code,
                        )
                        assertTrue(job.isCancelled)
                        assertFailsWith<ApiFailure> { sessions.media.acquire(session.id, 1, token, Job()) }
                        sessions.close()
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `selection prefers healthy then unverified then suspect and never quarantined`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-select-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val clock = MutableClock()
                    val config = FeedConfig("p", "http://example.test/feed.m3u", probe = false)
                    val offerings = listOf("suspect", "unverified", "healthy", "quarantined").map {
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
                    for (minute in listOf(0L, 5L, 10L)) {
                        val at = clock.now.plusSeconds(minute * 60)
                        store.recordObservation(
                            "quarantined",
                            Observation("q-$minute", at, false, FailureClass.OFFERING),
                            at,
                        )
                    }
                    val feeds = FeedService(listOf(config), store, http, clock)
                    feeds.initialize()
                    val catalog = lineupStack(feeds, store, clock, http).catalog
                    val channel = catalog.catalog().channels.single().id
                    val sessions = SessionManager(catalog, store, clock)
                    suspend fun selected(key: String): String {
                        val token = sessions.open(
                            OpenPlayback(channel, "preview", key),
                        ).descriptor.url.substringAfterLast('/')
                        return sessions.media.acquire(
                            sessions.open(OpenPlayback(channel, "preview", key)).id,
                            1,
                            token,
                            Job(),
                        ).offering.id
                    }
                    assertEquals("healthy", selected("one"))
                    store.setManuallyDisabled("healthy", true)
                    assertEquals("unverified", selected("two"))
                    store.setManuallyDisabled("unverified", true)
                    assertEquals("suspect", selected("three"))
                    store.setManuallyDisabled("suspect", true)
                    assertEquals("SOURCE_UNAVAILABLE", assertFailsWith<ApiFailure> { selected("four") }.code)
                    sessions.close()
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a source that fails to resolve moves the session to the next and never back`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-failover-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val clock = MutableClock()
                    val config = FeedConfig("p", "http://example.test/feed.m3u", probe = false)
                    val offerings = listOf("first", "second").map {
                        Offering(it, "c", "p", "http://example.test/$it.ts", "mpegts")
                    }
                    store.saveSnapshot(
                        FeedSnapshot("p", listOf(Channel("c", "1", "Station")), offerings, clock.now.toString()),
                    )
                    for (id in listOf(
                        "first",
                        "second",
                    )) {
                        store.recordObservation(id, Observation("ok-$id", clock.now, true), clock.now)
                    }
                    val feeds = FeedService(listOf(config), store, http, clock)
                    feeds.initialize()
                    val catalog = lineupStack(feeds, store, clock, http).catalog
                    val sessions = SessionManager(catalog, store, clock)
                    val opened = sessions.open(OpenPlayback(catalog.catalog().channels.single().id, "watch", "one"))
                    val first = Job()
                    assertEquals(
                        "first",
                        sessions.media.acquire(
                            opened.id,
                            1,
                            opened.descriptor.url.substringAfterLast('/'),
                            first,
                        ).offering.id,
                    )
                    assertTrue(sessions.media.sourceFailed(opened.id, 1, first))
                    val moved = sessions.get(opened.id)
                    assertEquals(2, moved.descriptor.epoch)
                    val second = Job()
                    assertEquals(
                        "second",
                        sessions.media.acquire(
                            opened.id,
                            2,
                            moved.descriptor.url.substringAfterLast('/'),
                            second,
                        ).offering.id,
                    )
                    assertTrue(
                        sessions.media.sourceFailed(opened.id, 1, first),
                        "a request from an older epoch just re-reads",
                    )
                    assertFalse(sessions.media.sourceFailed(opened.id, 2, second), "no source is left")
                    sessions.close()
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `switch source keeps the session id and turns through every source`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-switch-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val clock = MutableClock()
                    val config = FeedConfig("p", "http://example.test/feed.m3u", probe = false)
                    val offerings = listOf("first", "second", "third").map {
                        Offering(it, "c", "p", "http://example.test/$it.ts", "mpegts")
                    }
                    store.saveSnapshot(
                        FeedSnapshot("p", listOf(Channel("c", "1", "Station")), offerings, clock.now.toString()),
                    )
                    for (offering in offerings) {
                        store.recordObservation(
                            offering.id,
                            Observation("ok-${offering.id}", clock.now, true),
                            clock.now,
                        )
                    }
                    val feeds = FeedService(listOf(config), store, http, clock)
                    feeds.initialize()
                    val catalog = lineupStack(feeds, store, clock, http).catalog
                    val sessions = SessionManager(catalog, store, clock)
                    val opened = sessions.open(OpenPlayback(catalog.catalog().channels.single().id, "watch", "k"))
                    val held = Job()
                    sessions.media.acquire(opened.id, 1, opened.descriptor.url.substringAfterLast('/'), held)

                    val switched = sessions.media.switchSource(opened.id)
                    assertEquals(opened.id, switched.id)
                    assertEquals(2, switched.descriptor.epoch)
                    assertTrue(held.isCancelled, "the old source's request stops")
                    val played = mutableListOf(sessions.media.offeringOf(opened.id).id)
                    repeat(3) {
                        sessions.media.switchSource(opened.id)
                        played += sessions.media.offeringOf(opened.id).id
                    }
                    assertEquals(listOf("second", "third", "first", "second"), played)
                    val stats = sessions.snapshot().single()
                    assertEquals(4, stats.sourceSwitches)
                    assertEquals("second", stats.offeringId)
                    assertNull(opened.health, "an open says nothing of health yet")
                    assertEquals(4, sessions.get(opened.id).health?.sourceSwitches, "a read carries the health")
                    assertEquals(4, sessions.update(opened.id, UpdatePlayback()).health?.sourceSwitches)

                    store.setManuallyDisabled("first", true)
                    store.setManuallyDisabled("third", true)
                    val refused = assertFailsWith<ApiFailure> { sessions.media.switchSource(opened.id) }
                    assertEquals("NO_OTHER_SOURCE", refused.code)
                    assertEquals("second", sessions.media.offeringOf(opened.id).id, "a refused switch stays put")
                    sessions.close()
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `failover moves to a source not played yet and stays when none is left`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-failover-")
        try {
            HttpClient(CIO).use { http ->
                openStore(dir).use { store ->
                    val clock = MutableClock()
                    val config = FeedConfig("p", "http://example.test/feed.m3u", probe = false)
                    val offerings = listOf("first", "second").map {
                        Offering(it, "c", "p", "http://example.test/$it.m3u8", "hls")
                    }
                    store.saveSnapshot(
                        FeedSnapshot("p", listOf(Channel("c", "1", "Station")), offerings, clock.now.toString()),
                    )
                    for (offering in offerings) {
                        store.recordObservation(
                            offering.id,
                            Observation("ok-${offering.id}", clock.now, true),
                            clock.now,
                        )
                    }
                    val feeds = FeedService(listOf(config), store, http, clock)
                    feeds.initialize()
                    val catalog = lineupStack(feeds, store, clock, http).catalog
                    val sessions = SessionManager(catalog, store, clock)
                    val channel = catalog.catalog().channels.single().id
                    val opened = sessions.open(OpenPlayback(channel, "watch", "k"))
                    val playing = Job()
                    val other = Job()
                    val token = opened.descriptor.url.substringAfterLast('/')
                    sessions.media.acquire(opened.id, 1, token, playing)
                    sessions.media.acquire(opened.id, 1, token, other)

                    assertEquals("second", sessions.media.failover(opened.id, 1, playing, "stall")?.id)
                    assertFalse(playing.isCancelled, "the request that found the stall goes on")
                    assertTrue(other.isCancelled)
                    assertEquals(2, sessions.get(opened.id).descriptor.epoch)
                    assertEquals(
                        "second",
                        sessions.media.failover(opened.id, 1, null, "stall")?.id,
                        "a request of an older epoch finds the session moved on",
                    )
                    assertNull(sessions.media.failover(opened.id, 2, null, "stall"), "never back to a stalled one")
                    assertEquals("second", sessions.media.offeringOf(opened.id).id)
                    assertEquals(1, sessions.snapshot().single().sourceSwitches)
                    sessions.close()
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `re-acquiring a live MPEG-TS stream cancels the previous media request but HLS requests coexist`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-reacquire-")
            try {
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val config = FeedConfig("p", "http://example.test/feed.m3u", probe = false)
                        val channels = listOf(Channel("ts", "1", "TS"), Channel("hls", "2", "HLS"))
                        val offerings =
                            listOf(
                                Offering("ts-source", "ts", "p", "http://example.test/live.ts", "mpegts"),
                                Offering("hls-source", "hls", "p", "http://example.test/live.m3u8", "hls"),
                            )
                        store.saveSnapshot(FeedSnapshot("p", channels, offerings, clock.now.toString()))
                        val feeds = FeedService(listOf(config), store, http, clock)
                        feeds.initialize()
                        val catalog = lineupStack(feeds, store, clock, http).catalog
                        val sessions = SessionManager(catalog, store, clock)
                        val ids = catalog.catalog().channels.associate { it.name to it.id }
                        val ts = sessions.open(OpenPlayback(ids.getValue("TS"), "watch", "ts"))
                        val tsToken = ts.descriptor.url.substringAfterLast('/')
                        val first = Job()
                        val second = Job()
                        assertTrue(sessions.media.acquire(ts.id, 1, tsToken, first).shared)
                        sessions.media.acquire(ts.id, 1, tsToken, second)
                        assertTrue(first.isCancelled)
                        assertFalse(second.isCancelled)
                        val hls = sessions.open(OpenPlayback(ids.getValue("HLS"), "watch", "hls"))
                        val hlsToken = hls.descriptor.url.substringAfterLast('/')
                        val playlist = Job()
                        val segment = Job()
                        assertFalse(sessions.media.acquire(hls.id, 1, hlsToken, playlist).shared)
                        sessions.media.acquire(hls.id, 1, hlsToken, segment)
                        assertFalse(playlist.isCancelled)
                        assertFalse(segment.isCancelled)
                        sessions.close()
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `playlist tokens survive the segment LRU and lookups are bidirectional`() {
        val session =
            LiveSession(
                "s",
                OpenPlayback("c", "watch", "k"),
                Offering("o", "c", "p", "http://example.test/resolve?id=1", "hls"),
                Instant.MAX,
            )
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
        assertEquals(
            "http://example.test/v/seg4200.ts",
            session.resources[session.resource("http://example.test/v/seg4200.ts")],
        )
        session.resources.clear()
        assertEquals(0, session.resources.size)
    }

    @Test fun `quarantined source blocks new playback without deleting channel`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-quarantine-play-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val feeds =
                            FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.refresh(fixture.feed)
                        val offering = feeds.published.offerings.single()
                        for (minute in listOf(0L, 5L, 10L)) {
                            val at = clock.now.plusSeconds(minute * 60)
                            store.recordObservation(
                                offering.id,
                                Observation("failure-$minute", at, false, FailureClass.OFFERING),
                                at,
                            )
                        }
                        val catalog = lineupStack(feeds, store, clock, http).catalog
                        val channel = catalog.catalog().channels.single()
                        assertFalse(channel.playbackEligible)
                        assertEquals(
                            "SOURCE_UNAVAILABLE",
                            assertFailsWith<ApiFailure> {
                                SessionManager(
                                    catalog,
                                    store,
                                    clock,
                                ).open(OpenPlayback(channel.id, idempotencyKey = "blocked"))
                            }.code,
                        )
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Runs [block] over the fixture feed's one channel, adopted into the lineup, in a fresh store. */
    private fun withChannel(
        prefix: String,
        block: suspend (CatalogService, BackendStore, MutableClock, String) -> Unit,
    ) = runBlocking<Unit> {
        val dir = Files.createTempDirectory(prefix)
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val feeds =
                            FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.refresh(fixture.feed)
                        val catalog = lineupStack(feeds, store, clock, http).catalog
                        block(catalog, store, clock, catalog.catalog().channels.single().id)
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

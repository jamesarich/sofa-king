package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.health.*
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class FeedServiceTest {
    @Test fun `atomic last good import persists across failure and restart without resetting disables`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-feed-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http ->
            val clock = MutableClock()
            var id = ""; var offering = ""
            openStore(dir).use { store ->
                val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                feeds.initialize(); feeds.refresh(fixture.feed)
                id = feeds.catalog().channels.single().id; offering = feeds.offerings().single().id
                store.setManuallyDisabled(offering, true)
                fixture.malformed = true; feeds.refresh(fixture.feed)
                assertEquals(id, feeds.catalog().channels.single().id)
                assertEquals("IMPORT_FAILED", feeds.providers().single().errorCode)
                assertFalse(feeds.catalog().channels.single().playbackEligible)
            }
            openStore(dir).use { store ->
                val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true)); feeds.initialize()
                assertEquals(id, feeds.catalog().channels.single().id)
                assertTrue(store.health(offering)!!.manuallyDisabled)
                assertEquals(1, feeds.guide(GuideQuery(listOf(id), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z")).channels.single().programmes.size)
            }
        } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `source withdrawal preserves channel identity and rediscovery preserves manual disable`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-withdrawal-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val config = fixture.feed.copy(allowEmpty = true)
            val feeds = FeedService(listOf(config), store, http, MutableClock(), UpstreamPolicy(allowLoopback = true)); feeds.refresh(config)
            val channel = feeds.catalog().channels.single(); val offering = feeds.offerings().single()
            store.setManuallyDisabled(offering.id, true)
            fixture.withdrawn = true; feeds.refresh(config)
            assertEquals(channel.id, feeds.catalog().channels.single().id)
            assertFalse(feeds.catalog().channels.single().playbackEligible)
            assertTrue(feeds.offerings().isEmpty())
            fixture.withdrawn = false; feeds.refresh(config)
            assertEquals(offering.id, feeds.offerings().single().id)
            assertTrue(store.health(offering.id)!!.manuallyDisabled)
        } } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `malformed entries are skipped and reported without failing the provider`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-partial-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val feeds = FeedService(listOf(fixture.feed), store, http, MutableClock(), UpstreamPolicy(allowLoopback = true))
            fixture.partial = true; feeds.refresh(fixture.feed)
            val status = feeds.providers().single()
            assertNull(status.errorCode)
            assertEquals(1, status.channels); assertEquals(1, status.sources)
            assertEquals(2, status.skippedEntries)
            assertEquals("duplicate-source=1, missing-id=1", status.skipReasons)
            assertEquals("Station", feeds.catalog().channels.single().name)
            fixture.partial = false; feeds.refresh(fixture.feed)
            assertEquals(0, feeds.providers().single().skippedEntries); assertNull(feeds.providers().single().skipReasons)
        } } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `catalog revision is stable until health or guide currency changes`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-revision-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock(); val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true)); feeds.refresh(fixture.feed)
            val initial = feeds.catalogRevision()
            assertEquals(initial, feeds.catalogRevision())
            assertEquals(initial, feeds.catalog(initial).revision)
            assertEquals("available", feeds.catalog().channels.single().guideStatus)
            store.setManuallyDisabled(feeds.offerings().single().id, true)
            val disabled = feeds.catalogRevision()
            assertNotEquals(initial, disabled)
            assertFalse(feeds.catalog(disabled).channels.single().playbackEligible)
            clock.now = Instant.parse("2026-10-10T14:00:01Z")
            val stale = feeds.catalogRevision()
            assertNotEquals(disabled, stale)
            assertEquals("stale", feeds.catalog(stale).channels.single().guideStatus)
            assertEquals(stale, feeds.catalogRevision())
        } } } } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `guide overlap bounds and revision changes are explicit`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-guide-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val feeds = FeedService(listOf(fixture.feed), store, http, MutableClock(), UpstreamPolicy(allowLoopback = true)); feeds.refresh(fixture.feed)
            val id = feeds.catalog().channels.single().id
            val query = GuideQuery(listOf(id), "2026-10-10T14:00:00Z", "2026-10-10T15:00:00Z")
            assertTrue(feeds.guide(query).channels.single().programmes.isEmpty())
            assertEquals("RESYNC_REQUIRED", assertFailsWith<ApiFailure> { feeds.guide(query.copy(revision = "old")) }.code)
            assertFailsWith<ApiFailure> { feeds.guide(query.copy(to = "2026-10-12T14:00:00Z")) }
            assertFailsWith<ApiFailure> { feeds.guide(query.copy(channelIds = List(201) { id })) }
            assertEquals("CHANNEL_NOT_FOUND", assertFailsWith<ApiFailure> { feeds.guide(query.copy(channelIds = listOf("unknown"))) }.code)
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}

package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GuideStatus
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.store.health
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.saveSnapshot
import dev.jamesrich.sofaking.backend.store.setManuallyDisabled
import dev.jamesrich.sofaking.backend.store.snapshots
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedServiceTest {
    @Test
    fun `atomic last good import persists across failure and restart without resetting disables`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-feed-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    val clock = MutableClock()
                    var id = ""
                    var offering = ""
                    openStore(dir).use { store ->
                        val feeds =
                            FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.initialize()
                        feeds.refresh(fixture.feed)
                        val stack = lineupStack(feeds, store, clock, http, guides = listOf(fixture.gzipGuide))
                        id = stack.catalog.catalog().channels.single().id
                        offering = feeds.published.offerings.single().id
                        store.setManuallyDisabled(offering, true)
                        fixture.malformed = true
                        feeds.refresh(fixture.feed)
                        assertEquals(id, stack.catalog.catalog().channels.single().id)
                        assertEquals("IMPORT_FAILED", feeds.statuses.single().errorCode)
                        assertFalse(stack.catalog.catalog().channels.single().playbackEligible)
                    }
                    openStore(dir).use { store ->
                        val feeds =
                            FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.initialize()
                        val stack =
                            lineupStack(feeds, store, clock, http, guides = listOf(fixture.gzipGuide), adopt = false)
                        assertEquals(
                            id,
                            stack.catalog.catalog().channels.single().id,
                            "the lineup is persisted, not re-adopted",
                        )
                        assertTrue(store.health(offering)!!.manuallyDisabled)
                        assertEquals(
                            1,
                            stack.catalog.guide(
                                GuideQuery(listOf(id), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z"),
                            ).channels.single().programs.size,
                        )
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `source withdrawal preserves channel identity and rediscovery preserves manual disable`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-withdrawal-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val config = fixture.feed.copy(allowEmpty = true)
                        val clock = MutableClock()
                        val feeds =
                            FeedService(listOf(config), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.refresh(config)
                        val catalog = lineupStack(feeds, store, clock, http).catalog
                        val channel = catalog.catalog().channels.single()
                        val offering = feeds.published.offerings.single()
                        val before = catalog.catalogRevision()
                        assertTrue(catalog.catalog(before).channels.single().playbackEligible)
                        fixture.withdrawn = true
                        feeds.refresh(config)
                        val withdrawn = catalog.catalogRevision()
                        assertNotEquals(
                            before,
                            withdrawn,
                            "a withdrawal writes no health row yet changes playbackEligible",
                        )
                        assertEquals(channel.id, catalog.catalog().channels.single().id)
                        assertFalse(catalog.catalog(withdrawn).channels.single().playbackEligible)
                        assertTrue(feeds.published.offerings.isEmpty())
                        store.setManuallyDisabled(offering.id, true)
                        fixture.withdrawn = false
                        feeds.refresh(config)
                        assertNotEquals(withdrawn, catalog.catalogRevision())
                        assertEquals(offering.id, feeds.published.offerings.single().id)
                        assertTrue(store.health(offering.id)!!.manuallyDisabled)
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `malformed entries are skipped and reported without failing the provider`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-partial-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val feeds =
                            FeedService(
                                listOf(fixture.feed),
                                store,
                                http,
                                MutableClock(),
                                UpstreamPolicy(allowLoopback = true),
                            )
                        fixture.partial = true
                        feeds.refresh(fixture.feed)
                        val status = feeds.statuses.single()
                        assertNull(status.errorCode)
                        assertEquals(2, status.channels)
                        assertEquals(2, status.sources)
                        assertEquals(1, status.skippedEntries)
                        assertEquals("duplicate-source=1", status.skipReasons)
                        assertEquals("url-identity=1", status.notes)
                        assertEquals(listOf("Station", "Nameless Id"), feeds.published.channels().map { it.name })
                        val nameless = feeds.published.channels().last()
                        assertEquals("9", nameless.displayNumber)
                        assertNull(nameless.legacyGuideId)
                        fixture.partial = false
                        feeds.refresh(fixture.feed)
                        assertEquals(0, feeds.statuses.single().skippedEntries)
                        assertNull(feeds.statuses.single().skipReasons)
                        assertNull(feeds.statuses.single().notes)
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `catalog revision is stable until health lineup or guide currency changes`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-revision-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val feeds =
                            FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.refresh(fixture.feed)
                        val stack = lineupStack(feeds, store, clock, http, guides = listOf(fixture.gzipGuide))
                        val catalog = stack.catalog
                        val initial = catalog.catalogRevision()
                        assertEquals(initial, catalog.catalogRevision())
                        assertEquals(initial, catalog.catalog(initial).revision)
                        assertEquals("available", catalog.catalog().channels.single().guideStatus)
                        store.setManuallyDisabled(feeds.published.offerings.single().id, true)
                        val disabled = catalog.catalogRevision()
                        assertNotEquals(initial, disabled)
                        assertFalse(catalog.catalog(disabled).channels.single().playbackEligible)
                        clock.now = Instant.parse("2026-10-10T14:00:01Z")
                        val stale = catalog.catalogRevision()
                        assertNotEquals(disabled, stale)
                        assertEquals("stale", catalog.catalog(stale).channels.single().guideStatus)
                        assertEquals(stale, catalog.catalogRevision())
                        stack.lineup.update(catalog.catalog().channels.single().id, UpdateLineupEntry(name = "Renamed"))
                        assertNotEquals(stale, catalog.catalogRevision())
                        assertEquals("Renamed", catalog.catalog().channels.single().name)
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `guide overlap bounds and revision changes are explicit`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-guide-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val feeds =
                            FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        feeds.refresh(fixture.feed)
                        val catalog = lineupStack(feeds, store, clock, http, guides = listOf(fixture.gzipGuide)).catalog
                        val id = catalog.catalog().channels.single().id
                        val query = GuideQuery(listOf(id), "2026-10-10T14:00:00Z", "2026-10-10T15:00:00Z")
                        assertTrue(catalog.guide(query).channels.single().programs.isEmpty())
                        val overlapping = catalog.guide(query.copy(from = "2026-10-10T13:30:00Z")).channels.single()
                        assertEquals("Morning", overlapping.programs.single().title)
                        assertEquals(id, overlapping.programs.single().channelId)
                        assertEquals(
                            "RESYNC_REQUIRED",
                            assertFailsWith<ApiFailure> {
                                catalog.guide(query.copy(revision = "old"))
                            }.code,
                        )
                        assertFailsWith<ApiFailure> { catalog.guide(query.copy(to = "2026-10-12T14:00:00Z")) }
                        assertFailsWith<ApiFailure> { catalog.guide(query.copy(channelIds = List(201) { id })) }
                        val withRemoved = catalog.guide(
                            query.copy(from = "2026-10-10T13:30:00Z", channelIds = listOf(id, "unknown")),
                        ).channels
                        assertEquals(listOf(id, "unknown"), withRemoved.map { it.channelId })
                        assertEquals("Morning", withRemoved.first().programs.single().title)
                        assertEquals(Wire.GuideStatus.REMOVED, withRemoved.last().status)
                        assertTrue(withRemoved.last().programs.isEmpty())
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an import that changes nothing rewrites nothing and wakes nothing after the first`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-feed-unchanged-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        var imported = 0
                        val feeds =
                            FeedService(
                                listOf(fixture.feed),
                                store,
                                http,
                                clock,
                                UpstreamPolicy(allowLoopback = true),
                            ) {
                                imported++
                            }
                        feeds.initialize()
                        feeds.refresh(fixture.feed)
                        val first = store.snapshots().single().importedAt
                        assertEquals(1, imported)
                        clock.now = clock.now.plusSeconds(3600)
                        feeds.refresh(fixture.feed)
                        assertEquals(
                            first,
                            store.snapshots().single().importedAt,
                            "the stored snapshot is not rewritten",
                        )
                        assertEquals(1, imported)
                        assertEquals(
                            clock.now.toString(),
                            feeds.statuses.single().lastSuccessAt,
                            "the import still counts as a success",
                        )

                        val restarted =
                            FeedService(
                                listOf(fixture.feed),
                                store,
                                http,
                                clock,
                                UpstreamPolicy(allowLoopback = true),
                            ) {
                                imported++
                            }
                        restarted.initialize()
                        restarted.refresh(fixture.feed)
                        assertEquals(
                            2,
                            imported,
                            "the first success after a start wakes the lineup even when nothing changed",
                        )
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a provider retired for 30 days and sources no feed lists lose their stored data, hand edits stay`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-feed-retired-")
            try {
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val start = clock.now
                        fun snapshot(provider: String, ids: List<String>, at: Instant) = FeedSnapshot(
                            provider,
                            ids.map {
                                Channel(it, "1", it)
                            },
                            ids.map { Offering(it, it, provider, "http://example.test/$it.m3u8", "hls") },
                            at.toString(),
                        )
                        store.saveSnapshot(snapshot("gone", listOf("old", "kept-by-hand"), start))
                        store.setManuallyDisabled("kept-by-hand", true)
                        store.saveSnapshot(
                            snapshot("paused", listOf("recent"), start.plus(java.time.Duration.ofDays(59))),
                        )
                        store.saveSnapshot(
                            snapshot("live", listOf("current"), start.plus(java.time.Duration.ofDays(60))),
                        )
                        clock.now = start.plus(java.time.Duration.ofDays(60))

                        FeedService(
                            listOf(FeedConfig("live", "http://example.test/feed.m3u")),
                            store,
                            http,
                            clock,
                        ).initialize()
                        assertEquals(
                            setOf("live", "paused"),
                            store.snapshots().map {
                                it.providerId
                            }.toSet(),
                            "a provider left out for under 30 days keeps its snapshot",
                        )
                        assertNull(store.health("old"))
                        assertNotNull(store.health("kept-by-hand"), "a source disabled by hand keeps its row")
                        assertNotNull(store.health("recent"))
                        assertNotNull(store.health("current"))

                        FeedService(emptyList(), store, http, clock).initialize()
                        assertEquals(
                            setOf("live", "paused"),
                            store.snapshots().map {
                                it.providerId
                            }.toSet(),
                            "with nothing configured nothing is pruned",
                        )
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
}

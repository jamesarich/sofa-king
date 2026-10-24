package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.AdoptProvider
import dev.jamesrich.sofaking.backend.api.AdoptResult
import dev.jamesrich.sofaking.backend.api.CreateLineupEntry
import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.health.Observation
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.recordObservation
import dev.jamesrich.sofaking.backend.store.setManuallyDisabled
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LineupServiceTest {
    @Test fun `nothing is served until it enters the lineup and an entry fills in from its channel`() = lineupTest {
        assertTrue(stack.catalog.catalog().channels.isEmpty(), "nothing is served until it enters the lineup")
        assertEquals(2, feeds.published.channels().size, "feed channels outside the lineup stay candidates")
        val ts = feeds.published.channels("ts").single()
        val created = stack.lineup.create(CreateLineupEntry(channelId = ts.id, number = "100"))
        assertEquals("TS Station", created.name)
        assertEquals("100", created.number)
        assertEquals(feeds.published.offeringsOf(ts.id).map { it.id }, created.offeringIds)
    }

    @Test fun `entries are served in lineup order and a reorder names every entry`() = lineupTest {
        val created = tsEntry()
        val manual = manualEntry()
        assertEquals(listOf("Manual", "TS Station"), names())
        stack.lineup.reorder(listOf(created.id, manual.id))
        assertEquals(listOf("TS Station", "Manual"), names())
        assertEquals("INVALID_REQUEST", codeOf { stack.lineup.reorder(listOf(created.id)) })
    }

    @Test fun `a disabled entry leaves the catalog and returns where an update puts it`() = lineupTest {
        tsEntry()
        val manual = manualEntry()
        stack.lineup.update(manual.id, UpdateLineupEntry(enabled = false, group = "Hidden"))
        assertEquals(listOf("TS Station"), names())
        val disabled = stack.catalog.guide(
            GuideQuery(listOf(manual.id), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z"),
        ).channels.single()
        assertEquals(Wire.GuideStatus.REMOVED, disabled.status)
        assertTrue(disabled.programs.isEmpty())
        stack.lineup.update(manual.id, UpdateLineupEntry(enabled = true, position = 0, clearGroup = true))
        assertEquals(listOf("Manual", "TS Station"), names())
        assertTrue(stack.catalog.catalog().channels.first().tags.isEmpty())
    }

    @Test fun `bad entries are refused with their codes`() = lineupTest {
        val created = tsEntry()
        val manual = manualEntry()
        assertEquals(
            "SOURCE_NOT_FOUND",
            codeOf { stack.lineup.update(manual.id, UpdateLineupEntry(offerings = listOf("missing"))) },
        )
        assertEquals(
            "INVALID_REQUEST",
            codeOf {
                stack.lineup.create(CreateLineupEntry(number = "2", name = "Bad", guide = GuideMapping("nope", "x")))
            },
        )
        assertEquals(
            "INVALID_REQUEST",
            codeOf { stack.lineup.create(CreateLineupEntry(number = "2", name = "Bad", logo = "ftp://x")) },
        )
        assertEquals("CHANNEL_NOT_FOUND", codeOf { stack.lineup.create(CreateLineupEntry(channelId = "unknown")) })
        stack.lineup.delete(created.id)
        assertEquals("LINEUP_ENTRY_NOT_FOUND", codeOf { stack.lineup.delete(created.id) })
    }

    @Test fun `adopt adds only the channels whose sources the lineup does not have yet`() = lineupTest {
        manualEntry()
        assertEquals(
            AdoptResult(0, 1),
            stack.lineup.adopt(AdoptProvider("fixture")),
            "a channel whose sources are already in the lineup is not adopted twice",
        )
        assertEquals(AdoptResult(1, 0), stack.lineup.adopt(AdoptProvider("ts")))
        assertEquals("PROVIDER_NOT_FOUND", codeOf { stack.lineup.adopt(AdoptProvider("nope")) })
    }

    @Test fun `playback walks the entry's sources in order within each health rank`() = failoverTest {
        assertEquals(primary.id, selected("both-unverified"))
        store.recordObservation(alternate.id, Observation("ok", clock.now, true), clock.now)
        assertEquals(alternate.id, selected("healthy-second"), "a healthy second choice beats an unverified first")
        store.recordObservation(primary.id, Observation("ok2", clock.now, true), clock.now)
        assertEquals(primary.id, selected("both-healthy"))
    }

    @Test fun `playback skips withdrawn sources and fails once none is left`() = failoverTest {
        fixture.withdrawn = true
        feeds.refresh(fixture.feed.copy(allowEmpty = true))
        assertEquals(alternate.id, selected("primary-withdrawn"))
        assertEquals(
            listOf(primary.id, alternate.id),
            stack.lineup.entry(entry.id)!!.offeringIds,
            "withdrawn sources stay in the entry",
        )
        store.setManuallyDisabled(alternate.id, true)
        assertEquals("SOURCE_UNAVAILABLE", codeOf { selected("none-left") })
        assertEquals(
            "CHANNEL_NOT_FOUND",
            codeOf {
                sessions.open(OpenPlayback(feeds.published.channels("fixture").single().id, "preview", "feed-id"))
            },
            "feed channel ids are not playable",
        )
    }

    private open class LineupScope(
        val fixture: Fixture,
        val store: BackendStore,
        val clock: MutableClock,
        val feeds: FeedService,
        val stack: LineupStack,
    ) {
        suspend fun tsEntry(): LineupEntryRecord = stack.lineup.create(
            CreateLineupEntry(channelId = feeds.published.channels("ts").single().id, number = "100"),
        )

        /** An entry made by hand from the fixture channel's sources, first in the lineup. */
        suspend fun manualEntry(): LineupEntryRecord {
            val sources = feeds.published.offeringsOf(feeds.published.channels("fixture").single().id).map { it.id }
            return stack.lineup.create(
                CreateLineupEntry(number = "1", name = "Manual", offerings = sources, position = 0),
            )
        }

        suspend fun names(): List<String> = stack.catalog.catalog().channels.map { it.name }

        suspend fun codeOf(block: suspend () -> Unit): String = assertFailsWith<ApiFailure> { block() }.code
    }

    /** An entry over the fixture feed's source then the alternate feed's, and sessions to play it. */
    private class FailoverScope(scope: LineupScope) :
        LineupScope(scope.fixture, scope.store, scope.clock, scope.feeds, scope.stack) {
        val primary = feeds.published.offeringsOf(feeds.published.channels("fixture").single().id).single()
        val alternate = feeds.published.offeringsOf(feeds.published.channels("alt").single().id).single()
        val sessions = SessionManager(stack.catalog, store, clock)
        lateinit var entry: LineupEntryRecord

        /** The source a new preview session under [key] plays. */
        suspend fun selected(key: String): String {
            val opened = sessions.open(OpenPlayback(entry.id, "preview", key))
            return sessions.media.acquire(
                opened.id,
                1,
                opened.descriptor.url.substringAfterLast('/'),
                Job(),
            ).offering.id
        }
    }

    /** Runs [block] over the fixture and the TS feeds, refreshed, with nothing adopted into the lineup. */
    private fun lineupTest(block: suspend LineupScope.() -> Unit) =
        withFeeds("sofa-lineup-", { listOf(feed, tsFeed) }) {
            it.block()
        }

    private fun failoverTest(block: suspend FailoverScope.() -> Unit) =
        withFeeds("sofa-failover-", { listOf(feed, altFeed) }) { scope ->
            val failover = FailoverScope(scope)
            failover.entry = scope.stack.lineup.create(
                CreateLineupEntry(
                    channelId = scope.feeds.published.channels("fixture").single().id,
                    offerings = listOf(failover.primary.id, failover.alternate.id),
                ),
            )
            try {
                failover.block()
            } finally {
                failover.sessions.close()
            }
        }

    private fun withFeeds(
        prefix: String,
        configs: Fixture.() -> List<FeedConfig>,
        block: suspend (LineupScope) -> Unit,
    ) = runBlocking<Unit> {
        val dir = Files.createTempDirectory(prefix)
        try {
            Fixture().use { fixture ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val feeds =
                            FeedService(fixture.configs(), store, http, clock, UpstreamPolicy(allowLoopback = true))
                        for (config in fixture.configs()) feeds.refresh(config)
                        val stack = lineupStack(feeds, store, clock, http, adopt = false)
                        block(LineupScope(fixture, store, clock, feeds, stack))
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

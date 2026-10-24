package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.AdoptProvider
import dev.jamesrich.sofaking.backend.api.AdoptResult
import dev.jamesrich.sofaking.backend.api.CreateLineupEntry
import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.GuideStatus
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.health.Observation
import dev.jamesrich.sofaking.backend.playback.SessionManager
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
    @Test fun `only lineup entries are served in lineup order and feed channels outside it stay candidates`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-lineup-")
            try {
                Fixture().use { fixture ->
                    HttpClient(CIO).use { http ->
                        openStore(dir).use { store ->
                            val clock = MutableClock()
                            val feeds =
                                FeedService(
                                    listOf(fixture.feed, fixture.tsFeed),
                                    store,
                                    http,
                                    clock,
                                    UpstreamPolicy(allowLoopback = true),
                                )
                            feeds.refresh(fixture.feed)
                            feeds.refresh(fixture.tsFeed)
                            val stack = lineupStack(feeds, store, clock, http, adopt = false)
                            assertTrue(
                                stack.catalog.catalog().channels.isEmpty(),
                                "nothing is served until it enters the lineup",
                            )
                            assertEquals(2, feeds.published.channels().size)
                            val ts = feeds.published.channels("ts").single()
                            val created = stack.lineup.create(CreateLineupEntry(channelId = ts.id, number = "100"))
                            assertEquals("TS Station", created.name)
                            assertEquals("100", created.number)
                            assertEquals(feeds.published.offeringsOf(ts.id).map { it.id }, created.offeringIds)
                            val manual = stack.lineup.create(
                                CreateLineupEntry(
                                    number = "1",
                                    name = "Manual",
                                    offerings = feeds.published.offeringsOf(
                                        feeds.published.channels("fixture").single().id,
                                    ).map {
                                        it.id
                                    },
                                    position = 0,
                                ),
                            )
                            assertEquals(
                                listOf("Manual", "TS Station"),
                                stack.catalog.catalog().channels.map {
                                    it.name
                                },
                            )
                            stack.lineup.reorder(listOf(created.id, manual.id))
                            assertEquals(
                                listOf("TS Station", "Manual"),
                                stack.catalog.catalog().channels.map {
                                    it.name
                                },
                            )
                            assertEquals(
                                "INVALID_REQUEST",
                                assertFailsWith<ApiFailure> {
                                    stack.lineup.reorder(listOf(created.id))
                                }.code,
                            )
                            stack.lineup.update(manual.id, UpdateLineupEntry(enabled = false, group = "Hidden"))
                            assertEquals(listOf("TS Station"), stack.catalog.catalog().channels.map { it.name })
                            val disabled = stack.catalog.guide(
                                GuideQuery(listOf(manual.id), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z"),
                            ).channels.single()
                            assertEquals(Wire.GuideStatus.REMOVED, disabled.status)
                            assertTrue(disabled.programs.isEmpty())
                            stack.lineup.update(
                                manual.id,
                                UpdateLineupEntry(enabled = true, position = 0, clearGroup = true),
                            )
                            assertEquals(
                                listOf("Manual", "TS Station"),
                                stack.catalog.catalog().channels.map {
                                    it.name
                                },
                            )
                            assertTrue(stack.catalog.catalog().channels.first().tags.isEmpty())
                            assertEquals(
                                "SOURCE_NOT_FOUND",
                                assertFailsWith<ApiFailure> {
                                    stack.lineup.update(manual.id, UpdateLineupEntry(offerings = listOf("missing")))
                                }.code,
                            )
                            assertEquals(
                                "INVALID_REQUEST",
                                assertFailsWith<ApiFailure> {
                                    stack.lineup.create(
                                        CreateLineupEntry(
                                            number = "2",
                                            name = "Bad",
                                            guide = GuideMapping("nope", "x"),
                                        ),
                                    )
                                }.code,
                            )
                            assertEquals(
                                "INVALID_REQUEST",
                                assertFailsWith<ApiFailure> {
                                    stack.lineup.create(CreateLineupEntry(number = "2", name = "Bad", logo = "ftp://x"))
                                }.code,
                            )
                            assertEquals(
                                "CHANNEL_NOT_FOUND",
                                assertFailsWith<ApiFailure> {
                                    stack.lineup.create(CreateLineupEntry(channelId = "unknown"))
                                }.code,
                            )
                            stack.lineup.delete(created.id)
                            assertEquals(
                                "LINEUP_ENTRY_NOT_FOUND",
                                assertFailsWith<ApiFailure> {
                                    stack.lineup.delete(created.id)
                                }.code,
                            )
                            val adopted = stack.lineup.adopt(AdoptProvider("fixture"))
                            assertEquals(
                                AdoptResult(0, 1),
                                adopted,
                                "a channel whose sources are already in the lineup is not adopted twice",
                            )
                            assertEquals(AdoptResult(1, 0), stack.lineup.adopt(AdoptProvider("ts")))
                            assertEquals(
                                "PROVIDER_NOT_FOUND",
                                assertFailsWith<ApiFailure> {
                                    stack.lineup.adopt(AdoptProvider("nope"))
                                }.code,
                            )
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `playback walks the entry's sources in order within each health rank and skips withdrawn ones`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-failover-")
            try {
                Fixture().use { fixture ->
                    HttpClient(CIO).use { http ->
                        openStore(dir).use { store ->
                            val clock = MutableClock()
                            val feeds =
                                FeedService(
                                    listOf(fixture.feed, fixture.altFeed),
                                    store,
                                    http,
                                    clock,
                                    UpstreamPolicy(allowLoopback = true),
                                )
                            feeds.refresh(fixture.feed)
                            feeds.refresh(fixture.altFeed)
                            val stack = lineupStack(feeds, store, clock, http, adopt = false)
                            val primary = feeds.published.offeringsOf(
                                feeds.published.channels("fixture").single().id,
                            ).single()
                            val alternate = feeds.published.offeringsOf(
                                feeds.published.channels("alt").single().id,
                            ).single()
                            val entry = stack.lineup.create(
                                CreateLineupEntry(
                                    channelId = feeds.published.channels("fixture").single().id,
                                    offerings = listOf(primary.id, alternate.id),
                                ),
                            )
                            val sessions = SessionManager(stack.catalog, store, clock)
                            suspend fun selected(key: String): String {
                                val opened = sessions.open(OpenPlayback(entry.id, "preview", key))
                                return sessions.acquire(
                                    opened.id,
                                    1,
                                    opened.descriptor.url.substringAfterLast('/'),
                                    Job(),
                                ).offering.id
                            }
                            assertEquals(primary.id, selected("both-unverified"))
                            store.recordObservation(alternate.id, Observation("ok", clock.now, true), clock.now)
                            assertEquals(
                                alternate.id,
                                selected("healthy-second"),
                                "a healthy second choice beats an unverified first",
                            )
                            store.recordObservation(primary.id, Observation("ok2", clock.now, true), clock.now)
                            assertEquals(primary.id, selected("both-healthy"))
                            fixture.withdrawn = true
                            feeds.refresh(fixture.feed.copy(allowEmpty = true))
                            assertEquals(alternate.id, selected("primary-withdrawn"))
                            assertEquals(
                                listOf(primary.id, alternate.id),
                                stack.lineup.entry(entry.id)!!.offeringIds,
                                "withdrawn sources stay in the entry",
                            )
                            store.setManuallyDisabled(alternate.id, true)
                            assertEquals(
                                "SOURCE_UNAVAILABLE",
                                assertFailsWith<ApiFailure> {
                                    selected("none-left")
                                }.code,
                            )
                            assertEquals(
                                "CHANNEL_NOT_FOUND",
                                assertFailsWith<ApiFailure> {
                                    selected0(sessions, feeds.published.channels("fixture").single().id)
                                }.code,
                                "feed channel ids are not playable",
                            )
                            sessions.close()
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    private suspend fun selected0(sessions: SessionManager, channelId: String) =
        sessions.open(OpenPlayback(channelId, "preview", "feed-id"))
}

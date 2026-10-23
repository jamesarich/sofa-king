package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.api.CreateLineupEntry
import dev.jamesrich.sofaking.backend.api.GuideChannelInfo
import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GuideServiceTest {
    @Test fun `guides import independently keep only mapped channels and survive restart`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-guides-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http ->
            val clock = MutableClock()
            var channelId = ""
            openStore(dir).use { store ->
                val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true)); feeds.refresh(fixture.feed)
                val stack = lineupStack(feeds, store, clock, http, guides = listOf(fixture.brokenGuide, fixture.gzipGuide))
                val statuses = stack.guides.statuses().associateBy { it.id }
                assertEquals("IMPORT_FAILED", statuses.getValue("broken").errorCode)
                assertNull(statuses.getValue("gz").errorCode)
                assertEquals(2, statuses.getValue("gz").channels, "the channel index lists every channel")
                assertEquals(1, statuses.getValue("gz").programmes, "only the mapped channel's programmes are retained")
                channelId = stack.catalog.catalog().channels.single().id
                val entry = stack.lineup.entry(channelId)!!
                assertEquals(GuideMapping("gz", "station"), entry.guide, "the implicit provider guide comes after explicit guides")
                assertEquals("available", stack.catalog.catalog().channels.single().guideStatus)
                assertEquals(listOf(GuideChannelInfo("gz", "station", "Station HD", "${fixture.base}/station.png")), stack.guides.searchChannels("gz", "HD", 10))
                assertEquals(listOf("gz"), stack.guides.guidesListing("other"))
            }
            openStore(dir).use { store ->
                val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true)); feeds.initialize()
                lateinit var lineup: LineupService
                val guides = GuideService(listOf(fixture.gzipGuide), store, http, clock, UpstreamPolicy(allowLoopback = true)) { lineup.wanted() }
                guides.initialize()
                lineup = LineupService(store, clock, feeds, guides); lineup.initialize()
                val catalog = CatalogService(feeds, guides, lineup, store, clock)
                assertEquals(1, guides.statuses().single().programmes)
                assertEquals(Instant.parse("2026-10-10T14:00:00Z"), guides.coverage().getValue("gz").getValue("station"))
                val window = catalog.guide(GuideQuery(listOf(channelId), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z")).channels.single()
                assertEquals("Morning", window.programmes.single().title)
            }
        } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `DOCTYPE guides import, plain and through a two-hop redirect to gzip`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-guides-doctype-")
        try { Fixture().use { fixture -> HttpClient(CIO) { followRedirects = false }.use { http -> openStore(dir).use { store ->
            val clock = MutableClock()
            val feeds = FeedService(emptyList(), store, http, clock, UpstreamPolicy(allowLoopback = true))
            val stack = lineupStack(feeds, store, clock, http, guides = listOf(fixture.redirectedDoctypeGuide, fixture.doctypeGuide))
            val statuses = stack.guides.statuses().associateBy { it.id }
            assertNull(statuses.getValue("mjh").errorCode)
            assertEquals(clock.now.toString(), statuses.getValue("mjh").lastSuccessAt)
            assertEquals(2, statuses.getValue("mjh").channels)
            assertNull(statuses.getValue("tvapp2").errorCode)
            assertEquals(2, statuses.getValue("tvapp2").channels)
            assertEquals("CW Forever", stack.guides.searchChannels("mjh", "forever", 10).single().displayName)
            assertEquals(0, fixture.dtdRequests.get(), "the xmltv.dtd named by the DOCTYPE is never requested")
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `a failed import logs the message without URLs`() {
        val cause = IllegalStateException("Guide fetch failed for https://guide.example/epg.xml.gz?token=secret123 [url=http://x.test/a?k=v]\n  after retry")
        assertEquals("Guide fetch failed for <url> [url=<url>] after retry", redactedMessage(cause))
        assertEquals("Guide exceeds size limit", redactedMessage(RuntimeException("", IllegalArgumentException("Guide exceeds size limit"))))
        assertEquals("", redactedMessage(RuntimeException()))
        assertEquals(300, redactedMessage(RuntimeException("y".repeat(1000))).length)
    }

    @Test fun `a new mapping triggers one coalesced refresh that retains the newly wanted channel`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-guides-trigger-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock()
            val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true)); feeds.refresh(fixture.feed)
            val stack = lineupStack(feeds, store, clock, http, guides = listOf(fixture.gzipGuide))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                stack.guides.start(scope)
                val other = stack.lineup.create(CreateLineupEntry(number = "9", name = "Other", guide = GuideMapping("gz", "other")))
                pollUntil(10_000, 50) { stack.guides.statuses().single().programmes >= 2 }
                val window = stack.catalog.guide(GuideQuery(listOf(other.id), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z")).channels.single()
                assertEquals("Elsewhere", window.programmes.single().title)
                assertEquals("available", stack.catalog.catalog().channels.single { it.id == other.id }.guideStatus)
                assertFalse(stack.catalog.catalog().channels.single { it.id == other.id }.playbackEligible, "an entry without sources is listed but not playable")
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `concurrent imports of different guides both stay published`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-guides-concurrent-")
        try { Fixture().use { fixture -> HttpClient(CIO) { followRedirects = false }.use { http -> openStore(dir).use { store ->
            val clock = MutableClock()
            val feeds = FeedService(emptyList(), store, http, clock, UpstreamPolicy(allowLoopback = true))
            val stack = lineupStack(feeds, store, clock, http, guides = listOf(fixture.gzipGuide, fixture.doctypeGuide))
            repeat(20) { round ->
                coroutineScope {
                    launch(Dispatchers.Default) { stack.guides.refresh(fixture.gzipGuide) }
                    launch(Dispatchers.Default) { stack.guides.refresh(fixture.doctypeGuide) }
                }
                assertEquals(setOf("gz", "tvapp2"), stack.guides.coverage().keys, "round $round lost a concurrent import")
                assertEquals(setOf("gz", "tvapp2"), stack.guides.statuses().filter { it.errorCode == null && it.lastSuccessAt != null }.map { it.id }.toSet(), "round $round status lost")
            }
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}

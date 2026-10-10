package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DispatcharrImportTest {
    @Test fun `source keys ignore query tokens and case of the authority`() {
        assertEquals("http://192.168.1.3:3000/api/ext/v1/p/a b", sourceKey("HTTP://192.168.1.3:3000/api/ext/v1/p/a%20b?token=abc"))
        assertEquals(sourceKey("http://h/x.m3u8?token=1"), sourceKey("http://h/x.m3u8?token=2"))
        assertNotEquals(sourceKey("http://h/x.m3u8"), sourceKey("http://h/y.m3u8"))
        assertNull(sourceKey("not a url"))
    }

    @Test fun `import matches streams by URL without tokens maps guides and upserts by uuid`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-import-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock()
            val feeds = FeedService(listOf(fixture.feed, fixture.altFeed, fixture.tsFeed), store, http, clock, UpstreamPolicy(allowLoopback = true))
            for (config in feeds.configs) feeds.refresh(config)
            val stack = lineupStack(feeds, store, clock, http, guides = listOf(fixture.gzipGuide), adopt = false)
            val importer = DispatcharrImporter(feeds, stack.guides, stack.lineup)
            val uuid = "7d2a4c1e-6a3f-4c8b-9d1e-aaaaaaaaaaaa"
            val export = DispatcharrExport(
                format = DispatcharrImporter.FORMAT, profile = "sofaking",
                epgSources = listOf(DispatcharrEpgSource(3, "Gzip Guide", "${fixture.base}/guide.xml.gz?key=renewable"), DispatcharrEpgSource(4, "Unknown", "http://elsewhere.test/epg.xml")),
                channels = listOf(
                    DispatcharrChannel(uuid, "5", "Station", "News", "https://img.test/station.png", "station", 3, "station", true,
                        listOf(DispatcharrStream("${fixture.base}/alt.m3u8?token=one", provider = "alt"), DispatcharrStream("${fixture.base}/master.m3u8?token=two", provider = "fixture"), DispatcharrStream("http://gone.test/dead.m3u8"))),
                    DispatcharrChannel(null, "6", "TS Station", null, null, "ts-station", 4, "ts-station", true, listOf(DispatcharrStream("${fixture.base}/live.ts"))),
                    DispatcharrChannel(null, "7", "Nothing Matches", null, null, null, null, null, true, listOf(DispatcharrStream("http://gone.test/x.ts"))),
                ),
            )
            val dry = importer.import(ImportLineup(export, dryRun = true))
            assertEquals(2, dry.created); assertEquals(0, dry.updated); assertEquals(1, dry.skipped)
            assertEquals(3, dry.matchedStreams); assertEquals(2, dry.unmatchedStreams)
            assertEquals(mapOf("3" to "gz", "4" to null), dry.guideSources)
            assertTrue(stack.lineup.entries().isEmpty(), "a dry run writes nothing")
            val station = dry.entries.first { it.number == "5" }
            assertEquals("created", station.action); assertEquals(2, station.matchedStreams); assertEquals(listOf("http://gone.test/dead.m3u8"), station.unmatchedStreams); assertEquals("mapped", station.guide)
            assertEquals("unmapped-source", dry.entries.first { it.number == "6" }.guide)
            assertEquals("skipped", dry.entries.first { it.number == "7" }.action)

            val report = importer.import(ImportLineup(export, guideMap = mapOf("4" to "gz")))
            assertEquals(2, report.created)
            val entry = stack.lineup.entry(uuid)!!
            assertEquals(listOf(feeds.offeringsOf(feeds.channels("alt").single().id).single().id, feeds.offeringsOf(feeds.channels("fixture").single().id).single().id), entry.offeringIds, "Dispatcharr stream order is the failover order")
            assertEquals(GuideMapping("gz", "station"), entry.guide)
            assertEquals(GuideMapping("gz", "ts-station"), stack.lineup.entries().first { it.number == "6" }.guide, "guideMap overrides URL matching")
            assertEquals(listOf("5", "6"), stack.catalog.catalog().channels.map { it.displayNumber })

            val again = importer.import(ImportLineup(export.copy(channels = listOf(export.channels.first().copy(number = "15"))), includeUnmatched = true))
            assertEquals(1, again.updated); assertEquals(0, again.created)
            assertEquals("15", stack.lineup.entry(uuid)!!.number)
            assertEquals(2, stack.lineup.entries().size)
            val withUnmatched = importer.import(ImportLineup(export.copy(channels = export.channels.drop(2)), includeUnmatched = true))
            assertEquals(1, withUnmatched.created)
            assertTrue(stack.lineup.entries().first { it.number == "7" }.offeringIds.isEmpty())
            assertEquals("INVALID_REQUEST", assertFailsWith<ApiFailure> { importer.import(ImportLineup(export.copy(format = "other"))) }.code)
            assertEquals("INVALID_REQUEST", assertFailsWith<ApiFailure> { importer.import(ImportLineup(export, guideMap = mapOf("3" to "missing"))) }.code)
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}

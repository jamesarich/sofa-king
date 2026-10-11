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
    @Test fun `source keys drop renewable query parameters and keep identity parameters sorted`() {
        assertEquals("http://192.168.1.3:3000/api/ext/v1/p/a b", sourceKey("HTTP://192.168.1.3:3000/api/ext/v1/p/a%20b?token=abc"))
        assertEquals(sourceKey("http://h/x.m3u8?token=1"), sourceKey("http://h/x.m3u8?token=2"))
        assertNotEquals(sourceKey("http://h/x.m3u8"), sourceKey("http://h/y.m3u8"))
        assertNull(sourceKey("not a url"))
        val dlhd = "http://restream.lan:3000/api/ext/v1/dlhd/https%3A%2F%2Fupstream.example%2Fwatch.php%3Fid%3D521"
        assertEquals("$dlhd?pl=dlhd".replace("%3A", ":").replace("%2F", "/").replace("%3F", "?").replace("%3D", "="), sourceKey("$dlhd?token=AAAA&pl=dlhd"))
        assertEquals(sourceKey("$dlhd?token=AAAA&pl=dlhd"), sourceKey("$dlhd?pl=dlhd&Token=BBBB&expires=1&sig=x&key=k"))
        assertNotEquals(sourceKey("$dlhd?pl=dlhd"), sourceKey("$dlhd?pl=other"))
        assertNotEquals(sourceKey("$dlhd?pl=dlhd"), sourceKey("${dlhd.dropLast(1)}2?pl=dlhd"), "the identity inside the encoded path tells sources apart")
        assertEquals(sourceKey("$dlhd?pl=dlhd"), sourceKey("$dlhd?pl=dlhd&token=1", setOf("token")))
        assertEquals(pathKey("$dlhd?pl=dlhd"), sourceKey("$dlhd?pl=dlhd&token=1", setOf("token", "pl")))
        assertEquals("http://h/x.m3u8?id=1", sourceKey("http://h/x.m3u8?id=1&token=t"))
        assertEquals("http://h/x.m3u8", pathKey("http://h/x.m3u8?id=1&token=t"))
        assertFailsWith<IllegalArgumentException> { validateRenewableQuery(listOf("a b")) }
    }

    @Test fun `import matches streams without a published identity by source key or by path`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-import-idless-")
        try { Fixture().use { fixture -> HttpClient(CIO).use { http -> openStore(dir).use { store ->
            val clock = MutableClock()
            val feeds = FeedService(listOf(fixture.feed), store, http, clock, UpstreamPolicy(allowLoopback = true))
            fixture.partial = true; feeds.refresh(fixture.feed)
            val stack = lineupStack(feeds, store, clock, http, guides = emptyList(), adopt = false)
            val importer = DispatcharrImporter(feeds, stack.guides, stack.lineup)
            val nameless = feeds.channels().single { it.name == "Nameless Id" }
            val export = DispatcharrExport(
                format = DispatcharrImporter.FORMAT, profile = "sofaking", epgSources = emptyList(),
                channels = listOf(
                    DispatcharrChannel(null, "9", "Nameless Id", null, null, null, null, null, true, listOf(DispatcharrStream("${fixture.base}/other.m3u8?token=rotated&expires=9"))),
                    DispatcharrChannel(null, "10", "Path Only", null, null, null, null, null, true, listOf(DispatcharrStream("${fixture.base}/other.m3u8?variant=old"))),
                ),
            )
            val report = importer.import(ImportLineup(export))
            assertEquals(2, report.created); assertEquals(2, report.matchedStreams); assertEquals(0, report.unmatchedStreams)
            assertEquals(feeds.offeringsOf(nameless.id).map { it.id }, stack.lineup.entries().single { it.number == "9" }.offeringIds)
            assertEquals(feeds.offeringsOf(nameless.id).map { it.id }, stack.lineup.entries().single { it.number == "10" }.offeringIds, "a lone path match stands in for a changed identity query")
            val missing = importer.import(ImportLineup(export.copy(channels = listOf(export.channels.first().copy(streams = listOf(DispatcharrStream("${fixture.base}/gone.m3u8?token=secret"))))), dryRun = true))
            assertEquals(listOf("${fixture.base}/gone.m3u8"), missing.entries.single().unmatchedStreams, "unmatched keys never carry tokens")
        } } } } finally { dir.toFile().deleteRecursively() }
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
                    DispatcharrChannel(null, "", "No Number", null, null, null, null, null, true, listOf(DispatcharrStream("${fixture.base}/live.ts"))),
                ),
            )
            val dry = importer.import(ImportLineup(export, dryRun = true))
            assertEquals(2, dry.created); assertEquals(0, dry.updated); assertEquals(2, dry.skipped)
            val unnumbered = dry.entries.single { it.name == "No Number" }
            assertEquals("skipped", unnumbered.action); assertEquals(1, unnumbered.matchedStreams); assertTrue(unnumbered.error!!.contains("Number"))
            assertEquals(4, dry.matchedStreams); assertEquals(2, dry.unmatchedStreams)
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
            assertEquals(1, withUnmatched.created); assertEquals(1, withUnmatched.skipped)
            assertTrue(stack.lineup.entries().first { it.number == "7" }.offeringIds.isEmpty())
            assertEquals("INVALID_REQUEST", assertFailsWith<ApiFailure> { importer.import(ImportLineup(export.copy(format = "other"))) }.code)
            assertEquals("INVALID_REQUEST", assertFailsWith<ApiFailure> { importer.import(ImportLineup(export, guideMap = mapOf("3" to "missing"))) }.code)
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}

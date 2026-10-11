package dev.jamesrich.sofaking.backend.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedParserTest {
    private fun playlist(url: String = "http://example.test/live.m3u8") =
        """
        #EXTM3U
        #EXTINF:-1 tvg-id="station" tvg-chno="5" group-title="News, Local",News, Local
        $url
        """.trimIndent()

    private fun entry(playlist: String) = "\n" + playlist.removePrefix("#EXTM3U")

    @Test fun `identity survives signed URL refresh and quoted commas`() {
        val (first, offerings) = FeedParser.playlist("provider", playlist())
        val (second, renewed) = FeedParser.playlist("provider", playlist("http://example.test/live.m3u8?token=new"))
        assertEquals(first, second)
        assertEquals("News, Local", first.single().name)
        assertEquals(offerings.single().id, renewed.single().id)
        assertEquals("hls", offerings.single().protocol)
    }

    @Test fun `source identity survives alternative reorder and skips renewable query duplicates`() {
        val first = playlist("http://one.test/live.m3u8") + entry(playlist("http://two.test/live.m3u8"))
        val second = playlist("http://two.test/live.m3u8") + entry(playlist("http://one.test/live.m3u8"))
        assertEquals(
            FeedParser.playlist("p", first).offerings.map { it.id }.toSet(),
            FeedParser.playlist("p", second).offerings.map { it.id }.toSet(),
        )
        val parsed = FeedParser.playlist("p", playlist("http://one.test/live.m3u8?token=1") + entry(playlist("http://one.test/live.m3u8?token=2")))
        assertEquals(1, parsed.offerings.size)
        assertEquals("http://one.test/live.m3u8?token=1", parsed.offerings.single().url)
        assertEquals(mapOf("duplicate-source" to 1), parsed.skipped)
        val distinct = FeedParser.playlist("p", playlist("http://one.test/live.m3u8?id=1") + entry(playlist("http://one.test/live.m3u8?id=2")))
        assertEquals(2, distinct.offerings.size, "a query parameter that is not renewable carries identity")
        assertEquals(1, distinct.channels.size)
        assertTrue(distinct.skipped.isEmpty())
    }

    private val distro = "http://restream.lan:3000/api/ext/v1/distro/https%3A%2F%2Fstream-us-east-1.example.com%2Fplaylist.m3u8%3Flivestream%3D1%26live%3D1%26channel%3D"
    private val dlhd = "http://restream.lan:3000/api/ext/v1/dlhd/https%3A%2F%2Fupstream.example%2Fwatch.php%3Fid%3D"

    private fun idless(name: String, url: String, group: String = "Sports") = "\n#EXTINF:-1 tvg-logo=\"http://restream.lan:3000/logo.png\" group-title=\"$group\",$name\n$url"

    @Test fun `entries without an identity are keyed by URL and distinct encoded upstreams never collide`() {
        val text = "#EXTM3U" + idless("Alpha", "${distro}alpha") + idless("Beta", "${distro}beta") + idless("Gamma", "${dlhd}521?token=AAAA&pl=dlhd")
        val parsed = FeedParser.playlist("masq", text)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), parsed.channels.map { it.name })
        assertEquals(3, parsed.channels.map { it.id }.distinct().size)
        assertEquals(3, parsed.offerings.map { it.id }.distinct().size)
        assertEquals(parsed.channels.map { it.id }, parsed.offerings.map { it.channelId })
        assertTrue(parsed.skipped.isEmpty())
        assertEquals(mapOf("url-identity" to 3), parsed.notes)
        assertEquals("url-identity=3", parsed.noteSummary)
        assertNull(parsed.channels.first().legacyGuideId)
        assertEquals(listOf("Sports"), parsed.channels.first().tags)
        val gamma = parsed.channels.last()
        assertEquals(8, gamma.displayNumber.length, "a URL-keyed channel without tvg-chno gets a short derived number")
        assertEquals(gamma.id.take(8), gamma.displayNumber)
    }

    @Test fun `URL identity is stable across token rotation reorder and renames`() {
        val first = FeedParser.playlist("masq", "#EXTM3U" + idless("Gamma", "${dlhd}521?token=AAAA&pl=dlhd") + idless("Alpha", "${distro}alpha"))
        val second = FeedParser.playlist("masq", "#EXTM3U" + idless("Alpha HD", "${distro}alpha") + idless("Gamma", "${dlhd}521?pl=dlhd&token=BBBB"))
        assertEquals(first.channels.map { it.id }.toSet(), second.channels.map { it.id }.toSet())
        assertEquals(first.offerings.map { it.id }.toSet(), second.offerings.map { it.id }.toSet())
        assertEquals("Alpha HD", second.channels.single { it.id == first.channels.last().id }.name, "the name is display only")
        assertEquals("${dlhd}521?pl=dlhd&token=BBBB", second.offerings.single { it.channelId == first.channels.first().id }.url, "the stored URL carries the current token")
        val other = FeedParser.playlist("masq", "#EXTM3U" + idless("Gamma", "${dlhd}522?token=AAAA&pl=dlhd"))
        assertNotEquals(first.channels.first().id, other.channels.single().id, "the identity inside the encoded path tells sources apart")
        assertNotEquals(first.channels.first().id, FeedParser.playlist("other", "#EXTM3U" + idless("Gamma", "${dlhd}521?token=AAAA&pl=dlhd")).channels.single().id)
        val renewablePl = FeedParser.playlist("masq", "#EXTM3U" + idless("Gamma", "${dlhd}521?token=AAAA&pl=dlhd"), renewable = setOf("token", "pl"))
        assertNotEquals(first.offerings.first().id, renewablePl.offerings.single().id, "a per-provider renewable list changes the key")
        assertEquals(renewablePl.offerings.single().id, FeedParser.playlist("masq", "#EXTM3U" + idless("Gamma", "${dlhd}521?token=CCCC&pl=other"), renewable = setOf("token", "pl")).offerings.single().id)
    }

    @Test fun `a reused identity with another name becomes an alternative source of the first entry`() {
        val parsed = FeedParser.playlist("p", playlist("http://one.test/live.m3u8") + entry(playlist("http://two.test/live.m3u8").replace("News, Local", "Other")))
        assertEquals(listOf("News, Local"), parsed.channels.map { it.name })
        assertEquals(2, parsed.offerings.size)
        assertEquals(setOf(parsed.channels.single().id), parsed.offerings.map { it.channelId }.toSet())
        assertEquals(mapOf("merged-key" to 1), parsed.notes)
        assertTrue(parsed.skipped.isEmpty())
    }

    @Test fun `explicit protocol supports opaque provider URLs`() {
        assertEquals("hls", FeedParser.playlist("p", playlist("http://example.test/resolve?id=1"), "hls").offerings.single().protocol)
        assertEquals(
            "hls",
            FeedParser
                .playlist("p", playlist("http://example.test/resolve?id=1").replace("tvg-id=", "stream-type=\"hls\" tvg-id="))
                .offerings
                .single()
                .protocol,
        )
    }

    @Test fun `published channel UUID and numeric legacy join are preserved`() {
        val id = "228e18ba-3f42-4c33-9095-22a3205dbe61"
        val (channels, _) = FeedParser.playlist("provider", playlist().replace("tvg-id=\"station\"", "channel-uuid=\"$id\" tvg-id=\"5\""))
        assertEquals(id, channels.single().id)
        assertEquals("5", channels.single().legacyGuideId)
    }

    @Test fun `malformed entries are skipped and counted while the well formed rest imports`() {
        val text =
            playlist() +
                entry(playlist("http://two.test/live.ts").replace("tvg-id=\"station\"", "")).replace("News, Local\nhttp://two", "\nhttp://two") +
                entry(playlist("file:///etc/passwd").replace("tvg-id=\"station\"", "tvg-id=\"file\"")) +
                entry(playlist("http://four.test/live.ts").replace("tvg-id=\"station\"", "tvg-id=\"four\" stream-type=\"rtmp\"")) +
                entry(playlist("http://five.test/live.ts").replace("tvg-id=\"station\"", "channel-uuid=\"not-a-uuid\"")) +
                "\nhttp://six.test/orphan.ts\n"
        val parsed = FeedParser.playlist("p", text)
        assertEquals(listOf("News, Local"), parsed.channels.map { it.name })
        assertEquals(1, parsed.offerings.size)
        assertEquals(
            mapOf("bad-protocol" to 1, "invalid-url" to 1, "invalid-uuid" to 1, "missing-name" to 1, "no-extinf" to 1),
            parsed.skipped,
        )
        assertEquals(5, parsed.skippedCount)
        assertEquals("bad-protocol=1, invalid-url=1, invalid-uuid=1, missing-name=1, no-extinf=1", parsed.skipSummary)
        assertTrue(parsed.notes.isEmpty())
        assertNull(FeedParser.playlist("p", playlist()).skipSummary)
        assertNull(FeedParser.playlist("p", playlist()).noteSummary)
    }

    @Test fun `a playlist that is not extended M3U still fails the import`() {
        assertFailsWith<IllegalArgumentException> { FeedParser.playlist("p", "http://example.test/live.m3u8\n") }
    }

    @Test fun `XMLTV preserves UTC offset and rejects malformed records`() {
        val parsed =
            FeedParser.guide(
                """<tv>
          <programme channel="station" start="20261010080000 -0500" stop="20261010090000 -0500"><title>Morning</title><desc>News &amp; weather</desc></programme>
          <programme channel="station" start="20261010090000" stop="bad"><title>Invalid</title></programme>
          <programme channel="unwanted" start="20261010080000 -0500" stop="20261010090000 -0500"><title>Dropped</title></programme>
        </tv>""",
            ) { it == "station" }
        assertEquals(1, parsed.programmes.size)
        assertEquals("2026-10-10T13:00:00Z", parsed.programmes.single().startsAt.toString())
        assertEquals("News & weather", parsed.programmes.single().description)
        assertEquals(listOf("station", "unwanted"), parsed.channels.map { it.id }.sorted(), "programme channel ids are indexed even without a channel block")
    }

    @Test fun `gzip XMLTV is recognized by its magic bytes and the channel block is indexed`() {
        val xml = """<tv><channel id="a"><display-name>Alpha</display-name><icon src="https://img.test/a.png"/></channel>
            <programme channel="a" start="20261010080000 +0000" stop="20261010090000 +0000"><title>One</title></programme>
            <programme channel="a" start="20261010080000 +0000" stop="20261010090000 +0000"><title>One</title></programme></tv>"""
        val bytes = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(xml.toByteArray()) } }.toByteArray()
        val parsed = FeedParser.guide(java.io.ByteArrayInputStream(bytes)) { true }
        assertEquals(GuideChannel("a", "Alpha", "https://img.test/a.png"), parsed.channels.single())
        assertEquals(1, parsed.programmes.size, "exact duplicates collapse")
        assertEquals("One", parsed.programmes.single().title)
    }

    @Test fun `XMLTV with a DOCTYPE declaration parses as plain or gzip`() {
        val tvapp2 = FeedParser.guide(Fixture.resource("guides/tvapp2-doctype.xml").inputStream()) { true }
        assertEquals(listOf("111871", "10021"), tvapp2.channels.map { it.id })
        assertEquals(listOf("ACC", "AMC"), tvapp2.channels.map { it.displayName })
        assertEquals(2, tvapp2.programmes.size)
        assertEquals("Biztalk Special Coverage on AI EP3", tvapp2.programmes.first { it.channelId == "10021" }.title)

        val gz = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(Fixture.resource("guides/mjh-doctype.xml")) } }.toByteArray()
        val mjh = FeedParser.guide(java.io.ByteArrayInputStream(gz)) { it == "USBA370000104" }
        assertEquals(2, mjh.channels.size)
        assertEquals("CW Forever", mjh.channels.first().displayName)
        assertEquals("48 Hours", mjh.programmes.single().title, "the mapped channel's programme survives a rating block")
    }

    @Test fun `XMLTV never fetches the DTD or resolves external entities`() {
        Fixture().use { fixture ->
            val external = FeedParser.guide("""<!DOCTYPE tv SYSTEM "${fixture.base}/xmltv.dtd"><tv><channel id="a"><display-name>A</display-name></channel></tv>""")
            assertEquals("A", external.channels.single().displayName, "an external DTD is skipped, not fetched")

            val parameter = FeedParser.guide("""<!DOCTYPE tv [<!ENTITY % ext SYSTEM "${fixture.base}/xmltv.dtd"> %ext;]><tv><channel id="b"/></tv>""")
            assertEquals("b", parameter.channels.single().id, "a parameter entity in the internal subset is ignored")

            val secret = java.nio.file.Files.createTempFile("sofa-xxe-", ".txt").also { it.toFile().writeText("FILE-ENTITY-MARKER"); it.toFile().deleteOnExit() }
            for (target in listOf("${fixture.base}/entity.txt", secret.toUri().toString())) {
                val failure = assertFails {
                    FeedParser.guide("""<!DOCTYPE tv [<!ENTITY xxe SYSTEM "$target">]><tv><programme channel="station" start="20261010080000 +0000" stop="20261010090000 +0000"><title>&xxe;</title></programme></tv>""")
                }
                assertTrue(failure.message.orEmpty().contains("not declared"), "the entity is undeclared, never resolved: ${failure.message}")
                assertFalse(failure.message.orEmpty().contains("MARKER"))
            }
            assertEquals(0, fixture.dtdRequests.get(), "no request left the parser for a DTD or an entity")
        }
        assertFails { FeedParser.guide("<html/>") }
    }

    @Test fun `a guide of more than 100 MB streams without being held in memory`() {
        val head = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="synthetic"><channel id="kept"><display-name>Kept</display-name></channel><channel id="bulk"><display-name>Bulk</display-name></channel>
<programme channel="kept" start="20261010080000 +0000" stop="20261010090000 +0000"><title>Kept programme</title></programme>
"""
        val programme = """<programme channel="bulk" start="20261010080000 +0000" stop="20261010090000 +0000"><title>Bulk programme title</title><desc>${"x".repeat(400)}</desc></programme>
"""
        val repeats = (110L shl 20) / programme.length + 1
        val input = SyntheticGuideStream(head.toByteArray(), programme.toByteArray(), repeats, "</tv>\n".toByteArray())
        val parsed = FeedParser.guide(input) { it == "kept" }
        assertTrue(input.produced > 110L shl 20, "the stream exceeded 110 MiB: ${input.produced}")
        assertEquals(listOf("kept", "bulk"), parsed.channels.map { it.id })
        assertEquals("Kept programme", parsed.programmes.single().title)
    }

    /** Emits head, then [programme] [repeats] times, then tail, without materializing the document. */
    private class SyntheticGuideStream(
        private val head: ByteArray,
        private val programme: ByteArray,
        private val repeats: Long,
        private val tail: ByteArray,
    ) : java.io.InputStream() {
        var produced = 0L
        private var phase = 0
        private var remaining = repeats
        private var offset = 0

        private fun current(): ByteArray? = when (phase) { 0 -> head; 1 -> programme; 2 -> tail; else -> null }

        private fun advance() {
            offset = 0
            when (phase) {
                0 -> phase = 1
                1 -> if (--remaining <= 0) phase = 2
                2 -> phase = 3
            }
        }

        override fun read(): Int {
            val chunk = current() ?: return -1
            val byte = chunk[offset++].toInt() and 0xff
            produced++
            if (offset == chunk.size) advance()
            return byte
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val chunk = current() ?: return -1
            val count = minOf(len, chunk.size - offset)
            System.arraycopy(chunk, offset, b, off, count)
            offset += count
            produced += count
            if (offset == chunk.size) advance()
            return count
        }
    }

    @Test fun `invalid calendar dates are discarded instead of normalized`() {
        val invalid = """<tv>
            <programme channel="station" start="20260230080000 +0000" stop="20260230090000 +0000">
                <title>Invalid</title>
            </programme>
        </tv>"""
        assertTrue(FeedParser.guide(invalid).programmes.isEmpty())
    }
}

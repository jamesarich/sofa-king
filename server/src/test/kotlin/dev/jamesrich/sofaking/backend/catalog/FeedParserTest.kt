package dev.jamesrich.sofaking.backend.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
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

    @Test fun `source identity survives alternative reorder and skips query only duplicates`() {
        val first = playlist("http://one.test/live.m3u8") + entry(playlist("http://two.test/live.m3u8"))
        val second = playlist("http://two.test/live.m3u8") + entry(playlist("http://one.test/live.m3u8"))
        assertEquals(
            FeedParser.playlist("p", first).offerings.map { it.id }.toSet(),
            FeedParser.playlist("p", second).offerings.map { it.id }.toSet(),
        )
        val parsed = FeedParser.playlist("p", playlist("http://one.test/live.m3u8?id=1") + entry(playlist("http://one.test/live.m3u8?id=2")))
        assertEquals(1, parsed.offerings.size)
        assertEquals("http://one.test/live.m3u8?id=1", parsed.offerings.single().url)
        assertEquals(mapOf("duplicate-source" to 1), parsed.skipped)
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
                entry(playlist("http://two.test/live.ts").replace("tvg-id=\"station\"", "")) +
                entry(playlist("file:///etc/passwd").replace("tvg-id=\"station\"", "tvg-id=\"file\"")) +
                entry(playlist("http://three.test/live.ts").replace("News, Local", "Other")) +
                entry(playlist("http://four.test/live.ts").replace("tvg-id=\"station\"", "tvg-id=\"four\" stream-type=\"rtmp\"")) +
                entry(playlist("http://five.test/live.ts").replace("tvg-id=\"station\"", "channel-uuid=\"not-a-uuid\"")) +
                "\nhttp://six.test/orphan.ts\n"
        val parsed = FeedParser.playlist("p", text)
        assertEquals(listOf("News, Local"), parsed.channels.map { it.name })
        assertEquals(1, parsed.offerings.size)
        assertEquals(
            mapOf("bad-protocol" to 1, "duplicate-key" to 1, "invalid-url" to 1, "invalid-uuid" to 1, "missing-id" to 1, "no-extinf" to 1),
            parsed.skipped,
        )
        assertEquals(6, parsed.skippedCount)
        assertEquals("bad-protocol=1, duplicate-key=1, invalid-url=1, invalid-uuid=1, missing-id=1, no-extinf=1", parsed.skipSummary)
        assertNull(FeedParser.playlist("p", playlist()).skipSummary)
    }

    @Test fun `a playlist that is not extended M3U still fails the import`() {
        assertFailsWith<IllegalArgumentException> { FeedParser.playlist("p", "http://example.test/live.m3u8\n") }
    }

    @Test fun `XMLTV preserves UTC offset and rejects malformed records`() {
        val (channels, _) = FeedParser.playlist("p", playlist())
        val programmes =
            FeedParser.guide(
                """<tv>
          <programme channel="station" start="20261010080000 -0500" stop="20261010090000 -0500"><title>Morning</title><desc>News &amp; weather</desc></programme>
          <programme channel="station" start="20261010090000" stop="bad"><title>Invalid</title></programme>
        </tv>""",
                channels,
            )
        assertEquals(1, programmes.size)
        assertEquals("2026-10-10T13:00:00Z", programmes.single().startsAt)
        assertEquals("News & weather", programmes.single().description)
    }

    @Test fun `XMLTV cannot resolve external entities`() {
        assertFails {
            FeedParser.guide(
                """<!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><tv><programme channel="station"><title>&xxe;</title></programme></tv>""",
                FeedParser.playlist("p", playlist()).channels,
            )
        }
        assertFails { FeedParser.guide("<html/>", emptyList()) }
    }

    @Test fun `invalid calendar dates are discarded instead of normalized`() {
        val channels = FeedParser.playlist("p", playlist()).channels
        val invalid = """<tv>
            <programme channel="station" start="20260230080000 +0000" stop="20260230090000 +0000">
                <title>Invalid</title>
            </programme>
        </tv>"""
        assertTrue(FeedParser.guide(invalid, channels).isEmpty())
    }
}

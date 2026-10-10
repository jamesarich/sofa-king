package dev.jamesrich.sofaking.backend.catalog

import kotlin.test.*

class FeedParserTest {
    private fun playlist(url: String = "http://example.test/live.m3u8") = """
        #EXTM3U
        #EXTINF:-1 tvg-id="station" tvg-chno="5" group-title="News, Local",News, Local
        $url
    """.trimIndent()
    @Test fun `identity survives signed URL refresh and quoted commas`() {
        val (first, offerings) = FeedParser.playlist("provider", playlist())
        val (second, renewed) = FeedParser.playlist("provider", playlist("http://example.test/live.m3u8?token=new"))
        assertEquals(first, second)
        assertEquals("News, Local", first.single().name)
        assertEquals(offerings.single().id, renewed.single().id)
        assertEquals("hls", offerings.single().protocol)
    }
    @Test fun `source identity survives alternative reorder and rejects ambiguous query only alternatives`() {
        val first = playlist("http://one.test/live.m3u8") + "\n" + playlist("http://two.test/live.m3u8").removePrefix("#EXTM3U")
        val second = playlist("http://two.test/live.m3u8") + "\n" + playlist("http://one.test/live.m3u8").removePrefix("#EXTM3U")
        assertEquals(FeedParser.playlist("p", first).second.map { it.id }.toSet(), FeedParser.playlist("p", second).second.map { it.id }.toSet())
        assertFailsWith<IllegalArgumentException> { FeedParser.playlist("p", playlist("http://one.test/live.m3u8?id=1") + "\n" + playlist("http://one.test/live.m3u8?id=2").removePrefix("#EXTM3U")) }
    }
    @Test fun `explicit protocol supports opaque provider URLs`() {
        assertEquals("hls", FeedParser.playlist("p", playlist("http://example.test/resolve?id=1"), "hls").second.single().protocol)
        assertEquals("hls", FeedParser.playlist("p", playlist("http://example.test/resolve?id=1").replace("tvg-id=", "stream-type=\"hls\" tvg-id=")).second.single().protocol)
    }
    @Test fun `published channel UUID and numeric legacy join are preserved`() {
        val id = "228e18ba-3f42-4c33-9095-22a3205dbe61"
        val (channels, _) = FeedParser.playlist("provider", playlist().replace("tvg-id=\"station\"", "channel-uuid=\"$id\" tvg-id=\"5\""))
        assertEquals(id, channels.single().id)
        assertEquals("5", channels.single().legacyGuideId)
    }
    @Test fun `rejects missing identity unsafe stream schemes and ambiguous station keys`() {
        assertFailsWith<IllegalArgumentException> { FeedParser.playlist("p", playlist().replace("tvg-id=\"station\"", "")) }
        assertFailsWith<IllegalArgumentException> { FeedParser.playlist("p", playlist("file:///etc/passwd")) }
        assertFailsWith<IllegalArgumentException> { FeedParser.playlist("p", playlist() + "\n" + playlist().replace("News, Local", "Other").removePrefix("#EXTM3U")) }
    }
    @Test fun `XMLTV preserves UTC offset and rejects malformed records`() {
        val (channels, _) = FeedParser.playlist("p", playlist())
        val programmes = FeedParser.guide("""<tv>
          <programme channel="station" start="20261010080000 -0500" stop="20261010090000 -0500"><title>Morning</title><desc>News &amp; weather</desc></programme>
          <programme channel="station" start="20261010090000" stop="bad"><title>Invalid</title></programme>
        </tv>""", channels)
        assertEquals(1, programmes.size)
        assertEquals("2026-10-10T13:00:00Z", programmes.single().startsAt)
        assertEquals("News & weather", programmes.single().description)
    }
    @Test fun `XMLTV cannot resolve external entities`() {
        assertFails { FeedParser.guide("""<!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><tv><programme channel="station"><title>&xxe;</title></programme></tv>""", FeedParser.playlist("p", playlist()).first) }
        assertFails { FeedParser.guide("<html/>" , emptyList()) }
    }
}

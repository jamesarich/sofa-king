package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.AdDateRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class LivePlaylistTest {
    private val start = "2026-11-04T20:00:00.000Z"
    private val startMs = Instant.parse(start).toEpochMilliseconds()

    private val media = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:120
        #EXT-X-PROGRAM-DATE-TIME:$start
        #EXTINF:6.000,
        a.ts
        #EXT-X-DATERANGE:ID="other",CLASS="com.example.other",START-DATE="$start",DURATION=10
        ${AdDateRange.tag("sofa-ad-1", "2026-11-04T20:00:06.000Z", durationMs = 30_000)}
        #EXT-X-SOFA-AD:ID=1
        #EXTINF:6.000,
        b.ts
        #EXTINF:4.500,
        c.ts
    """.trimIndent()

    @Test
    fun `a media playlist gives its clock and numbering and durations and one ad mark`() {
        val playlist = parseLivePlaylist(media)
        assertEquals(startMs, playlist.programDateTimeMs)
        assertEquals(120L, playlist.mediaSequence)
        assertEquals(6_000L, playlist.targetDurationMs)
        assertEquals(listOf(6_000L, 6_000L, 4_500L), playlist.segmentDurationsMs)
        assertEquals(listOf(BreakMark("sofa-ad-1", startMs + 6_000, startMs + 36_000, planned = false)), playlist.marks)
        assertNull(playlist.variantUrl)
    }

    @Test
    fun `the live edge is the clock plus every duration`() {
        assertEquals(startMs + 16_500L, parseLivePlaylist(media).liveEdgeMs)
    }

    @Test
    fun `a multivariant root resolves its first variant against the playlist url`() {
        val root = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            hls/live.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000
            hls/low.m3u8
        """.trimIndent()
        val playlist = parseLivePlaylist(root, "http://server:8080/media/abc/master.m3u8?x=1")
        assertEquals("http://server:8080/media/abc/hls/live.m3u8", playlist.variantUrl)
        assertEquals(emptyList(), playlist.segmentDurationsMs)
    }

    @Test
    fun `a root-relative variant keeps the playlist's host`() {
        val root = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/media/abc/live.m3u8"
        assertEquals(
            "http://server:8080/media/abc/live.m3u8",
            parseLivePlaylist(root, "http://server:8080/media/x/master.m3u8").variantUrl,
        )
    }

    @Test
    fun `without a program date time there is no clock and no live edge`() {
        val text = media.lines().filterNot { it.startsWith("#EXT-X-PROGRAM-DATE-TIME") }.joinToString("\n")
        val playlist = parseLivePlaylist(text)
        assertNull(playlist.programDateTimeMs)
        assertNull(playlist.liveEdgeMs)
        assertEquals(3, playlist.segmentDurationsMs.size)
    }
}

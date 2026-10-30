package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.contract.RecordingBreak
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistMarkersTest {
    private val source = URI("http://origin.test/live/index.m3u8")

    private fun segment(n: Int) = "http://origin.test/live/s$n.ts"

    /** A media playlist of two-second segments [first] to [last], with [tags] written before the segment they name. */
    private fun playlist(first: Int, last: Int, tags: Map<Int, String> = emptyMap()) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        for (n in first..last) {
            tags[n]?.let { append(it).append('\n') }
            append("#EXTINF:2.000,\n").append(segment(n)).append('\n')
        }
    }

    private fun PlaylistMarkers.fetch(range: IntRange) = range.forEach { segment(segment(it)) }

    @Test fun `a cue out and in place the break at the media's offsets`() {
        val markers = PlaylistMarkers()
        markers.begin()
        markers.playlist(playlist(0, 7, mapOf(3 to "#EXT-X-CUE-OUT:6", 6 to "#EXT-X-CUE-IN")), source)
        markers.fetch(0..7)
        assertEquals(listOf(RecordingBreak(6_000, 12_000)), markers.breaks())
    }

    @Test fun `a cue that left the window carries on through the refreshes until its cue in`() {
        val markers = PlaylistMarkers()
        markers.begin()
        markers.playlist(playlist(0, 4, mapOf(3 to "#EXT-X-CUE-OUT:10")), source)
        markers.fetch(0..4)
        // The window now starts inside the break and holds no cue out, then the cue in.
        markers.playlist(playlist(2, 6), source)
        markers.fetch(5..6)
        markers.playlist(playlist(4, 9, mapOf(7 to "#EXT-X-CUE-IN")), source)
        markers.fetch(7..9)
        assertEquals(listOf(RecordingBreak(6_000, 14_000)), markers.breaks())
    }

    @Test fun `a segment fetched twice counts once, and a rendition's segments are not counted`() {
        val markers = PlaylistMarkers()
        markers.begin()
        markers.playlist(playlist(0, 3, mapOf(1 to "#EXT-X-CUE-OUT:2", 2 to "#EXT-X-CUE-IN")), source)
        val audio = playlist(0, 3).replace("origin.test/live", "origin.test/audio")
        markers.playlist(audio, URI("http://origin.test/audio/a.m3u8"))
        markers.fetch(0..3)
        markers.fetch(0..3)
        markers.segment("http://origin.test/audio/s0.ts")
        assertEquals(listOf(RecordingBreak(2_000, 4_000)), markers.breaks())
    }

    @Test fun `a break still open when media ends ends there, and a new read carries the offsets on`() {
        val markers = PlaylistMarkers()
        markers.begin()
        markers.playlist(playlist(0, 3, mapOf(2 to "#EXT-X-CUE-OUT:60")), source)
        markers.fetch(0..3)
        assertEquals(listOf(RecordingBreak(4_000, 8_000)), markers.breaks())
        markers.begin()
        markers.playlist(playlist(10, 13, mapOf(12 to "#EXT-X-CUE-OUT:4")), source)
        markers.fetch(10..13)
        assertEquals(listOf(RecordingBreak(4_000, 8_000), RecordingBreak(12_000, 16_000)), markers.breaks())
    }

    @Test fun `breaks adjoining across reads are one, and a frozen recorder counts no more media`() {
        val markers = PlaylistMarkers()
        markers.begin()
        markers.playlist(playlist(0, 1, mapOf(1 to "#EXT-X-CUE-OUT:4")), source)
        markers.fetch(0..1)
        markers.begin()
        markers.playlist(playlist(5, 6, mapOf(5 to "#EXT-X-CUE-OUT:4")), source)
        markers.fetch(5..6)
        markers.freeze()
        markers.playlist(playlist(8, 9), source)
        markers.fetch(8..9)
        assertEquals(listOf(RecordingBreak(2_000, 8_000)), markers.breaks())
    }

    @Test fun `a playlist with no markers gives no breaks`() {
        val markers = PlaylistMarkers()
        markers.begin()
        markers.playlist(playlist(0, 5), source)
        markers.fetch(0..5)
        assertEquals(emptyList(), markers.breaks())
    }
}

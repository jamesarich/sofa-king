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

    private fun PlaylistMarkers.fetch(range: IntRange, bytes: Long = 0L) = range.forEach { segment(segment(it), bytes) }

    @Test fun `segments fetched but never written are taken back, and the next read's offsets follow the file`() {
        val markers = PlaylistMarkers()
        markers.begin()
        markers.playlist(playlist(0, 5, mapOf(4 to "#EXT-X-CUE-OUT:4")), source)
        markers.fetch(0..5, bytes = 1000)
        // Six segments of a thousand bytes were fetched, and the file holds the first four and a half.
        markers.end(written = 4500)
        markers.begin()
        markers.playlist(playlist(10, 11), source)
        markers.fetch(10..11, bytes = 1000)
        assertEquals(listOf(RecordingBreak(8_000, 10_000)), markers.breaks())
    }

    @Test fun `a break that began only in segments never written is not kept`() {
        val markers = PlaylistMarkers()
        markers.begin()
        markers.playlist(playlist(0, 5, mapOf(4 to "#EXT-X-CUE-OUT:4")), source)
        markers.fetch(0..5, bytes = 1000)
        markers.end(written = 3500)
        assertEquals(emptyList(), markers.breaks())
    }

    @Test fun `the clock follows the variant whose segments are read, not the probe segment of another`() {
        val markers = PlaylistMarkers()
        markers.begin()
        val low = URI("http://origin.test/low/index.m3u8")
        val high = URI("http://origin.test/high/index.m3u8")
        fun media(dir: String, tags: Map<Int, String> = emptyMap()) = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n")
            for (n in 0..9) {
                tags[n]?.let { append(it).append('\n') }
                append("#EXTINF:2.000,\nhttp://origin.test/$dir/s$n.ts\n")
            }
        }
        // FFmpeg opens both playlists and probes one segment of the low variant, then reads the high one.
        val cues = mapOf(3 to "#EXT-X-CUE-OUT:4", 5 to "#EXT-X-CUE-IN")
        markers.playlist(media("low", cues), low)
        markers.playlist(media("high", cues), high)
        markers.segment("http://origin.test/low/s0.ts", 281_000)
        for (n in 0..9) markers.segment("http://origin.test/high/s$n.ts", 1_600_000)
        assertEquals(listOf(RecordingBreak(6_000, 10_000)), markers.breaks())
        // The bytes written are the high variant's, and the probe's share of what was fetched is small.
        val end = markers.end(written = 16_000_000)
        assertEquals(0, end.takenBack)
        assertEquals(listOf(RecordingBreak(6_000, 10_000)), markers.breaks())
        markers.begin()
        markers.playlist(playlist(20, 21), source)
        markers.fetch(20..21)
        assertEquals(listOf(RecordingBreak(6_000, 10_000)), markers.breaks())
    }

    @Test fun `a read written in full loses nothing, and one with unknown sizes is left as counted`() {
        val full = PlaylistMarkers()
        full.begin()
        full.playlist(playlist(0, 5, mapOf(4 to "#EXT-X-CUE-OUT:4")), source)
        full.fetch(0..5, bytes = 1000)
        full.end(written = 6000)
        assertEquals(listOf(RecordingBreak(8_000, 12_000)), full.breaks())

        val unknown = PlaylistMarkers()
        unknown.begin()
        unknown.playlist(playlist(0, 5, mapOf(4 to "#EXT-X-CUE-OUT:4")), source)
        unknown.fetch(0..5)
        unknown.end(written = 100)
        assertEquals(listOf(RecordingBreak(8_000, 12_000)), unknown.breaks())
    }

    @Test fun `a cue out and in place the break at the media's offsets`() {
        val markers = PlaylistMarkers()
        markers.begin()
        markers.playlist(playlist(0, 7, mapOf(3 to "#EXT-X-CUE-OUT:6", 6 to "#EXT-X-CUE-IN")), source)
        markers.fetch(0..7)
        assertEquals(listOf(RecordingBreak(6_000, 12_000)), markers.breaks())
    }

    @Test fun `a read frozen before its body proved to be a playlist counts its markers once begun`() {
        val markers = PlaylistMarkers()
        markers.freeze()
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

package dev.jamesrich.sofaking.backend.multiview

import kotlin.test.Test
import kotlin.test.assertEquals

class MultiviewPlaylistTest {
    @Test fun `the first segment's discontinuities go and the playlist tags come first`() {
        // FFmpeg 6's first playlist with append_list, as AVFoundation failed on it with CoreMedia -12312.
        val text = "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
            "#EXT-X-DISCONTINUITY\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-DISCONTINUITY\n#EXTINF:4.000000,\nseg0.ts\n" +
            "#EXTINF:4.000000,\nseg1.ts\n"
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-INDEPENDENT-SEGMENTS\n" +
                "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4.000000,\nseg0.ts\n#EXTINF:4.000000,\nseg1.ts\n",
            MultiviewPlaylist().serve(text),
        )
    }

    @Test fun `a restart's discontinuity stays and is counted once it leaves the window`() {
        val playlist = MultiviewPlaylist()
        val restarted = "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:4\n" +
            "#EXT-X-INDEPENDENT-SEGMENTS\n#EXTINF:4.000000,\nseg4.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4.000000,\n" +
            "seg5.ts\n"
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-INDEPENDENT-SEGMENTS\n" +
                "#EXT-X-MEDIA-SEQUENCE:4\n#EXTINF:4.000000,\nseg4.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4.000000,\n" +
                "seg5.ts\n",
            playlist.serve(restarted),
        )
        val leading = "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:5\n" +
            "#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-DISCONTINUITY\n#EXTINF:4.000000,\nseg5.ts\n" +
            "#EXTINF:4.000000,\nseg6.ts\n"
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-INDEPENDENT-SEGMENTS\n" +
                "#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-DISCONTINUITY\n#EXTINF:4.000000,\nseg5.ts\n" +
                "#EXTINF:4.000000,\nseg6.ts\n",
            playlist.serve(leading),
        )
        val past = "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:6\n" +
            "#EXT-X-INDEPENDENT-SEGMENTS\n#EXTINF:4.000000,\nseg6.ts\n"
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-INDEPENDENT-SEGMENTS\n" +
                "#EXT-X-MEDIA-SEQUENCE:6\n#EXT-X-DISCONTINUITY-SEQUENCE:1\n#EXTINF:4.000000,\nseg6.ts\n",
            playlist.serve(past),
        )
    }
}

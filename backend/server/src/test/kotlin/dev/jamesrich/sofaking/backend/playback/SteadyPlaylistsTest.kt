package dev.jamesrich.sofaking.backend.playback

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class SteadyPlaylistsTest {
    private val source = URI("https://siloh-ns1.plutotv.net/v2/stitch/hls/channel/5f1ac1f1/1600/playlist.m3u8")
    private val live = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:60\n#EXTINF:5,\nhls_1600-00060.ts\n"

    /** What Pluto answered at 02:27:02 on 2026-10-22, between two ordinary refreshes. */
    private val ended = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:61\n#EXT-X-ENDLIST\n"

    @Test fun `an empty ended answer is replaced by the last playlist that held segments`() {
        val playlists = SteadyPlaylists()
        assertEquals(live, playlists.steady(live, source))
        assertEquals(live, playlists.steady(ended, source))
    }

    @Test fun `an empty ended answer with nothing before it from that URL goes out as it is`() {
        val playlists = SteadyPlaylists()
        playlists.steady(live, source)
        assertEquals(
            ended,
            playlists.steady(
                ended,
                URI("https://siloh-ns1.plutotv.net/v2/stitch/hls/channel/5f1ac1f1/300/playlist.m3u8"),
            ),
        )
    }

    @Test fun `a finished playlist with segments and a master playlist pass through untouched`() {
        val playlists = SteadyPlaylists()
        val finished = live + "#EXT-X-ENDLIST\n"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1600000\n1600/playlist.m3u8\n"
        assertEquals(finished, playlists.steady(finished, source))
        assertEquals(master, playlists.steady(master, source))
        assertEquals(ended, playlists.steady(ended, source))
    }

    /** A live window from [sequence] holding [names], each a five-second segment. */
    private fun window(sequence: Long, vararg names: String) =
        "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:$sequence\n" +
            names.joinToString("") { "#EXTINF:5,\n$it.ts\n" }

    @Test fun `a window numbered lower with new segments is numbered on behind a discontinuity`() {
        val playlists = SteadyPlaylists()
        playlists.steady(window(60, "a60", "a61", "a62"), source)
        playlists.steady(window(61, "a61", "a62", "a63"), source)

        val restarted = playlists.steady(window(0, "b0", "b1", "b2"), source)

        assertEquals(
            "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:64\n#EXTINF:5," +
                "\n#EXT-X-DISCONTINUITY\nb0.ts\n#EXTINF:5,\nb1.ts\n#EXTINF:5,\nb2.ts\n",
            restarted,
        )
    }

    @Test fun `after a restart the numbering stays on and the discontinuity is counted once it leaves the window`() {
        val playlists = SteadyPlaylists()
        playlists.steady(window(61, "a61", "a62", "a63"), source)
        playlists.steady(window(0, "b0", "b1", "b2"), source)

        val next = playlists.steady(window(1, "b1", "b2", "b3"), source)

        assertEquals(
            "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:65\n#EXT-X-DISCONTINUITY-SEQUENCE:1\n#EXTINF:5," +
                "\nb1.ts\n#EXTINF:5,\nb2.ts\n#EXTINF:5,\nb3.ts\n",
            next,
        )
    }

    @Test fun `a stale copy numbered lower is replaced by the last window sent`() {
        val playlists = SteadyPlaylists()
        playlists.steady(window(60, "a60", "a61", "a62"), source)
        val latest = playlists.steady(window(61, "a61", "a62", "a63"), source)

        assertEquals(latest, playlists.steady(window(60, "a60", "a61", "a62"), source))
    }

    @Test fun `a source's own discontinuities keep counting across a restart`() {
        val playlists = SteadyPlaylists()
        val before =
            "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-DISCONTINUITY-SEQUENCE:13\n#EXT-X-MEDIA-SEQUENCE:390\n#" +
                "EXTINF:5,\na390.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:5,\na391.ts\n"
        playlists.steady(before, source)

        val restarted = playlists.steady(
            "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-DISCONTINUITY-SEQUENCE:0\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:5," +
                "\nb5.ts\n",
            source,
        )

        assertEquals(
            "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-DISCONTINUITY-SEQUENCE:14\n#EXT-X-MEDIA-SEQUENCE:392\n#EXTINF:" +
                "5,\n#EXT-X-DISCONTINUITY\nb5.ts\n",
            restarted,
        )
    }
}

package dev.jamesrich.sofaking.backend.playback

import java.net.URI
import kotlin.test.*

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
        assertEquals(ended, playlists.steady(ended, URI("https://siloh-ns1.plutotv.net/v2/stitch/hls/channel/5f1ac1f1/300/playlist.m3u8")))
    }

    @Test fun `a finished playlist with segments and a master playlist pass through untouched`() {
        val playlists = SteadyPlaylists()
        val finished = live + "#EXT-X-ENDLIST\n"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1600000\n1600/playlist.m3u8\n"
        assertEquals(finished, playlists.steady(finished, source))
        assertEquals(master, playlists.steady(master, source))
        assertEquals(ended, playlists.steady(ended, source))
    }
}

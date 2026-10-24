package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsRewriterTest {
    @Test
    fun `rewrites variants audio subtitles keys maps and relative segments preserving timing`() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        val output = HlsRewriter.rewrite(
            """#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,URI="../subtitles/list.m3u8"
#EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.test/key?secret=private"
#EXT-X-MAP:URI="init.mp4",BYTERANGE="200@0"
#EXT-X-DISCONTINUITY
#EXTINF:4.000,
segment.ts?token=private
#EXT-X-ENDLIST
""",
            URI("https://cdn.example.test/video/list.m3u8"),
        ) { url ->
            urls += url
            "/resource/${urls.size}"
        }
        assertEquals(4, urls.size)
        assertEquals("https://cdn.example.test/subtitles/list.m3u8", urls.first())
        assertFalse(output.contains("private"))
        assertFalse(output.contains("example.test"))
        assertTrue(output.contains("#EXT-X-DISCONTINUITY"))
        assertTrue(output.contains("BYTERANGE=\"200@0\""))
        assertTrue(output.contains("#EXTINF:4.000,"))
    }

    @Test fun `rejects unsupported variable steering and non HTTP resource schemes`() = runBlocking<Unit> {
        for (text in listOf(
            "#EXTM3U\n#EXT-X-DEFINE:NAME=\"x\",VALUE=\"y\"",
            "#EXTM3U\nfile:///etc/passwd",
            "#EXTM3U\n#EXT-X-CONTENT-STEERING:SERVER-URI=\"secret\"",
        )) {
            assertFailsWith<ApiFailure> { HlsRewriter.rewrite(text, URI("http://example.test/list.m3u8")) { it } }
        }
    }

    @Test fun `a reference with characters a URI may not carry is relayed percent-encoded`() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        HlsRewriter.rewrite(
            """#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subtitles",NAME="English[VTT]",URI="../sub-English[VTT].m3u8?a=1"
#EXT-X-STREAM-INF:BANDWIDTH=800000,SUBTITLES="subtitles"
video 360p.m3u8
""",
            URI("https://cdn.example.test/live/abc/master.m3u8"),
        ) { url ->
            urls += url
            "/resource/${urls.size}"
        }
        assertEquals(
            listOf(
                "https://cdn.example.test/live/sub-English%5BVTT%5D.m3u8?a=1",
                "https://cdn.example.test/live/abc/video%20360p.m3u8",
            ),
            urls,
        )
    }

    @Test fun `a playlist reference keeps its escapes and the brackets of an IPv6 host`() {
        assertEquals("seg%20a.ts", playlistUri("seg%20a.ts").toString())
        assertEquals("http://[::1]:8080/a%5Bb%5D.ts", playlistUri("http://[::1]:8080/a[b].ts").toString())
        assertEquals("//[::1]/x%5B1%5D?q=%5B2%5D", playlistUri("//[::1]/x[1]?q=[2]").toString())
    }
}

package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsRewriterTest {
    @Test fun `rewrites variants audio subtitles keys maps and relative segments preserving timing`() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        val output = HlsRewriter.rewrite("""#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,URI="../subtitles/list.m3u8"
#EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.test/key?secret=private"
#EXT-X-MAP:URI="init.mp4",BYTERANGE="200@0"
#EXT-X-DISCONTINUITY
#EXTINF:4.000,
segment.ts?token=private
#EXT-X-ENDLIST
""", URI("https://cdn.example.test/video/list.m3u8")) { url -> urls += url; "/resource/${urls.size}" }
        assertEquals(4, urls.size)
        assertEquals("https://cdn.example.test/subtitles/list.m3u8", urls.first())
        assertFalse(output.contains("private")); assertFalse(output.contains("example.test"))
        assertTrue(output.contains("#EXT-X-DISCONTINUITY")); assertTrue(output.contains("BYTERANGE=\"200@0\""))
        assertTrue(output.contains("#EXTINF:4.000,"))
    }
    @Test fun `rejects unsupported variable steering and non HTTP resource schemes`() = runBlocking<Unit> {
        for (text in listOf("#EXTM3U\n#EXT-X-DEFINE:NAME=\"x\",VALUE=\"y\"", "#EXTM3U\nfile:///etc/passwd", "#EXTM3U\n#EXT-X-CONTENT-STEERING:SERVER-URI=\"secret\"")) {
            assertFailsWith<ApiFailure> { HlsRewriter.rewrite(text, URI("http://example.test/list.m3u8")) { it } }
        }
    }
}

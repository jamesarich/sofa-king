package dev.jamesrich.sofaking.backend.upstream

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Some origins (PBS's livestream CDN) gzip a manifest whether or not the request accepts it. */
class GzipManifestTest {
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    @Test fun `a gzip manifest head sniffs as HLS once inflated and plain bytes pass through`() {
        val manifest = "#EXTM3U\n#EXT-X-VERSION:3\n" + (1..200).joinToString("\n") { "#EXT-X-STREAM-INF:BANDWIDTH=$it\nindex_$it.m3u8" }
        val compressed = gzip(manifest)
        assertTrue(isGzip(compressed))
        assertNull(sniffProtocol(compressed), "the raw bytes carry no signature")
        assertEquals("hls", sniffProtocol(inflateHead(compressed)))
        assertEquals(manifest, inflateHead(compressed, 1 shl 20).toString(Charsets.UTF_8))
        // A 2 KiB head can cut a long gzip stream short; what it holds still begins with the signature.
        assertTrue(compressed.size > 512, "the fixture must be longer than the cut")
        val partial = inflateHead(compressed.copyOf(512))
        assertTrue(partial.toString(Charsets.UTF_8).startsWith("#EXTM3U"), partial.toString(Charsets.UTF_8))
        val plain = "#EXTM3U\n".toByteArray()
        assertFalse(isGzip(plain))
        assertSame(plain, inflateHead(plain))
    }
}

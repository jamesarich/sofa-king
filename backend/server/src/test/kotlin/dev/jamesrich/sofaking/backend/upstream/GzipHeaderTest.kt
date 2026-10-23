package dev.jamesrich.sofaking.backend.upstream

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The gzip header parser behind [inflateHead], exercised through it with every optional header field. */
class GzipHeaderTest {
    private val body = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()

    /** A gzip member with the given flag fields; the deflate stream and trailer follow a hand-built header. */
    private fun member(
        extra: ByteArray? = null,
        name: String? = null,
        comment: String? = null,
        headerCrc: Boolean = false,
    ): ByteArray {
        val header = ByteArrayOutputStream()
        var flags = 0
        if (extra != null) flags = flags or 4
        if (name != null) flags = flags or 8
        if (comment != null) flags = flags or 16
        if (headerCrc) flags = flags or 2
        header.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, flags.toByte(), 0, 0, 0, 0, 0, 3))
        if (extra != null) {
            header.write(extra.size and 0xff)
            header.write(extra.size ushr 8)
            header.write(extra)
        }
        if (name != null) {
            header.write(name.toByteArray(Charsets.ISO_8859_1))
            header.write(0)
        }
        if (comment != null) {
            header.write(comment.toByteArray(Charsets.ISO_8859_1))
            header.write(0)
        }
        if (headerCrc) {
            val crc = CRC32().also { it.update(header.toByteArray()) }.value
            header.write((crc and 0xff).toInt())
            header.write((crc ushr 8 and 0xff).toInt())
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true).also {
            it.setInput(body)
            it.finish()
        }
        val compressed = ByteArray(1024).let { buffer -> buffer.copyOf(deflater.deflate(buffer)) }
        deflater.end()
        return header.toByteArray() + compressed + ByteArray(8)
    }

    @Test fun `a bare header and one with every optional field inflate to the same body`() {
        assertContentEquals(body, inflateHead(member()))
        assertContentEquals(
            body,
            inflateHead(member(extra = byteArrayOf(1, 2, 3, 0, 9))),
            "FEXTRA is skipped by its length, zero bytes included",
        )
        assertContentEquals(body, inflateHead(member(name = "index.m3u8")), "FNAME ends at its NUL")
        assertContentEquals(body, inflateHead(member(comment = "origin")), "FCOMMENT ends at its NUL")
        assertContentEquals(body, inflateHead(member(headerCrc = true)), "FHCRC adds two bytes")
        assertContentEquals(
            body,
            inflateHead(member(extra = byteArrayOf(7), name = "a", comment = "b", headerCrc = true)),
            "the fields come in that order",
        )
    }

    @Test fun `a header cut short inflates to nothing instead of reading past the end`() {
        val full = member(name = "index.m3u8")
        assertEquals(0, inflateHead(full.copyOf(9)).size, "shorter than the fixed header")
        assertEquals(0, inflateHead(full.copyOf(12)).size, "cut inside the name")
        val withExtra = member(extra = ByteArray(40))
        assertEquals(0, inflateHead(withExtra.copyOf(11)).size, "cut inside the FEXTRA length")
        assertEquals(0, inflateHead(withExtra.copyOf(30)).size, "cut inside the FEXTRA bytes")
    }
}

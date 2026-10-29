package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.KIB
import dev.jamesrich.sofaking.backend.upstream.isGzip
import dev.jamesrich.sofaking.contract.Channel
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory

/**
 * [skipped] counts malformed entries by short reason; the import still succeeds with the well-formed rest.
 * [notes] counts imported entries whose identity was derived: `url-identity` and `merged-key`.
 */
data class ParsedPlaylist(
    val channels: List<Channel>,
    val offerings: List<Offering>,
    val skipped: Map<String, Int> = emptyMap(),
    val notes: Map<String, Int> = emptyMap(),
) {
    val skippedCount: Int get() = skipped.values.sum()

    /** For example `missing-id=3, duplicate-source=1`, or null when nothing was skipped. */
    val skipSummary: String? get() = summary(skipped)

    /** For example `merged-key=2, url-identity=724`, or null when every identity was published. */
    val noteSummary: String? get() = summary(notes)

    private fun summary(counts: Map<String, Int>) = counts.entries.sortedBy {
        it.key
    }.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { null }
}

/**
 * [controlCharacters] counts bytes XML 1.0 forbids (C0 controls other than tab, LF and CR)
 * that were dropped before parsing.
 */
data class ParsedGuide(
    val channels: List<GuideChannel>,
    val programs: List<GuideProgram>,
    val controlCharacters: Int = 0,
)

/**
 * Drops the C0 control bytes XML 1.0 forbids, which real guides carry in titles (a raw backspace, for
 * one) and which would otherwise fail the whole import. Those bytes are never part of a multi-byte
 * sequence in UTF-8 or a single-byte encoding; a UTF-16 or UTF-32 document, recognized by a BOM or a
 * NUL in its first bytes, passes through untouched.
 */
private class ControlCharacterStrippingInputStream(input: InputStream) : FilterInputStream(input) {
    var dropped = 0
        private set
    private var active = true

    /** The first four bytes, read ahead for the encoding decision and served before the rest. */
    private var head: ByteArray? = null
    private var headOffset = 0

    override fun read(): Int {
        val one = ByteArray(1)
        while (true) {
            val count = read(one, 0, 1)
            if (count < 0) return -1
            if (count == 1) return one[0].toInt() and BYTE_MASK
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val count = fill(b, off, len)
            if (count <= 0 || !active) return count
            var kept = 0
            for (index in off until off + count) {
                val byte = b[index].toInt() and 0xff
                if (forbidden(byte)) dropped++ else b[off + kept++] = b[index]
            }
            if (kept > 0) return kept
        }
    }

    private fun fill(b: ByteArray, off: Int, len: Int): Int {
        val pending = head ?: sniff()
        if (headOffset < pending.size) {
            val count = minOf(len, pending.size - headOffset)
            System.arraycopy(pending, headOffset, b, off, count)
            headOffset += count
            return count
        }
        return super.read(b, off, len)
    }

    /** The parser reads the prolog a byte at a time, so the decision needs its own read-ahead. */
    private fun sniff(): ByteArray {
        val bytes = ByteArray(SNIFF_BYTES)
        var read = 0
        while (read < SNIFF_BYTES) {
            val count = super.read(bytes, read, SNIFF_BYTES - read)
            if (count < 0) break
            read += count
        }
        val first = bytes.copyOf(read)
        val bom16 =
            read >= 2 && (
                (first[0] == 0xfe.toByte() && first[1] == 0xff.toByte()) ||
                    (first[0] == 0xff.toByte() && first[1] == 0xfe.toByte())
                )
        active = !bom16 && first.none { it == 0.toByte() }
        head = first
        return first
    }

    /** The C0 controls XML 1.0 forbids: all below a space but tab, line feed and carriage return. */
    private fun forbidden(byte: Int) = byte < ' '.code && byte != '\t'.code && byte != '\n'.code && byte != '\r'.code
}

/** Fails once more than [limit] decompressed bytes have been read, so a hostile guide cannot run unbounded. */
private class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) consumed(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
        if (it > 0) {
            consumed(it.toLong())
        }
    }

    private fun consumed(bytes: Long) {
        count += bytes
        require(count <= limit) { "Guide exceeds size limit" }
    }
}

object FeedParser {
    fun playlist(
        provider: String,
        text: String,
        defaultProtocol: String? = null,
        renewable: Set<String> = DEFAULT_RENEWABLE_QUERY,
        /** The playlist's own URL; relative `tvg-logo` references resolve against it. */
        baseUrl: String? = null,
    ): ParsedPlaylist = PlaylistReader(provider, defaultProtocol, renewable, baseUrl).read(text)

    /**
     * Parses XMLTV from [input] (plain or gzip), keeping only programs whose channel [wanted] accepts.
     *
     * A `<!DOCTYPE tv SYSTEM "xmltv.dtd">` declaration is reported and skipped, never processed: the
     * external DTD is not fetched, internal-subset declarations are ignored, and a reference to an entity
     * declared there fails the parse as undeclared instead of resolving.
     */
    fun guide(
        input: InputStream,
        /** The guide's own URL; relative `<icon src>` references resolve against it. */
        baseUrl: String? = null,
        wanted: (String) -> Boolean,
    ): ParsedGuide {
        val factory =
            XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty("javax.xml.stream.isSupportingExternalEntities", false)
                setXMLResolver { _, _, _, _ -> throw IllegalArgumentException("External XML entities are forbidden") }
            }
        val stripped = ControlCharacterStrippingInputStream(BoundedInputStream(decompressed(input), GUIDE_BYTE_LIMIT))
        val reader = factory.createXMLStreamReader(stripped)
        val (channels, programs) = try {
            XmltvReader(reader, baseUrl, wanted).read()
        } finally {
            reader.close()
        }
        return ParsedGuide(channels, programs, stripped.dropped)
    }

    fun guide(text: String, wanted: (String) -> Boolean = { true }): ParsedGuide =
        guide(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), wanted = wanted)

    /** Gzip is recognized by its magic bytes, whatever the URL or content type says. */
    private fun decompressed(input: InputStream): InputStream {
        val stream = PushbackInputStream(input, 2)
        val head = ByteArray(2)
        var read = 0
        while (read < 2) {
            val count = stream.read(head, read, 2 - read)
            if (count < 0) break
            read += count
        }
        if (read > 0) stream.unread(head, 0, read)
        return if (read == 2 && isGzip(head)) {
            GZIPInputStream(stream, GZIP_BUFFER)
        } else {
            stream
        }
    }

    private const val GUIDE_BYTE_LIMIT = 1L shl 30
    private const val GZIP_BUFFER = 64 * KIB
}

private const val BYTE_MASK = 0xFF

/** How much of a body is read ahead to tell its encoding: enough for any byte order mark. */
private const val SNIFF_BYTES = 4

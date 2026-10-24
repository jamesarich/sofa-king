package dev.jamesrich.sofaking.backend.upstream

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

internal const val TS_PACKET = 188
internal const val TS_SYNC = 0x47.toByte()

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private const val HLS_TAG = "#EXTM3U"

/** Three aligned sync bytes tell MPEG-TS from bytes that hold one by chance. */
private const val SYNC_PACKETS = 3

/** RFC 1952: a gzip member's fixed header, the flags byte in it, and the flags that add optional fields. */
private const val GZIP_HEADER = 10
private const val GZIP_FLAGS_AT = 3
private const val FHCRC = 2
private const val FEXTRA = 4
private const val FNAME = 8
private const val FCOMMENT = 16

/**
 * "hls" for an extended M3U head, "mpegts" for three aligned sync bytes, otherwise null;
 * [size] bounds a working buffer.
 */
internal fun sniffProtocol(head: ByteArray, size: Int = head.size): String? {
    var start = 0
    if (size >= UTF8_BOM.size && head.copyOfRange(0, UTF8_BOM.size).contentEquals(UTF8_BOM)) start = UTF8_BOM.size
    while (start < size && head[start].toInt().toChar().isWhitespace()) start++
    if (size - start >= HLS_TAG.length && String(head, start, HLS_TAG.length, Charsets.US_ASCII) == HLS_TAG) {
        return "hls"
    }
    if (tsSyncOffset(head, to = size) != null) return "mpegts"
    return null
}

/** Offset of the first sync byte confirmed by [packets] aligned successors, or null. */
internal fun tsSyncOffset(bytes: ByteArray, packets: Int = SYNC_PACKETS, from: Int = 0, to: Int = bytes.size): Int? {
    for (offset in from until minOf(from + TS_PACKET, to)) {
        if (bytes[offset] != TS_SYNC) continue
        var aligned = true
        for (step in 1 until packets) {
            val index = offset + step * TS_PACKET
            if (index >= to || bytes[index] != TS_SYNC) {
                aligned = false
                break
            }
        }
        if (aligned) return offset
    }
    return null
}

internal fun isGzip(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

/**
 * The start of a gzip body inflated as far as the bytes allow; some origins compress manifests whatever the
 * request asked for. Anything that is not gzip comes back unchanged.
 */
internal fun inflateHead(head: ByteArray, limit: Int = 65_536): ByteArray {
    if (!isGzip(head)) return head
    val start = gzipHeaderLength(head) ?: return ByteArray(0)
    // A raw inflater yields what a truncated stream holds; GZIPInputStream would insist on the trailer.
    val inflater = java.util.zip.Inflater(true)
    val out = java.io.ByteArrayOutputStream()
    try {
        inflater.setInput(head, start, head.size - start)
        val buffer = ByteArray(8192)
        while (!inflater.finished() && out.size() < limit) {
            val count = inflater.inflate(buffer)
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, count)
        }
    } catch (_: java.util.zip.DataFormatException) {
        // Whatever inflated before the damage is still the start of the body.
    } finally {
        inflater.end()
    }
    return out.toByteArray()
}

/** Bytes before the deflate stream in a gzip member, or null when the header itself is cut short. */
private fun gzipHeaderLength(bytes: ByteArray): Int? {
    if (bytes.size < GZIP_HEADER) return null
    val flags = bytes[GZIP_FLAGS_AT].toInt()
    var index = GZIP_HEADER
    if (flags and FEXTRA != 0) {
        if (bytes.size < index + Short.SIZE_BYTES) return null
        // The extra field's length, a little-endian 16-bit word, comes before it.
        val extra = bytes[index].toUByte().toInt() or (bytes[index + 1].toUByte().toInt() shl Byte.SIZE_BITS)
        index += Short.SIZE_BYTES + extra
    }
    for (bit in intArrayOf(FNAME, FCOMMENT)) {
        if (flags and bit != 0) {
            while (index < bytes.size && bytes[index] != 0.toByte()) index++
            index++
        }
    }
    if (flags and FHCRC != 0) index += Short.SIZE_BYTES
    return index.takeIf { it <= bytes.size }
}

/** Reads up to [limit] bytes, stopping early only at end of stream. */
internal suspend fun ByteReadChannel.readHead(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var filled = 0
    while (filled < limit) {
        val read = readAvailable(buffer, filled, limit - filled)
        if (read < 0) break
        filled += read
    }
    return buffer.copyOf(filled)
}

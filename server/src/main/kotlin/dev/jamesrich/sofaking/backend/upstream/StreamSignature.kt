package dev.jamesrich.sofaking.backend.upstream

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

internal const val TS_PACKET = 188
internal const val TS_SYNC = 0x47.toByte()

/** "hls" for an extended M3U head, "mpegts" for three aligned sync bytes, otherwise null. */
internal fun sniffProtocol(head: ByteArray): String? {
    var start = 0
    if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) start = 3
    while (start < head.size && head[start].toInt().toChar().isWhitespace()) start++
    if (head.size - start >= 7 && String(head, start, 7, Charsets.US_ASCII) == "#EXTM3U") return "hls"
    if (tsSyncOffset(head, 3) != null) return "mpegts"
    return null
}

/** Offset of the first sync byte confirmed by [packets] aligned successors, or null. */
internal fun tsSyncOffset(bytes: ByteArray, packets: Int = 3, from: Int = 0, to: Int = bytes.size): Int? {
    for (offset in from until minOf(from + TS_PACKET, to)) {
        if (bytes[offset] != TS_SYNC) continue
        var aligned = true
        for (step in 1 until packets) {
            val index = offset + step * TS_PACKET
            if (index >= to || bytes[index] != TS_SYNC) { aligned = false; break }
        }
        if (aligned) return offset
    }
    return null
}

/** Reads up to [limit] bytes, stopping early only at end of stream. */
internal suspend fun ByteReadChannel.readHead(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var filled = 0
    while (filled < limit) {
        val read = readAvailable(buffer, filled, limit)
        if (read < 0) break
        filled += read
    }
    return buffer.copyOf(filled)
}

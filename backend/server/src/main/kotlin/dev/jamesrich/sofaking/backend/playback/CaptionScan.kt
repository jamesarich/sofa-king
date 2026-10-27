package dev.jamesrich.sofaking.backend.playback

/**
 * Looks through a segment's bytes, as they arrive, for CEA-608 captions. Broadcasts carry them as ATSC A/53 caption
 * data (`GA94`, then type 3) inside the video: in an SEI message for H.264 and HEVC, or as MPEG-2 user data. Since the
 * marker is in the video's own bytes, the scan reads an MPEG-TS or fragmented MP4 segment as it is, without demuxing.
 * A marker that a TS packet header splits is missed, but a captioned segment carries one in every frame.
 */
internal class CaptionScan {
    var found = false
        private set
    private var tail = ByteArray(0)

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (found || length <= 0) return
        val joined = tail + bytes.copyOfRange(offset, offset + length)
        found = carries608(joined)
        tail = joined.copyOfRange(maxOf(0, joined.size - CAPTION_DATA_MOST), joined.size)
    }
}

/**
 * Whether [bytes] hold A/53 caption data with a valid CEA-608 byte pair, on field 1 or 2, that is not padding. A
 * stream that sends only padding has a caption channel but nothing in it, so it does not count.
 */
internal fun carries608(bytes: ByteArray): Boolean {
    var at = 0
    while (at + HEADER <= bytes.size) {
        if (isMarker(bytes, at) && hasPair(bytes, at)) return true
        at++
    }
    return false
}

private fun isMarker(bytes: ByteArray, at: Int): Boolean {
    for (index in GA94.indices) {
        if (bytes[at + index] != GA94[index]) return false
    }
    return true
}

/** Whether the caption data after the marker at [at] holds a CEA-608 pair with text or a command in it. */
private fun hasPair(bytes: ByteArray, at: Int): Boolean {
    val flags = bytes[at + GA94.size].toInt()
    // The flags byte, then a reserved byte, then the entries.
    val first = at + GA94.size + 2
    val count = if (flags and PROCESS_CC_DATA == 0) 0 else flags and CC_COUNT
    return (0 until count).map { first + it * ENTRY }.takeWhile { it + ENTRY <= bytes.size }.any { start ->
        val head = bytes[start].toInt()
        val valid = head and MARKER_BITS == MARKER_BITS && head and CC_VALID != 0
        // cc_type 0 and 1 are the two fields of CEA-608; 2 and 3 carry CEA-708.
        val field608 = head and CC_TYPE <= 1
        val content = (bytes[start + 1].toInt() and PARITY_STRIPPED) or (bytes[start + 2].toInt() and PARITY_STRIPPED)
        valid && field608 && content != 0
    }
}

/** `GA94`, then user_data_type_code 3, cc_data. */
private val GA94 = byteArrayOf(0x47, 0x41, 0x39, 0x34, 0x03)

/** The marker, the flags byte and the reserved byte before the first entry. */
private val HEADER = GA94.size + 2

private const val PROCESS_CC_DATA = 0x40
private const val CC_COUNT = 0x1F
private const val ENTRY = 3
private const val MARKER_BITS = 0xF8
private const val CC_VALID = 0x04
private const val CC_TYPE = 0x03
private const val PARITY_STRIPPED = 0x7F

/** The longest caption data: the header and 31 entries, which a scan keeps across the bytes it is fed in. */
private const val CAPTION_DATA_MOST = 7 + 31 * ENTRY

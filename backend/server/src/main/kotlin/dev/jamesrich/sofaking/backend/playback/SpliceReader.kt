package dev.jamesrich.sofaking.backend.playback

/**
 * One SCTE-35 splice point a segment announces: the stream leaving the network for a break ([out]) or coming back,
 * at [ptsTicks] on the 90 kHz clock (null for "now", the segment that carries it), with the break's planned length
 * when the cue says.
 */
internal data class Splice(val out: Boolean, val ptsTicks: Long?, val durationMs: Long?)

/** What one transport stream segment carried: the PTS of its first video frame and the splices it announced. */
internal class SegmentCues(val firstPtsTicks: Long?, val splices: List<Splice>)

/**
 * Reads the SCTE-35 cues inside an MPEG transport stream segment as it streams past. Many playouts (Amagi's, Samsung
 * TV Plus, Whale and LG's) keep the broadcast's cue stream, PMT stream type 0x86, in every segment while their
 * playlists say nothing; a `splice_insert` leaving the network starts a break, carries its length, and one returning
 * ends it. A `time_signal` counts when a segmentation descriptor names a break, ad or placement opportunity; overlays
 * and program boundaries do not. No JVM library reads SCTE-35, so the few fields used are read here, by ANSI/SCTE 35.
 * An encrypted segment, or anything else that is not a transport stream, yields nothing.
 */
internal class SpliceReader {
    private val packet = ByteArray(PACKET)
    private var filled = 0
    private var off = false
    private val pmtPids = HashSet<Int>()
    private val cuePids = HashSet<Int>()
    private var videoPid = -1
    private var firstPts: Long? = null
    private val sections = HashMap<Int, SectionBuffer>()
    private val splices = ArrayList<Splice>()

    /** Reads [length] bytes of [bytes] from [offset]; a packet split across calls is joined. */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var index = offset
        val end = offset + length
        while (!off && index < end) {
            val take = minOf(PACKET - filled, end - index)
            System.arraycopy(bytes, index, packet, filled, take)
            filled += take
            index += take
            if (filled == PACKET) {
                filled = 0
                if (packet[0] != SYNC) off = true else read(packet)
            }
        }
    }

    /** What the segment carried, once it has all been fed. */
    fun finish(): SegmentCues = SegmentCues(firstPts, splices.toList())

    private fun read(p: ByteArray) {
        val pid = p.bits13(1)
        val start = p[1].u and 0x40 != 0
        val control = p[3].u shr 4 and 3
        if (control and 1 == 0) return
        val from = if (control and 2 != 0) 5 + p[4].u else 4
        if (from >= PACKET) return
        when {
            pid == videoPid -> if (start && firstPts == null) firstPts = pesPts(p, from)

            pid == 0 || pid in pmtPids || pid in cuePids -> sections.getOrPut(pid) {
                SectionBuffer()
            }.add(p, from, start) { section(pid, it) }
        }
    }

    private fun section(pid: Int, s: ByteArray) = try {
        when {
            pid == 0 && s[0].u == TABLE_PAT -> pat(s)
            pid in pmtPids && s[0].u == TABLE_PMT -> pmt(s)
            pid in cuePids && s[0].u == TABLE_SPLICE_INFO -> SpliceInfo.read(s)?.let(splices::add)
            else -> Unit
        }
    } catch (_: IndexOutOfBoundsException) {
        // A section shorter than its fields say is skipped.
    }

    private fun pat(s: ByteArray) {
        var i = PAT_PROGRAMS_AT
        while (i + PAT_PROGRAM_BYTES <= s.size - CRC_BYTES) {
            // Program 0 names the network PID, not a PMT.
            if (s.uint(i, 2) != 0L) pmtPids += s.bits13(i + 2)
            i += PAT_PROGRAM_BYTES
        }
    }

    private fun pmt(s: ByteArray) {
        var i = PMT_STREAMS_AT + s.bits12(PMT_INFO_LENGTH_AT)
        while (i + PMT_STREAM_BYTES <= s.size - CRC_BYTES) {
            val type = s[i].u
            val pid = s.bits13(i + 1)
            when (type) {
                STREAM_SCTE35 -> cuePids += pid
                STREAM_H264, STREAM_H265 -> if (videoPid < 0) videoPid = pid
            }
            i += PMT_STREAM_BYTES + s.bits12(i + PMT_STREAM_INFO_LENGTH_AT)
        }
    }

    /** One PID's PSI sections, joined across packets; each whole one goes to the callback. */
    private class SectionBuffer {
        private var bytes = ByteArray(0)
        private var started = false

        fun add(p: ByteArray, from: Int, start: Boolean, whole: (ByteArray) -> Unit) {
            var payload = from
            if (start) {
                val pointer = p[from].u
                // The bytes before the pointer finish the section already begun.
                if (started && pointer > 0) append(p, from + 1, minOf(PACKET, from + 1 + pointer))
                drain(whole)
                bytes = ByteArray(0)
                started = true
                payload = from + 1 + pointer
            } else if (!started) {
                return
            }
            if (payload < PACKET) append(p, payload, PACKET)
            drain(whole)
        }

        private fun append(p: ByteArray, from: Int, to: Int) {
            if (bytes.size + (to - from) > SECTION_LIMIT) {
                bytes = ByteArray(0)
                started = false
                return
            }
            bytes += p.copyOfRange(from, to)
        }

        /** Hands on every whole section at the head of the buffer; stuffing (0xFF) ends the packet's sections. */
        private fun drain(whole: (ByteArray) -> Unit) {
            while (bytes.size >= SECTION_HEADER_BYTES && bytes[0].u != STUFFING) {
                val length = SECTION_HEADER_BYTES + bytes.bits12(1)
                if (bytes.size < length) return
                whole(bytes.copyOfRange(0, length))
                bytes = bytes.copyOfRange(length, bytes.size)
            }
            // A new section only begins where a packet says, so a finished buffer waits for the next start.
            if (bytes.isEmpty() || bytes[0].u == STUFFING) {
                bytes = ByteArray(0)
                started = false
            }
        }
    }
}

/** The PES start code prefix, 00 00 01, at [at]. */
private fun ByteArray.startCodeAt(at: Int): Boolean = this[at].u == 0 && this[at + 1].u == 0 && this[at + 2].u == 1

/** A 33-bit field in the last bits of the 5 bytes at [at], as in `break_duration()`. */
private fun ticks(s: ByteArray, at: Int): Long = s.uint(at, TIME_BYTES) and PTS_MASK

/** The PTS of the PES packet starting at [from], or null when it has none. */
private fun pesPts(p: ByteArray, from: Int): Long? {
    val at = from + PES_PTS_AT
    if (at + TIME_BYTES > PACKET || !p.startCodeAt(from) || p[from + PES_FLAGS_AT].u and BIT_7 == 0) return null
    // 3, 15 and 15 bits of the PTS, each followed by a marker bit.
    val high = p[at].u.toLong() shr 1 and PTS_HIGH_MASK
    val middle = p.uint(at + 1, 2) shr 1
    val low = p.uint(at + PES_PTS_LOW_AT, 2) shr 1
    return (high shl 2 * PTS_PART_BITS) or (middle shl PTS_PART_BITS) or low
}

private val Byte.u: Int get() = toInt() and 0xFF

private fun ByteArray.uint(at: Int, count: Int): Long {
    var value = 0L
    for (i in at until at + count) value = value shl Byte.SIZE_BITS or this[i].u.toLong()
    return value
}

/** The 12-bit field in the last bits of the 2 bytes at [at], such as a section_length. */
private fun ByteArray.bits12(at: Int): Int = (this[at].u and LOW_4_BITS) shl Byte.SIZE_BITS or this[at + 1].u

/** The 13-bit field in the last bits of the 2 bytes at [at], a PID. */
private fun ByteArray.bits13(at: Int): Int = (this[at].u and LOW_5_BITS) shl Byte.SIZE_BITS or this[at + 1].u

/** Reads a `splice_info_section`, by ANSI/SCTE 35: only the fields a break needs. */
private object SpliceInfo {
    /**
     * The splice a `splice_info_section` announces, or null for a keepalive, a cancel, an
     * encrypted one or another command.
     */
    fun read(s: ByteArray): Splice? {
        // The encrypted flag shares its byte with the top bit of pts_adjustment.
        if (s.size < SPLICE_INFO_MIN_BYTES || s[SPLICE_ADJUSTMENT_AT].u and BIT_7 != 0) return null
        val adjust = ticks(s, SPLICE_ADJUSTMENT_AT)
        val commandLength = s.bits12(SPLICE_COMMAND_LENGTH_AT)
        val command = SPLICE_COMMAND_AT
        val splice = when (s[SPLICE_COMMAND_TYPE_AT].u) {
            COMMAND_SPLICE_INSERT -> spliceInsert(s, command)

            COMMAND_TIME_SIGNAL -> if (commandLength == LENGTH_UNSET) {
                null
            } else {
                timeSignal(s, command, command + commandLength)
            }

            else -> null
        } ?: return null
        return splice.copy(ptsTicks = splice.ptsTicks?.let { (it + adjust) and PTS_MASK })
    }

    private fun spliceInsert(s: ByteArray, at: Int): Splice? {
        // After splice_event_id: the cancel flag, then the flags byte.
        val cancel = at + EVENT_ID_BYTES
        if (s.size < cancel + 2 || s[cancel].u and BIT_7 != 0) return null
        val flags = s[cancel + 1].u
        val out = flags and BIT_7 != 0
        val program = flags and BIT_6 != 0
        val hasDuration = flags and BIT_5 != 0
        val immediate = flags and BIT_4 != 0
        var i = cancel + 2
        var pts: Long? = null
        if (program && !immediate) {
            pts = spliceTime(s, i)
            i += spliceTimeBytes(s, i)
        } else if (!program) {
            // Component splices are rare; their times are skipped and the cue counts from its own segment.
            val components = s[i++].u
            repeat(components) {
                i++
                if (!immediate) i += spliceTimeBytes(s, i)
            }
        }
        val duration = if (hasDuration && i + TIME_BYTES <= s.size) ticks(s, i) / TICKS_PER_MS else null
        return Splice(out, pts, duration)
    }

    /**
     * A `time_signal`: a splice only when one of its segmentation descriptors starts or ends a
     * break, ad or placement opportunity.
     */
    private fun timeSignal(s: ByteArray, at: Int, descriptorsAt: Int): Splice? {
        val pts = spliceTime(s, at)
        if (descriptorsAt + 2 > s.size) return null
        val end = minOf(s.size - CRC_BYTES, descriptorsAt + 2 + s.uint(descriptorsAt, 2).toInt())
        var i = descriptorsAt + 2
        while (i + 2 <= end) {
            val tag = s[i].u
            val length = s[i + 1].u
            if (tag == DESCRIPTOR_SEGMENTATION && i + 2 + length <= end) {
                segmentation(s, i + 2, i + 2 + length)?.let { (out, duration) -> return Splice(out, pts, duration) }
            }
            i += 2 + length
        }
        return null
    }

    /**
     * From a segmentation descriptor's body: whether it leaves for a break (true) or returns
     * (false), and the length it plans.
     */
    private fun segmentation(s: ByteArray, from: Int, to: Int): Pair<Boolean, Long?>? {
        // identifier "CUEI", segmentation_event_id, then the cancel flag.
        var i = from + IDENTIFIER_BYTES + EVENT_ID_BYTES
        if (i >= to || s[i].u and BIT_7 != 0) return null
        i++
        val flags = s[i].u
        i++
        // Without program_segmentation_flag, a count of components follows, each with its own PTS offset.
        if (flags and BIT_7 == 0) i += 1 + s[i].u * COMPONENT_BYTES
        val hasDuration = flags and BIT_6 != 0
        val duration = if (hasDuration) s.uint(i, SEGMENTATION_DURATION_BYTES) / TICKS_PER_MS else null
        if (hasDuration) i += SEGMENTATION_DURATION_BYTES
        // segmentation_upid_type and the UPID's length, the UPID, then segmentation_type_id.
        val typeAt = if (i + 2 > to) null else (i + 2 + s[i + 1].u).takeIf { it < to }
        val type = typeAt?.let { s[it].u }
        return when {
            type == null -> null
            type in BREAK_STARTS -> true to duration
            type in BREAK_ENDS -> false to null
            else -> null
        }
    }

    /** A `splice_time()` at [at]: its PTS, or null when none is specified. */
    private fun spliceTime(s: ByteArray, at: Int): Long? =
        if (at + TIME_BYTES <= s.size && s[at].u and BIT_7 != 0) ticks(s, at) else null

    /** How many bytes the `splice_time()` at [at] takes: a time when its time_specified_flag is set, else one. */
    private fun spliceTimeBytes(s: ByteArray, at: Int): Int = if (s[at].u and BIT_7 != 0) TIME_BYTES else 1
}

private const val PACKET = 188
private const val SYNC = 0x47.toByte()
private const val SECTION_LIMIT = 4096
private const val STREAM_SCTE35 = 0x86
private const val STREAM_H264 = 0x1B
private const val STREAM_H265 = 0x24
private const val PTS_MASK = (1L shl 33) - 1
private const val PTS_HIGH_MASK = 0x07L
private const val PTS_PART_BITS = 15
private const val TICKS_PER_MS = 90

// Flags, by bit, from the high bit down, and the masks for a field's last bits.
private const val BIT_7 = 0x80
private const val BIT_6 = 0x40
private const val BIT_5 = 0x20
private const val BIT_4 = 0x10
private const val LOW_4_BITS = 0x0F
private const val LOW_5_BITS = 0x1F

// PSI sections, by ISO/IEC 13818-1: table_id, the 12-bit section_length, and a CRC_32 at the end.
private const val SECTION_HEADER_BYTES = 3
private const val CRC_BYTES = 4
private const val STUFFING = 0xFF
private const val TABLE_PAT = 0x00
private const val TABLE_PMT = 0x02
private const val TABLE_SPLICE_INFO = 0xFC
private const val PAT_PROGRAMS_AT = 8
private const val PAT_PROGRAM_BYTES = 4
private const val PMT_INFO_LENGTH_AT = 10
private const val PMT_STREAMS_AT = 12
private const val PMT_STREAM_BYTES = 5
private const val PMT_STREAM_INFO_LENGTH_AT = 3

// PES headers: the start code, stream_id, PES_packet_length and a flags byte, then the PTS_DTS_flags byte.
private const val PES_FLAGS_AT = 7
private const val PES_PTS_AT = 9
private const val PES_PTS_LOW_AT = 3

// splice_info_section, by ANSI/SCTE 35.
private const val SPLICE_INFO_MIN_BYTES = 17
private const val SPLICE_ADJUSTMENT_AT = 4
private const val SPLICE_COMMAND_LENGTH_AT = 11
private const val SPLICE_COMMAND_TYPE_AT = 13
private const val SPLICE_COMMAND_AT = 14
private const val LENGTH_UNSET = 0xFFF
private const val COMMAND_SPLICE_INSERT = 0x05
private const val COMMAND_TIME_SIGNAL = 0x06
private const val DESCRIPTOR_SEGMENTATION = 0x02
private const val IDENTIFIER_BYTES = 4
private const val EVENT_ID_BYTES = 4
private const val COMPONENT_BYTES = 6
private const val SEGMENTATION_DURATION_BYTES = 5

/** A 33-bit time on the 90 kHz clock, in the last bits of 5 bytes. */
private const val TIME_BYTES = 5

/**
 * Segmentation types that begin a break: break, provider and distributor ad, provider and
 * distributor placement opportunity.
 */
private val BREAK_STARTS = setOf(0x22, 0x30, 0x32, 0x34, 0x36)

/** The types that end them. */
private val BREAK_ENDS = setOf(0x23, 0x31, 0x33, 0x35, 0x37)

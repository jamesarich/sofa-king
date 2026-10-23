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
        val pid = (p[1].u and 0x1F) shl 8 or p[2].u
        val start = p[1].u and 0x40 != 0
        val control = p[3].u shr 4 and 3
        if (control and 1 == 0) return
        val from = if (control and 2 != 0) 5 + p[4].u else 4
        if (from >= PACKET) return
        when {
            pid == videoPid -> if (start && firstPts == null) firstPts = pesPts(p, from)
            pid == 0 || pid in pmtPids || pid in cuePids -> sections.getOrPut(pid) { SectionBuffer() }.add(p, from, start) { section(pid, it) }
        }
    }

    private fun section(pid: Int, s: ByteArray) = try {
        when {
            pid == 0 && s[0].u == 0x00 -> pat(s)
            pid in pmtPids && s[0].u == 0x02 -> pmt(s)
            pid in cuePids && s[0].u == 0xFC -> spliceInfo(s)?.let(splices::add)
            else -> Unit
        }
    } catch (_: IndexOutOfBoundsException) {
        // A section shorter than its fields say is skipped.
    }

    private fun pat(s: ByteArray) {
        var i = 8
        while (i + 4 <= s.size - 4) {
            if ((s[i].u shl 8 or s[i + 1].u) != 0) pmtPids += (s[i + 2].u and 0x1F) shl 8 or s[i + 3].u
            i += 4
        }
    }

    private fun pmt(s: ByteArray) {
        var i = 12 + ((s[10].u and 0x0F) shl 8 or s[11].u)
        while (i + 5 <= s.size - 4) {
            val type = s[i].u
            val pid = (s[i + 1].u and 0x1F) shl 8 or s[i + 2].u
            when (type) {
                STREAM_SCTE35 -> cuePids += pid
                STREAM_H264, STREAM_H265 -> if (videoPid < 0) videoPid = pid
            }
            i += 5 + ((s[i + 3].u and 0x0F) shl 8 or s[i + 4].u)
        }
    }

    /** The splice a `splice_info_section` announces, or null for a keepalive, a cancel, an encrypted one or another command. */
    private fun spliceInfo(s: ByteArray): Splice? {
        if (s.size < 17 || s[4].u and 0x80 != 0) return null
        val adjust = (s[4].u.toLong() and 1 shl 32) or s.uint(5, 4)
        val commandLength = (s[11].u and 0x0F) shl 8 or s[12].u
        val command = 14
        val splice = when (s[13].u) {
            0x05 -> spliceInsert(s, command)
            0x06 -> if (commandLength == 0xFFF) null else timeSignal(s, command, command + commandLength)
            else -> null
        } ?: return null
        return splice.copy(ptsTicks = splice.ptsTicks?.let { (it + adjust) and PTS_MASK })
    }

    private fun spliceInsert(s: ByteArray, at: Int): Splice? {
        if (s.size < at + 6 || s[at + 4].u and 0x80 != 0) return null
        val flags = s[at + 5].u
        val out = flags and 0x80 != 0
        val program = flags and 0x40 != 0
        val hasDuration = flags and 0x20 != 0
        val immediate = flags and 0x10 != 0
        var i = at + 6
        var pts: Long? = null
        if (program && !immediate) {
            pts = spliceTime(s, i)
            i += if (s[i].u and 0x80 != 0) 5 else 1
        } else if (!program) {
            // Component splices are rare; their times are skipped and the cue counts from its own segment.
            val components = s[i++].u
            repeat(components) {
                i++
                if (!immediate) i += if (s[i].u and 0x80 != 0) 5 else 1
            }
        }
        val duration = if (hasDuration && i + 5 <= s.size) ticks(s, i) / 90 else null
        return Splice(out, pts, duration)
    }

    /** A `time_signal`: a splice only when one of its segmentation descriptors starts or ends a break, ad or placement opportunity. */
    private fun timeSignal(s: ByteArray, at: Int, descriptorsAt: Int): Splice? {
        val pts = spliceTime(s, at)
        if (descriptorsAt + 2 > s.size) return null
        val end = minOf(s.size - 4, descriptorsAt + 2 + (s[descriptorsAt].u shl 8 or s[descriptorsAt + 1].u))
        var i = descriptorsAt + 2
        while (i + 2 <= end) {
            val tag = s[i].u
            val length = s[i + 1].u
            if (tag == 0x02 && i + 2 + length <= end) segmentation(s, i + 2, i + 2 + length)?.let { (out, duration) -> return Splice(out, pts, duration) }
            i += 2 + length
        }
        return null
    }

    /** From a segmentation descriptor's body: whether it leaves for a break (true) or returns (false), and the length it plans. */
    private fun segmentation(s: ByteArray, from: Int, to: Int): Pair<Boolean, Long?>? {
        // identifier "CUEI" (4), segmentation_event_id (4), then the cancel flag.
        var i = from + 8
        if (i >= to || s[i].u and 0x80 != 0) return null
        i++
        val flags = s[i].u
        i++
        if (flags and 0x80 == 0) i += 1 + s[i].u * 6
        val duration = if (flags and 0x40 != 0) (s.uint(i + 1, 4) or (s[i].u.toLong() shl 32)) / 90 else null
        if (flags and 0x40 != 0) i += 5
        if (i + 2 > to) return null
        i += 2 + s[i + 1].u
        if (i >= to) return null
        val type = s[i].u
        return when (type) {
            in BREAK_STARTS -> true to duration
            in BREAK_ENDS -> false to null
            else -> null
        }
    }

    /** A `splice_time()` at [at]: its PTS, or null when none is specified. */
    private fun spliceTime(s: ByteArray, at: Int): Long? =
        if (at < s.size && s[at].u and 0x80 != 0 && at + 5 <= s.size) (s[at].u.toLong() and 1 shl 32) or s.uint(at + 1, 4) else null

    /** A 33-bit field in the last bits of the 5 bytes at [at], as in `break_duration()`. */
    private fun ticks(s: ByteArray, at: Int): Long = (s[at].u.toLong() and 1 shl 32) or s.uint(at + 1, 4)

    /** The PTS of the PES packet starting at [from], or null when it has none. */
    private fun pesPts(p: ByteArray, from: Int): Long? {
        if (from + 14 > PACKET || p[from].u != 0 || p[from + 1].u != 0 || p[from + 2].u != 1 || p[from + 7].u and 0x80 == 0) return null
        val b = from + 9
        return ((p[b].u.toLong() shr 1 and 7) shl 30) or (p[b + 1].u.toLong() shl 22) or ((p[b + 2].u.toLong() shr 1) shl 15) or
            (p[b + 3].u.toLong() shl 7) or (p[b + 4].u.toLong() shr 1)
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
            if (bytes.size + (to - from) > SECTION_LIMIT) { bytes = ByteArray(0); started = false; return }
            bytes += p.copyOfRange(from, to)
        }

        /** Hands on every whole section at the head of the buffer; stuffing (0xFF) ends the packet's sections. */
        private fun drain(whole: (ByteArray) -> Unit) {
            while (bytes.size >= 3 && bytes[0].u != 0xFF) {
                val length = 3 + ((bytes[1].u and 0x0F) shl 8 or bytes[2].u)
                if (bytes.size < length) return
                whole(bytes.copyOfRange(0, length))
                bytes = bytes.copyOfRange(length, bytes.size)
            }
            // A new section only begins where a packet says, so a finished buffer waits for the next start.
            if (bytes.isEmpty() || bytes[0].u == 0xFF) { bytes = ByteArray(0); started = false }
        }
    }
}

private val Byte.u: Int get() = toInt() and 0xFF

private fun ByteArray.uint(at: Int, count: Int): Long {
    var value = 0L
    for (i in at until at + count) value = value shl 8 or this[i].u.toLong()
    return value
}

private const val PACKET = 188
private const val SYNC = 0x47.toByte()
private const val SECTION_LIMIT = 4096
private const val STREAM_SCTE35 = 0x86
private const val STREAM_H264 = 0x1B
private const val STREAM_H265 = 0x24
private const val PTS_MASK = (1L shl 33) - 1

/** Segmentation types that begin a break: break, provider and distributor ad, provider and distributor placement opportunity. */
private val BREAK_STARTS = setOf(0x22, 0x30, 0x32, 0x34, 0x36)
/** The types that end them. */
private val BREAK_ENDS = setOf(0x23, 0x31, 0x33, 0x35, 0x37)

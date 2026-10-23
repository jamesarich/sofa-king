package dev.jamesrich.sofaking.backend.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class SpliceReaderTest {
    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A 188-byte packet on [pid] with [payload] after the header, padded with stuffing. */
    private fun packet(pid: Int, start: Boolean, payload: ByteArray, counter: Int = 0): ByteArray {
        val packet = ByteArray(188) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        packet[2] = pid.toByte()
        packet[3] = (0x10 or (counter and 0x0F)).toByte()
        payload.copyInto(packet, 4)
        return packet
    }

    /** A packet starting a PSI section: pointer field 0, then [section]. */
    private fun psi(pid: Int, section: ByteArray) = packet(pid, true, byteArrayOf(0) + section)

    // Program 1's map on PID 0x1000; the CRCs are not checked.
    private val pat = psi(0, hex("00b00d0001c100000001f00000000000"))

    // H.264 video on 0x100 and SCTE-35 on 0x1f0.
    private val pmt = psi(0x1000, hex("02b0170001c10000e100f0001be100f00086e1f0f00000000000"))

    private fun pes(pts: Long): ByteArray {
        val header = byteArrayOf(
            0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or ((pts shr 29).toInt() and 0x0E)).toByte(), (pts shr 22).toByte(),
            ((pts shr 14).toInt() or 1).toByte(),
            (pts shr 7).toByte(), ((pts shl 1).toInt() or 1).toByte(),
        )
        return packet(0x100, true, header + ByteArray(170))
    }

    // Encoded with threefive: the spec's splice_insert sample (out, at 21514.559089 s, a 60.293567 s break) ...
    private val out =
        psi(0x1f0, hex("fc302500000000000000fff014054800008f7feffe7369c02efe0052ccf50001000000009a4d54ae"))

    // ... its return at 21574.852656 s, an immediate two-minute break, and a keepalive.
    private val back = psi(0x1f0, hex("fc302000000000000000fff00f054800008f7f4ffe73bc8d23000100000000b944d9bd"))
    private val immediate = psi(0x1f0, hex("fc302000000000000000fff00f05000000057ffffe00a4cb80000100000000cd931be4"))
    private val keepalive = psi(0x1f0, hex("fc301100000000000000fff0000000007a4fbfff"))

    private fun read(vararg packets: ByteArray, chunk: Int = 188): SegmentCues {
        val bytes = packets.reduce(ByteArray::plus)
        val reader = SpliceReader()
        for (from in bytes.indices step chunk) reader.feed(bytes, from, minOf(chunk, bytes.size - from))
        return reader.finish()
    }

    @Test
    fun `a splice_insert leaving the network gives its splice time and break length and the return its time`() {
        val cues = read(pat, pmt, pes(1_936_130_318), keepalive, out, back, chunk = 7)
        assertEquals(1_936_130_318, cues.firstPtsTicks)
        assertEquals(listOf(Splice(true, 1_936_310_318, 60_293), Splice(false, 1_941_736_739, null)), cues.splices)
        assertEquals(2_000, spliceOffsetMs(cues.firstPtsTicks, cues.splices[0].ptsTicks))
    }

    @Test
    fun `an immediate splice has no time of its own`() {
        assertEquals(listOf(Splice(true, null, 120_000)), read(pat, pmt, immediate).splices)
    }

    @Test
    fun `a time_signal counts when its segmentation descriptor starts or ends a placement opportunity`() {
        // The spec's samples: a 307 s provider placement opportunity start (0x34), and an end (0x35).
        val start =
            psi(
                0x1f0,
                hex(
                    "fc3034000000000000fffff00506fe72bd0050001e021c435545494800008e7fcf0001a599b00808000000002ca0a18" +
                        "a3402009ac9d17e",
                ),
            )
        val end =
            psi(
                0x1f0,
                hex(
                    "fc302f000000000000fffff00506fe746290a000190217435545494800008e7f9f0808000000002ca0a18a350200a9c" +
                        "c6758",
                ),
            )
        assertEquals(
            listOf(Splice(true, 1_924_989_008, 307_000), Splice(false, 1_952_616_608, null)),
            read(pat, pmt, start, end).splices,
        )
    }

    @Test
    fun `cues on a PID the map does not name, or bytes that are not a transport stream, give nothing`() {
        assertEquals(emptyList<Splice>(), read(pat, out).splices)
        assertEquals(emptyList<Splice>(), read(ByteArray(188 * 3) { 1 }).splices)
    }

    @Test
    fun `a splice time counts from the segment's first frame across the 33-bit wrap`() {
        assertEquals(3_000, spliceOffsetMs((1L shl 33) - 90_000, 180_000))
        assertEquals(-1_000, spliceOffsetMs(180_000, 90_000))
        assertEquals(0, spliceOffsetMs(0, 90_000L * 3600))
        assertEquals(0, spliceOffsetMs(null, 90_000))
    }
}

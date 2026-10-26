package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurroundToneTest {
    private val wav = SurroundTone.wav()

    private fun int16(at: Int) = (wav[at].toInt() and 0xFF) or ((wav[at + 1].toInt() and 0xFF) shl 8)

    private fun int32(at: Int) = int16(at) or (int16(at + 2) shl 16)

    private fun ascii(at: Int, length: Int) = wav.sliceArray(at until at + length).decodeToString()

    /** The loudest sample of each channel over [fromMs] to [toMs]. */
    private fun peaks(fromMs: Long, toMs: Long): List<Int> {
        val frameBytes = SurroundTone.CHANNELS * 2
        val first = (SurroundTone.SAMPLE_RATE * fromMs / 1_000).toInt()
        val last = (SurroundTone.SAMPLE_RATE * toMs / 1_000).toInt()
        return (0 until SurroundTone.CHANNELS).map { channel ->
            (first until last).maxOf { kotlin.math.abs(int16(68 + it * frameBytes + channel * 2).toShort().toInt()) }
        }
    }

    @Test
    fun `the header says 16-bit 48 kHz 5_1`() {
        assertEquals("RIFF", ascii(0, 4))
        assertEquals("WAVE", ascii(8, 4))
        assertEquals(wav.size - 8, int32(4))
        assertEquals(0xFFFE, int16(20), "WAVE_FORMAT_EXTENSIBLE")
        assertEquals(6, int16(22))
        assertEquals(48_000, int32(24))
        assertEquals(16, int16(34))
        assertEquals(0x3F, int32(40), "front left, right, center, LFE, back left and right")
        assertEquals("data", ascii(60, 4))
        assertEquals(wav.size - 68, int32(64))
        assertEquals(48_000 * SurroundTone.DURATION_MS / 1_000 * 12, (wav.size - 68).toLong())
    }

    @Test
    fun `each step sounds only its speaker and then falls silent`() {
        Speaker.entries.forEachIndexed { step, speaker ->
            val start = step * (SurroundTone.TONE_MS + SurroundTone.GAP_MS)
            val tone = peaks(start, start + SurroundTone.TONE_MS)
            tone.forEachIndexed { channel, peak ->
                if (channel == speaker.channel) assertTrue(peak > 7_000, "$speaker sounds") else assertEquals(0, peak)
            }
            val gap = start + SurroundTone.TONE_MS
            assertEquals(List(6) { 0 }, peaks(gap, gap + SurroundTone.GAP_MS), "$speaker's gap is silent")
        }
    }

    @Test
    fun `the speaker named follows the time played`() {
        assertEquals(Speaker.FRONT_LEFT, SurroundTone.speakerAt(0))
        assertEquals(Speaker.FRONT_LEFT, SurroundTone.speakerAt(1_499))
        assertEquals(Speaker.CENTER, SurroundTone.speakerAt(1_500))
        assertEquals(Speaker.SUBWOOFER, SurroundTone.speakerAt(SurroundTone.DURATION_MS - 1))
        assertNull(SurroundTone.speakerAt(SurroundTone.DURATION_MS))
        assertNull(SurroundTone.speakerAt(-1))
    }
}

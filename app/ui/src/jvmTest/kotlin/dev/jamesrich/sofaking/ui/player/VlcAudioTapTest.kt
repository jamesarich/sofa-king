package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class VlcAudioTapTest {
    private val url = "http://relay/song.flac"

    @Test
    fun `nothing heard and nothing mirrored changes nothing`() {
        assertEquals(MirrorStep.Keep, nextStep(Heard(null, false, 0L), Mirrored(null, false, 0L)))
    }

    @Test
    fun `a stopped player stops the mirror`() {
        assertEquals(MirrorStep.Stop, nextStep(Heard(null, false, 0L), Mirrored(url, false, 5_000L)))
    }

    @Test
    fun `a new song opens at the heard position`() {
        assertEquals(
            MirrorStep.Open(url, 12_000L),
            nextStep(Heard(url, true, 12_000L), Mirrored("http://relay/other.flac", false, 99L)),
        )
    }

    @Test
    fun `a pause is followed`() {
        assertEquals(MirrorStep.Pause(true), nextStep(Heard(url, false, 4_000L), Mirrored(url, false, 4_000L)))
        assertEquals(MirrorStep.Pause(false), nextStep(Heard(url, true, 4_000L), Mirrored(url, true, 4_000L)))
    }

    @Test
    fun `drift past the limit seeks and drift within it does not`() {
        val near = Mirrored(url, false, 10_000L + MIRROR_DRIFT_MS)
        val far = Mirrored(url, false, 10_000L + MIRROR_DRIFT_MS + 1)
        assertEquals(MirrorStep.Keep, nextStep(Heard(url, true, 10_000L), near))
        assertEquals(MirrorStep.Seek(10_000L), nextStep(Heard(url, true, 10_000L), far))
    }

    @Test
    fun `a paused player is not chased`() {
        assertEquals(MirrorStep.Keep, nextStep(Heard(url, false, 10_000L), Mirrored(url, true, 0L)))
    }

    @Test
    fun `16-bit little-endian samples scale to the unit range`() {
        val bytes = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        val out = FloatArray(4)
        pcm16ToFloats(bytes, 4, out)
        assertEquals(0.5f, out[0])
        assertEquals(-0.5f, out[1])
        assertEquals(32767f / 32768f, out[2])
        assertEquals(-1f, out[3])
    }
}

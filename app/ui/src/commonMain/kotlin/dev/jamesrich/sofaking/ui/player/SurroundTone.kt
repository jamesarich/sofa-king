package dev.jamesrich.sofaking.ui.player

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The six speakers of a 5.1 setup, in the order the surround test sounds them, going around the room. */
enum class Speaker {
    FRONT_LEFT,
    CENTER,
    FRONT_RIGHT,
    SURROUND_RIGHT,
    SURROUND_LEFT,
    SUBWOOFER,
    ;

    /** The speaker's place in a 5.1 WAV's frames. */
    val channel: Int get() = WAV_ORDER.indexOf(this)
}

/** How a 5.1 WAV orders its channels: front left, front right, center, LFE, back left, back right. */
private val WAV_ORDER = listOf(
    Speaker.FRONT_LEFT,
    Speaker.FRONT_RIGHT,
    Speaker.CENTER,
    Speaker.SUBWOOFER,
    Speaker.SURROUND_LEFT,
    Speaker.SURROUND_RIGHT,
)

/**
 * The surround test: a tone from each [Speaker] in turn, built here as a 5.1 WAV rather than shipped as a file. The
 * player decodes it like any multichannel audio, so it checks the speakers are wired and mapped the way the app
 * sends sound out.
 */
object SurroundTone {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 6
    const val TONE_MS = 1_000L
    const val GAP_MS = 500L
    const val DURATION_MS = (TONE_MS + GAP_MS) * 6

    /** A tone for the full-range speakers; the subwoofer gets a low one it can play. */
    private const val TONE_HZ = 440.0
    private const val LFE_HZ = 50.0

    /** About -12 dBFS, so the test is clear without being loud. */
    private const val LEVEL = 0.25

    /** Each tone fades in and out over this long, so it starts and stops without a click. */
    private const val FADE_MS = 20L

    private const val BYTES_PER_SAMPLE = 2
    private const val HEADER_BYTES = 68

    /** "RIFF" and the size field, which the size leaves out. */
    private const val RIFF_PREAMBLE_BYTES = 8
    private const val FORMAT_CHUNK_BYTES = 40
    private const val EXTENSIBLE = 0xFFFE
    private const val PCM_FORMAT = 1
    private const val BITS = 16
    private const val VALID_BITS_EXTRA = 22

    /** Front left, front right, center, LFE, back left and back right: a 5.1 layout. */
    private const val CHANNEL_MASK_5_1 = 0x3F
    private const val SHORT_MAX = 32_767
    private const val MS_PER_SECOND = 1_000L

    /** The KSDATAFORMAT_SUBTYPE_PCM GUID's last 14 bytes, after the format tag. */
    private val PCM_SUBTYPE_TAIL =
        intArrayOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71)

    /** The speaker sounding [elapsedMs] into the test, through its gap after it; null once the test is over. */
    fun speakerAt(elapsedMs: Long): Speaker? = if (elapsedMs < 0 ||
        elapsedMs >= DURATION_MS
    ) {
        null
    } else {
        Speaker.entries[(elapsedMs / (TONE_MS + GAP_MS)).toInt()]
    }

    /** The whole test as a 16-bit, 48 kHz, 5.1 WAV (WAVE_FORMAT_EXTENSIBLE, so players know the speaker layout). */
    fun wav(): ByteArray {
        val frames = (SAMPLE_RATE * DURATION_MS / MS_PER_SECOND).toInt()
        val dataBytes = frames * CHANNELS * BYTES_PER_SAMPLE
        val out = Bytes(HEADER_BYTES + dataBytes)
        out.ascii("RIFF")
        out.int32(HEADER_BYTES - RIFF_PREAMBLE_BYTES + dataBytes)
        out.ascii("WAVE")
        out.ascii("fmt ")
        out.int32(FORMAT_CHUNK_BYTES)
        out.int16(EXTENSIBLE)
        out.int16(CHANNELS)
        out.int32(SAMPLE_RATE)
        out.int32(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)
        out.int16(CHANNELS * BYTES_PER_SAMPLE)
        out.int16(BITS)
        out.int16(VALID_BITS_EXTRA)
        out.int16(BITS)
        out.int32(CHANNEL_MASK_5_1)
        out.int16(PCM_FORMAT)
        PCM_SUBTYPE_TAIL.forEach { out.byte(it.toByte()) }
        out.ascii("data")
        out.int32(dataBytes)
        val toneFrames = (SAMPLE_RATE * TONE_MS / MS_PER_SECOND).toInt()
        val stepFrames = (SAMPLE_RATE * (TONE_MS + GAP_MS) / MS_PER_SECOND).toInt()
        val fadeFrames = (SAMPLE_RATE * FADE_MS / MS_PER_SECOND).toInt()
        for (frame in 0 until frames) {
            val speaker = Speaker.entries[frame / stepFrames]
            val inStep = frame % stepFrames
            val sample = if (inStep < toneFrames) tone(speaker, inStep, toneFrames, fadeFrames) else 0
            for (channel in 0 until CHANNELS) out.int16(if (channel == speaker.channel) sample else 0)
        }
        return out.bytes
    }

    private fun tone(speaker: Speaker, frame: Int, frames: Int, fade: Int): Int {
        val hz = if (speaker == Speaker.SUBWOOFER) LFE_HZ else TONE_HZ
        val envelope = min(1.0, min(frame, frames - 1 - frame).toDouble() / fade)
        return (sin(2 * PI * hz * frame / SAMPLE_RATE) * LEVEL * envelope * SHORT_MAX).roundToInt()
    }

    /** A little-endian writer over a buffer of known size. */
    private class Bytes(size: Int) {
        val bytes = ByteArray(size)
        private var at = 0

        fun byte(value: Byte) {
            bytes[at++] = value
        }

        fun ascii(text: String) = text.forEach { byte(it.code.toByte()) }

        fun int16(value: Int) {
            byte(value.toByte())
            byte((value shr Byte.SIZE_BITS).toByte())
        }

        fun int32(value: Int) {
            int16(value)
            int16(value shr Short.SIZE_BITS)
        }
    }
}

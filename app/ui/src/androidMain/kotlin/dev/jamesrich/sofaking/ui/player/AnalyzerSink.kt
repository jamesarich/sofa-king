package dev.jamesrich.sofaking.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import dev.jamesrich.sofaking.visualizer.AudioAnalyzer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hands the samples the audio sink is about to play to an [AudioAnalyzer], as floats. Only 16-bit and float PCM are
 * read; anything else (a passthrough stream, 24-bit) leaves the visualizer at its quiet frame.
 */
@OptIn(UnstableApi::class)
internal class AnalyzerSink(private val analyzer: AudioAnalyzer) : TeeAudioProcessor.AudioBufferSink {
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var scratch = FloatArray(0)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        analyzer.sampleRate = sampleRateHz
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        // The buffer is the sink's to play: read a copy, in the order the decoder wrote it.
        val samples = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        val count = when (encoding) {
            C.ENCODING_PCM_16BIT -> samples.remaining() / (2 * channels)
            C.ENCODING_PCM_FLOAT -> samples.remaining() / (4 * channels)
            else -> return
        }
        if (count == 0) return
        if (scratch.size < count * channels) scratch = FloatArray(count * channels)
        if (encoding == C.ENCODING_PCM_16BIT) {
            val shorts = samples.asShortBuffer()
            for (i in 0 until count * channels) scratch[i] = shorts.get(i) / PCM16_FULL_SCALE
        } else {
            val floats = samples.asFloatBuffer()
            for (i in 0 until count * channels) scratch[i] = floats.get(i)
        }
        analyzer.push(scratch, count, channels)
    }

    private companion object {
        const val PCM16_FULL_SCALE = 32768f
    }
}

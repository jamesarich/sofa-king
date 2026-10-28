package dev.jamesrich.sofaking.ui.player

import androidx.media3.common.C
import dev.jamesrich.sofaking.visualizer.AudioAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AnalyzerSinkTest {
    @Test
    fun `16-bit samples reach the analyzer as floats, with the rate the sink opened at`() {
        val analyzer = AudioAnalyzer()
        val sink = AnalyzerSink(analyzer)
        sink.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        val buffer = ByteBuffer.allocate(4 * 100).order(ByteOrder.nativeOrder())
        repeat(100) {
            buffer.putShort(16_384)
            buffer.putShort((-16_384).toShort())
        }
        buffer.flip()
        sink.handleBuffer(buffer)
        val frame = analyzer.frame(0.033)
        assertEquals(48_000, analyzer.sampleRate)
        assertEquals(0.5f, frame.left.last(), 1e-4f)
        assertEquals(-0.5f, frame.right.last(), 1e-4f)
        assertEquals("the sink's buffer is left as it was", 400, buffer.remaining())
    }

    @Test
    fun `an encoding the sink cannot read leaves the analyzer quiet`() {
        val analyzer = AudioAnalyzer()
        val sink = AnalyzerSink(analyzer)
        sink.flush(48_000, 2, C.ENCODING_AC3)
        sink.handleBuffer(ByteBuffer.allocate(64))
        assertTrue(analyzer.frame(0.033).left.all { it == 0f })
    }
}

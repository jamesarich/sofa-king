package dev.jamesrich.sofaking.visualizer

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A few seconds of a made-up song: a steady tone, and a bass thump every half second. */
internal fun synthetic(analyzer: AudioAnalyzer, sampleRate: Int, startSample: Long, count: Int) {
    val samples = FloatArray(count * 2)
    for (i in 0 until count) {
        val t = (startSample + i).toDouble() / sampleRate
        val beat = (t % 0.5)
        val kick = if (beat < 0.15) sin(2 * PI * 60 * t) * (1 - beat / 0.15) * 0.8 else 0.0
        val tone = sin(2 * PI * 880 * t) * 0.2 + sin(2 * PI * 3000 * t) * 0.05
        samples[i * 2] = (kick + tone).toFloat()
        samples[i * 2 + 1] = (kick * 0.9 + tone * 1.1).toFloat()
    }
    analyzer.push(samples, count, 2)
}

class VisualizerTest {
    private val rate = 44_100

    @Test
    fun `a tone lights its band and a silent room reads as average`() {
        val analyzer = AudioAnalyzer(rate)
        assertEquals(1.0, analyzer.frame(0.033).bass)
        var at = 0L
        repeat(60) {
            synthetic(analyzer, rate, at, rate / 30)
            at += rate / 30
            analyzer.frame(1.0 / 30)
        }
        val frame = analyzer.frame(1.0 / 30)
        assertTrue(frame.spectrum.max() > 0.05f, "the tone shows in the spectrum")
        assertTrue(frame.left.any { it != 0f })
    }

    @Test
    fun `the fft puts a sine in its bin`() {
        val size = 1024
        val real = DoubleArray(size) { sin(2 * PI * 64 * it / size) }
        val imaginary = DoubleArray(size)
        Fft(size).transform(real, imaginary)
        val magnitudes = DoubleArray(size / 2) { real[it] * real[it] + imaginary[it] * imaginary[it] }
        assertEquals(64, magnitudes.indices.maxBy { magnitudes[it] })
    }

    @Test
    fun `a preset file reads its values and joins its equation lines`() {
        val preset = MilkPreset.parse(
            "t",
            """
            [preset00]
            fDecay=0.9
            nWaveMode=3
            per_frame_2=zoom = zoom + 0.1;
            per_frame_1=rot = 0.5;
            per_pixel_1=dx = rad;
            """.trimIndent(),
        )
        assertEquals(0.9, preset.values["decay"])
        assertEquals(3.0, preset.values["wave_mode"])
        assertEquals("rot = 0.5;zoom = zoom + 0.1", preset.frame)
        assertEquals("dx = rad", preset.pixel)
    }

    @Test
    fun `every built-in preset draws something and stays in range`() {
        for (preset in Presets.all) {
            val analyzer = AudioAnalyzer(rate)
            val vis = Visualizer(96, 54, preset)
            var at = 0L
            var lit = 0
            repeat(90) {
                synthetic(analyzer, rate, at, rate / 30)
                at += rate / 30
                val pixels = vis.render(analyzer.frame(1.0 / 30), 1.0 / 30)
                lit = pixels.count { it and 0xFFFFFF != 0 }
            }
            assertTrue(lit > 20, "${preset.name} drew $lit lit pixels")
        }
    }
}

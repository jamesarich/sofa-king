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
        assertEquals("rot = 0.5; zoom = zoom + 0.1;", preset.frame)
        assertEquals("dx = rad;", preset.pixel)
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

    @Test
    fun `custom waves and shapes are read and drawn`() {
        val preset = MilkPreset.parse(
            "custom",
            """
            fDecay=0.9
            fWaveAlpha=0
            wavecode_0_enabled=1
            wavecode_0_samples=128
            wavecode_0_r=1
            wavecode_0_g=0.5
            wavecode_0_b=0
            wavecode_0_a=1
            wave_0_per_point1=x = sample; y = 0.5 + value1 * 0.4;
            shapecode_0_enabled=1
            shapecode_0_sides=6
            shapecode_0_rad=0.2
            shapecode_0_a=1
            shapecode_0_a2=0.2
            shapecode_0_r=0
            shapecode_0_g=1
            shapecode_0_b=1
            shapecode_0_num_inst=2
            shape_0_per_frame1=x = 0.3 + instance * 0.4;
            """.trimIndent(),
        )
        assertEquals(1, preset.waves.size)
        assertEquals(1, preset.shapes.size)
        assertEquals("x = sample; y = 0.5 + value1 * 0.4;", preset.waves[0].point)
        val analyzer = AudioAnalyzer(rate)
        val vis = Visualizer(96, 54, preset)
        synthetic(analyzer, rate, 0, rate / 10)
        val pixels = vis.render(analyzer.frame(0.1), 0.1)
        val green = pixels.count { (it shr 8 and 0xFF) > 120 && (it shr 16 and 0xFF) < 100 }
        assertTrue(green > 200, "the two hexagons fade from cyan to green ($green pixels)")
        assertTrue(pixels.any { (it shr 16 and 0xFF) > 150 && (it and 0xFF) < 60 }, "the wave is orange")
    }

    @Test
    fun `a pack keeps each preset as it was written`() {
        val a = Presets.all[1]
        val b = Presets.all[5]
        val pack = MilkPreset.parsePack(
            Presets.all.slice(listOf(1, 5)).joinToString("\n") {
                "@@ ${it.name}\n${it.toText()}"
            },
        )
        assertEquals(listOf(a.name, b.name), pack.map { it.name })
        assertEquals(a.frame, pack[0].frame)
        assertEquals(a.pixel, pack[0].pixel)
        assertEquals(b.init, pack[1].init)
        assertEquals(a.values, pack[0].values)
    }
}

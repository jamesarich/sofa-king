package dev.jamesrich.sofaking.ui.visualizer

import dev.jamesrich.sofaking.visualizer.AudioAnalyzer
import dev.jamesrich.sofaking.visualizer.MilkPreset
import dev.jamesrich.sofaking.visualizer.Visualizer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassicPackTest {
    private val pack = MilkPreset.parsePack(File("src/commonMain/composeResources/$CLASSIC_PACK_FILE").readText())

    @Test
    fun `the bundled pack has its presets, each with a name and a look of its own`() {
        assertTrue(pack.size > 1000, "the pack holds ${pack.size} presets")
        assertTrue(pack.all { it.name.isNotBlank() })
        assertEquals(pack.size, pack.map { it.toText() }.toSet().size, "no two presets are the same")
    }

    @Test
    fun `every preset in the pack compiles and draws a few frames`() {
        val analyzer = AudioAnalyzer(SAMPLE_RATE)
        for (preset in pack) {
            val vis = Visualizer(32, 18, preset)
            repeat(FRAMES) { vis.render(analyzer.frame(FRAME_SECONDS), FRAME_SECONDS) }
            assertEquals(0, vis.unreadable, "${preset.name} has equations the engine cannot read")
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val FRAMES = 5
        const val FRAME_SECONDS = 1.0 / 30
    }
}

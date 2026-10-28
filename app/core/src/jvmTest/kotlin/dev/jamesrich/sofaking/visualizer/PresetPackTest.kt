package dev.jamesrich.sofaking.visualizer

import dev.jamesrich.sofaking.visualizer.eel.EelException
import dev.jamesrich.sofaking.visualizer.eel.EelScope
import java.io.File
import kotlin.test.Test

/**
 * Runs every `.milk` file under `SOFAKING_VIS_PACK_IN` through the engine and reports how many read, draw and move;
 * with `SOFAKING_VIS_PACK_OUT` it also writes the ones that do as a pack, each stripped to what the engine uses.
 * Does nothing without the first variable; it is how the bundled pack is made (app/docs/development.md).
 */
class PresetPackTest {
    private val rate = 44_100

    /** How a preset fared in a short run against the made-up song. */
    private class Verdict(val unreadable: Int, val lit: Int, val mean: Double, val moved: Boolean, val ms: Long) {
        val good get() = unreadable == 0 && lit > MIN_LIT && mean in 0.02..0.85 && moved && ms < MAX_MS
    }

    @Test
    fun `reports how a pack of presets fares`() {
        val root = System.getenv("SOFAKING_VIS_PACK_IN") ?: return
        val out = System.getenv("SOFAKING_VIS_PACK_OUT")?.let(::File)
        val files = File(root).walkTopDown().filter { it.extension == "milk" }.sortedBy { it.path }.toList()
        val pack = StringBuilder()
        val tally = HashMap<String, Int>()
        var kept = 0
        val problems = HashMap<String, Int>()
        for (file in files) {
            val name = file.nameWithoutExtension.filter { it.code in 32..126 }.trim()
            val preset = MilkPreset.parse(name, file.readText(Charsets.ISO_8859_1))
            val verdict = try {
                run(preset)
            } catch (e: RuntimeException) {
                tally.merge("failed: ${e::class.simpleName}", 1, Int::plus)
                continue
            }
            val reason = when {
                verdict.good -> "kept"
                verdict.unreadable > 0 -> "unreadable equations"
                verdict.ms >= MAX_MS -> "too slow"
                verdict.lit <= MIN_LIT -> "black"
                verdict.mean !in 0.02..0.85 -> "washed out or too dark"
                else -> "frozen"
            }
            tally.merge(reason, 1, Int::plus)
            if (verdict.unreadable > 0) why(preset).forEach { problems.merge(it, 1, Int::plus) }
            if (verdict.good) {
                kept++
                pack.append(MilkPreset.PACK_MARK).append(name).append('\n').append(preset.toText())
            }
        }
        problems.entries.sortedByDescending {
            it.value
        }.take(15).forEach { println("PACK problem ${it.value} x ${it.key}") }
        println("PACK ${files.size} files, $kept kept: $tally")
        out?.writeText(pack.toString())
        println("PACK size ${pack.length} characters")
    }

    /** What the evaluator says about each program of [preset] that it cannot read. */
    private fun why(preset: MilkPreset): List<String> {
        val programs =
            listOf(preset.init, preset.frame, preset.pixel) +
                (preset.waves + preset.shapes).flatMap { listOf(it.init, it.frame, it.point) }
        return programs.filter { it.isNotBlank() }.mapNotNull {
            try {
                EelScope().compile(it)
                null
            } catch (e: EelException) {
                (e.message?.take(40) ?: "") +
                    (
                        if (System.getenv("SOFAKING_VIS_PACK_DEBUG") !=
                            null
                        ) {
                            " IN " + it.take(200).replace('\n', ' ')
                        } else {
                            ""
                        }
                        )
            }
        }
    }

    private fun run(preset: MilkPreset): Verdict {
        val started = System.nanoTime()
        val analyzer = AudioAnalyzer(rate)
        val vis = Visualizer(64, 36, preset)
        var at = 0L
        var last = IntArray(0)
        var pixels = IntArray(0)
        repeat(FRAMES) { frame ->
            synthetic(analyzer, rate, at, rate / 30)
            at += rate / 30
            if (frame == FRAMES - 4) last = vis.pixels.copyOf()
            pixels = vis.render(analyzer.frame(1.0 / 30), 1.0 / 30)
        }
        val lit = pixels.count { it and 0xFFFFFF != 0 }
        val mean = pixels.sumOf { ((it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF)) / 765.0 } / pixels.size
        val changed = pixels.indices.count { pixels[it] != last[it] }
        return Verdict(
            vis.unreadable,
            lit,
            mean,
            changed > pixels.size / 100,
            (System.nanoTime() - started) / 1_000_000,
        )
    }

    private companion object {
        const val FRAMES = 60
        const val MIN_LIT = 60
        const val MAX_MS = 700L
    }
}

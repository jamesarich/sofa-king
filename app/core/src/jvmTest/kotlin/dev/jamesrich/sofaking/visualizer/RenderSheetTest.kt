package dev.jamesrich.sofaking.visualizer

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Writes a contact sheet of every built-in preset after a few seconds of the made-up song, when `SOFAKING_VIS_SHEET`
 * names a file. Run it to look at the presets; it asserts nothing.
 */
class RenderSheetTest {
    @Test
    fun `writes a contact sheet when asked`() {
        val out = System.getenv("SOFAKING_VIS_SHEET") ?: return
        val presets = Presets.all
        val w = 320
        val h = 180
        val sheet = BufferedImage(w * 2, h * ((presets.size + 1) / 2), BufferedImage.TYPE_INT_ARGB)
        val rate = 44_100
        presets.forEachIndexed { index, preset ->
            val analyzer = AudioAnalyzer(rate)
            val vis = Visualizer(w, h, preset)
            var at = 0L
            var pixels = IntArray(0)
            repeat(150) {
                synthetic(analyzer, rate, at, rate / 30)
                at += rate / 30
                pixels = vis.render(analyzer.frame(1.0 / 30), 1.0 / 30)
            }
            sheet.setRGB((index % 2) * w, (index / 2) * h, w, h, pixels, 0, w)
        }
        ImageIO.write(sheet, "png", File(out))
    }
}

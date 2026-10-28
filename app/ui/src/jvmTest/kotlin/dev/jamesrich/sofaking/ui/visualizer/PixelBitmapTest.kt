package dev.jamesrich.sofaking.ui.visualizer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlin.test.Test
import kotlin.test.assertEquals

class PixelBitmapTest {
    @Test
    fun `the picture shows the pixels it was given`() {
        val bitmap = PixelBitmap(2, 1)
        bitmap.set(intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        val skia = bitmap.image.asSkiaBitmap()
        assertEquals(0xFFFF0000.toInt(), skia.getColor(0, 0))
        assertEquals(0xFF0000FF.toInt(), skia.getColor(1, 0))
    }

    @Test
    fun `each set gives a picture a draw can tell from the last`() {
        val bitmap = PixelBitmap(1, 1)
        val first: ImageBitmap = bitmap.image
        bitmap.set(intArrayOf(0xFF00FF00.toInt()))
        assert(bitmap.image !== first)
    }
}

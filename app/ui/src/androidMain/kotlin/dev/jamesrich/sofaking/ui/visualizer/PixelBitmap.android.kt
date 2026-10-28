package dev.jamesrich.sofaking.ui.visualizer

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun PixelBitmap(width: Int, height: Int): PixelBitmap = BitmapPixelBitmap(width, height)

private class BitmapPixelBitmap(private val width: Int, private val height: Int) : PixelBitmap {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private var current by mutableStateOf(bitmap.asImageBitmap())

    override val image: ImageBitmap get() = current

    override fun set(argb: IntArray) {
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        // A new wrapper over the same pixels: state only changes when the value does.
        current = bitmap.asImageBitmap()
    }
}

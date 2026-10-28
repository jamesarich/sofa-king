package dev.jamesrich.sofaking.ui.visualizer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal actual fun PixelBitmap(width: Int, height: Int): PixelBitmap = SkiaPixelBitmap(width, height)

private class SkiaPixelBitmap(private val width: Int, private val height: Int) : PixelBitmap {
    private val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)

    private var current by mutableStateOf(picture(IntArray(width * height)))

    override val image: ImageBitmap get() = current

    // The pixels are opaque, so the premultiplied alpha changes nothing.
    override fun set(argb: IntArray) {
        current = picture(argb)
    }

    private fun picture(argb: IntArray) =
        Image.makeRaster(info, bgraBytes(argb), width * BYTES_PER_PIXEL).toComposeImageBitmap()
}

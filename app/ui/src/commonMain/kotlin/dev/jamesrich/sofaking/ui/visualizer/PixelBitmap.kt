package dev.jamesrich.sofaking.ui.visualizer

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A picture the visualizer paints on, frame after frame. [image] is a Compose state, so a draw that reads it runs again
 * after each [set].
 */
internal interface PixelBitmap {
    val image: ImageBitmap

    /** Replaces the picture with [argb] pixels (alpha in the top byte), as many as the bitmap holds. */
    fun set(argb: IntArray)
}

/** A [PixelBitmap] of [width] by [height] pixels. */
internal expect fun PixelBitmap(width: Int, height: Int): PixelBitmap

internal const val BYTES_PER_PIXEL = 4
private const val GREEN_SHIFT = 8
private const val RED_SHIFT = 16
private const val ALPHA_SHIFT = 24
private const val ALPHA_BYTE = 3

/** [argb] pixels as the bytes of a BGRA picture, the blue byte first, for the Skia targets. */
internal fun bgraBytes(argb: IntArray): ByteArray {
    val bytes = ByteArray(argb.size * BYTES_PER_PIXEL)
    for (i in argb.indices) {
        val p = argb[i]
        bytes[i * BYTES_PER_PIXEL] = p.toByte()
        bytes[i * BYTES_PER_PIXEL + 1] = (p shr GREEN_SHIFT).toByte()
        bytes[i * BYTES_PER_PIXEL + 2] = (p shr RED_SHIFT).toByte()
        bytes[i * BYTES_PER_PIXEL + ALPHA_BYTE] = (p shr ALPHA_SHIFT).toByte()
    }
    return bytes
}

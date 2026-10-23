package dev.jamesrich.sofaking.backend.logos

import dev.jamesrich.sofaking.backend.runCatchingCancellable
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** The longest side a stored PNG or JPEG logo keeps; guide tiles draw logos far smaller. */
internal const val LOGO_MAX_SIDE = 512

/** Images up to this many pixels are decoded for scaling; a larger one is kept as fetched. */
private const val MAX_DECODED_PIXELS = 16_000_000L

/**
 * [bytes] of a PNG or JPEG logo scaled down to fit [maxSide] square, or [bytes] unchanged when the image already
 * fits, is another type, cannot be read, or would not get smaller. PNGs keep their alpha.
 */
internal fun fitLogo(bytes: ByteArray, contentType: String, maxSide: Int = LOGO_MAX_SIDE): ByteArray {
    val format = when (contentType) {
        "image/png" -> "png"
        "image/jpeg" -> "jpeg"
        else -> return bytes
    }
    return try {
        // ImageIO's decoders throw more than IOException on a malformed image; such a logo stays as fetched.
        runCatchingCancellable { scaled(bytes, format, maxSide) }.getOrDefault(bytes)
    } catch (_: LinkageError) {
        // A runtime without the imaging natives keeps logos as fetched.
        bytes
    }
}

/** [fitLogo]'s work, which may throw whatever the image decoders throw. */
private fun scaled(bytes: ByteArray, format: String, maxSide: Int): ByteArray {
    val (width, height) = dimensions(bytes) ?: return bytes
    if (width <= maxSide && height <= maxSide) return bytes
    if (width.toLong() * height > MAX_DECODED_PIXELS) return bytes
    val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
    val scale = maxSide.toDouble() / maxOf(width, height)
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val type = if (format == "png") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
    val scaled = BufferedImage(targetWidth, targetHeight, type)
    val graphics = scaled.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }
    val out = ByteArrayOutputStream()
    if (!ImageIO.write(scaled, format, out)) return bytes
    return out.toByteArray().takeIf { it.size < bytes.size } ?: bytes
}

/** Width and height from the image header, without decoding the pixels. */
private fun dimensions(bytes: ByteArray): Pair<Int, Int>? =
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.input = input
            reader.getWidth(0) to reader.getHeight(0)
        } finally {
            reader.dispose()
        }
    }

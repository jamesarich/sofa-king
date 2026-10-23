package dev.jamesrich.sofaking.backend.logos

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogoScalingTest {
    private fun encode(width: Int, height: Int, format: String, alpha: Boolean): ByteArray {
        val image = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.paint = java.awt.GradientPaint(0f, 0f, Color.RED, width.toFloat(), height.toFloat(), Color.BLUE)
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.WHITE
        graphics.fillOval(width / 4, height / 4, width / 2, height / 2)
        graphics.dispose()
        if (alpha) image.setRGB(0, 0, 0)
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    @Test fun `a large PNG is scaled to fit the box and keeps its alpha`() {
        val original = encode(1600, 900, "png", alpha = true)
        val fitted = fitLogo(original, "image/png")
        assertTrue(fitted.size < original.size)
        val image = decode(fitted)
        assertEquals(LOGO_MAX_SIDE, image.width)
        assertEquals(288, image.height)
        assertTrue(image.colorModel.hasAlpha())
    }

    @Test fun `a large JPEG is scaled to fit the box`() {
        val fitted = fitLogo(encode(1000, 1200, "jpeg", alpha = false), "image/jpeg")
        val image = decode(fitted)
        assertEquals(427, image.width)
        assertEquals(LOGO_MAX_SIDE, image.height)
    }

    @Test fun `small images, other types and unreadable bodies are kept as fetched`() {
        val small = encode(400, 200, "png", alpha = true)
        assertSame(small, fitLogo(small, "image/png"))
        val gif = encode(1600, 900, "gif", alpha = false)
        assertSame(gif, fitLogo(gif, "image/gif"))
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()
        assertSame(svg, fitLogo(svg, "image/svg+xml"))
        val broken = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        assertSame(broken, fitLogo(broken, "image/png"))
    }
}

package dev.jamesrich.sofaking.ui

import androidx.compose.ui.graphics.Color
import dev.jamesrich.sofaking.ui.theme.SofaDusk
import dev.jamesrich.sofaking.ui.theme.SofaMauve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmbientColorTest {
    private fun pixel(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `no pixels have no dominant color`() {
        assertNull(dominantColor(IntArray(0)))
    }

    @Test
    fun `fully transparent pixels have no dominant color`() {
        assertNull(dominantColor(IntArray(16) { pixel(200, 30, 30, a = 0) }))
    }

    @Test
    fun `a single color picture gives that color`() {
        val color = assertNotNull(dominantColor(IntArray(64) { pixel(40, 90, 200) }))
        assertEquals(Color(40, 90, 200), color)
    }

    @Test
    fun `the larger color wins`() {
        val pixels = IntArray(100) { if (it < 70) pixel(200, 30, 30) else pixel(30, 30, 200) }
        assertEquals(Color(200, 30, 30), dominantColor(pixels))
    }

    @Test
    fun `a vivid color outweighs a slightly larger gray`() {
        val pixels = IntArray(100) { if (it < 45) pixel(10, 200, 60) else pixel(128, 128, 128) }
        assertEquals(Color(10, 200, 60), dominantColor(pixels))
    }

    @Test
    fun `transparent pixels do not count toward a color`() {
        val pixels = IntArray(100) { if (it < 90) pixel(250, 250, 250, a = 0) else pixel(20, 120, 60) }
        assertEquals(Color(20, 120, 60), dominantColor(pixels))
    }

    @Test
    fun `a bright vivid color is darkened to the luminance cap`() {
        val tint = ambientTint(Color(255, 255, 0))
        assertTrue(contrastRatio(SofaMauve, tint) >= 7f, "mauve over $tint")
    }

    @Test
    fun `mauve and dusk text keep their documented contrast over any tint`() {
        for (r in 0..255 step 17) {
            for (g in 0..255 step 17) {
                for (b in 0..255 step 17) {
                    val tint = ambientTint(Color(r, g, b))
                    assertTrue(contrastRatio(SofaMauve, tint) >= 7f, "mauve over $tint from $r $g $b")
                    assertTrue(contrastRatio(SofaDusk, tint) >= 11f, "dusk over $tint from $r $g $b")
                }
            }
        }
    }

    @Test
    fun `a dark color already inside the limits is unchanged`() {
        val dark = Color(0.04f, 0.05f, 0.08f)
        val tint = ambientTint(dark)
        assertEquals(dark.red, tint.red, 0.001f)
        assertEquals(dark.green, tint.green, 0.001f)
        assertEquals(dark.blue, tint.blue, 0.001f)
    }

    @Test
    fun `black stays black`() {
        assertEquals(Color(0f, 0f, 0f), ambientTint(Color.Black))
    }

    @Test
    fun `saturation is capped and the hue kept`() {
        val tint = ambientTint(Color(0f, 0.2f, 1f))
        assertTrue(tint.blue > tint.green && tint.green > tint.red, "hue order kept in $tint")
        val high = toLinear(tint.blue)
        val saturation = (high - toLinear(tint.red)) / high
        assertTrue(saturation <= MAX_TINT_SATURATION + 0.01f, "saturation $saturation")
    }

    @Test
    fun `the cache keeps what was stored and drops the oldest past its capacity`() {
        val cache = AmbientColorCache(capacity = 2)
        cache["a"] = Color.Red
        cache["b"] = Color.Green
        cache["c"] = Color.Blue
        assertNull(cache["a"])
        assertEquals(Color.Green, cache["b"])
        assertEquals(Color.Blue, cache["c"])
    }

    @Test
    fun `storing a picture again makes it the newest`() {
        val cache = AmbientColorCache(capacity = 2)
        cache["a"] = Color.Red
        cache["b"] = Color.Green
        cache["a"] = Color.Yellow
        cache["c"] = Color.Blue
        assertNull(cache["b"])
        assertEquals(Color.Yellow, cache["a"])
    }
}

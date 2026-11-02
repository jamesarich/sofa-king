package dev.jamesrich.sofaking.ui.visualizer

import dev.jamesrich.sofaking.visualizer.Presets
import kotlin.test.Test
import kotlin.test.assertEquals

class NextLookTest {
    private val looks = Presets.all

    @Test
    fun `the next look follows the current one and wraps round`() {
        assertEquals(looks[1].name, nextLook(looks, looks[0].name))
        assertEquals(looks[0].name, nextLook(looks, looks.last().name))
    }

    @Test
    fun `a look that is not in the list goes to the first`() {
        assertEquals(looks[0].name, nextLook(looks, "Gone"))
    }
}

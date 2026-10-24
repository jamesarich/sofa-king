package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayModesTest {
    private val modes = listOf(
        DisplayMode(1, 3840, 2160, 60f),
        DisplayMode(2, 3840, 2160, 59.94f),
        DisplayMode(3, 3840, 2160, 50f),
        DisplayMode(4, 3840, 2160, 24f),
        DisplayMode(5, 3840, 2160, 23.976f),
        DisplayMode(6, 1920, 1080, 59.94f),
    )

    @Test
    fun `a mode whose refresh rate is a whole multiple of the frame rate fits`() {
        assertEquals(DisplayMode(2, 3840, 2160, 59.94f), matchingDisplayMode(modes, currentId = 1, frameRate = 29.97f))
        assertEquals(DisplayMode(3, 3840, 2160, 50f), matchingDisplayMode(modes, currentId = 1, frameRate = 25f))
        assertEquals(DisplayMode(4, 3840, 2160, 24f), matchingDisplayMode(modes, currentId = 1, frameRate = 24f))
    }

    @Test
    fun `the current mode is kept when it already fits`() {
        assertNull(matchingDisplayMode(modes, currentId = 1, frameRate = 30f))
        assertNull(matchingDisplayMode(modes, currentId = 2, frameRate = 29.97f))
        assertNull(matchingDisplayMode(modes, currentId = 1, frameRate = 60f))
    }

    @Test
    fun `only modes of the current size count and the highest fitting refresh rate wins`() {
        assertEquals(DisplayMode(2, 3840, 2160, 59.94f), matchingDisplayMode(modes, currentId = 5, frameRate = 29.97f))
        assertNull(
            matchingDisplayMode(
                listOf(DisplayMode(1, 3840, 2160, 60f), DisplayMode(6, 1920, 1080, 50f)),
                currentId = 1,
                frameRate = 25f,
            ),
        )
    }

    @Test
    fun `an unknown frame rate or mode leaves the display alone`() {
        assertNull(matchingDisplayMode(modes, currentId = 1, frameRate = null))
        assertNull(matchingDisplayMode(modes, currentId = 1, frameRate = 0f))
        assertNull(matchingDisplayMode(modes, currentId = 99, frameRate = 25f))
    }
}

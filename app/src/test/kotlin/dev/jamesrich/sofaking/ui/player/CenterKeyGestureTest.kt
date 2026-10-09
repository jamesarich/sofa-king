package dev.jamesrich.sofaking.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class CenterKeyGestureTest {
    private val gesture = CenterKeyGesture()

    @Test
    fun `a quick tap is a short press on key-up`() {
        assertEquals(CenterPress.NONE, gesture.onKeyDown(isLongPress = false, repeatCount = 0))
        assertEquals(CenterPress.SHORT, gesture.onKeyUp())
    }

    @Test
    fun `a long press fires once on the long-press key-down and never on key-up`() {
        assertEquals(CenterPress.LONG, gesture.onKeyDown(isLongPress = true, repeatCount = 0))
        assertEquals(CenterPress.NONE, gesture.onKeyDown(isLongPress = true, repeatCount = 1))
        assertEquals(CenterPress.NONE, gesture.onKeyUp())
    }

    @Test
    fun `a held key that repeats counts as a long press`() {
        assertEquals(CenterPress.NONE, gesture.onKeyDown(isLongPress = false, repeatCount = 0))
        assertEquals(CenterPress.LONG, gesture.onKeyDown(isLongPress = false, repeatCount = 1))
        assertEquals(CenterPress.NONE, gesture.onKeyDown(isLongPress = false, repeatCount = 2))
        assertEquals(CenterPress.NONE, gesture.onKeyUp())
    }

    @Test
    fun `key-up without a key-down does nothing`() {
        assertEquals(CenterPress.NONE, gesture.onKeyUp())
    }

    @Test
    fun `a press after a long press is judged on its own`() {
        gesture.onKeyDown(isLongPress = true, repeatCount = 0)
        gesture.onKeyUp()
        gesture.onKeyDown(isLongPress = false, repeatCount = 0)
        assertEquals(CenterPress.SHORT, gesture.onKeyUp())
    }
}

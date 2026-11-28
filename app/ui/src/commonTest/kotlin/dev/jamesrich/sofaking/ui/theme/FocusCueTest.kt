package dev.jamesrich.sofaking.ui.theme

import dev.jamesrich.sofaking.ui.InputKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusCueTest {
    @Test fun `a TV always shows the focused control`() {
        assertTrue(focusCueShown(focused = true, lastInput = null))
        assertFalse(focusCueShown(focused = false, lastInput = null))
    }

    @Test fun `off a TV the cue follows a key and leaves a clicked control plain`() {
        assertTrue(focusCueShown(focused = true, lastInput = InputKind.KEYS))
        assertFalse(focusCueShown(focused = true, lastInput = InputKind.POINTER))
        assertFalse(focusCueShown(focused = false, lastInput = InputKind.KEYS))
    }
}

package dev.jamesrich.sofaking.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionTest {
    @Test
    fun `a motion scale of zero turns motion off`() {
        assertFalse(motionEnabled(0f))
    }

    @Test
    fun `a normal or unset motion scale keeps motion on`() {
        assertTrue(motionEnabled(1f))
        assertTrue(motionEnabled(null))
    }
}

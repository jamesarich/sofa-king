package dev.jamesrich.sofaking.ui.guide

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeldOkGuardTest {
    @Test
    fun `a press that opens nothing passes through`() {
        val guard = HeldOkGuard()
        assertFalse(guard.onKeyDown())
        assertFalse(guard.onKeyUp())
    }

    @Test
    fun `the rest of a held OK that opened the details is swallowed`() {
        val guard = HeldOkGuard()
        assertFalse(guard.onKeyDown())
        guard.arm()
        assertTrue(guard.onKeyDown())
        assertTrue(guard.onKeyUp())
    }

    @Test
    fun `the next press after a swallowed one passes through`() {
        val guard = HeldOkGuard()
        guard.onKeyDown()
        guard.arm()
        guard.onKeyUp()
        assertFalse(guard.onKeyDown())
        assertFalse(guard.onKeyUp())
    }

    @Test
    fun `opening with OK up arms nothing`() {
        val guard = HeldOkGuard()
        guard.arm()
        assertFalse(guard.onKeyDown())
        assertFalse(guard.onKeyUp())
    }
}

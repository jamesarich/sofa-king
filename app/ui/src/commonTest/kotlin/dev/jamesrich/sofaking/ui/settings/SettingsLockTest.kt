package dev.jamesrich.sofaking.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class SettingsLockTest {
    @Test
    fun `five misses in a row start a wait that ends on its own`() {
        val time = TestTimeSource()
        val lock = SettingsLock(time)
        repeat(SettingsLock.MAX_MISSES - 1) { lock.missed() }
        assertFalse(lock.waiting)
        assertEquals(SettingsLock.MAX_MISSES - 1, lock.misses)
        lock.missed()
        assertTrue(lock.waiting)
        assertEquals(0, lock.misses, "the count starts over with the wait")
        time += SettingsLock.WAIT - 1.seconds
        assertTrue(lock.waiting)
        assertTrue(lock.remaining <= 1.seconds)
        time += 1.seconds
        assertFalse(lock.waiting)
    }

    @Test
    fun `the right PIN clears the misses and the wait`() {
        val time = TestTimeSource()
        val lock = SettingsLock(time)
        repeat(SettingsLock.MAX_MISSES) { lock.missed() }
        assertTrue(lock.waiting)
        lock.passed()
        assertFalse(lock.waiting)
        assertEquals(0, lock.misses)
    }

    @Test
    fun `the PIN mask hides every digit`() {
        assertEquals("••••", maskPin("1234"))
        assertEquals("", maskPin(""))
    }
}

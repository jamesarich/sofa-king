package dev.jamesrich.sofaking.backend.logging

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DebugWindowTest {
    private val clock = MutableClock()
    private val scope = TestScope(StandardTestDispatcher())
    private val applied = mutableListOf<String>()
    private var base = "info"
    private val window = DebugWindow(clock, scope, { base }, { applied += it }, Duration.ofMinutes(30))

    @Test fun `the window logs at DEBUG and puts the operator's level back when it ends`() {
        window.open()
        assertEquals(listOf("debug"), applied)
        assertEquals(clock.now.plus(Duration.ofMinutes(30)), window.until)
        assertEquals("debug", window.level("warn"), "a level saved while it is open stays at DEBUG")
        assertEquals("trace", window.level("trace"), "a finer level stays")

        scope.advanceTimeBy(Duration.ofMinutes(30).toMillis() + 1)
        scope.runCurrent()
        assertEquals(listOf("debug", "info"), applied)
        assertNull(window.until)
        assertEquals("warn", window.level("warn"))
        scope.cancel()
    }

    @Test fun `stopping ends it at once, and opening again restarts the clock`() {
        window.open()
        clock.now = clock.now.plus(Duration.ofMinutes(10))
        window.open()
        assertEquals(clock.now.plus(Duration.ofMinutes(30)), assertNotNull(window.until))
        base = "warn"
        window.close()
        assertEquals(listOf("debug", "debug", "warn"), applied)
        window.close()
        assertEquals(3, applied.size, "closing a shut window does nothing")
        scope.cancel()
    }
}

package dev.jamesrich.sofaking.backend.pairing

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingServiceTest {
    @Test fun `a code is six digits, claimed once, and gone after that`() {
        val clock = MutableClock()
        val service = PairingService(clock)
        val issued = service.issue()
        assertEquals(6, issued.code.length)
        assertTrue(issued.code.all { it.isDigit() })
        assertEquals(clock.now.plus(Duration.ofMinutes(10)), issued.expiresAt)
        assertEquals(issued.code, service.current()?.code)
        assertTrue(service.claim(" ${issued.code} "))
        assertFalse(service.claim(issued.code), "a spent code does not open twice")
        assertNull(service.current())
    }

    @Test fun `five wrong claims void the code and a new one replaces it`() {
        val service = PairingService(MutableClock(), maxAttempts = 5)
        val issued = service.issue()
        val wrong = if (issued.code == "000000") "111111" else "000000"
        repeat(4) { assertFalse(service.claim(wrong)) }
        assertEquals(issued.code, service.current()?.code, "four misses leave the code live")
        assertFalse(service.claim(wrong))
        assertNull(service.current(), "the fifth miss voids it")
        assertFalse(service.claim(issued.code))
        val next = service.issue()
        assertTrue(service.claim(next.code))
    }

    @Test fun `a code expires with the clock and issuing again replaces the live one`() {
        val clock = MutableClock()
        val service = PairingService(clock, lifetime = Duration.ofMinutes(10))
        val first = service.issue()
        clock.now = clock.now.plus(Duration.ofMinutes(10))
        assertNull(service.current())
        assertFalse(service.claim(first.code))
        val second = service.issue()
        val third = service.issue()
        assertNotEquals(second, third)
        assertFalse(service.claim(second.code).also { if (second.code == third.code) return })
        assertTrue(service.claim(third.code))
    }
}

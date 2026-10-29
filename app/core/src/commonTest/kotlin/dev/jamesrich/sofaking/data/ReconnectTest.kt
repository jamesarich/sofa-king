package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectTest {
    @Test
    fun `early reconnects come quickly and back off`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L), (0..3).map(::reconnectDelayMs))
    }

    @Test
    fun `later reconnects settle at the cap`() {
        assertEquals(RECONNECT_CAP_MS, reconnectDelayMs(4))
        assertEquals(RECONNECT_CAP_MS, reconnectDelayMs(100))
    }
}

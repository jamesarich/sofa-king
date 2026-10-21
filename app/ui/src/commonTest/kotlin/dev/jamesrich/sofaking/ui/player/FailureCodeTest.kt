package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.SofaKingException
import kotlin.test.assertEquals
import kotlin.test.Test

class FailureCodeTest {
    @Test
    fun `a server error shows its own code`() {
        assertEquals("CHANNEL_NOT_FOUND", failureCode(SofaKingException(404, "CHANNEL_NOT_FOUND", "gone")))
    }

    @Test
    fun `any other failure shows that the session was unavailable`() {
        assertEquals("SESSION_UNAVAILABLE", failureCode(IllegalStateException("timeout")))
    }
}

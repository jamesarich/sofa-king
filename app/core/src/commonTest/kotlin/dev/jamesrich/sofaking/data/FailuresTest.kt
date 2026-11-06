package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals

class FailuresTest {
    private val build = { message: String, signedOut: Boolean -> "$message/$signedOut" }

    @Test
    fun `a rejected token replaces the loaded state`() {
        val rejected = SofaKingException(401, "AUTH_REQUIRED", "no")

        assertEquals("AUTH_REQUIRED: no/true", failedLoad("home", rejected, "loaded", build))
    }

    @Test
    fun `a forbidden answer counts as signed out`() {
        assertEquals("FORBIDDEN: no/true", failedLoad("home", SofaKingException(403, "FORBIDDEN", "no"), null, build))
    }

    @Test
    fun `another failure keeps the loaded state`() {
        assertEquals("loaded", failedLoad("home", SofaKingException(503, "NOT_READY", "no"), "loaded", build))
    }

    @Test
    fun `another failure with nothing loaded is not signed out`() {
        assertEquals("boom/false", failedLoad("home", IllegalStateException("boom"), null, build))
    }
}

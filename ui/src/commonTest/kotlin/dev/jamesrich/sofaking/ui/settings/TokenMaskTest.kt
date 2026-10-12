package dev.jamesrich.sofaking.ui.settings

import kotlin.test.assertEquals
import kotlin.test.Test

class TokenMaskTest {
    @Test
    fun `only the last four characters stay readable`() {
        assertEquals("••••••wxyz", maskToken("abcdefwxyz"))
        assertEquals("•bcde", maskToken("abcde"))
    }

    @Test
    fun `a short token is masked whole`() {
        assertEquals("••••", maskToken("abcd"))
        assertEquals("•", maskToken("a"))
    }

    @Test
    fun `an empty token stays empty so the hint can show`() {
        assertEquals("", maskToken(""))
    }

    @Test
    fun `the masked text keeps the token's length`() {
        val token = "sofa-king-token-2026"
        assertEquals(token.length, maskToken(token).length)
    }
}

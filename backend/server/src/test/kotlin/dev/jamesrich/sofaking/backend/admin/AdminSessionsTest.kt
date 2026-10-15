package dev.jamesrich.sofaking.backend.admin

import kotlin.test.*

class SameSecretTest {
    @Test fun `only the identical string matches, whatever its length`() {
        val expected = "x".repeat(32) + "yz"
        assertTrue(sameSecret(expected, expected))
        assertFalse(sameSecret(null, expected))
        assertFalse(sameSecret("", expected))
        assertFalse(sameSecret(expected.dropLast(1), expected), "a prefix is not the secret")
        assertFalse(sameSecret(expected + "0", expected), "nor is an extension")
        assertFalse(sameSecret(expected.uppercase(), expected), "nor a case change")
        assertFalse(sameSecret("X".repeat(34), expected))
        assertTrue(sameSecret("", ""))
    }
}

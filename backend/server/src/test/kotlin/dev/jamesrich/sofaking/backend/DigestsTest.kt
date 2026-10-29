package dev.jamesrich.sofaking.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DigestsTest {
    @Test fun `hash is the lowercase hex SHA-256 of the UTF-8 bytes`() {
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(abc, hash("abc"))
        assertEquals(abc, hash("abc".toByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash(""))
    }

    @Test fun `randomHex yields two lowercase hex digits per byte`() {
        val value = randomHex(6)
        assertEquals(12, value.length)
        assertTrue(value.all { it in "0123456789abcdef" })
        assertNotEquals(randomHex(16), randomHex(16))
    }

    @Test fun `randomUrlToken is unpadded URL-safe Base64`() {
        val value = randomUrlToken(32)
        assertEquals(43, value.length)
        assertTrue(value.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(randomUrlToken(32), randomUrlToken(32))
    }
}

package dev.jamesrich.sofaking

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtworkRulesTest {
    @Test
    fun `artwork on the configured server is accepted`() {
        assertTrue(fromServer("http://192.168.1.10:8080", "http://192.168.1.10:8080/artwork/a/poster"))
        assertTrue(fromServer("http://192.168.1.10:8080/", "http://192.168.1.10:8080/artwork/a/poster?v=2"))
    }

    @Test
    fun `artwork on another host or port is rejected`() {
        assertFalse(fromServer("http://192.168.1.10:8080", "http://192.168.1.20:8080/artwork/a/poster"))
        assertFalse(fromServer("http://192.168.1.10:8080", "http://192.168.1.10:9090/artwork/a/poster"))
        assertFalse(fromServer("http://192.168.1.10:8080", "https://192.168.1.10:8080/artwork/a/poster"))
    }

    @Test
    fun `artwork is rejected while no server is configured`() {
        assertFalse(fromServer(null, "http://192.168.1.10:8080/artwork/a/poster"))
        assertFalse(fromServer("", "http://192.168.1.10:8080/artwork/a/poster"))
    }

    @Test
    fun `a host with credentials in front of it is not the server`() {
        assertFalse(fromServer("http://sofa.lan", "http://sofa.lan@evil.example/artwork/a/poster"))
        assertNull(originOf("http://user@sofa.lan/x"))
    }

    @Test
    fun `origins ignore case and default ports`() {
        assertEquals("http://sofa.lan", originOf("HTTP://Sofa.LAN:80/artwork"))
        assertEquals("https://sofa.lan", originOf("https://sofa.lan:443"))
        assertEquals("http://sofa.lan:8080", originOf("http://sofa.lan:8080/x"))
        assertNull(originOf("ftp://sofa.lan/x"))
        assertNull(originOf("not a url"))
    }

    @Test
    fun `cleanup keeps downloads in progress and artwork still in the row`() {
        val names = listOf("keep", "old", "wip.part", "old.part")
        assertEquals(listOf("old"), staleArtwork(names, keep = setOf("keep")))
    }

    @Test
    fun `only image content types are cached`() {
        assertTrue(isImage("image/jpeg"))
        assertTrue(isImage(" Image/PNG; charset=binary"))
        assertFalse(isImage("text/html"))
        assertFalse(isImage(null))
    }

    @Test
    fun `a redirect or an oversized or non image response is refused`() {
        assertNull(refusal(200, "image/webp", 1_000))
        assertNull(refusal(200, "image/webp", -1))
        assertEquals("HTTP 302", refusal(302, "image/webp", 1_000))
        assertEquals("not an image", refusal(200, "text/html", 1_000))
        assertEquals("too large", refusal(200, "image/jpeg", MAX_ARTWORK_BYTES + 1))
    }

    @Test
    fun `a copy within the limit is complete`() {
        val out = ByteArrayOutputStream()
        copyLimited(ByteArrayInputStream(ByteArray(20_000) { 7 }), out, limit = 20_000)
        assertContentEquals(ByteArray(20_000) { 7 }, out.toByteArray())
    }

    @Test
    fun `a copy over the limit fails`() {
        assertFailsWith<IOException> {
            copyLimited(ByteArrayInputStream(ByteArray(20_001)), ByteArrayOutputStream(), limit = 20_000)
        }
    }
}

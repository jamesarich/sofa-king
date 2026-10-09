package dev.jamesrich.sofaking.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {
    @Test
    fun `a LAN address with a port is kept as typed`() {
        assertEquals("http://192.168.1.3:9191", ServerAddress.normalizeUrl("http://192.168.1.3:9191"))
    }

    @Test
    fun `whitespace and a trailing slash are dropped`() {
        assertEquals("http://dispatcharr.lan:9191", ServerAddress.normalizeUrl("  http://dispatcharr.lan:9191/  "))
    }

    @Test
    fun `https and an upper-case scheme are accepted`() {
        assertEquals("HTTPS://tv.example.org", ServerAddress.normalizeUrl("HTTPS://tv.example.org"))
    }

    @Test
    fun `a bare host or an empty field is rejected`() {
        assertNull(ServerAddress.normalizeUrl("192.168.1.3:9191"))
        assertNull(ServerAddress.normalizeUrl("dispatcharr.lan"))
        assertNull(ServerAddress.normalizeUrl(""))
        assertNull(ServerAddress.normalizeUrl("   "))
    }

    @Test
    fun `other schemes and a scheme without a host are rejected`() {
        assertNull(ServerAddress.normalizeUrl("ftp://host"))
        assertNull(ServerAddress.normalizeUrl("http://"))
        assertNull(ServerAddress.normalizeUrl("http:///output"))
    }

    @Test
    fun `a profile is trimmed and must be a single path segment`() {
        assertEquals("livingroom", ServerAddress.normalizeProfile(" livingroom "))
        assertNull(ServerAddress.normalizeProfile(""))
        assertNull(ServerAddress.normalizeProfile("living/room"))
    }
}

package dev.jamesrich.sofaking.data

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ServerAddressTest {
    @Test
    fun `a LAN address with a port is kept as typed`() {
        assertEquals("http://192.168.1.3:9193", ServerAddress.normalizeUrl("http://192.168.1.3:9193"))
    }

    @Test
    fun `whitespace and a trailing slash are dropped`() {
        assertEquals("http://sofa.lan:9193", ServerAddress.normalizeUrl("  http://sofa.lan:9193/  "))
    }

    @Test
    fun `https and an upper-case scheme are accepted`() {
        assertEquals("HTTPS://tv.example.org", ServerAddress.normalizeUrl("HTTPS://tv.example.org"))
    }

    @Test
    fun `a bare host or an empty field is rejected`() {
        assertNull(ServerAddress.normalizeUrl("192.168.1.3:9193"))
        assertNull(ServerAddress.normalizeUrl("sofa.lan"))
        assertNull(ServerAddress.normalizeUrl(""))
        assertNull(ServerAddress.normalizeUrl("   "))
    }

    @Test
    fun `other schemes and a scheme without a host are rejected`() {
        assertNull(ServerAddress.normalizeUrl("ftp://host"))
        assertNull(ServerAddress.normalizeUrl("http://"))
        assertNull(ServerAddress.normalizeUrl("http:///api"))
    }

    @Test
    fun `an IPv6 literal with a port and an empty port are accepted`() {
        assertEquals("http://[fd00::1]:9193", ServerAddress.normalizeUrl("http://[fd00::1]:9193"))
        assertEquals("http://sofa.lan:", ServerAddress.normalizeUrl("http://sofa.lan:"))
    }

    @Test
    fun `a host with spaces or an unclosed IPv6 bracket is rejected`() {
        assertNull(ServerAddress.normalizeUrl("http://my server:9193"))
        assertNull(ServerAddress.normalizeUrl("http://[fd00::1:9193"))
        assertNull(ServerAddress.normalizeUrl("http://sofa.lan:91x3"))
    }

    @Test
    fun `a token is trimmed and may be empty`() {
        assertEquals("abc-123_XYZ.~", ServerAddress.normalizeToken("  abc-123_XYZ.~ "))
        assertEquals("", ServerAddress.normalizeToken(""))
        assertEquals("", ServerAddress.normalizeToken("   "))
    }

    @Test
    fun `a token cannot contain spaces control characters or non-ASCII`() {
        assertNull(ServerAddress.normalizeToken("abc 123"))
        assertNull(ServerAddress.normalizeToken("abc\t123"))
        assertNull(ServerAddress.normalizeToken("abc\n123"))
        assertNull(ServerAddress.normalizeToken("tökén"))
    }

    @Test
    fun `base addresses cannot contain credentials queries or fragments`() {
        assertNull(ServerAddress.normalizeUrl("https://user:secret@tv.example"))
        assertNull(ServerAddress.normalizeUrl("https://tv.example?token=secret"))
        assertNull(ServerAddress.normalizeUrl("https://tv.example#fragment"))
        assertNull(ServerAddress.normalizeUrl("http://tv.example:65536"))
    }
}

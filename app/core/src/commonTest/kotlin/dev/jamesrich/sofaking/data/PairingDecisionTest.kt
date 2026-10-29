package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingDecisionTest {
    private fun decide(configured: String, server: String) = decidePairing(configured, PairingRequest(server, "123456"))

    @Test
    fun `a device with no server pairs at once`() {
        assertEquals(PairingDecision.PAIR, decide("", "http://sofa.lan:9193"))
        assertEquals(PairingDecision.PAIR, decide("  ", "https://tv.example.org"))
    }

    @Test
    fun `the saved server pairs at once`() {
        assertEquals(PairingDecision.PAIR, decide("http://sofa.lan:9193", "http://sofa.lan:9193"))
    }

    @Test
    fun `a trailing slash and host case do not make another server`() {
        assertEquals(PairingDecision.PAIR, decide("http://sofa.lan:9193/", "http://sofa.lan:9193"))
        assertEquals(PairingDecision.PAIR, decide("http://SOFA.lan:9193", "http://sofa.LAN:9193/"))
    }

    @Test
    fun `a default port equals none`() {
        assertEquals(PairingDecision.PAIR, decide("https://tv.example.org", "https://tv.example.org:443"))
        assertEquals(PairingDecision.PAIR, decide("http://tv.example.org:80", "http://tv.example.org:80/"))
    }

    @Test
    fun `a bare address equals the same address with its scheme and port`() {
        assertEquals(PairingDecision.PAIR, decide("http://192.168.1.3:9193", "192.168.1.3"))
    }

    @Test
    fun `another host asks`() {
        assertEquals(PairingDecision.ASK, decide("http://sofa.lan:9193", "http://other.example:9193"))
    }

    @Test
    fun `another port asks`() {
        assertEquals(PairingDecision.ASK, decide("http://sofa.lan:9193", "http://sofa.lan:9194"))
    }

    @Test
    fun `http against https asks`() {
        assertEquals(PairingDecision.ASK, decide("http://tv.example.org", "https://tv.example.org"))
        assertEquals(PairingDecision.ASK, decide("https://tv.example.org", "http://tv.example.org"))
    }

    @Test
    fun `a saved address that is not a server asks`() {
        assertEquals(PairingDecision.ASK, decide("not a server", "http://sofa.lan:9193"))
    }

    @Test
    fun `a request naming no usable server is ignored`() {
        assertEquals(PairingDecision.IGNORE, decide("http://sofa.lan:9193", "not a server"))
        assertEquals(PairingDecision.IGNORE, decide("", "http://sofa.lan/api"))
    }

    @Test
    fun `a malformed link yields nothing to decide`() {
        assertNull(parsePairingLink("sofaking://pair?server=http://sofa.lan/api&code=123456"))
        assertNull(parsePairingLink("sofaking://pair?code=123456"))
    }

    @Test
    fun `the label leaves a scheme's own port off`() {
        assertEquals("sofa.lan:9193", serverLabel("http://sofa.lan:9193"))
        assertEquals("tv.example.org", serverLabel("https://tv.example.org:443"))
        assertEquals("tv.example.org", serverLabel("tv.example.org:80"))
    }
}

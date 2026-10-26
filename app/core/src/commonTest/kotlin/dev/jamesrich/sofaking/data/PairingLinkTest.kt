package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingLinkTest {
    @Test
    fun `the portal's link yields the server and the code`() {
        assertEquals(
            PairingRequest("http://192.168.1.3:9193", "123456"),
            parsePairingLink("sofaking://pair?server=http%3A%2F%2F192.168.1.3%3A9193&code=123456"),
        )
        assertEquals(
            PairingRequest("https://tv.example.org", "000001"),
            parsePairingLink(" sofaking://pair?code=000001&server=https://tv.example.org/ "),
        )
        assertEquals(
            PairingRequest("http://192.168.1.3:9193", "123456"),
            parsePairingLink("sofaking://pair?server=192.168.1.3&code=123456"),
            "a bare address is completed as Settings completes it",
        )
    }

    @Test
    fun `other links and unusable values are refused`() {
        assertNull(parsePairingLink("sofaking://channel/abc"))
        assertNull(parsePairingLink("https://example.org/pair?server=http://h&code=123456"))
        assertNull(parsePairingLink("sofaking://pair?server=sofa.lan/api&code=123456"))
        assertNull(parsePairingLink("sofaking://pair?server=http://192.168.1.3:9193&code=12345"))
        assertNull(parsePairingLink("sofaking://pair?server=http://192.168.1.3:9193"))
        assertNull(parsePairingLink(""))
    }

    @Test
    fun `the web app's pairing fragment pairs with the page's own server`() {
        assertEquals(
            PairingRequest("http://192.168.1.3:9193", "123456"),
            parsePairingFragment("#pair=123456", "http://192.168.1.3:9193"),
        )
        assertEquals(
            PairingRequest("https://tv.example.org", "000001"),
            parsePairingFragment("pair=000001", "https://tv.example.org"),
        )
    }

    @Test
    fun `other fragments are not pairing`() {
        assertNull(parsePairingFragment("", "http://192.168.1.3:9193"))
        assertNull(parsePairingFragment("#pair=12345", "http://192.168.1.3:9193"))
        assertNull(parsePairingFragment("#guide", "http://192.168.1.3:9193"))
    }
}

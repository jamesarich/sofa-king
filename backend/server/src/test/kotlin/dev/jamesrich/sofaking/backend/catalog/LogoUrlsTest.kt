package dev.jamesrich.sofaking.backend.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogoUrlsTest {
    @Test fun `logo references become absolute encoded HTTP URLs or nothing`() {
        assertEquals("http://h/logos/a%20b.png", normalizeLogoUrl("http://h/logos/a b.png"))
        assertEquals(
            "http://h/logos/a%20b.png",
            normalizeLogoUrl("  http://h/logos/a%20b.png\n"),
            "existing escapes are kept, whitespace is trimmed",
        )
        assertEquals("http://h/l/caf%C3%A9.png", normalizeLogoUrl("http://h/l/café.png"))
        assertEquals("http://h/x?q=%22a%22&s=1", normalizeLogoUrl("http://h/x?q=\"a\"&s=1"))
        assertEquals(
            "http://h/100%25.png",
            normalizeLogoUrl("http://h/100%.png"),
            "a bare percent is encoded, a valid escape is not",
        )
        assertEquals("http://h:3000/logos/x.png", normalizeLogoUrl("/logos/x.png", "http://h:3000/feeds/default/m3u"))
        assertEquals("http://h:3000/feeds/default/x.png", normalizeLogoUrl("x.png", "http://h:3000/feeds/default/m3u"))
        assertEquals("https://cdn.test/x.png", normalizeLogoUrl("//cdn.test/x.png", "https://h/epg/guide.xml"))
        assertEquals(
            "http://h/logos/a%20b.png",
            normalizeLogoUrl("/logos/a b.png", "http://h/feed one.m3u"),
            "the base is encoded too",
        )
        assertNull(normalizeLogoUrl("/logos/x.png"), "a relative reference without a base means nothing")
        assertNull(normalizeLogoUrl("ftp://h/x.png"))
        assertNull(normalizeLogoUrl("http://user:pw@h/x.png"))
        assertNull(normalizeLogoUrl("http:///x.png"))
        assertNull(normalizeLogoUrl("javascript:alert(1)"))
        assertNull(normalizeLogoUrl(""))
        assertNull(normalizeLogoUrl("   "))
        assertNull(normalizeLogoUrl(null))
        assertNull(normalizeLogoUrl("http://h/" + "x".repeat(2048)))
    }
}

class PercentEncodeTest {
    @Test fun `unreserved and delimiter characters pass, everything else is UTF-8 percent-encoded`() {
        assertEquals("abc-._~:/?#[]@!$&'()*+,;=09", percentEncode("abc-._~:/?#[]@!$&'()*+,;=09"))
        assertEquals("a%20b", percentEncode("a b"))
        assertEquals("%22q%22", percentEncode("\"q\""))
        assertEquals("caf%C3%A9", percentEncode("café"))
        assertEquals("%F0%9F%93%BA", percentEncode("📺"), "a surrogate pair is one code point, four bytes")
        assertEquals("%7F%01", percentEncode("\u007f\u0001"), "control characters are encoded")
    }

    @Test fun `an existing escape is kept while a bare or malformed percent is encoded`() {
        assertEquals("a%20b", percentEncode("a%20b"))
        assertEquals("100%25", percentEncode("100%"))
        assertEquals("%25zz", percentEncode("%zz"))
        assertEquals("%252", percentEncode("%2"), "two hex digits must follow")
        assertEquals("%2F%25", percentEncode("%2F%"))
        assertEquals("", percentEncode(""))
    }
}

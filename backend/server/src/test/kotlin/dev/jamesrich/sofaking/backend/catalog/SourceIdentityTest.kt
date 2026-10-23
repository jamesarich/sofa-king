package dev.jamesrich.sofaking.backend.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SourceIdentityTest {
    @Test fun `source keys drop renewable query parameters and keep identity parameters sorted`() {
        assertEquals("http://192.168.1.3:3000/api/ext/v1/p/a b", sourceKey("HTTP://192.168.1.3:3000/api/ext/v1/p/a%20b?token=abc"))
        assertEquals(sourceKey("http://h/x.m3u8?token=1"), sourceKey("http://h/x.m3u8?token=2"))
        assertNotEquals(sourceKey("http://h/x.m3u8"), sourceKey("http://h/y.m3u8"))
        assertNull(sourceKey("not a url"))
        val dlhd = "http://restream.lan:3000/api/ext/v1/dlhd/https%3A%2F%2Fupstream.example%2Fwatch.php%3Fid%3D521"
        val decoded = dlhd.replace("%3A", ":").replace("%2F", "/").replace("%3F", "?").replace("%3D", "=")
        assertEquals("$decoded?pl=dlhd", sourceKey("$dlhd?token=AAAA&pl=dlhd"))
        assertEquals(sourceKey("$dlhd?token=AAAA&pl=dlhd"), sourceKey("$dlhd?pl=dlhd&Token=BBBB&expires=1&sig=x&key=k"))
        assertNotEquals(sourceKey("$dlhd?pl=dlhd"), sourceKey("$dlhd?pl=other"))
        assertNotEquals(sourceKey("$dlhd?pl=dlhd"), sourceKey("${dlhd.dropLast(1)}2?pl=dlhd"), "the identity inside the encoded path tells sources apart")
        assertEquals(sourceKey("$dlhd?pl=dlhd"), sourceKey("$dlhd?pl=dlhd&token=1", setOf("token")))
        assertEquals(decoded, sourceKey("$dlhd?pl=dlhd&token=1", setOf("token", "pl")))
        assertEquals("http://h/x.m3u8?id=1", sourceKey("http://h/x.m3u8?id=1&token=t"))
        assertFailsWith<IllegalArgumentException> { validateRenewableQuery(listOf("a b")) }
    }
}

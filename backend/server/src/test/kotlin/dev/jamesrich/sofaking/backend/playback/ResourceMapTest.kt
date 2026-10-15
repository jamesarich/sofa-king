package dev.jamesrich.sofaking.backend.playback

import kotlin.test.*

class ResourceMapTest {
    @Test fun `pinned entries never leave while segments evict least recently used beyond the limit`() {
        val map = ResourceMap(segmentLimit = 3)
        map.put("root", "http://o/live.m3u8", pinned = true)
        map.put("s1", "http://o/1.ts", pinned = false)
        map.put("s2", "http://o/2.ts", pinned = false)
        map.put("s3", "http://o/3.ts", pinned = false)
        assertEquals(4, map.size)
        assertEquals("s1", map.tokenFor("http://o/1.ts"), "looking a URL up makes its segment recent")
        map.put("s4", "http://o/4.ts", pinned = false)
        assertEquals(4, map.size, "the fourth segment evicts one")
        assertNull(map["s2"], "the least recently used segment went")
        assertNull(map.tokenFor("http://o/2.ts"), "and its URL no longer resolves to a token")
        assertEquals("http://o/1.ts", map["s1"], "the refreshed segment stayed")
        assertEquals("http://o/4.ts", map["s4"])
        assertEquals("http://o/live.m3u8", map["root"])
        repeat(10) { map.put("x$it", "http://o/x$it.ts", pinned = false) }
        assertEquals("http://o/live.m3u8", map["root"], "pinned entries survive any number of segments")
        assertEquals("root", map.tokenFor("http://o/live.m3u8"))
        assertEquals(4, map.size)
    }

    @Test fun `a URL keeps one token and clear forgets everything`() {
        val map = ResourceMap(segmentLimit = 8)
        map.put("a", "http://o/k.key", pinned = false)
        map.put("b", "http://o/k.key", pinned = false)
        assertEquals("b", map.tokenFor("http://o/k.key"), "the newest token wins the URL")
        assertEquals("http://o/k.key", map["a"], "the older token still resolves until it is evicted")
        map.clear()
        assertEquals(0, map.size)
        assertNull(map["a"])
        assertNull(map.tokenFor("http://o/k.key"))
    }
}

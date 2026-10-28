package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivePlaylistStartTest {
    private fun live(segments: Int) = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n" +
        (0 until segments).joinToString("") { "#EXTINF:2.0,\nseg$it.ts\n" }

    @Test fun `a live playlist waits until it lists three segments`() {
        assertFalse(startsAlready(live(1)))
        assertFalse(startsAlready(live(2)))
        assertTrue(startsAlready(live(3)))
        assertTrue(startsAlready(live(10)))
    }

    @Test fun `a finished or multivariant or unread playlist starts at once`() {
        assertTrue(startsAlready(live(1) + "#EXT-X-ENDLIST\n"))
        assertTrue(startsAlready("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia.m3u8\n"))
        assertTrue(startsAlready(null))
    }
}

package dev.jamesrich.sofaking.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveStartTest {
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

    @Test fun `awaiting segments polls once a second until the playlist lists three`() = runTest {
        var reads = 0
        awaitLiveSegments { live(++reads) }
        assertEquals(3, reads)
    }

    @Test fun `awaiting segments gives up after forty reads of a playlist that never fills`() = runTest {
        var reads = 0
        awaitLiveSegments {
            reads++
            live(1)
        }
        assertEquals(40, reads)
    }
}

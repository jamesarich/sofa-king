package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveBreaksTest {
    /** Six-second segments numbered from 8, the ones numbered 10 and 11 marked as a two-minute break. */
    private val tags = listOf("#EXT-X-TARGETDURATION:6", "#EXT-X-SOFA-AD:FIRST=10,LAST=11,ENDS=120.000")

    private fun at(positionMs: Long, segments: Int = 4, tags: List<String> = this.tags) = adBreakAt(
        tags = tags,
        mediaSequence = 8,
        startsMs = List(segments) { it * 6_000L },
        durationsMs = List(segments) { 6_000L },
        positionMs = positionMs,
    )

    @Test
    fun `a marked segment is a break that ends where the stream planned`() {
        assertEquals(AdBreakNow(remainingMs = 119_000), at(13_000))
    }

    @Test
    fun `the show segment after the run in the window is where the break ends`() {
        assertEquals(AdBreakNow(remainingMs = 11_000), at(13_000, segments = 5))
    }

    @Test
    fun `a show segment is not a break`() {
        assertNull(at(7_000))
        assertNull(at(25_000, segments = 5))
    }

    @Test
    fun `a run with no planned end and no show segment yet has no countdown`() {
        assertEquals(AdBreakNow(remainingMs = null), at(19_000, tags = listOf("#EXT-X-SOFA-AD:FIRST=10,LAST=11")))
    }

    @Test
    fun `a playlist without marks or a position past its window has no break`() {
        assertNull(at(13_000, tags = listOf("#EXT-X-CUE-OUT:120")))
        assertNull(at(24_000))
    }
}

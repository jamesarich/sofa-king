package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.AdBreakNow
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

    /**
     * A five-segment window of six-second segments numbered from [sequence], on a clock where segment 0 starts at 0.
     */
    private fun AdMarks.window(sequence: Long, vararg tags: String) =
        add(tags.toList(), sequence, List(5) { (sequence + it) * 6_000L }, List(5) { 6_000L })

    @Test
    fun `a segment the window slid past is still placed from an earlier window`() {
        val marks = AdMarks()
        marks.window(10, "#EXT-X-SOFA-AD:FIRST=10,LAST=14")
        marks.window(12, "#EXT-X-SOFA-AD:FIRST=12,LAST=16")
        // The picture is in segment 11, which the latest window no longer lists; the show
        // segment after 16 is unknown yet.
        assertEquals(AdBreakNow(remainingMs = null), marks.at(68_000))
        marks.window(13, "#EXT-X-SOFA-AD:FIRST=13,LAST=16")
        assertEquals(AdBreakNow(remainingMs = 34_000), marks.at(68_000))
        assertNull(marks.at(102_000))
    }

    @Test
    fun `runs bridged by a later window become one break`() {
        val marks = AdMarks()
        marks.window(10, "#EXT-X-SOFA-AD:FIRST=10,LAST=11", "#EXT-X-SOFA-AD:FIRST=13,LAST=14")
        assertEquals(AdBreakNow(remainingMs = 6_000), marks.at(66_000))
        marks.window(11, "#EXT-X-SOFA-AD:FIRST=11,LAST=14")
        assertEquals(AdBreakNow(remainingMs = 24_000), marks.at(66_000))
    }

    @Test
    fun `a window that does not meet the history starts it again`() {
        val marks = AdMarks()
        marks.window(10, "#EXT-X-SOFA-AD:FIRST=10,LAST=14")
        marks.window(1)
        assertNull(marks.at(6_000))
        assertNull(marks.at(66_000))
    }

    @Test
    fun `old segments and their runs are dropped past the limit`() {
        val marks = AdMarks(limit = 6)
        marks.window(10, "#EXT-X-SOFA-AD:FIRST=10,LAST=11")
        marks.window(14)
        assertNull(marks.at(60_000))
        assertNull(marks.at(70_000))
        assertNull(marks.at(84_000))
    }
}

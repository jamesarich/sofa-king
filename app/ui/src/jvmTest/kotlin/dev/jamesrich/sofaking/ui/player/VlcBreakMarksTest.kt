package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.contract.AdDateRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VlcBreakMarksTest {
    private val start = "2026-11-04T20:00:00.000Z"

    /** Three 6 s segments from [start], so the live edge is 18 s in, and one break from [from] ms for [lasts] ms. */
    private fun playlist(from: Long, lasts: Long): String {
        val date = java.time.Instant.parse(start).plusMillis(from).toString()
        return """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:1
            #EXT-X-PROGRAM-DATE-TIME:$start
            #EXTINF:6.000,
            a.ts
            #EXTINF:6.000,
            b.ts
            #EXTINF:6.000,
            c.ts
            ${AdDateRange.tag("sofa-ad-1", date, durationMs = lasts)}
        """.trimIndent()
    }

    private fun marks(text: String): VlcBreakMarks =
        VlcBreakMarks(liveDelayMs = 15_000, fetch = { text }).also { it.follow("http://server/live.m3u8") }

    /** Waits for the first read of the playlist, which the break at the picture's time answers for. */
    private fun VlcBreakMarks.awaitBreak(): Any? {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            adBreak()?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    @Test
    fun `the picture is placed by the lead the server reports`() {
        // The break runs 6 to 36 s; a lead of 9 s puts the picture 9 s in, the 15 s default 3 s in, before it.
        val marks = marks(playlist(from = 6_000, lasts = 30_000))
        try {
            marks.lead = 9_000
            assertNotNull(marks.awaitBreak(), "9 s behind an 18 s edge is inside the break")
            marks.lead = 14_000
            assertNull(marks.adBreak(), "14 s behind the edge is before it")
            marks.lead = null
            assertNull(marks.adBreak(), "without a lead the 15 s constant places the picture")
        } finally {
            marks.release()
        }
    }

    @Test
    fun `without a lead the 15 s constant places the picture, and a lead replaces it`() {
        val marks = marks(playlist(from = 0, lasts = 5_000))
        try {
            assertNotNull(marks.awaitBreak(), "15 s behind an 18 s edge is 3 s in, inside a break that ends at 5 s")
            marks.lead = 9_000
            assertNull(marks.adBreak(), "a 9 s lead puts the picture 9 s in, after the break")
        } finally {
            marks.release()
        }
    }
}

package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.AdDateRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class BreakMarksTest {
    private val start = "2026-11-04T20:00:00.000Z"
    private val startMs = Instant.parse(start).toEpochMilliseconds()

    private fun line(id: String = "sofa-ad-10", date: String = start, duration: Long? = null, planned: Long? = null) =
        AdDateRange.tag(id, date, duration, planned)

    @Test
    fun `an ad range with a duration is a mark that ends then`() {
        assertEquals(
            BreakMark("sofa-ad-10", startMs, startMs + 30_000, planned = false),
            parseBreakMark(line(duration = 30_000)),
        )
    }

    @Test
    fun `a planned duration is a planned end`() {
        assertEquals(
            BreakMark("sofa-ad-10", startMs, startMs + 120_000, planned = true),
            parseBreakMark(line(planned = 120_000)),
        )
    }

    @Test
    fun `another class is ignored`() {
        val other = """#EXT-X-DATERANGE:ID="x",CLASS="com.example.other",START-DATE="$start",DURATION=30"""
        assertNull(parseBreakMark(other))
        assertNull(parseBreakMark("#EXT-X-SOFA-AD:FIRST=10,LAST=11"))
    }

    @Test
    fun `a bad start date is ignored`() {
        assertNull(parseBreakMark(line(date = "yesterday", duration = 30_000)))
        val marks = BreakMarks().apply { addLines(listOf(line(date = "yesterday", duration = 30_000))) }
        assertNull(marks.at(startMs + 1_000))
    }

    @Test
    fun `a later window's mark replaces the planned one by id`() {
        val marks = BreakMarks()
        marks.addLines(listOf(line(planned = 120_000)))
        assertEquals(AdBreakNow(110_000), marks.at(startMs + 10_000))
        marks.addLines(listOf(line(duration = 40_000)))
        assertEquals(AdBreakNow(30_000), marks.at(startMs + 10_000))
        assertNull(marks.at(startMs + 50_000))
    }

    @Test
    fun `an instant inside a mark is a break with its remaining time`() {
        val marks = BreakMarks().apply { addLines(listOf(line(duration = 30_000))) }
        assertEquals(AdBreakNow(30_000), marks.at(startMs))
        assertEquals(AdBreakNow(5_000), marks.at(startMs + 25_000))
    }

    @Test
    fun `an instant outside every mark is no break`() {
        val marks = BreakMarks().apply { addLines(listOf(line(duration = 30_000))) }
        assertNull(marks.at(startMs - 1))
        assertNull(marks.at(startMs + 30_000))
    }

    @Test
    fun `a mark with no end has no countdown`() {
        val marks = BreakMarks().apply { addLines(listOf(line())) }
        assertEquals(AdBreakNow(remainingMs = null), marks.at(startMs + 60_000))
    }

    @Test
    fun `an open mark absent from the next refresh stops covering`() {
        val marks = BreakMarks()
        marks.addLines(listOf(line()))
        assertEquals(AdBreakNow(remainingMs = null), marks.at(startMs + 60_000))
        marks.addLines(emptyList())
        assertNull(marks.at(startMs + 60_000))
    }

    @Test
    fun `a renamed open mark does not leave the old one covering`() {
        val marks = BreakMarks()
        marks.addLines(listOf(line(id = "sofa-ad-10")))
        val later = "2026-11-04T20:00:20.000Z"
        marks.addLines(listOf(line(id = "sofa-ad-12", date = later)))
        // The old mark ended where the new one starts; only the new one covers after it, and it has no end.
        assertEquals(AdBreakNow(remainingMs = null), marks.at(startMs + 60_000))
        marks.addLines(listOf(line(id = "sofa-ad-12", date = later, duration = 10_000)))
        assertNull(marks.at(startMs + 60_000))
        assertEquals(AdBreakNow(remainingMs = 5_000), marks.at(startMs + 25_000))
    }

    @Test
    fun `a closed mark still counts after it leaves the refresh until it ends`() {
        val marks = BreakMarks()
        marks.replaceOpen(listOf(BreakMark("a", startMs, startMs + 30_000, planned = false)))
        marks.replaceOpen(listOf(BreakMark("b", startMs + 100_000, null, planned = false)))
        assertEquals(AdBreakNow(5_000), marks.at(startMs + 25_000))
        assertNull(marks.at(startMs + 30_000))
    }

    @Test
    fun `a closed mark wins over an open one that covers the same moment`() {
        val marks = BreakMarks()
        marks.replaceOpen(
            listOf(
                BreakMark("closed", startMs, startMs + 30_000, planned = false),
                BreakMark("open", startMs + 5_000, null, planned = false),
            ),
        )
        assertEquals(AdBreakNow(10_000), marks.at(startMs + 20_000))
    }

    @Test
    fun `marks past the limit are dropped oldest first`() {
        val marks = BreakMarks(limit = 2)
        for (i in 0 until 3) marks.add(BreakMark("m$i", i * 100_000L, i * 100_000L + 10_000, planned = false))
        assertNull(marks.at(5_000))
        assertEquals(AdBreakNow(5_000), marks.at(105_000))
        assertEquals(AdBreakNow(5_000), marks.at(205_000))
    }
}

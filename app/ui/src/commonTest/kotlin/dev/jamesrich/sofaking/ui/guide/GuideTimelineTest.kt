package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Program
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GuideTimelineTest {
    private val base = Instant.parse("2026-10-09T10:00:00Z")
    private val windowEnd = GuideTimeline.windowEnd(base)

    /** Minutes relative to [base]; negative values start before the window. */
    private fun at(minutes: Long): Instant = (base + (minutes * 60).seconds)

    private fun show(title: String, startMin: Long, stopMin: Long, channelId: String = "1") = Program(
        channelId = channelId,
        start = at(startMin),
        stop = at(stopMin),
        title = title,
        description = null,
    )

    private fun cells(vararg programs: Program, end: Instant = windowEnd) =
        GuideTimeline.cellsFor(programs.toList(), base, end)

    private fun assertTiles(cells: List<GuideCell>, from: Long, to: Long) {
        assertEquals(from, cells.first().startMinute)
        assertEquals(to, cells.last().endMinute)
        cells.zipWithNext().forEach { (a, b) -> assertEquals(a.endMinute, b.startMinute, "cells must abut") }
        cells.forEach { assertTrue(it.minutes > 0, "cell ${it.startMinute}..${it.endMinute} is empty") }
    }

    // windowStart

    @Test
    fun `window start floors to the previous half hour`() {
        assertEquals(
            Instant.parse("2026-10-09T10:00:00Z"),
            GuideTimeline.windowStart(Instant.parse("2026-10-09T10:17:42Z")),
        )
        assertEquals(
            Instant.parse("2026-10-09T10:00:00Z"),
            GuideTimeline.windowStart(Instant.parse("2026-10-09T10:29:59Z")),
        )
    }

    @Test
    fun `window start keeps an exact half hour`() {
        assertEquals(
            Instant.parse("2026-10-09T10:30:00Z"),
            GuideTimeline.windowStart(Instant.parse("2026-10-09T10:30:00Z")),
        )
    }

    @Test
    fun `window start floors across midnight and day boundaries`() {
        assertEquals(
            Instant.parse("2026-10-09T23:30:00Z"),
            GuideTimeline.windowStart(Instant.parse("2026-10-10T00:00:00Z") - 1.seconds),
        )
        assertEquals(
            Instant.parse("2026-10-10T00:00:00Z"),
            GuideTimeline.windowStart(Instant.parse("2026-10-10T00:00:01Z")),
        )
    }

    @Test
    fun `window end is 24 hours after start`() {
        assertEquals(Instant.parse("2026-10-10T10:00:00Z"), GuideTimeline.windowEnd(base))
    }

    // windowStartFor

    @Test
    fun `the window is kept while now is inside it`() {
        assertEquals(base, GuideTimeline.windowStartFor(base, base))
        assertEquals(base, GuideTimeline.windowStartFor(base, at(90)))
        assertEquals(base, GuideTimeline.windowStartFor(base, at(1440) - 1.seconds))
    }

    @Test
    fun `a new window starts once now has run past the end`() {
        assertEquals(Instant.parse("2026-10-10T10:00:00Z"), GuideTimeline.windowStartFor(base, at(1440)))
        assertEquals(Instant.parse("2026-10-10T11:30:00Z"), GuideTimeline.windowStartFor(base, at(1440 + 95)))
    }

    @Test
    fun `a clock set back before the window also starts a new one`() {
        assertEquals(Instant.parse("2026-10-09T09:30:00Z"), GuideTimeline.windowStartFor(base, at(-20)))
    }

    // GuideCell.covers

    @Test
    fun `a cell covers its start minute and not its end minute`() {
        val cell = GuideCell(null, 60, 120)
        assertTrue(cell.covers(60))
        assertTrue(cell.covers(119))
        assertEquals(false, cell.covers(120))
        assertEquals(false, cell.covers(59))
        assertEquals(false, cell.covers(null))
    }

    // offsetMinutes

    @Test
    fun `offset is zero at the window start and counts minutes after it`() {
        assertEquals(0L, GuideTimeline.offsetMinutes(base, base))
        assertEquals(90L, GuideTimeline.offsetMinutes(base, at(90)))
    }

    @Test
    fun `offset clamps to the window`() {
        assertEquals(0L, GuideTimeline.offsetMinutes(base, at(-45)))
        assertEquals(1440L, GuideTimeline.offsetMinutes(base, at(1440 + 300)))
    }

    @Test
    fun `offset truncates partial minutes`() {
        assertEquals(1L, GuideTimeline.offsetMinutes(base, (base + (119).seconds)))
    }

    // cellsFor

    @Test
    fun `empty program list yields one no-listing gap spanning the window`() {
        val result = cells()
        assertEquals(1, result.size)
        assertNull(result[0].program)
        assertEquals(0L, result[0].startMinute)
        assertEquals(1440L, result[0].endMinute)
    }

    @Test
    fun `empty or inverted window yields no cells`() {
        assertEquals(emptyList<GuideCell>(), cells(show("A", 0, 30), end = base))
        assertEquals(emptyList<GuideCell>(), cells(show("A", 0, 30), end = at(-60)))
    }

    @Test
    fun `program inside the window is bracketed by gaps`() {
        val result = cells(show("News", 60, 120))
        assertEquals(3, result.size)
        assertNull(result[0].program)
        assertEquals(0L to 60L, result[0].startMinute to result[0].endMinute)
        assertEquals("News", result[1].program?.title)
        assertEquals(60L to 120L, result[1].startMinute to result[1].endMinute)
        assertNull(result[2].program)
        assertEquals(120L to 1440L, result[2].startMinute to result[2].endMinute)
        assertTiles(result, 0, 1440)
    }

    @Test
    fun `program starting before the window is clipped to its start`() {
        val result = cells(show("Late night", -30, 60))
        assertEquals("Late night", result[0].program?.title)
        assertEquals(0L to 60L, result[0].startMinute to result[0].endMinute)
        assertTiles(result, 0, 1440)
    }

    @Test
    fun `program ending after the window is clipped to its end`() {
        val result = cells(show("Long film", 1380, 1500))
        assertEquals(2, result.size)
        assertNull(result[0].program)
        assertEquals("Long film", result[1].program?.title)
        assertEquals(1380L to 1440L, result[1].startMinute to result[1].endMinute)
        assertTiles(result, 0, 1440)
    }

    @Test
    fun `program covering the whole window fills it`() {
        val result = cells(show("Marathon", -600, 3000))
        assertEquals(1, result.size)
        assertEquals("Marathon", result[0].program?.title)
        assertEquals(0L to 1440L, result[0].startMinute to result[0].endMinute)
    }

    @Test
    fun `program entirely before the window is dropped`() {
        val result = cells(show("Gone", -120, -1))
        assertEquals(1, result.size)
        assertNull(result[0].program)
        assertTiles(result, 0, 1440)
    }

    @Test
    fun `program entirely after the window is dropped`() {
        val result = cells(show("Tomorrow", 1500, 1560))
        assertEquals(1, result.size)
        assertNull(result[0].program)
        assertTiles(result, 0, 1440)
    }

    @Test
    fun `program ending exactly at the window start is dropped`() {
        val result = cells(show("Just before", -60, 0))
        assertEquals(1, result.size)
        assertNull(result[0].program)
    }

    @Test
    fun `back to back programs leave no gaps`() {
        val result = cells(show("A", 0, 30), show("B", 30, 90), show("C", 90, 1440))
        assertEquals(listOf("A", "B", "C"), result.map { it.program?.title })
        assertTiles(result, 0, 1440)
    }

    @Test
    fun `unsorted programs are placed by start time`() {
        val result = cells(show("Second", 60, 120), show("First", 0, 60))
        assertEquals(listOf("First", "Second"), result.mapNotNull { it.program?.title })
        assertTiles(result, 0, 1440)
    }

    @Test
    fun `overlap keeps the earlier program and trims the later one`() {
        val result = cells(show("Early", 30, 90), show("Overlap", 60, 120))
        assertEquals(listOf("Early", "Overlap"), result.mapNotNull { it.program?.title })
        assertEquals(90L to 120L, result[2].startMinute to result[2].endMinute)
        assertTiles(result, 0, 1440)
    }

    @Test
    fun `a later program fully inside an earlier one is dropped`() {
        val result = cells(show("Long", 0, 120), show("Inside", 30, 60))
        assertEquals(listOf("Long"), result.mapNotNull { it.program?.title })
        assertTiles(result, 0, 1440)
    }

    @Test
    fun `inverted and zero-length programs are dropped`() {
        val result = cells(show("Backwards", 90, 60), show("Instant", 120, 120))
        assertEquals(1, result.size)
        assertNull(result[0].program)
        assertTiles(result, 0, 1440)
    }

    @Test
    fun `cells tile a shorter window exactly`() {
        val result = cells(show("A", -10, 45), show("B", 45, 500), end = at(60))
        assertEquals(listOf("A", "B"), result.map { it.program?.title })
        assertTiles(result, 0, 60)
    }

    @Test
    fun `program keeps the channel id it was given`() {
        val result = cells(show("A", 0, 30, channelId = "7"))
        assertEquals("7", result.first { it.program != null }.program?.channelId)
    }
}

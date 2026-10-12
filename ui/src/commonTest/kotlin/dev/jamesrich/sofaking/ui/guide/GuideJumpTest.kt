package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Programme
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GuideJumpTest {
    private val base = Instant.parse("2026-10-09T10:00:00Z")

    private fun programme(startMin: Long, stopMin: Long) = Programme(
        channelId = "1",
        start = base.plusSeconds(startMin * 60),
        stop = base.plusSeconds(stopMin * 60),
        title = "Show",
        description = null,
    )

    // cellIndexAt

    @Test
    fun `cell index is found by minute and -1 outside the cells`() {
        val cells = GuideTimeline.cellsFor(listOf(programme(60, 120)), base, base.plusSeconds(1440 * 60))
        assertEquals(0, GuideTimeline.cellIndexAt(cells, 0))
        assertEquals(1, GuideTimeline.cellIndexAt(cells, 60))
        assertEquals(1, GuideTimeline.cellIndexAt(cells, 119))
        assertEquals(2, GuideTimeline.cellIndexAt(cells, 120))
        assertEquals(-1, GuideTimeline.cellIndexAt(cells, 1440))
    }

    // cellAfterUpdate

    @Test
    fun `a filled-in row replaces the gap cell with the programme under the cursor`() {
        val window = base.plusSeconds(1440 * 60)
        val before = GuideTimeline.cellsFor(emptyList(), base, window).single()
        val after = GuideTimeline.cellsFor(listOf(programme(0, 15), programme(15, 60)), base, window)
        assertEquals(after[1], GuideTimeline.cellAfterUpdate(after, before, cursor = 25))
        assertEquals(after[0], GuideTimeline.cellAfterUpdate(after, before, cursor = 0))
    }

    @Test
    fun `a cursor outside the previous cell falls back to the cell at its start`() {
        val window = base.plusSeconds(1440 * 60)
        val before = GuideCell(programme(60, 120), 60, 120)
        val after = GuideTimeline.cellsFor(listOf(programme(0, 90), programme(90, 150)), base, window)
        assertEquals(after[0], GuideTimeline.cellAfterUpdate(after, before, cursor = 500))
    }

    @Test
    fun `an emptied row has no cell to stand in`() {
        val before = GuideCell(programme(60, 120), 60, 120)
        assertEquals(null, GuideTimeline.cellAfterUpdate(emptyList(), before, cursor = 70))
    }

    // visibleSpan

    @Test
    fun `visible span at the start of the window`() {
        assertEquals(0L to 100L, GuideTimeline.visibleSpan(0f, 600f, 6f))
    }

    @Test
    fun `visible span includes partly visible minutes at both edges`() {
        assertEquals(60L to 161L, GuideTimeline.visibleSpan(362f, 600f, 6f))
    }

    @Test
    fun `visible span clamps to the window`() {
        assertEquals(1400L to 1440L, GuideTimeline.visibleSpan(8400f, 1000f, 6f))
    }
}

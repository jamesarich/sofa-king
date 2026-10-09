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
        subtitle = null,
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

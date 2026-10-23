package dev.jamesrich.sofaking.ui.guide

import androidx.compose.runtime.Immutable
import dev.jamesrich.sofaking.data.GuideWindow
import dev.jamesrich.sofaking.data.Program
import kotlin.time.Instant
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A span of one guide row, in whole minutes from the window start. [program] is null for a gap. */
@Immutable
internal data class GuideCell(
    val program: Program?,
    val startMinute: Long,
    val endMinute: Long,
) {
    val minutes: Long get() = endMinute - startMinute

    /** True when [minute] (null for "outside the window") falls inside this cell. */
    fun covers(minute: Long?): Boolean = minute != null && minute >= startMinute && minute < endMinute
}

internal object GuideTimeline {
    const val SLOT_MINUTES = GuideWindow.SLOT_MINUTES
    const val WINDOW_MINUTES = GuideWindow.WINDOW_MINUTES

    /** Floors [now] to the previous half hour on the UTC epoch grid. */
    fun windowStart(now: Instant): Instant = GuideWindow.start(now)

    fun windowEnd(windowStart: Instant): Instant = GuideWindow.end(windowStart)

    /**
     * The window to show at [now] given the [current] one: the same window while [now] is inside it, so the
     * grid never shifts under the user, and a fresh one once the clock has left it either way.
     */
    fun windowStartFor(current: Instant, now: Instant): Instant =
        if (now >= current && now < windowEnd(current)) current else windowStart(now)

    /** Whole minutes from [windowStart] to [instant], clamped to 0..[WINDOW_MINUTES]. */
    fun offsetMinutes(windowStart: Instant, instant: Instant): Long =
        (instant - windowStart).inWholeMinutes.coerceIn(0L, WINDOW_MINUTES)


    /** Index of the cell covering [minute], or -1 when none does. */
    fun cellIndexAt(cells: List<GuideCell>, minute: Long): Int =
        cells.indexOfFirst { minute >= it.startMinute && minute < it.endMinute }

    /**
     * The cell standing in for [previous] once its row's programs changed: the one covering [cursor] when
     * the cursor was inside [previous] (focus keeps it there), else the one at the previous cell's start.
     * Null when the new [cells] are empty.
     */
    fun cellAfterUpdate(cells: List<GuideCell>, previous: GuideCell, cursor: Long): GuideCell? {
        val minute = if (previous.covers(cursor)) cursor else previous.startMinute
        return cells.getOrNull(cellIndexAt(cells, minute))
    }

    /**
     * The window minutes visible in a strip scrolled by [scrollPx] and [viewportPx] wide, where
     * [pxPerMinute] is the width of one minute. Partly visible minutes at either edge count as visible.
     */
    fun visibleSpan(scrollPx: Float, viewportPx: Float, pxPerMinute: Float): Pair<Long, Long> {
        val start = floor(scrollPx / pxPerMinute).toLong().coerceIn(0L, WINDOW_MINUTES)
        val end = ceil((scrollPx + viewportPx) / pxPerMinute).toLong().coerceIn(0L, WINDOW_MINUTES)
        return start to end
    }

    /**
     * Covers `[windowStart, windowEnd)` with cells that tile it exactly, in order.
     *
     * Programs are taken in start order and clipped to the window. Overlap rule: the
     * earlier-starting program keeps its full time; a later one is trimmed to begin where
     * the previous cell ends and is dropped if nothing is left. Empty or inverted programs
     * are dropped. Times are compared in whole minutes, so seconds are truncated.
     */
    fun cellsFor(programs: List<Program>, windowStart: Instant, windowEnd: Instant): List<GuideCell> {
        val total = offsetMinutes(windowStart, windowEnd)
        if (total == 0L) return emptyList()

        val cells = mutableListOf<GuideCell>()
        var cursor = 0L
        for (program in programs.sortedBy { it.start }) {
            val start = max(offsetMinutes(windowStart, program.start), cursor)
            val end = min(offsetMinutes(windowStart, program.stop), total)
            if (end <= start) continue
            if (start > cursor) cells += GuideCell(null, cursor, start)
            cells += GuideCell(program, start, end)
            cursor = end
        }
        if (cursor < total) cells += GuideCell(null, cursor, total)
        return cells
    }
}

package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Programme
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A span of one guide row, in whole minutes from the window start. [programme] is null for a gap. */
internal data class GuideCell(
    val programme: Programme?,
    val startMinute: Long,
    val endMinute: Long,
) {
    val minutes: Long get() = endMinute - startMinute
}

internal object GuideTimeline {
    const val SLOT_MINUTES = 30L
    const val WINDOW_MINUTES = 1440L

    /** Floors [now] to the previous half hour on the UTC epoch grid. */
    fun windowStart(now: Instant): Instant {
        val slotSeconds = SLOT_MINUTES * 60
        return Instant.ofEpochSecond(Math.floorDiv(now.epochSecond, slotSeconds) * slotSeconds)
    }

    fun windowEnd(windowStart: Instant): Instant = windowStart.plusSeconds(WINDOW_MINUTES * 60)

    /** Whole minutes from [windowStart] to [instant], clamped to 0..[WINDOW_MINUTES]. */
    fun offsetMinutes(windowStart: Instant, instant: Instant): Long =
        Duration.between(windowStart, instant).toMinutes().coerceIn(0L, WINDOW_MINUTES)

    /** The start of the half hour containing [minute], in window minutes. */
    fun slotFloor(minute: Long): Long = minute.coerceIn(0L, WINDOW_MINUTES) / SLOT_MINUTES * SLOT_MINUTES

    /**
     * The window minute a time jump lands on: [fromMinute] moved by [deltaMinutes], kept between the
     * half hour containing [nowMinute] and the last slot of the window, so the target always has a cell.
     */
    fun jumpStartMinute(fromMinute: Long, deltaMinutes: Long, nowMinute: Long): Long {
        val lastSlot = WINDOW_MINUTES - SLOT_MINUTES
        val low = min(slotFloor(nowMinute), lastSlot)
        return (fromMinute + deltaMinutes).coerceIn(low, lastSlot)
    }

    /** Index of the cell covering [minute], or -1 when none does. */
    fun cellIndexAt(cells: List<GuideCell>, minute: Long): Int =
        cells.indexOfFirst { minute >= it.startMinute && minute < it.endMinute }

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
     * Programmes are taken in start order and clipped to the window. Overlap rule: the
     * earlier-starting programme keeps its full time; a later one is trimmed to begin where
     * the previous cell ends and is dropped if nothing is left. Empty or inverted programmes
     * are dropped. Times are compared in whole minutes, so seconds are truncated.
     */
    fun cellsFor(programmes: List<Programme>, windowStart: Instant, windowEnd: Instant): List<GuideCell> {
        val total = offsetMinutes(windowStart, windowEnd)
        if (total == 0L) return emptyList()

        val cells = mutableListOf<GuideCell>()
        var cursor = 0L
        for (programme in programmes.sortedBy { it.start }) {
            val start = max(offsetMinutes(windowStart, programme.start), cursor)
            val end = min(offsetMinutes(windowStart, programme.stop), total)
            if (end <= start) continue
            if (start > cursor) cells += GuideCell(null, cursor, start)
            cells += GuideCell(programme, start, end)
            cursor = end
        }
        if (cursor < total) cells += GuideCell(null, cursor, total)
        return cells
    }
}

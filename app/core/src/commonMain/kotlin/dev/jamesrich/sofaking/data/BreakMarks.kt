package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.AdDateRange
import kotlin.time.Instant

/**
 * An ad break on the playlist's program date time clock, in epoch milliseconds. [endMs] is null while the break's
 * length is unknown; [planned] marks an end that came from the stream's planned duration, which a later window's
 * [id] may replace with the real one.
 */
data class BreakMark(val id: String, val startMs: Long, val endMs: Long?, val planned: Boolean)

/** The mark [line] holds, or null when it is not an ad `#EXT-X-DATERANGE` or its start date does not read. */
fun parseBreakMark(line: String): BreakMark? {
    val range = AdDateRange.parse(line) ?: return null
    val startMs = runCatching { Instant.parse(range.startDate).toEpochMilliseconds() }.getOrNull() ?: return null
    return when {
        range.durationMs != null -> BreakMark(range.id, startMs, startMs + range.durationMs!!, planned = false)

        range.plannedDurationMs != null ->
            BreakMark(range.id, startMs, startMs + range.plannedDurationMs!!, planned = true)

        else -> BreakMark(range.id, startMs, null, planned = false)
    }
}

/**
 * The ad marks of a live playlist's recent windows, kept by id so each refresh updates a break in place. A refresh
 * carries every mark the playlist holds now, so a mark with no end (or only a planned one) that a refresh no longer
 * carries has ended: it stops covering at the newest start seen, and a stale mark never covers forever.
 */
class BreakMarks(private val limit: Int = DEFAULT_LIMIT) {
    private val marks = LinkedHashMap<String, BreakMark>()

    /** A refresh whose playlist [tags] hold these marks (see [replaceOpen]). */
    fun addLines(tags: List<String>) = replaceOpen(tags.mapNotNull(::parseBreakMark))

    /**
     * Takes the marks of one refresh, all those the playlist holds now: each replaces the mark with its id, and an
     * open or planned mark of an earlier refresh that is not among them ends where the newest mark starts. A closed
     * mark stays until it ages out.
     */
    fun replaceOpen(current: List<BreakMark>) {
        val newestStart = (marks.values.asSequence() + current.asSequence()).maxOfOrNull { it.startMs }
        val present = current.mapTo(HashSet()) { it.id }
        for (mark in marks.values.toList()) {
            if (mark.id in present || (mark.endMs != null && !mark.planned)) continue
            val end = maxOf(mark.startMs, minOf(newestStart ?: mark.startMs, mark.endMs ?: Long.MAX_VALUE))
            if (end <= mark.startMs) marks.remove(mark.id) else marks[mark.id] = mark.copy(endMs = end, planned = false)
        }
        current.forEach(::add)
    }

    /** Adds [mark], replacing the one with its id, and drops the oldest marks past the limit. */
    fun add(mark: BreakMark) {
        marks.remove(mark.id)
        marks[mark.id] = mark
        while (marks.size > limit) marks.remove(marks.keys.first())
    }

    /** The break covering [wallClockMs], with the time it has left, or null outside every mark. */
    fun at(wallClockMs: Long): AdBreakNow? {
        val covering = marks.values.filter { wallClockMs >= it.startMs && (it.endMs == null || wallClockMs < it.endMs) }
        val mark = covering.lastOrNull { it.endMs != null } ?: covering.lastOrNull() ?: return null
        return AdBreakNow(mark.endMs?.let { it - wallClockMs })
    }

    private companion object {
        const val DEFAULT_LIMIT = 64
    }
}

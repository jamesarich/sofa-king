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

/** The ad marks of a live playlist's recent windows, kept by id so each refresh updates a break in place. */
class BreakMarks(private val limit: Int = DEFAULT_LIMIT) {
    private val marks = LinkedHashMap<String, BreakMark>()

    /** Adds the marks among a window's playlist [tags]. */
    fun addLines(tags: List<String>) {
        for (tag in tags) parseBreakMark(tag)?.let(::add)
    }

    /** Adds [mark], replacing the one with its id, and drops the oldest marks past the limit. */
    fun add(mark: BreakMark) {
        marks.remove(mark.id)
        marks[mark.id] = mark
        while (marks.size > limit) marks.remove(marks.keys.first())
    }

    /** The break covering [wallClockMs], with the time it has left, or null outside every mark. */
    fun at(wallClockMs: Long): AdBreakNow? {
        val mark = marks.values.lastOrNull { wallClockMs >= it.startMs && (it.endMs == null || wallClockMs < it.endMs) }
            ?: return null
        return AdBreakNow(mark.endMs?.let { it - wallClockMs })
    }

    private companion object {
        const val DEFAULT_LIMIT = 64
    }
}

package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.contract.AdRun
import dev.jamesrich.sofaking.data.AdBreakNow
import kotlin.math.abs

/**
 * Whether the segment playing at [positionMs] is an ad, from the server's [AdRun] marks among a live media playlist's
 * [tags]. [mediaSequence] numbers the first of the playlist's segments, which start at [startsMs] and last
 * [durationsMs], on the same clock as [positionMs]. One window only; a player keeps [AdMarks] across refreshes.
 */
internal fun adBreakAt(
    tags: List<String>,
    mediaSequence: Long,
    startsMs: List<Long>,
    durationsMs: List<Long>,
    positionMs: Long,
): AdBreakNow? = AdMarks().apply { add(tags, mediaSequence, startsMs, durationsMs) }.at(positionMs)

/**
 * The segments and [AdRun] marks of a live playlist's recent windows, on the player's own clock. Media3 can play a
 * segment some way behind the window's first one once a refresh has slid past it (Pluto's window holds only about
 * five), so one window is not enough to place the picture. A window that does not meet the history (a new session
 * restarted the numbering, or the clock moved) starts it again. A break ends where the next show segment starts when
 * a window has held it, else where the stream planned.
 */
internal class AdMarks(private val limit: Int = 256) {
    private class Run(val first: Long, val last: Long, val endMs: Long?)

    private var first = 0L
    private val starts = ArrayList<Long>()
    private val durations = ArrayList<Long>()
    private val runs = ArrayList<Run>()

    /** Adds one window: its [tags], its segments numbered from [mediaSequence], starting at [startsMs] and lasting [durationsMs]. */
    fun add(tags: List<String>, mediaSequence: Long, startsMs: List<Long>, durationsMs: List<Long>) {
        if (startsMs.isEmpty() || startsMs.size != durationsMs.size) return
        val offset = mediaSequence - first
        val meets = starts.isNotEmpty() && offset in 0..starts.size &&
            (offset == starts.size.toLong() || abs(starts[offset.toInt()] - startsMs[0]) <= SLIP_MS)
        if (!meets) {
            starts.clear()
            durations.clear()
            runs.clear()
            first = mediaSequence
        }
        val at = (mediaSequence - first).toInt()
        for (i in startsMs.indices) {
            if (at + i < starts.size) {
                starts[at + i] = startsMs[i]
                durations[at + i] = durationsMs[i]
            } else {
                starts += startsMs[i]
                durations += durationsMs[i]
            }
        }
        for (run in tags.mapNotNull(AdRun::parse)) remember(run)
        val drop = starts.size - limit
        if (drop > 0) {
            starts.subList(0, drop).clear()
            durations.subList(0, drop).clear()
            first += drop
            runs.removeAll { it.last < first }
        }
    }

    /** The break playing at [positionMs], or null outside one or outside what the windows held. */
    fun at(positionMs: Long): AdBreakNow? {
        val index = starts.indices.lastOrNull { starts[it] <= positionMs } ?: return null
        if (index == starts.lastIndex && positionMs >= starts[index] + durations[index]) return null
        val sequence = first + index
        val run = runs.firstOrNull { sequence in it.first..it.last } ?: return null
        val endMs = starts.getOrNull((run.last + 1 - first).toInt()) ?: run.endMs
        return AdBreakNow(endMs?.let { (it - positionMs).coerceAtLeast(0) })
    }

    /** Joins [run] with the runs it overlaps or follows on from; the latest planned end wins. */
    private fun remember(run: AdRun) {
        val endMs = run.endsAfterMs?.let { ends -> starts.getOrNull((run.first - first).toInt())?.plus(ends) }
        val touching = runs.filter { it.last + 1 >= run.first && run.last + 1 >= it.first }
        runs.removeAll(touching)
        runs += Run(
            first = minOf(run.first, touching.minOfOrNull { it.first } ?: run.first),
            last = maxOf(run.last, touching.maxOfOrNull { it.last } ?: run.last),
            endMs = endMs ?: touching.lastOrNull { it.endMs != null }?.endMs,
        )
    }
}

/** How far a segment's start may move between windows and still be the same clock. */
private const val SLIP_MS = 1_000L

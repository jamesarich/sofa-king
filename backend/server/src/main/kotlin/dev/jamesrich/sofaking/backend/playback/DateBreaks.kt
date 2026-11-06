package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.contract.AdRun

/** How many refreshes a closed break's date range stays in the playlist after its last segment leaves the window. */
private const val KEEP_CLOSED = 2

/** The most date ranges one timeline keeps. */
private const val DATE_BREAK_LIMIT = 16

/**
 * The breaks of one [AdTimeline] announced as date ranges, each with the ID and start date it had when its run first
 * appeared, however far the window slides after that. A run that overlaps or follows on from a break is the same
 * break. A break's end, once known, is fixed, and its closed range stays in the playlist for [KEEP_CLOSED] refreshes
 * after its last segment leaves the window, so a player sees it close.
 */
internal class DateBreaks(private val timeline: AdTimeline) {
    private class Tracked(val id: String, val startMs: Long, val first: Long, var last: Long) {
        var endMs: Long? = null
        var plannedEndMs: Long? = null

        /** The refreshes since the break closed and its last segment left the window. */
        var goneRefreshes = 0
    }

    private val tracked = ArrayList<Tracked>()

    /** The date ranges to announce for [runs], the ad runs of this refresh's [window]. */
    fun track(runs: List<AdRun>, window: List<AdBreaks.Segment>): List<AdBreaks.Range> {
        val seen = HashSet<Tracked>()
        for (run in runs) seen += follow(run, window) ?: continue
        val oldest = window.firstOrNull()?.sequence
        val iterator = tracked.iterator()
        while (iterator.hasNext()) {
            val one = iterator.next()
            if (one.endMs == null && one !in seen) {
                one.endMs = timeline.dateOf(one.last + 1, window) ?: one.plannedEndMs ?: one.startMs
            }
            if (one.endMs != null && oldest != null && one.last < oldest) one.goneRefreshes++
            if (one.goneRefreshes > KEEP_CLOSED) iterator.remove()
        }
        while (tracked.size > DATE_BREAK_LIMIT) tracked.removeAt(0)
        return tracked.map { range(it) }
    }

    fun clear() = tracked.clear()

    /** The break [run] belongs to, updated with what the window says about it; null when it has no date to start on. */
    private fun follow(run: AdRun, window: List<AdBreaks.Segment>): Tracked? {
        val one = tracked.lastOrNull {
            val adjacent = if (it.endMs == null) 1L else 0L
            run.first <= it.last + adjacent && run.last + 1 >= it.first
        } ?: timeline.dateOf(run.first, window)?.let {
            Tracked("sofa-ad-${run.first}", it, run.first, run.last).also(tracked::add)
        }
        if (one != null && one.endMs == null) {
            one.last = maxOf(one.last, run.last)
            val planned = run.endsAfterMs?.let { ends -> timeline.dateOf(run.first, window)?.plus(ends) }
            if (planned != null) one.plannedEndMs = planned
            val next = run.last + 1
            one.endMs = if (timeline.starts.containsKey(next)) timeline.dateOf(next, window) else null
        }
        return one
    }

    private fun range(one: Tracked) = AdBreaks.Range(
        one.id,
        one.startMs,
        one.first,
        one.last,
        durationMs = one.endMs?.let { (it - one.startMs).coerceAtLeast(0) },
        plannedMs = one.plannedEndMs?.let { (it - one.startMs).coerceAtLeast(0) },
    )
}

package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.contract.AdBreakState
import dev.jamesrich.sofaking.contract.AdRun
import java.net.URI
import java.util.TreeMap

/**
 * Where an app session's player is among the ad runs of its live playlists, for the engines that cannot read the
 * [AdRun] marks themselves (AVPlayer, libVLC, a browser's video element). Every media playlist relayed to the session
 * passes through [annotate], which keeps each segment's place on a timeline of the playlist's own; [served] notes each
 * segment the player finished fetching. The player plays the last segment it fetched once the media it has loaded
 * ahead of the picture has played, so [at] counts that much back from the segment's end.
 *
 * Whale and LG put a single `#EXT-X-CUE-OUT:<duration>` before a break and `#EXT-X-CUE-IN` after it, with nothing on
 * the segments between, so a refresh whose window starts inside the break holds no cue at all. The timeline keeps a
 * cue open across refreshes until a `#EXT-X-CUE-IN`, its planned end, or [OPEN_CUE_LIMIT_MS] without one.
 */
class AdClock(private val segmentLimit: Int = 1024) {
    private class Mark(val playlist: Timeline, val sequence: Long)

    /** One media playlist's segments, by media sequence number, with start times counted from the first one seen. */
    private class Timeline {
        val starts = TreeMap<Long, Long>()
        val durations = HashMap<Long, Long>()
        /** The runs seen, by first segment, each with where the stream planned its end on this timeline, when it said. */
        val runs = TreeMap<Long, Run>()
        var lastServed: Long? = null
        var servedAt = 0L
        /** The first segment of a cue break the last refresh left open. */
        var openCue: Long? = null
    }

    private class Run(val first: Long, var last: Long, var plannedEndMs: Long?)

    private val timelines = object : LinkedHashMap<String, Timeline>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Timeline>) = size > TIMELINE_LIMIT
    }
    private val marks = object : LinkedHashMap<String, Mark>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mark>) = size > segmentLimit
    }
    private var servedCount = 0L

    /** [text] with its ad runs marked (see [AdBreaks.annotate]), after noting its segments for [at]. */
    @Synchronized
    fun annotate(text: String, base: URI): String {
        val read = AdBreaks.parse(text, base) ?: return text
        val timeline = timelines.getOrPut(base.toString()) { Timeline() }
        place(timeline, read.segments)
        val parsed = carry(timeline, read)
        val runs = AdBreaks.runs(parsed)
        for (run in runs) remember(timeline, run)
        timeline.openCue = when {
            read.cueOpenAtEnd -> parsed.segments.lastOrNull()?.takeIf { it.ad }?.let { last -> timeline.runs.floorEntry(last.sequence)?.key }
            parsed.segments.lastOrNull()?.ad == true && parsed.segments.none { it.cueIn } -> timeline.openCue
            else -> null
        }
        return AdBreaks.annotate(text, runs)
    }

    /**
     * [parsed] with the segments of a cue break the previous refresh left open marked as ads: from the break's first
     * segment up to a `#EXT-X-CUE-IN`, the break's planned end, or [OPEN_CUE_LIMIT_MS] without either.
     */
    private fun carry(timeline: Timeline, parsed: AdBreaks.Parsed): AdBreaks.Parsed {
        val first = timeline.openCue ?: return parsed
        val run = timeline.runs[first] ?: return parsed
        val runStart = timeline.starts[first] ?: return parsed
        val end = run.plannedEndMs ?: (runStart + OPEN_CUE_LIMIT_MS)
        var open = true
        var changed = false
        val plannedEnd = HashMap(parsed.plannedEndMs)
        val segments = ArrayList<AdBreaks.Segment>(parsed.segments.size)
        for ((index, segment) in parsed.segments.withIndex()) {
            if (segment.cueIn) open = false
            val at = timeline.starts[segment.sequence]
            if (!open || segment.ad || segment.sequence < first || at == null || at >= end) {
                segments += segment
                continue
            }
            // A run that starts here in this window counts down to the end the cue planned.
            if (segments.lastOrNull()?.ad != true) run.plannedEndMs?.let { plannedEnd[index] = it - at }
            segments += segment.copy(ad = true)
            changed = true
        }
        return if (changed) AdBreaks.Parsed(segments, plannedEnd, parsed.cueOpenAtEnd) else parsed
    }

    /** The player finished fetching [url]; a segment of a playlist seen here becomes the one it plays last. */
    @Synchronized
    fun served(url: String) {
        val mark = marks[url] ?: return
        mark.playlist.lastServed = mark.sequence
        mark.playlist.servedAt = ++servedCount
    }

    /** The break playing now for a player with [aheadMs] of media loaded past the picture; not in one when unknown. */
    @Synchronized
    fun at(aheadMs: Long): AdBreakState {
        // Audio renditions are fetched beside the video: the marked playlist served lately is the one playing.
        val timeline = timelines.values.filter { it.runs.isNotEmpty() && it.lastServed != null && servedCount - it.servedAt < RECENT_SERVES }
            .maxByOrNull { it.servedAt } ?: return AdBreakState()
        val last = timeline.lastServed ?: return AdBreakState()
        val lastStart = timeline.starts[last] ?: return AdBreakState()
        val position = lastStart + (timeline.durations[last] ?: 0L) - aheadMs.coerceAtLeast(0L)
        // Starts grow with the sequence number, so the segment on screen is the last one starting at or before the position.
        val (sequence, start) = timeline.starts.descendingMap().entries.firstOrNull { it.value <= position }?.let { it.key to it.value }
            ?: return AdBreakState()
        if (position >= start + (timeline.durations[sequence] ?: 0L)) return AdBreakState()
        val run = timeline.runs.floorEntry(sequence)?.value?.takeIf { sequence <= it.last } ?: return AdBreakState()
        val endMs = timeline.starts[run.last + 1] ?: run.plannedEndMs
        return AdBreakState(inBreak = true, remainingMs = endMs?.let { (it - position).coerceAtLeast(0L) })
    }

    /** Extends [timeline] with [segments]; a window that no longer meets what was seen starts it again. */
    private fun place(timeline: Timeline, segments: List<AdBreaks.Segment>) {
        val first = segments.firstOrNull() ?: return
        if (timeline.starts.isNotEmpty() && first.sequence !in timeline.starts && first.sequence != timeline.starts.lastKey() + 1) {
            timeline.starts.clear()
            timeline.durations.clear()
            timeline.runs.clear()
            timeline.lastServed = null
        }
        var start = timeline.starts[first.sequence]
            ?: timeline.starts.lastEntry()?.let { it.value + (timeline.durations[it.key] ?: 0L) }
            ?: 0L
        for (segment in segments) {
            timeline.starts.putIfAbsent(segment.sequence, start)
            start = timeline.starts.getValue(segment.sequence)
            timeline.durations[segment.sequence] = segment.durationMs
            marks[segment.url] = Mark(timeline, segment.sequence)
            start += segment.durationMs
        }
        while (timeline.starts.size > segmentLimit) {
            val oldest = timeline.starts.pollFirstEntry().key
            timeline.durations.remove(oldest)
            timeline.runs.headMap(oldest, true).entries.removeIf { it.value.last <= oldest }
        }
    }

    /** Adds [run], joining a run it overlaps or follows on from; its planned end is kept on the timeline. */
    private fun remember(timeline: Timeline, run: AdRun) {
        val plannedEnd = run.endsAfterMs?.let { ends -> timeline.starts[run.first]?.plus(ends) }
        val joined = timeline.runs.floorEntry(run.last)?.value?.takeIf { it.last + 1 >= run.first }
        if (joined == null) {
            timeline.runs[run.first] = Run(run.first, run.last, plannedEnd)
            return
        }
        joined.last = maxOf(joined.last, run.last)
        if (plannedEnd != null) joined.plannedEndMs = plannedEnd
    }
}

/** The playlists a session keeps a timeline for: its variants and renditions, and a few it switched away from. */
private const val TIMELINE_LIMIT = 16
/** How long a cue break with neither a planned end nor a `#EXT-X-CUE-IN` stays open across refreshes. */
private const val OPEN_CUE_LIMIT_MS = 240_000L
/** How many segments of other playlists may have been served since a marked playlist's last one for it to still be playing. */
private const val RECENT_SERVES = 6

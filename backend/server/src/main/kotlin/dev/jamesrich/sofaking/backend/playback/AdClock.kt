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
 *
 * Many playouts mark nothing in the playlist but keep SCTE-35 cues inside the segments (see [SpliceReader]); [served]
 * takes what a fetched segment carried. A cue's splice time counts from the segment's first frame, so the break lands
 * on the timeline at its own moment and lasts the length the cue gave, until a cue returning to the network, or
 * [OPEN_CUE_LIMIT_MS] when it gave none. The segments it covers are marked from then on.
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
        /** The breaks the cues inside its segments announced, in order. */
        val splices = ArrayList<SplicedBreak>()
    }

    private class Run(val first: Long, var last: Long, var plannedEndMs: Long?)

    /** A break an in-segment cue announced, from [startMs] to [endMs] on its timeline; [planned] when a cue gave the end. */
    private class SplicedBreak(val startMs: Long, var endMs: Long, var planned: Boolean)

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
        val parsed = spliced(timeline, carry(timeline, read))
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

    /** [parsed] with the segments an in-segment cue's break covers marked as ads. */
    private fun spliced(timeline: Timeline, parsed: AdBreaks.Parsed): AdBreaks.Parsed {
        if (timeline.splices.isEmpty()) return parsed
        var changed = false
        val plannedEnd = HashMap(parsed.plannedEndMs)
        val segments = ArrayList<AdBreaks.Segment>(parsed.segments.size)
        for ((index, segment) in parsed.segments.withIndex()) {
            val at = timeline.starts[segment.sequence]
            val covering = at?.let { splicedAt(timeline, it, segment.durationMs) }
            if (segment.ad || covering == null) {
                segments += segment
                continue
            }
            if (segments.lastOrNull()?.ad != true && covering.planned) plannedEnd[index] = covering.endMs - at
            segments += segment.copy(ad = true)
            changed = true
        }
        return if (changed) AdBreaks.Parsed(segments, plannedEnd, parsed.cueOpenAtEnd) else parsed
    }

    /** The spliced break the middle of a segment starting at [startMs] falls in, if any. */
    private fun splicedAt(timeline: Timeline, startMs: Long, durationMs: Long): SplicedBreak? {
        val middle = startMs + durationMs / 2
        return timeline.splices.lastOrNull { middle >= it.startMs && middle < it.endMs }
    }

    /**
     * The player finished fetching [url]; a segment of a playlist seen here becomes the one it plays last, and the
     * cues it carried, if [cues] were read from it, place their breaks on its timeline.
     */
    @Synchronized
    internal fun served(url: String, cues: SegmentCues? = null) {
        val mark = marks[url] ?: return
        mark.playlist.lastServed = mark.sequence
        mark.playlist.servedAt = ++servedCount
        if (cues != null && cues.splices.isNotEmpty()) splice(mark.playlist, mark.sequence, cues)
    }

    private fun splice(timeline: Timeline, sequence: Long, cues: SegmentCues) {
        val segmentStart = timeline.starts[sequence] ?: return
        for (splice in cues.splices) {
            val at = segmentStart + spliceOffsetMs(cues.firstPtsTicks, splice.ptsTicks)
            // A cue is sent again in the segments before its splice, and a break may be named twice; one break stands.
            val current = timeline.splices.lastOrNull()?.takeIf { at >= it.startMs - REPEAT_SLACK_MS && at < it.endMs }
            if (splice.out) {
                if (current == null) timeline.splices += SplicedBreak(at, at + (splice.durationMs ?: OPEN_CUE_LIMIT_MS), splice.durationMs != null)
            } else if (current != null && at > current.startMs) {
                current.endMs = at
                current.planned = true
                // The segments from the return on leave the run they joined while the break was planned longer.
                val back = timeline.starts.entries.firstOrNull { (segment, start) -> start + (timeline.durations[segment] ?: 0L) / 2 >= at }?.key
                if (back != null) {
                    timeline.runs.floorEntry(back)?.value?.takeIf { it.first < back && it.last >= back }?.let {
                        it.last = back - 1
                        it.plannedEndMs = at
                    }
                }
            }
        }
        // The segments already listed join the runs now, for [at]; later ones are marked as their playlists arrive.
        for (spliced in timeline.splices) {
            val inside = timeline.starts.entries.filter { (segment, start) -> splicedAt(timeline, start, timeline.durations[segment] ?: 0L) === spliced }
            val first = inside.firstOrNull() ?: continue
            remember(timeline, AdRun(first.key, inside.last().key, if (spliced.planned) spliced.endMs - first.value else null))
        }
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
            timeline.splices.clear()
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
        timeline.starts.firstEntry()?.let { (_, start) -> timeline.splices.removeIf { it.endMs < start } }
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
/** How close before a break's start a cue still names the same break. */
private const val REPEAT_SLACK_MS = 2_000L
/** How far a cue's splice time may be from its segment's first frame; one further off counts from the segment itself. */
private const val SPLICE_REACH_TICKS = 60L * 90_000

/**
 * How many ms after a segment's first frame, at [firstPtsTicks], a splice at [ptsTicks] falls, on the 33-bit 90 kHz
 * clock that wraps every 26.5 hours; 0 when either is unknown or they are implausibly far apart.
 */
internal fun spliceOffsetMs(firstPtsTicks: Long?, ptsTicks: Long?): Long {
    if (firstPtsTicks == null || ptsTicks == null) return 0L
    var ticks = (ptsTicks - firstPtsTicks) and ((1L shl 33) - 1)
    if (ticks >= 1L shl 32) ticks -= 1L shl 33
    return if (ticks in -SPLICE_REACH_TICKS..SPLICE_REACH_TICKS) ticks / 90 else 0L
}

/** How many segments of other playlists may have been served since a marked playlist's last one for it to still be playing. */
private const val RECENT_SERVES = 6

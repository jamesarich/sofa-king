package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.ads.BreakEntry
import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.contract.AdBreakState
import dev.jamesrich.sofaking.contract.AdRun
import java.util.TreeMap
import kotlin.math.roundToLong

/** How a cue break with neither a planned end nor a `#EXT-X-CUE-IN` stays open across refreshes. */
internal const val OPEN_CUE_LIMIT_MS = 240_000L

/** How close before a break's start a cue still names the same break. */
internal const val REPEAT_SLACK_MS = 2_000L

/** How far apart two silent stretches may be and still be one, over a segment boundary the audio falls short of. */
internal const val JOIN_MS = 500L

/**
 * One media playlist's segments, by media sequence number, with start times counted from the first one seen, and
 * the ad runs and breaks an [AdClock] placed on it. It keeps at most [segmentLimit] segments.
 */
internal class AdTimeline(val key: String, private val segmentLimit: Int) {
    val starts = TreeMap<Long, Long>()
    val durations = HashMap<Long, Long>()

    /** The runs seen, by first segment, each with where the stream planned its end on this timeline, when it said. */
    val runs = TreeMap<Long, Run>()
    var lastServed: Long? = null
    var servedAt = 0L

    /** The first segment of a cue break the last refresh left open. */
    var openCue: Long? = null

    /** The breaks the cues inside its segments announced, in order. */
    val splices = ArrayList<TimedBreak>()

    /** Whether the playlist ever carried a marker; learning stops on it then. */
    var exact = false
        private set

    /** The silent stretches heard and not yet over, by start. */
    val quiet = QuietStretches()

    /** The learned breaks that may be marked, in order. */
    val learnedMarks = ArrayList<TimedBreak>()

    /** The Chromaprint points heard, the marked runs already learned, by first segment, and the entry followed now. */
    val points = PointLog()
    val learnedRuns = HashSet<Long>()
    var following: Following? = null
    var lastLearnedEndMs: Long? = null

    /** The library entry the canary last checked, and where its match ended, so one airing counts once. */
    var canaryEntry: String? = null
    var canaryUntilMs = 0L

    /** The end of the latest segment heard. */
    var heardTo = 0L

    /** For the census: the media fetched, the marked breaks and the breaks learned. */
    var playedMs = 0L
    var breaks = 0
    var learned = 0

    class Run(val first: Long, var last: Long, var plannedEndMs: Long?)

    /** Extends the timeline with [segments]; a window that no longer meets what was seen starts it again. */
    fun place(segments: List<AdBreaks.Segment>) {
        val first = segments.firstOrNull() ?: return
        if (starts.isNotEmpty() && first.sequence !in starts && first.sequence != starts.lastKey() + 1) reset()
        var start = starts[first.sequence]
            ?: starts.lastEntry()?.let { it.value + (durations[it.key] ?: 0L) }
            ?: 0L
        for (segment in segments) {
            starts.putIfAbsent(segment.sequence, start)
            start = starts.getValue(segment.sequence)
            durations[segment.sequence] = segment.durationMs
            start += segment.durationMs
        }
        while (starts.size > segmentLimit) {
            val oldest = starts.pollFirstEntry().key
            durations.remove(oldest)
            runs.headMap(oldest, true).entries.removeIf { it.value.last <= oldest }
        }
        starts.firstEntry()?.let { (_, start) ->
            splices.removeIf { it.endMs < start }
            learnedMarks.removeIf { it.endMs < start }
        }
    }

    private fun reset() {
        starts.clear()
        durations.clear()
        runs.clear()
        splices.clear()
        quiet.clear()
        learnedMarks.clear()
        points.clear()
        learnedRuns.clear()
        following = null
        lastLearnedEndMs = null
        heardTo = 0L
        lastServed = null
    }

    /**
     * The playlist carried a marker: what was being learned on it is dropped, since the markers say where its breaks
     * are.
     */
    fun markExact() {
        exact = true
        quiet.clear()
        learnedMarks.clear()
        following = null
    }

    /** Adds [run], joining a run it overlaps or follows on from; its planned end is kept on the timeline. */
    fun remember(run: AdRun) {
        val plannedEnd = run.endsAfterMs?.let { ends -> starts[run.first]?.plus(ends) }
        val joined = runs.floorEntry(run.last)?.value?.takeIf { it.last + 1 >= run.first }
        if (joined == null) {
            if (exact) breaks++
            runs[run.first] = Run(run.first, run.last, plannedEnd)
            return
        }
        joined.last = maxOf(joined.last, run.last)
        if (plannedEnd != null) joined.plannedEndMs = plannedEnd
    }

    /**
     * The segments already listed that [breaks] cover join the runs now; later ones are marked as their playlists
     * arrive.
     */
    fun join(breaks: List<TimedBreak>) {
        for (timed in breaks) {
            val inside = starts.entries.filter { (segment, start) ->
                breakAt(breaks, start, durations[segment] ?: 0L) === timed
            }
            val first = inside.firstOrNull() ?: continue
            remember(AdRun(first.key, inside.last().key, if (timed.planned) timed.endMs - first.value else null))
        }
    }

    /**
     * [parsed] with the segments of a cue break the previous refresh left open marked as ads: from the break's first
     * segment up to a `#EXT-X-CUE-IN`, the break's planned end, or [OPEN_CUE_LIMIT_MS] without either.
     */
    fun carry(parsed: AdBreaks.Parsed): AdBreaks.Parsed {
        val first = openCue ?: return parsed
        val run = runs[first] ?: return parsed
        val runStart = starts[first] ?: return parsed
        val end = run.plannedEndMs ?: (runStart + OPEN_CUE_LIMIT_MS)
        // The cue closes at the first `#EXT-X-CUE-IN`, and this window's segments before it stay as they were.
        val closesAt = parsed.segments.indexOfFirst { it.cueIn }.takeIf { it >= 0 } ?: parsed.segments.size
        return marked(parsed) { index, segment, at ->
            val insideCue = index < closesAt && segment.sequence >= first && at < end
            // A run that starts here in this window counts down to the end the cue planned.
            if (insideCue) Cover(run.plannedEndMs?.let { it - at }) else null
        }
    }

    /** [parsed] with the segments one of [breaks] covers marked as ads. */
    fun covered(parsed: AdBreaks.Parsed, breaks: List<TimedBreak>): AdBreaks.Parsed {
        if (breaks.isEmpty()) return parsed
        return marked(parsed) { _, segment, at ->
            breakAt(breaks, at, segment.durationMs)?.let { Cover(if (it.planned) it.endMs - at else null) }
        }
    }

    /** A segment [marked] turns into an ad, and the planned end it gives a run that starts there. */
    private class Cover(val endsAfterMs: Long?)

    /**
     * [parsed] with each segment that is not an ad yet but that [cover] covers, given its index and start on the
     * timeline, marked as one; the planned end goes on the first segment of each run.
     */
    private fun marked(parsed: AdBreaks.Parsed, cover: (Int, AdBreaks.Segment, Long) -> Cover?): AdBreaks.Parsed {
        var changed = false
        val plannedEnd = HashMap(parsed.plannedEndMs)
        val segments = ArrayList<AdBreaks.Segment>(parsed.segments.size)
        for ((index, segment) in parsed.segments.withIndex()) {
            val covering = starts[segment.sequence]?.takeUnless { segment.ad }?.let { cover(index, segment, it) }
            if (covering == null) {
                segments += segment
            } else {
                if (segments.lastOrNull()?.ad != true) covering.endsAfterMs?.let { plannedEnd[index] = it }
                segments += segment.copy(ad = true)
                changed = true
            }
        }
        return if (changed) AdBreaks.Parsed(segments, plannedEnd, parsed.cueOpenAtEnd) else parsed
    }

    /** Places the breaks the cues [cues] read from segment [sequence] announce or end. */
    fun splice(sequence: Long, cues: SegmentCues) {
        val segmentStart = starts[sequence] ?: return
        markExact()
        for (splice in cues.splices) {
            val at = segmentStart + spliceOffsetMs(cues.firstPtsTicks, splice.ptsTicks)
            // A cue is sent again in the segments before its splice, and a break may be named twice; one break stands.
            val current = splices.lastOrNull()?.takeIf { at >= it.startMs - REPEAT_SLACK_MS && at < it.endMs }
            if (splice.out && current == null) {
                splices += TimedBreak(at, at + (splice.durationMs ?: OPEN_CUE_LIMIT_MS), splice.durationMs != null)
            } else if (!splice.out && current != null && at > current.startMs) {
                current.endMs = at
                current.planned = true
                endRunAt(at)
            }
        }
        join(splices)
    }

    /** The segments from a return at [at] on leave the run they joined while the break was planned longer. */
    private fun endRunAt(at: Long) {
        val back = starts.entries.firstOrNull { (segment, start) -> start + (durations[segment] ?: 0L) / 2 >= at }?.key
            ?: return
        runs.floorEntry(back)?.value?.takeIf { it.first < back && it.last >= back }?.let {
            it.last = back - 1
            it.plannedEndMs = at
        }
    }

    /** The break playing at [position] on the timeline; not in one when no run covers the segment there. */
    fun stateAt(position: Long): AdBreakState {
        // Starts grow with the sequence number, so the segment on screen is the last one starting at or before it.
        val (sequence, start) = starts.descendingMap().entries.firstOrNull { it.value <= position }
            ?.let { it.key to it.value }
            ?: return AdBreakState()
        val run = runs.floorEntry(sequence)?.value?.takeIf { sequence <= it.last }
        if (run == null || position >= start + (durations[sequence] ?: 0L)) return AdBreakState()
        val endMs = starts[run.last + 1] ?: run.plannedEndMs
        return AdBreakState(inBreak = true, remainingMs = endMs?.let { (it - position).coerceAtLeast(0L) })
    }
}

/** The one of [breaks] the middle of a segment starting at [startMs] falls in, if any. */
private fun breakAt(breaks: List<TimedBreak>, startMs: Long, durationMs: Long): TimedBreak? {
    val middle = startMs + durationMs / 2
    return breaks.lastOrNull { middle >= it.startMs && middle < it.endMs }
}

/**
 * A break placed by time, from [startMs] to [endMs] on its timeline: one an in-segment cue announced, or a learned
 * one; [planned] when its end is known.
 */
internal class TimedBreak(val startMs: Long, var endMs: Long, var planned: Boolean)

/** A library entry a playlist's audio is matching: the entry's point for global point g is g + [shift]. */
internal class Following(
    val entry: BreakEntry,
    val shift: Int,
    val startMs: Long,
    var lastPoint: Long,
    val timed: TimedBreak,
)

/** A timeline's silent stretches heard and not yet over, by start. */
internal class QuietStretches {
    private val byStart = TreeMap<Long, Quiet>()

    val all: Collection<Quiet> get() = byStart.values

    /** Adds the silent stretch from [from] to [to], joining the ones it meets. */
    fun add(from: Long, to: Long) {
        var start = from
        var end = to
        var slate: TimedBreak? = null
        // Segments are mostly heard in order, but one may land after the next; a stretch meets those on either side.
        while (true) {
            val (key, met) = byStart.floorEntry(end + JOIN_MS)?.takeIf { it.value.endMs + JOIN_MS >= start } ?: break
            byStart.remove(key)
            start = minOf(start, met.startMs)
            end = maxOf(end, met.endMs)
            slate = slate ?: met.slate
        }
        byStart[start] = Quiet(start, end, slate)
    }

    fun remove(start: Long) {
        byStart.remove(start)
    }

    fun clear() = byStart.clear()
}

/** A silent stretch on a timeline, and the slate break it became once it lasted [SLATE_MS]. */
internal class Quiet(val startMs: Long, var endMs: Long, var slate: TimedBreak? = null)

/** A playlist's recent Chromaprint points, numbered from the first heard, each with its time on the timeline. */
internal class PointLog {
    private var words = IntArray(1024)
    private var times = LongArray(1024)

    /** The number of the first point kept, and of the point after the last. */
    var first = 0L
        private set
    var end = 0L
        private set

    fun add(points: IntArray, firstMs: Long) {
        for ((k, word) in points.withIndex()) {
            if ((end - first).toInt() == words.size) {
                // The oldest half goes; a library match never reaches back that far.
                val keep = words.size / 2
                words.copyInto(words, 0, words.size - keep)
                times.copyInto(times, 0, times.size - keep)
                first = end - keep
            }
            words[(end - first).toInt()] = word
            times[(end - first).toInt()] = firstMs + (k * BreakLibrary.POINT_MS).roundToLong()
            end++
        }
    }

    fun word(point: Long) = words[(point - first).toInt()]

    fun time(point: Long) = times[(point - first).toInt()]

    val lastTime: Long? get() = if (end > first) time(end - 1) else null

    /** The last [count] points or fewer, with the number of the first. */
    fun recent(count: Int): Pair<IntArray, Long> {
        val from = maxOf(first, end - count)
        return words.copyOfRange((from - first).toInt(), (end - first).toInt()) to from
    }

    /** The points whose times fall from [fromMs] to [toMs]. */
    fun between(fromMs: Long, toMs: Long): IntArray {
        val kept = (first until end).filter { time(it) in fromMs until toMs }
        return IntArray(kept.size) { word(kept[it]) }
    }

    fun clear() {
        first = 0
        end = 0
    }
}

package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.contract.AdBreakState
import dev.jamesrich.sofaking.contract.AdRun
import java.net.URI
import java.util.TreeMap
import java.util.UUID
import kotlin.math.roundToLong

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
 *
 * A playlist that never carried a marker may still be learned from: [heard] takes how loud a fetched segment was, and
 * a stretch of [SLATE_MS] or more below [SILENCE_DB] across its segments is a silent slate, the filler some playouts
 * air when a break goes unsold. [learning] says what to do with one: nothing, report it to [learned] only (shadow), or
 * also mark the segments it covers. A shorter stretch of [SHORT_SLATE_MS] or more is only ever reported. The first
 * marker a playlist carries ends the learning on it, since a marker is exact.
 *
 * With a [library], [heard] also takes the segment's Chromaprint points (see [AudioFollower]). On a marked playlist,
 * each break its markers opened and closed is learned, a few seconds in from each edge, and every match of the
 * library is checked against the markers (the canary, see [BreakLibrary]). On a playlist without markers, a match of
 * [BreakLibrary.MIN_LEARN_POINTS] reaching the newest audio starts a learned break from where the match starts; it
 * follows the entry while the audio keeps matching, and a match just after one ends needs only
 * [CONTINUE_POINTS], since breaks run ad after ad. Only an entry that may blank ([BreakEntry.blanking]) is marked,
 * under [LearnedBreaks.ON], while the canary is not [BreakLibrary.frozen].
 */
class AdClock(
    private val segmentLimit: Int = 1024,
    private val learning: () -> LearnedBreaks = { LearnedBreaks.OFF },
    private val library: BreakLibrary? = null,
    /** The channel and provider the session plays, for the library. */
    private val channelId: String = "",
    private val providerId: String = "",
    private val learned: (LearnedBreak) -> Unit = {},
) {
    /** Names this clock's breaks in the library, so its own are never its canary. */
    private val tag = UUID.randomUUID().toString()
    private class Mark(val playlist: Timeline, val sequence: Long)

    /** One media playlist's segments, by media sequence number, with start times counted from the first one seen. */
    private class Timeline(val key: String) {
        val starts = TreeMap<Long, Long>()
        val durations = HashMap<Long, Long>()

        /**
         * The runs seen, by first segment, each with where the stream planned its end on this timeline, when it said.
         */
        val runs = TreeMap<Long, Run>()
        var lastServed: Long? = null
        var servedAt = 0L

        /** The first segment of a cue break the last refresh left open. */
        var openCue: Long? = null

        /** The breaks the cues inside its segments announced, in order. */
        val splices = ArrayList<TimedBreak>()

        /** Whether the playlist ever carried a marker; learning stops on it then. */
        var exact = false

        /** The silent stretches heard and not yet over, by start. */
        val quiet = TreeMap<Long, Quiet>()

        /** The learned breaks that may be marked, in order. */
        val learnedMarks = ArrayList<TimedBreak>()

        /**
         * The Chromaprint points heard, the marked runs already learned, by first segment, and
         * the library entry followed now.
         */
        val points = PointLog()
        val learnedRuns = HashSet<Long>()
        var following: Following? = null
        var lastLearnedEndMs: Long? = null

        /** The library entry the canary last checked, and where its match ended, so one airing counts once. */
        var canaryEntry: String? = null
        var canaryUntilMs = 0L

        /** The end of the latest segment heard. */
        var heardTo = 0L

        /** For [census]: the media fetched, the marked breaks and the breaks learned. */
        var playedMs = 0L
        var breaks = 0
        var learned = 0
    }

    private class Run(val first: Long, var last: Long, var plannedEndMs: Long?)

    /**
     * A break placed by time, from [startMs] to [endMs] on its timeline: one an in-segment cue announced, or a learned
     * one; [planned] when its end is known.
     */
    private class TimedBreak(val startMs: Long, var endMs: Long, var planned: Boolean)

    /** A library entry a playlist's audio is matching: the entry's point for global point g is g + [shift]. */
    private class Following(
        val entry: dev.jamesrich.sofaking.backend.ads.BreakEntry,
        val shift: Int,
        val startMs: Long,
        var lastPoint: Long,
        val timed: TimedBreak,
    )

    /** A playlist's recent Chromaprint points, numbered from the first heard, each with its time on the timeline. */
    private class PointLog {
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

    /** A silent stretch on a timeline, and the slate break it became once it lasted [SLATE_MS]. */
    private class Quiet(val startMs: Long, var endMs: Long, var slate: TimedBreak? = null)

    private val timelines = object : LinkedHashMap<String, Timeline>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Timeline>) = size > TIMELINE_LIMIT
    }
    private val marks = object : LinkedHashMap<String, Mark>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mark>) = size > segmentLimit
    }
    private var servedCount = 0L

    /** The reader of this clock's segment audio, made by the relay the first time it has one to read. */
    internal var follower: AudioFollower? = null

    /** Whether segments should be [heard]: learning is not off. */
    val listening: Boolean get() = learning() != LearnedBreaks.OFF

    /** [text] with its ad runs marked (see [AdBreaks.annotate]), after noting its segments for [at]. */
    @Synchronized
    fun annotate(text: String, base: URI): String {
        val read = AdBreaks.parse(text, base) ?: return text
        val timeline = timelines.getOrPut(base.toString()) { Timeline(base.toString()) }
        place(timeline, read.segments)
        if (read.segments.any { it.ad }) exact(timeline)
        val learnedMarks = if (learning() == LearnedBreaks.ON) timeline.learnedMarks else emptyList()
        val parsed = covered(timeline, covered(timeline, carry(timeline, read), timeline.splices), learnedMarks)
        val runs = AdBreaks.runs(parsed)
        for (run in runs) remember(timeline, run)
        timeline.openCue = when {
            read.cueOpenAtEnd -> parsed.segments.lastOrNull()?.takeIf {
                it.ad
            }?.let { last -> timeline.runs.floorEntry(last.sequence)?.key }

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

    /** [parsed] with the segments one of [breaks] covers marked as ads. */
    private fun covered(timeline: Timeline, parsed: AdBreaks.Parsed, breaks: List<TimedBreak>): AdBreaks.Parsed {
        if (breaks.isEmpty()) return parsed
        var changed = false
        val plannedEnd = HashMap(parsed.plannedEndMs)
        val segments = ArrayList<AdBreaks.Segment>(parsed.segments.size)
        for ((index, segment) in parsed.segments.withIndex()) {
            val at = timeline.starts[segment.sequence]
            val covering = at?.let { breakAt(breaks, it, segment.durationMs) }
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

    /** The one of [breaks] the middle of a segment starting at [startMs] falls in, if any. */
    private fun breakAt(breaks: List<TimedBreak>, startMs: Long, durationMs: Long): TimedBreak? {
        val middle = startMs + durationMs / 2
        return breaks.lastOrNull { middle >= it.startMs && middle < it.endMs }
    }

    /**
     * The player finished fetching [url]; a segment of a playlist seen here becomes the one it plays last, and the
     * cues it carried, if [cues] were read from it, place their breaks on its timeline.
     */
    @Synchronized
    internal fun served(url: String, cues: SegmentCues? = null) {
        val mark = marks[url] ?: return
        if (mark.sequence > (mark.playlist.lastServed ?: Long.MIN_VALUE)) {
            mark.playlist.playedMs +=
                mark.playlist.durations[mark.sequence] ?: 0L
        }
        mark.playlist.lastServed = mark.sequence
        mark.playlist.servedAt = ++servedCount
        if (cues != null && cues.splices.isNotEmpty()) splice(mark.playlist, mark.sequence, cues)
    }

    private fun splice(timeline: Timeline, sequence: Long, cues: SegmentCues) {
        val segmentStart = timeline.starts[sequence] ?: return
        exact(timeline)
        for (splice in cues.splices) {
            val at = segmentStart + spliceOffsetMs(cues.firstPtsTicks, splice.ptsTicks)
            // A cue is sent again in the segments before its splice, and a break may be named twice; one break stands.
            val current = timeline.splices.lastOrNull()?.takeIf { at >= it.startMs - REPEAT_SLACK_MS && at < it.endMs }
            if (splice.out) {
                if (current == null) {
                    timeline.splices +=
                        TimedBreak(at, at + (splice.durationMs ?: OPEN_CUE_LIMIT_MS), splice.durationMs != null)
                }
            } else if (current != null && at > current.startMs) {
                current.endMs = at
                current.planned = true
                // The segments from the return on leave the run they joined while the break was planned longer.
                val back = timeline.starts.entries.firstOrNull { (segment, start) ->
                    start + (timeline.durations[segment] ?: 0L) / 2 >= at
                }?.key
                if (back != null) {
                    timeline.runs.floorEntry(back)?.value?.takeIf { it.first < back && it.last >= back }?.let {
                        it.last = back - 1
                        it.plannedEndMs = at
                    }
                }
            }
        }
        join(timeline, timeline.splices)
    }

    /**
     * The segments already listed that [breaks] cover join the runs now, for [at]; later ones are
     * marked as their playlists arrive.
     */
    private fun join(timeline: Timeline, breaks: List<TimedBreak>) {
        for (timed in breaks) {
            val inside = timeline.starts.entries.filter { (segment, start) ->
                breakAt(
                    breaks,
                    start,
                    timeline.durations[segment] ?: 0L,
                ) === timed
            }
            val first = inside.firstOrNull() ?: continue
            remember(
                timeline,
                AdRun(first.key, inside.last().key, if (timed.planned) timed.endMs - first.value else null),
            )
        }
    }

    /**
     * The playlist carried a marker: what was being learned on it is dropped, since the
     * markers say where its breaks are.
     */
    private fun exact(timeline: Timeline) {
        timeline.exact = true
        timeline.quiet.clear()
        timeline.learnedMarks.clear()
        timeline.following = null
    }

    /** Where [url] is: its playlist, its sequence number and where it starts on the playlist's timeline. */
    @Synchronized
    internal fun placement(url: String): Placement? {
        val mark = marks[url] ?: return null
        return mark.playlist.starts[mark.sequence]?.let { Placement(mark.playlist.key, mark.sequence, it) }
    }

    /**
     * The player fetched [url], whose audio had [levels] in dBFS, one per [LOUDNESS_WINDOW_MS], and gave the
     * Chromaprint [points] new with it, the first at [firstPointMs] on the timeline (see [AudioFollower]): its silent
     * stretches join the ones heard before it on its playlist's timeline, a stretch long enough becomes a slate break,
     * and the points are learned from or matched (see the class notes).
     */
    @Synchronized
    internal fun heard(url: String, levels: FloatArray, points: IntArray? = null, firstPointMs: Long = 0L) {
        val mode = learning()
        if (mode == LearnedBreaks.OFF) return
        val mark = marks[url] ?: return
        val timeline = mark.playlist
        val start = timeline.starts[mark.sequence] ?: return
        val end = start + (timeline.durations[mark.sequence] ?: 0L)
        if (!timeline.exact) slates(timeline, start, end, levels, mode)
        timeline.heardTo = maxOf(timeline.heardTo, end)
        if (library != null && points != null && points.isNotEmpty()) {
            timeline.points.add(points, firstPointMs)
            if (timeline.exact) {
                learnClosed(library, timeline)
                canary(library, timeline)
            } else {
                follow(library, timeline, mode)
            }
        }
        if (mode == LearnedBreaks.ON) join(timeline, timeline.learnedMarks)
    }

    /** The silent stretches in [levels], of a segment from [start] to [end]; see the class notes. */
    private fun slates(timeline: Timeline, start: Long, end: Long, levels: FloatArray, mode: LearnedBreaks) {
        var from = -1
        for (index in 0..levels.size) {
            val silent = index < levels.size && levels[index] < SILENCE_DB
            if (silent && from < 0) from = index
            if (!silent && from >= 0) {
                // A stretch running to the last level runs to the segment's end, which its
                // audio may fall a little short of.
                quiet(
                    timeline,
                    start + from * LOUDNESS_WINDOW_MS,
                    if (index == levels.size) {
                        maxOf(end, start + index * LOUDNESS_WINDOW_MS)
                    } else {
                        start + index * LOUDNESS_WINDOW_MS
                    },
                )
                from = -1
            }
        }
        val heardTo = maxOf(timeline.heardTo, end)
        val marked = mode == LearnedBreaks.ON
        val over = ArrayList<Long>()
        for (quiet in timeline.quiet.values) {
            val length = quiet.endMs - quiet.startMs
            var slate = quiet.slate
            if (slate == null && length >= SLATE_MS) {
                slate =
                    TimedBreak(quiet.startMs, quiet.endMs, planned = false).also {
                        if (marked) {
                            timeline.learnedMarks +=
                                it
                        }
                    }
                quiet.slate = slate
                timeline.learned++
                learned(LearnedBreak(SLATE, length, ended = false, marked = marked))
            }
            slate?.endMs = quiet.endMs
            // Sound heard since the stretch ended closes it.
            if (quiet.endMs + JOIN_MS < heardTo) {
                over += quiet.startMs
                if (slate != null) {
                    slate.planned = true
                    learned(LearnedBreak(SLATE, length, ended = true, marked = marked))
                } else if (length >= SHORT_SLATE_MS) {
                    learned(LearnedBreak(SHORT_SLATE, length, ended = true, marked = false))
                }
            }
        }
        over.forEach { timeline.quiet.remove(it) }
    }

    /** Learns each break the markers opened and closed on [timeline] once its audio is all heard. */
    private fun learnClosed(library: BreakLibrary, timeline: Timeline) {
        val heard = timeline.points.lastTime ?: return
        for (run in timeline.runs.values) {
            if (run.first in timeline.learnedRuns) continue
            val startMs = timeline.starts[run.first] ?: continue
            // Closed once a later segment is listed, and its audio heard up to the trimmed end.
            val endMs = timeline.starts[run.last + 1] ?: continue
            if (heard < endMs - LEARN_EDGE_MS) continue
            timeline.learnedRuns += run.first
            // A cue left open until the limit gave no end, so its length is a guess.
            if (run.plannedEndMs == null && endMs - startMs >= OPEN_CUE_LIMIT_MS - REPEAT_SLACK_MS) continue
            val points = timeline.points.between(startMs + LEARN_EDGE_MS, endMs - LEARN_EDGE_MS)
            if (points.size >= BreakLibrary.MIN_LEARN_POINTS) {
                library.learn(points, channelId, providerId, "$tag:$startMs")
            }
        }
        timeline.starts.firstEntry()?.let { (oldest, _) -> timeline.learnedRuns.removeIf { it < oldest } }
    }

    /** Checks the library against the markers on [timeline]: see [BreakLibrary]. */
    private fun canary(library: BreakLibrary, timeline: Timeline) {
        val (words, base) = timeline.points.recent(CANARY_POINTS)
        val match = library.match(words, BreakLibrary.MIN_LEARN_POINTS, exclude = tag) ?: return
        val fromMs = timeline.points.time(base + match.queryFrom)
        val toMs = timeline.points.time(base + match.queryTo) + BreakLibrary.POINT_MS.roundToLong()
        if (timeline.canaryEntry == match.entry.id && fromMs <= timeline.canaryUntilMs + REPEAT_SLACK_MS) {
            timeline.canaryUntilMs = maxOf(timeline.canaryUntilMs, toMs)
            return
        }
        timeline.canaryEntry = match.entry.id
        timeline.canaryUntilMs = toMs
        val breaks = timeline.runs.values.mapNotNull { run ->
            val start = timeline.starts[run.first] ?: return@mapNotNull null
            start to
                (
                    timeline.starts[run.last + 1] ?: run.plannedEndMs
                        ?: (timeline.starts[run.last]?.plus(timeline.durations[run.last] ?: 0L) ?: start)
                    )
        }
        val inside = breaks.sumOf { (start, end) -> maxOf(0L, minOf(end, toMs) - maxOf(start, fromMs)) }
        if (inside * 2 >= toMs - fromMs) {
            library.hit(match.entry)
        } else if (breaks.none { (start, end) -> fromMs < end + CANARY_EDGE_MS && toMs > start - CANARY_EDGE_MS }) {
            library.falseHit(match.entry)
            learned(LearnedBreak(CANARY, toMs - fromMs, ended = true, marked = false, entry = match.entry.id))
        }
    }

    /** Matches the newest audio of [timeline], which has no markers, against the library: see the class notes. */
    private fun follow(library: BreakLibrary, timeline: Timeline, mode: LearnedBreaks) {
        val log = timeline.points
        timeline.following?.let { following ->
            val entry = following.entry.points
            var last = following.lastPoint
            var point = last + 1
            while (point < log.end) {
                val at = point + following.shift
                if (at >= entry.size) break
                if (Integer.bitCount(log.word(point) xor entry[at.toInt()]) <= BreakLibrary.MAX_BIT_ERRORS) {
                    last = point
                } else if (point - last > BreakLibrary.MAX_GAP_POINTS) {
                    break
                }
                point++
            }
            following.lastPoint = last
            val endMs = log.time(last) + BreakLibrary.POINT_MS.roundToLong()
            following.timed.endMs = endMs
            val over =
                log.end - 1 - last > BreakLibrary.MAX_GAP_POINTS || last + following.shift >= entry.size - 1 ||
                    endMs - following.startMs >= MAX_LEARNED_MS
            if (!over) return
            following.timed.planned = true
            timeline.following = null
            timeline.lastLearnedEndMs = endMs
            learned(
                LearnedBreak(
                    LIBRARY,
                    endMs - following.startMs,
                    ended = true,
                    marked =
                    following.timed in timeline.learnedMarks,
                    entry = following.entry.id,
                ),
            )
        }
        val newest = log.lastTime ?: return
        val continuing = timeline.lastLearnedEndMs?.let { newest - it < CONTINUE_MS } == true
        val (words, base) = log.recent(MATCH_POINTS)
        val match = library.match(words, if (continuing) CONTINUE_POINTS else BreakLibrary.MIN_LEARN_POINTS) ?: return
        // Only a match of the newest audio is a break now; an older one was missed while it played.
        if (words.size - 1 - match.queryTo > BreakLibrary.MAX_GAP_POINTS) return
        if (match.entry.blocked) return
        val startMs = log.time(base + match.queryFrom)
        val timed =
            TimedBreak(startMs, log.time(base + match.queryTo) + BreakLibrary.POINT_MS.roundToLong(), planned = false)
        val marked = mode == LearnedBreaks.ON && match.entry.blanking && !library.frozen()
        if (marked) timeline.learnedMarks += timed
        timeline.following =
            Following(
                match.entry,
                match.entryFrom - (base + match.queryFrom).toInt(),
                startMs,
                base + match.queryTo,
                timed,
            )
        timeline.learned++
        library.matched(match.entry)
        learned(LearnedBreak(LIBRARY, timed.endMs - startMs, ended = false, marked = marked, entry = match.entry.id))
    }

    /** Adds the silent stretch from [from] to [to] to [timeline]'s, joining the ones it meets. */
    private fun quiet(timeline: Timeline, from: Long, to: Long) {
        var start = from
        var end = to
        var slate: TimedBreak? = null
        // Segments are mostly heard in order, but one may land after the next; a stretch meets those on either side.
        while (true) {
            val (key, met) = timeline.quiet.floorEntry(end + JOIN_MS)?.takeIf { it.value.endMs + JOIN_MS >= start }
                ?: break
            timeline.quiet.remove(key)
            start = minOf(start, met.startMs)
            end = maxOf(end, met.endMs)
            slate = slate ?: met.slate
        }
        timeline.quiet[start] = Quiet(start, end, slate)
    }

    /**
     * What the session found so far, for the census: its playlists' most, since audio renditions repeat the video's.
     */
    @Synchronized
    fun census(): AdCensus = AdCensus(
        timelines.values.maxOfOrNull { it.playedMs } ?: 0L,
        timelines.values.any { it.exact },
        timelines.values.maxOfOrNull { it.breaks } ?: 0,
        timelines.values.maxOfOrNull { it.learned } ?: 0,
    )

    /** The break playing now for a player with [aheadMs] of media loaded past the picture; not in one when unknown. */
    @Synchronized
    fun at(aheadMs: Long): AdBreakState {
        // Audio renditions are fetched beside the video: the marked playlist served lately is the one playing.
        val timeline =
            timelines.values.filter {
                it.runs.isNotEmpty() && it.lastServed != null && servedCount - it.servedAt < RECENT_SERVES
            }
                .maxByOrNull { it.servedAt } ?: return AdBreakState()
        val last = timeline.lastServed ?: return AdBreakState()
        val lastStart = timeline.starts[last] ?: return AdBreakState()
        val position = lastStart + (timeline.durations[last] ?: 0L) - aheadMs.coerceAtLeast(0L)
        // Starts grow with the sequence number, so the segment on screen is the last one
        // starting at or before the position.
        val (sequence, start) = timeline.starts.descendingMap().entries.firstOrNull { it.value <= position }?.let {
            it.key to
                it.value
        }
            ?: return AdBreakState()
        if (position >= start + (timeline.durations[sequence] ?: 0L)) return AdBreakState()
        val run = timeline.runs.floorEntry(sequence)?.value?.takeIf { sequence <= it.last } ?: return AdBreakState()
        val endMs = timeline.starts[run.last + 1] ?: run.plannedEndMs
        return AdBreakState(inBreak = true, remainingMs = endMs?.let { (it - position).coerceAtLeast(0L) })
    }

    /** Extends [timeline] with [segments]; a window that no longer meets what was seen starts it again. */
    private fun place(timeline: Timeline, segments: List<AdBreaks.Segment>) {
        val first = segments.firstOrNull() ?: return
        if (timeline.starts.isNotEmpty() && first.sequence !in timeline.starts &&
            first.sequence != timeline.starts.lastKey() + 1
        ) {
            timeline.starts.clear()
            timeline.durations.clear()
            timeline.runs.clear()
            timeline.splices.clear()
            timeline.quiet.clear()
            timeline.learnedMarks.clear()
            timeline.points.clear()
            timeline.learnedRuns.clear()
            timeline.following = null
            timeline.lastLearnedEndMs = null
            timeline.heardTo = 0L
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
        timeline.starts.firstEntry()?.let { (_, start) ->
            timeline.splices.removeIf { it.endMs < start }
            timeline.learnedMarks.removeIf { it.endMs < start }
        }
    }

    /** Adds [run], joining a run it overlaps or follows on from; its planned end is kept on the timeline. */
    private fun remember(timeline: Timeline, run: AdRun) {
        val plannedEnd = run.endsAfterMs?.let { ends -> timeline.starts[run.first]?.plus(ends) }
        val joined = timeline.runs.floorEntry(run.last)?.value?.takeIf { it.last + 1 >= run.first }
        if (joined == null) {
            if (timeline.exact) timeline.breaks++
            timeline.runs[run.first] = Run(run.first, run.last, plannedEnd)
            return
        }
        joined.last = maxOf(joined.last, run.last)
        if (plannedEnd != null) joined.plannedEndMs = plannedEnd
    }
}

/**
 * A break a rule learned from the stream rather than a marker: [rule], how long it has lasted so far, whether it has
 * [ended], and whether the session [marked] it for the player.
 */
data class LearnedBreak(
    val rule: String,
    val lengthMs: Long,
    val ended: Boolean,
    val marked: Boolean,
    val entry: String? = null,
)

/** Where a fetched segment is: its playlist (the timeline's key), its sequence number and its start on the timeline. */
internal data class Placement(val playlist: String, val sequence: Long, val startMs: Long)

/**
 * What a session found on its channel: how much media it played, whether a marker was seen, how many breaks markers
 * placed and how many it learned (slates and library matches).
 */
data class AdCensus(val playedMs: Long, val marked: Boolean, val breaks: Int, val learned: Int)

/** The rule for a stretch of silence long enough to be a slate, and for a shorter one that is only reported. */
internal const val SLATE = "slate"
internal const val SHORT_SLATE = "slate-short"

/** The rule for a match of the break library, and for the canary's false hit on a marked channel. */
internal const val LIBRARY = "library"
internal const val CANARY = "canary-false"

/** Audio below this level, in dBFS, is silent; speech and music under a quiet scene stay well above it. */
private const val SILENCE_DB = -60f

/** How long a silent stretch lasts before it is a slate; a show's own pauses rarely reach it. */
private const val SLATE_MS = 20_000L

/** How long a shorter silent stretch lasts before it is reported, in shadow only. */
private const val SHORT_SLATE_MS = 10_000L

/** How far apart two silent stretches may be and still be one, over a segment boundary the audio falls short of. */
private const val JOIN_MS = 500L

/** How far in from each edge of a marked break its audio is learned, since markers land a little off the cut. */
private const val LEARN_EDGE_MS = 3_000L

/** The points the canary and the matcher look at: about 15 and 20 s. */
private const val CANARY_POINTS = 121
private const val MATCH_POINTS = 162

/** How close to a marked break a match may be and not count as show audio, since markers land a little off the cut. */
private const val CANARY_EDGE_MS = 5_000L

/** How soon after a learned break a match continues it, and the least that match may be: about 3 s. */
private const val CONTINUE_MS = 5_000L
private const val CONTINUE_POINTS = 25

/** The longest a learned break may run. */
private const val MAX_LEARNED_MS = 240_000L

/** The playlists a session keeps a timeline for: its variants and renditions, and a few it switched away from. */
private const val TIMELINE_LIMIT = 16

/** How long a cue break with neither a planned end nor a `#EXT-X-CUE-IN` stays open across refreshes. */
private const val OPEN_CUE_LIMIT_MS = 240_000L

/** How close before a break's start a cue still names the same break. */
private const val REPEAT_SLACK_MS = 2_000L

/**
 * How far a cue's splice time may be from its segment's first frame; one further off counts from the segment itself.
 */
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

/**
 * How many segments of other playlists may have been served since a marked playlist's last
 * one for it to still be playing.
 */
private const val RECENT_SERVES = 6

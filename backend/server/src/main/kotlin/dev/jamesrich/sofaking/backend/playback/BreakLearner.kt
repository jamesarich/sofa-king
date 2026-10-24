package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.backend.ads.BreakMatch
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import kotlin.math.roundToLong

/** The rule for a stretch of silence long enough to be a slate, and for a shorter one that is only reported. */
internal const val SLATE = "slate"
internal const val SHORT_SLATE = "slate-short"

/** The rule for a match of the break library, and for the canary's false hit on a marked channel. */
internal const val LIBRARY = "library"
internal const val CANARY = "canary-false"

/** Audio below this level, in dBFS, is silent; speech and music under a quiet scene stay well above it. */
internal const val SILENCE_DB = -60f

/** How long a silent stretch lasts before it is a slate; a show's own pauses rarely reach it. */
internal const val SLATE_MS = 20_000L

/** How long a shorter silent stretch lasts before it is reported, in shadow only. */
internal const val SHORT_SLATE_MS = 10_000L

/** How far in from each edge of a marked break its audio is learned, since markers land a little off the cut. */
private const val LEARN_EDGE_MS = 3_000L

/** The points the canary and the matcher look at: about 15 and 20 s. */
private const val CANARY_POINTS = 121
private const val MATCH_POINTS = 162

/** How close to a marked break a match may be and not count as show audio, since markers land a little off the cut. */
private const val CANARY_EDGE_MS = 5_000L

/** How soon after a learned break a match continues it, and the least that match may be: about 3 s. */
private const val CONTINUE_MS = 5_000L
internal const val CONTINUE_POINTS = 25

/** The longest a learned break may run. */
private const val MAX_LEARNED_MS = 240_000L

/**
 * Learns breaks on an [AdClock]'s timelines from the audio of their segments: silent slates, and with a [library],
 * the breaks markers place (to learn) or the library's matches (to follow). See the [AdClock] notes. [tag] names the
 * clock's own breaks in the library, so they are never its canary.
 */
internal class BreakLearner(
    private val library: BreakLibrary?,
    private val channelId: String,
    private val providerId: String,
    private val tag: String,
    private val learned: (LearnedBreak) -> Unit,
) {
    /** The segment from [start] to [end] on [timeline] sounded as [audio]. */
    fun heard(timeline: AdTimeline, start: Long, end: Long, audio: HeardAudio, mode: LearnedBreaks) {
        if (!timeline.exact) slates(timeline, start, end, audio.levels, mode)
        timeline.heardTo = maxOf(timeline.heardTo, end)
        val points = audio.points
        if (library != null && points != null && points.isNotEmpty()) {
            timeline.points.add(points, audio.firstPointMs)
            if (timeline.exact) {
                learnClosed(library, timeline)
                canary(library, timeline)
            } else {
                follow(library, timeline, mode)
            }
        }
        if (mode == LearnedBreaks.ON) timeline.join(timeline.learnedMarks)
    }

    /** The silent stretches in [levels], of a segment from [start] to [end], join [timeline]'s; see the class notes. */
    private fun slates(timeline: AdTimeline, start: Long, end: Long, levels: FloatArray, mode: LearnedBreaks) {
        for ((from, to) in silences(levels, start, end)) timeline.quiet.add(from, to)
        val heardTo = maxOf(timeline.heardTo, end)
        val marked = mode == LearnedBreaks.ON
        val over = ArrayList<Long>()
        for (quiet in timeline.quiet.all) {
            val length = quiet.endMs - quiet.startMs
            if (quiet.slate == null && length >= SLATE_MS) {
                val slate = TimedBreak(quiet.startMs, quiet.endMs, planned = false)
                if (marked) timeline.learnedMarks += slate
                quiet.slate = slate
                timeline.learned++
                learned(LearnedBreak(SLATE, length, ended = false, marked = marked))
            }
            val slate = quiet.slate
            slate?.endMs = quiet.endMs
            // Sound heard since the stretch ended closes it.
            if (quiet.endMs + JOIN_MS < heardTo) {
                over += quiet.startMs
                slate?.planned = true
                ended(slate != null, length, marked)
            }
        }
        over.forEach { timeline.quiet.remove(it) }
    }

    /** Reports a silent stretch of [length] that is over: a slate, or a shorter one long enough to report. */
    private fun ended(slate: Boolean, length: Long, marked: Boolean) {
        if (slate) {
            learned(LearnedBreak(SLATE, length, ended = true, marked = marked))
        } else if (length >= SHORT_SLATE_MS) {
            learned(LearnedBreak(SHORT_SLATE, length, ended = true, marked = false))
        }
    }

    /** Learns each break the markers opened and closed on [timeline] once its audio is all heard. */
    private fun learnClosed(library: BreakLibrary, timeline: AdTimeline) {
        val heard = timeline.points.lastTime ?: return
        for (run in timeline.runs.values) {
            val (startMs, endMs) = closed(timeline, run, heard) ?: continue
            timeline.learnedRuns += run.first
            // A cue left open until the limit gave no end, so its length is a guess.
            val guessed = run.plannedEndMs == null && endMs - startMs >= OPEN_CUE_LIMIT_MS - REPEAT_SLACK_MS
            val points = timeline.points.between(startMs + LEARN_EDGE_MS, endMs - LEARN_EDGE_MS)
            if (!guessed && points.size >= BreakLibrary.MIN_LEARN_POINTS) {
                library.learn(points, channelId, providerId, "$tag:$startMs")
            }
        }
        timeline.starts.firstEntry()?.let { (oldest, _) -> timeline.learnedRuns.removeIf { it < oldest } }
    }

    /**
     * Where [run] starts and ends, when it is not learned yet and is closed: a later segment is listed, and its audio
     * is [heard] up to the trimmed end.
     */
    private fun closed(timeline: AdTimeline, run: AdTimeline.Run, heard: Long): Pair<Long, Long>? {
        if (run.first in timeline.learnedRuns) return null
        val startMs = timeline.starts[run.first] ?: return null
        val endMs = timeline.starts[run.last + 1] ?: return null
        return (startMs to endMs).takeIf { heard >= endMs - LEARN_EDGE_MS }
    }

    /** Checks the library against the markers on [timeline]: see [BreakLibrary]. */
    private fun canary(library: BreakLibrary, timeline: AdTimeline) {
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
            val end = timeline.starts[run.last + 1] ?: run.plannedEndMs
                ?: timeline.starts[run.last]?.plus(timeline.durations[run.last] ?: 0L) ?: start
            start to end
        }
        val inside = breaks.sumOf { (start, end) -> maxOf(0L, minOf(end, toMs) - maxOf(start, fromMs)) }
        if (inside * 2 >= toMs - fromMs) {
            library.hit(match.entry)
        } else if (breaks.none { (start, end) -> fromMs < end + CANARY_EDGE_MS && toMs > start - CANARY_EDGE_MS }) {
            library.falseHit(match.entry)
            learned(LearnedBreak(CANARY, toMs - fromMs, ended = true, marked = false, entry = match.entry.id))
        }
    }

    /**
     * Matches the newest audio of [timeline], which has no markers, against the library: the entry it follows while
     * that matches, else a new match. See the class notes.
     */
    private fun follow(library: BreakLibrary, timeline: AdTimeline, mode: LearnedBreaks) {
        val ended = timeline.following?.let { followOn(timeline, it) } ?: true
        if (ended) startFollowing(library, timeline, mode)
    }

    /** Follows [following] over the points heard since; true once it is over. */
    private fun followOn(timeline: AdTimeline, following: Following): Boolean {
        val log = timeline.points
        val size = following.entry.points.size
        val last = lastMatching(log, following)
        following.lastPoint = last
        val endMs = log.time(last) + BreakLibrary.POINT_MS.roundToLong()
        following.timed.endMs = endMs
        val over = log.end - 1 - last > BreakLibrary.MAX_GAP_POINTS || last + following.shift >= size - 1 ||
            endMs - following.startMs >= MAX_LEARNED_MS
        if (over) {
            following.timed.planned = true
            timeline.following = null
            timeline.lastLearnedEndMs = endMs
            val marked = following.timed in timeline.learnedMarks
            learned(
                LearnedBreak(
                    LIBRARY,
                    endMs - following.startMs,
                    ended = true,
                    marked = marked,
                    entry = following.entry.id,
                ),
            )
        }
        return over
    }

    /** The last point after [Following.lastPoint] that still matches the entry, with no longer gap than allowed. */
    private fun lastMatching(log: PointLog, following: Following): Long {
        val entry = following.entry.points
        var last = following.lastPoint
        var point = last + 1
        while (point < log.end && point + following.shift < entry.size) {
            if (Integer.bitCount(log.word(point) xor entry[(point + following.shift).toInt()]) <=
                BreakLibrary.MAX_BIT_ERRORS
            ) {
                last = point
            } else if (point - last > BreakLibrary.MAX_GAP_POINTS) {
                break
            }
            point++
        }
        return last
    }

    /** Starts following the library entry that matches the newest audio of [timeline], if one does. */
    private fun startFollowing(library: BreakLibrary, timeline: AdTimeline, mode: LearnedBreaks) {
        val log = timeline.points
        val (match, base) = newestMatch(library, timeline) ?: return
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

    /**
     * The library's match of the newest audio on [timeline], with the number of the first point it was matched from;
     * only a match reaching the newest audio is a break now, since an older one was missed while it played. A match
     * soon after a learned break ends needs only [CONTINUE_POINTS].
     */
    private fun newestMatch(library: BreakLibrary, timeline: AdTimeline): Pair<BreakMatch, Long>? {
        val log = timeline.points
        val newest = log.lastTime ?: return null
        val continuing = timeline.lastLearnedEndMs?.let { newest - it < CONTINUE_MS } == true
        val (words, base) = log.recent(MATCH_POINTS)
        val match = library.match(words, if (continuing) CONTINUE_POINTS else BreakLibrary.MIN_LEARN_POINTS)
        return match?.takeIf { words.size - 1 - it.queryTo <= BreakLibrary.MAX_GAP_POINTS && !it.entry.blocked }
            ?.let { it to base }
    }
}

/**
 * The silent stretches in [levels], one per [LOUDNESS_WINDOW_MS], of a segment from [start] to [end]; a stretch
 * running to the last level runs to the segment's end, which its audio may fall a little short of.
 */
private fun silences(levels: FloatArray, start: Long, end: Long): List<Pair<Long, Long>> {
    val stretches = ArrayList<Pair<Long, Long>>()
    var from = -1
    for (index in 0..levels.size) {
        val silent = index < levels.size && levels[index] < SILENCE_DB
        if (silent && from < 0) from = index
        if (!silent && from >= 0) {
            val to = start + index * LOUDNESS_WINDOW_MS
            stretches += start + from * LOUDNESS_WINDOW_MS to if (index == levels.size) maxOf(end, to) else to
            from = -1
        }
    }
    return stretches
}

/**
 * How a fetched segment sounded: its [levels] in dBFS, and the Chromaprint [points] new with it, the first at
 * [firstPointMs].
 */
internal class HeardAudio(val levels: FloatArray, val points: IntArray?, val firstPointMs: Long)

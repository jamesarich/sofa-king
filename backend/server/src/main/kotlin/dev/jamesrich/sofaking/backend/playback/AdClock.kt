package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.backend.PTS_BITS
import dev.jamesrich.sofaking.backend.PTS_MASK
import dev.jamesrich.sofaking.backend.TICKS_PER_MS
import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.contract.AdBreakState
import dev.jamesrich.sofaking.contract.AdRun
import java.net.URI
import java.util.UUID

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
    /** Learns this clock's breaks; its tag names them in the library, so its own are never its canary. */
    private val learner = BreakLearner(library, channelId, providerId, UUID.randomUUID().toString(), learned)

    private class Mark(val playlist: AdTimeline, val sequence: Long)

    private val timelines = object : LinkedHashMap<String, AdTimeline>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AdTimeline>) = size > TIMELINE_LIMIT
    }
    private val marks = object : LinkedHashMap<String, Mark>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mark>) = size > segmentLimit
    }
    private var servedCount = 0L

    /** The reader of this clock's segment audio, made by the relay the first time it has one to read. */
    internal var follower: AudioFollower? = null

    /** Whether segments should be [heard]: learning is not off. */
    val listening: Boolean get() = learning() != LearnedBreaks.OFF

    /**
     * [text] with its ad runs marked (see [AdBreaks.annotate]) and the player held further back where the window has
     * room (see [withHoldBack]), after noting its segments for [at].
     */
    @Synchronized
    fun annotate(text: String, base: URI): String {
        val read = AdBreaks.parse(text, base) ?: return text
        val timeline = timelines.getOrPut(base.toString()) { AdTimeline(base.toString(), segmentLimit) }
        timeline.place(read.segments)
        for (segment in read.segments) marks[segment.url] = Mark(timeline, segment.sequence)
        if (read.segments.any { it.ad }) timeline.markExact()
        val learnedMarks = if (learning() == LearnedBreaks.ON) timeline.learnedMarks else emptyList()
        val parsed = timeline.covered(timeline.covered(timeline.carry(read), timeline.splices), learnedMarks)
        val runs = AdBreaks.runs(parsed)
        for (run in runs) timeline.remember(run)
        val last = parsed.segments.lastOrNull()
        timeline.openCue = when {
            read.cueOpenAtEnd -> last?.takeIf { it.ad }?.let { timeline.runs.floorEntry(it.sequence)?.key }
            last?.ad == true && parsed.segments.none { it.cueIn } -> timeline.openCue
            else -> null
        }
        val (held, holdBack) = withHoldBack(text, read.segments, timeline.holdBackMs)
        timeline.holdBackMs = holdBack
        return AdBreaks.annotate(held, runs)
    }

    /**
     * The player finished fetching [url]; a segment of a playlist seen here becomes the one it plays last, and the
     * cues it carried, if [cues] were read from it, place their breaks on its timeline.
     */
    @Synchronized
    internal fun served(url: String, cues: SegmentCues? = null) {
        val mark = marks[url] ?: return
        val timeline = mark.playlist
        if (mark.sequence > (timeline.lastServed ?: Long.MIN_VALUE)) {
            timeline.playedMs += timeline.durations[mark.sequence] ?: 0L
        }
        timeline.lastServed = mark.sequence
        timeline.servedAt = ++servedCount
        if (cues != null && cues.splices.isNotEmpty()) timeline.splice(mark.sequence, cues)
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
        learner.heard(timeline, start, end, HeardAudio(levels, points, firstPointMs), mode)
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
        val timeline = timelines.values
            .filter { it.runs.isNotEmpty() && it.lastServed != null && servedCount - it.servedAt < RECENT_SERVES }
            .maxByOrNull { it.servedAt }
        val last = timeline?.lastServed
        val lastStart = last?.let { timeline.starts[it] } ?: return AdBreakState()
        return timeline.stateAt(lastStart + (timeline.durations[last] ?: 0L) - aheadMs.coerceAtLeast(0L))
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

/** The playlists a session keeps a timeline for: its variants and renditions, and a few it switched away from. */
private const val TIMELINE_LIMIT = 16

/**
 * How far a cue's splice time may be from its segment's first frame; one further off counts from the segment itself.
 */
private const val SPLICE_REACH_TICKS = 60L * MS_PER_SECOND * TICKS_PER_MS

/**
 * How many ms after a segment's first frame, at [firstPtsTicks], a splice at [ptsTicks] falls, on the 33-bit 90 kHz
 * clock that wraps every 26.5 hours; 0 when either is unknown or they are implausibly far apart.
 */
internal fun spliceOffsetMs(firstPtsTicks: Long?, ptsTicks: Long?): Long {
    if (firstPtsTicks == null || ptsTicks == null) return 0L
    // The difference on the wrapping clock, taken as the nearer of forward and back.
    var ticks = (ptsTicks - firstPtsTicks) and PTS_MASK
    if (ticks >= 1L shl (PTS_BITS - 1)) ticks -= 1L shl PTS_BITS
    return if (ticks in -SPLICE_REACH_TICKS..SPLICE_REACH_TICKS) ticks / TICKS_PER_MS else 0L
}

/**
 * How many segments of other playlists may have been served since a marked playlist's last
 * one for it to still be playing.
 */
private const val RECENT_SERVES = 6

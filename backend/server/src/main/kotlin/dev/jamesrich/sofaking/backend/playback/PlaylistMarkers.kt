package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.contract.RecordingBreak
import java.net.URI

/**
 * The marked ad breaks of a recording, as offsets into the file, read from the playlists FFmpeg fetches while it
 * records (see [LoopbackRelay.reading]). The playlists are read as live sessions read them ([AdBreaks]), and a cue
 * left open across refreshes is carried until its `#EXT-X-CUE-IN` or [OPEN_CUE_LIMIT_MS].
 *
 * FFmpeg opens every variant playlist of a multivariant source and fetches one probe segment from each, then reads
 * the variants it keeps. So each media playlist of a read runs a clock of its own from the segments fetched from it,
 * and the file's clock is the one furthest on: the variant being read, not a probe. Each new segment starts where the
 * last one of its playlist ended, so an offset is right to within a segment. Once a source that has no playlists is
 * recorded into the same file, later offsets cannot be known and the breaks found so far are all there are
 * ([freeze]).
 */
class PlaylistMarkers {
    private class Seen(val durationMs: Long, var ad: Boolean, var counted: Boolean = false)

    /** A segment a playlist counted: where it starts on its clock, and the bytes fetched of it before and in it. */
    private class Counted(val startMs: Long, val bytesBefore: Long, val bytes: Long)

    /** One media playlist of the current read, with the clock its fetched segments run. */
    private class Track {
        val seen = object : LinkedHashMap<String, Seen>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>) = size > SEEN_LIMIT
        }
        var carrying = false
        var carriedMs = 0L
        var elapsedMs = 0L
        var openFrom: Long? = null
        val found = ArrayList<RecordingBreak>()
        val counted = ArrayList<Counted>()
        var bytes = 0L

        fun closeAt(at: Long) {
            openFrom?.takeIf { at > it }?.let { found += RecordingBreak(it, at) }
            openFrom = null
        }
    }

    private val tracks = LinkedHashMap<String, Track>()
    private val done = ArrayList<RecordingBreak>()
    private var base = 0L
    private var fetched = 0L
    private var frozen = false

    /** The playlist whose segments carry the file's clock: the one with the most media fetched, the first on a tie. */
    private fun leader(): Track? = tracks.values.maxByOrNull { it.elapsedMs }

    /** Takes what the read's leading playlist found into the recording's breaks and clock. */
    private fun commit() {
        leader()?.let { track ->
            track.closeAt(track.elapsedMs)
            track.found.forEach { done += RecordingBreak(base + it.startMs, base + it.endMs) }
            base += track.elapsedMs
        }
        tracks.clear()
        fetched = 0L
    }

    /**
     * A new read of the source starts: its playlists are new, and a break open at the end of the last one ends. A read
     * frozen for a body without playlists that turned out to have them counts again from here.
     */
    @Synchronized
    fun begin() {
        commit()
        frozen = false
    }

    /**
     * The read ends having written [written] bytes to the file. FFmpeg reads ahead, so segments it had fetched may
     * never have been muxed, and counting them would put every later offset late; a segment whose middle lies beyond
     * what was written is taken back, the clock with it, and breaks that began in it with them. The bytes written
     * hold every playlist FFmpeg read, a rendition's beside the leader's, so the leader is judged by its share of
     * what was fetched. Says how many segments the leader counted, how many of them were taken back and the bytes
     * fetched of all playlists, for the log.
     */
    @Synchronized
    fun end(written: Long): ReadEnd {
        val track = leader()
        if (frozen || track == null) return ReadEnd(0, 0, 0L)
        val share = if (fetched > 0) written.toDouble() * track.bytes / fetched else written.toDouble()
        val cut = track.counted.firstOrNull { it.bytesBefore + it.bytes / 2 > share }
        val takenBack = if (cut == null) 0 else track.counted.size - track.counted.indexOf(cut)
        if (cut != null) {
            track.elapsedMs = cut.startMs
            track.found.removeAll { it.startMs >= track.elapsedMs }
            track.found.replaceAll {
                if (it.endMs > track.elapsedMs) RecordingBreak(it.startMs, track.elapsedMs) else it
            }
            if (track.openFrom?.let { it >= track.elapsedMs } == true) track.openFrom = null
        }
        val total = track.counted.size
        track.counted.clear()
        return ReadEnd(total, takenBack, fetched)
    }

    /** What a read's end did to the clock: segments [counted], of which [takenBack], and their [fetched] bytes. */
    data class ReadEnd(val counted: Int, val takenBack: Int, val fetched: Long)

    /** Stops counting: media of a source without playlists is going into the file. */
    @Synchronized
    fun freeze() {
        commit()
        frozen = true
    }

    /** The playlist fetched from [source] with [text]; a multivariant playlist is ignored. */
    @Synchronized
    fun playlist(text: String, source: URI) {
        if (frozen) return
        val parsed = AdBreaks.parse(text, source)?.takeIf { it.segments.isNotEmpty() } ?: return
        val key = source.toString()
        val track = tracks[key] ?: if (tracks.size < TRACK_LIMIT) Track().also { tracks[key] = it } else return
        parsed.segments.forEach { note(track, it) }
        if (parsed.cueOpenAtEnd && !track.carrying) track.carriedMs = 0L
        track.carrying = (parsed.cueOpenAtEnd || track.carrying) && track.carriedMs < OPEN_CUE_LIMIT_MS
    }

    private fun note(track: Track, segment: AdBreaks.Segment) {
        if (segment.cueIn) track.carrying = false
        val known = track.seen[segment.url]
        if (known != null) {
            known.ad = known.ad || segment.ad
            return
        }
        // A window that starts inside a break holds no cue, so the segments new to it belong to the open one.
        val carried = track.carrying && !segment.ad && track.carriedMs < OPEN_CUE_LIMIT_MS
        if (carried) track.carriedMs += segment.durationMs
        track.seen[segment.url] = Seen(segment.durationMs, segment.ad || carried)
    }

    /**
     * FFmpeg finished fetching [url], [bytes] long; if it is a segment of a playlist of the read, that playlist's
     * clock moves on, and the file will hold it if FFmpeg keeps that playlist, which [end] checks against what was
     * written.
     */
    @Synchronized
    fun segment(url: String, bytes: Long = 0L) {
        if (frozen) return
        val track = tracks.values.firstOrNull { it.seen[url]?.counted == false } ?: return
        val seg = track.seen.getValue(url)
        seg.counted = true
        track.counted += Counted(track.elapsedMs, track.bytes, bytes)
        track.bytes += bytes
        fetched += bytes
        if (seg.ad) {
            if (track.openFrom == null) track.openFrom = track.elapsedMs
        } else {
            track.closeAt(track.elapsedMs)
        }
        track.elapsedMs += seg.durationMs
    }

    /** The breaks so far, in order; one still open ends where the media does. */
    @Synchronized
    fun breaks(): List<RecordingBreak> {
        val track = leader()
        val current = track?.let {
            val open = it.openFrom?.takeIf { from -> it.elapsedMs > from }?.let { from ->
                RecordingBreak(from, it.elapsedMs)
            }
            (it.found + listOfNotNull(open)).map { b -> RecordingBreak(base + b.startMs, base + b.endMs) }
        }.orEmpty()
        return (done + current).fold(ArrayList<RecordingBreak>()) { merged, next ->
            val last = merged.lastOrNull()
            if (last != null && next.startMs <= last.endMs) {
                merged[merged.lastIndex] = RecordingBreak(last.startMs, maxOf(last.endMs, next.endMs))
            } else {
                merged += next
            }
            merged
        }
    }

    private companion object {
        /** The segments remembered of a playlist, enough for any live window and then some. */
        const val SEEN_LIMIT = 4096

        /** The media playlists of a read followed; a multivariant source has a handful. */
        const val TRACK_LIMIT = 16
    }
}

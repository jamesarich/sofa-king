package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.contract.RecordingBreak
import java.net.URI

/**
 * The marked ad breaks of a recording, as offsets into the file, read from the playlists FFmpeg fetches while it
 * records (see [LoopbackRelay.reading]). The playlists are read as live sessions read them ([AdBreaks]), and a cue
 * left open across refreshes is carried until its `#EXT-X-CUE-IN` or [OPEN_CUE_LIMIT_MS]. The file's clock is the
 * media FFmpeg has finished fetching: each new segment of the first media playlist a read opens starts where the
 * last one ended, so an offset is right to within a segment. Once a source that has no playlists is recorded into the
 * same file, later offsets cannot be known and the breaks found so far are all there are ([freeze]).
 */
class PlaylistMarkers {
    private class Seen(val durationMs: Long, var ad: Boolean, var counted: Boolean = false)

    /** A segment this read counted: where it starts on the file's clock, and the bytes of the read before and in it. */
    private class Counted(val startMs: Long, val bytesBefore: Long, val bytes: Long)

    private val seen = object : LinkedHashMap<String, Seen>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>) = size > SEEN_LIMIT
    }
    private val found = ArrayList<RecordingBreak>()
    private var primary: String? = null
    private var carrying = false
    private var carriedMs = 0L
    private var elapsedMs = 0L
    private var openFrom: Long? = null
    private var frozen = false
    private val counted = ArrayList<Counted>()
    private var readBytes = 0L

    /**
     * A new read of the source starts: its playlists are new, and a break open at the end of the last one ends. A read
     * frozen for a body without playlists that turned out to have them counts again from here.
     */
    @Synchronized
    fun begin() {
        closeAt(elapsedMs)
        seen.clear()
        primary = null
        carrying = false
        carriedMs = 0L
        frozen = false
        counted.clear()
        readBytes = 0L
    }

    /**
     * The read ends having written [written] bytes to the file. FFmpeg reads ahead, so segments it had fetched may
     * never have been muxed, and counting them would put every later offset late; a segment whose middle lies beyond
     * what was written is taken back, the clock with it, and breaks that began in it with them. Says how many
     * segments the read counted, how many of them were taken back and the bytes of them fetched, for the log.
     */
    @Synchronized
    fun end(written: Long): ReadEnd {
        if (frozen) return ReadEnd(0, 0, 0L)
        val fetched = readBytes
        val total = counted.size
        val cut = counted.firstOrNull { it.bytesBefore + it.bytes / 2 > written }
        val takenBack = if (cut == null) 0 else counted.size - counted.indexOf(cut)
        if (cut != null) {
            elapsedMs = cut.startMs
            found.removeAll { it.startMs >= elapsedMs }
            found.replaceAll { if (it.endMs > elapsedMs) RecordingBreak(it.startMs, elapsedMs) else it }
            if (openFrom?.let { it >= elapsedMs } == true) openFrom = null
        }
        counted.clear()
        return ReadEnd(total, takenBack, fetched)
    }

    /** What a read's end did to the clock: segments [counted], of which [takenBack], and their [fetched] bytes. */
    data class ReadEnd(val counted: Int, val takenBack: Int, val fetched: Long)

    /** Stops counting: media of a source without playlists is going into the file. */
    @Synchronized
    fun freeze() {
        closeAt(elapsedMs)
        frozen = true
    }

    /** The playlist fetched from [source] with [text]; a multivariant playlist and later renditions are ignored. */
    @Synchronized
    fun playlist(text: String, source: URI) {
        if (frozen) return
        val parsed = AdBreaks.parse(text, source)?.takeIf { it.segments.isNotEmpty() } ?: return
        val key = source.toString()
        if (primary == null) primary = key
        if (key != primary) return
        parsed.segments.forEach(::note)
        if (parsed.cueOpenAtEnd && !carrying) carriedMs = 0L
        carrying = (parsed.cueOpenAtEnd || carrying) && carriedMs < OPEN_CUE_LIMIT_MS
    }

    private fun note(segment: AdBreaks.Segment) {
        if (segment.cueIn) carrying = false
        val known = seen[segment.url]
        if (known != null) {
            known.ad = known.ad || segment.ad
            return
        }
        // A window that starts inside a break holds no cue, so the segments new to it belong to the open one.
        val carried = carrying && !segment.ad && carriedMs < OPEN_CUE_LIMIT_MS
        if (carried) carriedMs += segment.durationMs
        seen[segment.url] = Seen(segment.durationMs, segment.ad || carried)
    }

    /**
     * FFmpeg finished fetching [url], [bytes] long; if it is a segment of the playlist being read, the file will hold
     * it, which [end] checks against what was written.
     */
    @Synchronized
    fun segment(url: String, bytes: Long = 0L) {
        if (frozen) return
        val seg = seen[url]?.takeUnless { it.counted } ?: return
        seg.counted = true
        counted += Counted(elapsedMs, readBytes, bytes)
        readBytes += bytes
        if (seg.ad) {
            if (openFrom == null) openFrom = elapsedMs
        } else {
            closeAt(elapsedMs)
        }
        elapsedMs += seg.durationMs
    }

    /** The breaks so far, in order; one still open ends where the media does. */
    @Synchronized
    fun breaks(): List<RecordingBreak> {
        val all = found + listOfNotNull(openFrom?.takeIf { elapsedMs > it }?.let { RecordingBreak(it, elapsedMs) })
        return all.fold(ArrayList<RecordingBreak>()) { merged, next ->
            val last = merged.lastOrNull()
            if (last != null && next.startMs <= last.endMs) {
                merged[merged.lastIndex] = RecordingBreak(last.startMs, maxOf(last.endMs, next.endMs))
            } else {
                merged += next
            }
            merged
        }
    }

    private fun closeAt(at: Long) {
        openFrom?.takeIf { at > it }?.let { found += RecordingBreak(it, at) }
        openFrom = null
    }

    private companion object {
        /** The segments remembered, enough for any live window and then some. */
        const val SEEN_LIMIT = 4096
    }
}

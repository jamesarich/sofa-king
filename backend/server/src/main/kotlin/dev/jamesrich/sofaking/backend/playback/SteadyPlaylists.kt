package dev.jamesrich.sofaking.backend.playback

import java.net.URI

/**
 * Keeps a live stream going through a source's lapses, per media playlist URL.
 *
 * Pluto now and then answers a live media playlist with no segments and `#EXT-X-ENDLIST`, which tells every player the
 * stream is over: Media3 plays out its buffer and fails, and the app tunes again. The last playlist from that URL that
 * held segments goes out in its place; the player sees no new segments and asks again on its next refresh.
 *
 * After such a lapse Pluto can come back with its numbering started over, lower than what the player already holds.
 * A player waits for media sequence numbers past its own, so it would stall at the end of its buffer for good. A
 * window whose segments were all sent before is a stale copy, and the last playlist goes out instead; a window of new
 * segments numbered lower is a restart, and it is numbered on from where the last window ended, behind a
 * `#EXT-X-DISCONTINUITY`, with `#EXT-X-DISCONTINUITY-SEQUENCE` kept counting.
 *
 * Without [holdLapses] a lapse passes through and ends the reader, for one that can open a fresh source.
 */
class SteadyPlaylists(private val limit: Int = 16, private val holdLapses: Boolean = true) {
    private val timelines = object : LinkedHashMap<String, Timeline>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Timeline>) = size > limit
    }

    /**
     * Whether a playlist that held segments has come back empty with an end marker. A reader that lives on the other
     * playlists of the source, such as a separate audio rendition, then writes only part of the stream.
     */
    @Volatile var lapsed = false
        private set

    /** [text] as the player should get it from [source]: held over a lapse, and numbered on across a restart. */
    @Synchronized
    fun steady(text: String, source: URI): String {
        val key = source.toString()
        val timeline = timelines[key]
        val segments = text.contains("#EXTINF")
        if (!segments && text.contains("#EXT-X-ENDLIST")) {
            if (timeline != null) lapsed = true
            return if (holdLapses) timeline?.sent ?: text else text
        }
        if (!segments || text.contains("#EXT-X-ENDLIST")) return text
        val window = Window.parse(text)
        return (timeline ?: Timeline().also { timelines[key] = it }).next(window)
    }

    /** What went out last from one URL, and how the source's numbering maps onto what the player has seen. */
    private class Timeline {
        var sent: String? = null
        private var first = 0L
        private var count = 0
        private var discontinuitySequence = 0L
        private var discontinuities = 0
        private var sequenceOffset = 0L
        private var discontinuityOffset = 0L

        /**
         * The numbers, as sent, of segments a restart put a discontinuity in front of, while
         * they may still be in a window.
         */
        private val restarts = ArrayList<Long>()

        /** The segments sent lately, to tell a stale copy from a restart. */
        private val recent = LinkedHashSet<String>()

        fun next(window: Window): String {
            val previous = sent
            if (previous != null && window.sequence + sequenceOffset < first) {
                if (recent.containsAll(window.uris)) return previous
                // A restart: its first segment follows the last one sent, and the discontinuities
                // sent so far have left the window.
                sequenceOffset = first + count - window.sequence
                restarts += first + count
                discontinuityOffset =
                    discontinuitySequence + discontinuities - window.discontinuitySequence -
                    restarts.count { it < first + count }
            }
            val outFirst = window.sequence + sequenceOffset
            // A restart's discontinuity that left the window is counted in the sequence from now on.
            restarts.removeAll { (it < outFirst).also { gone -> if (gone) discontinuityOffset++ } }
            val outDiscontinuitySequence = window.discontinuitySequence + discontinuityOffset
            val text = if (sequenceOffset == 0L && restarts.isEmpty() && discontinuityOffset == 0L) {
                window.text
            } else {
                window.renumbered(outFirst, outDiscontinuitySequence, restarts.map { (it - outFirst).toInt() }.toSet())
            }
            sent = text
            first = outFirst
            count = window.uris.size
            discontinuitySequence = outDiscontinuitySequence
            discontinuities = Window.parse(text).discontinuities
            recent.addAll(window.uris)
            val excess = recent.iterator()
            repeat(recent.size - RECENT) {
                excess.next()
                excess.remove()
            }
            return text
        }
    }

    /** A live media playlist's numbering and segments. */
    private class Window(
        val text: String,
        val sequence: Long,
        val discontinuitySequence: Long,
        val uris: List<String>,
        val discontinuities: Int,
    ) {
        /**
         * [text] numbered from [sequence], its discontinuities from [discontinuitySequence], with a discontinuity
         * before each segment index in [breaks].
         */
        fun renumbered(sequence: Long, discontinuitySequence: Long, breaks: Set<Int>): String {
            val out = StringBuilder()
            var index = 0
            var tagged = false
            var counted = false
            val numbered = text.contains(MEDIA_SEQUENCE)
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                when {
                    trimmed == "#EXTM3U" && !numbered -> {
                        out.append(line).append('\n').append(MEDIA_SEQUENCE).append(sequence).append('\n')
                        if (discontinuitySequence != 0L) {
                            out.append(DISCONTINUITY_SEQUENCE).append(discontinuitySequence).append('\n')
                        }
                        counted = true
                    }

                    trimmed.startsWith(MEDIA_SEQUENCE) -> {
                        out.append(MEDIA_SEQUENCE).append(sequence).append('\n')
                        if (!counted && discontinuitySequence != 0L) {
                            out.append(DISCONTINUITY_SEQUENCE).append(discontinuitySequence).append('\n')
                        }
                        counted = true
                    }

                    trimmed.startsWith(DISCONTINUITY_SEQUENCE) -> {
                        if (!counted) out.append(DISCONTINUITY_SEQUENCE).append(discontinuitySequence).append('\n')
                        counted = true
                    }

                    trimmed == DISCONTINUITY -> {
                        tagged = true
                        out.append(line).append('\n')
                    }

                    trimmed.isEmpty() -> Unit

                    trimmed.startsWith("#") -> out.append(line).append('\n')

                    else -> {
                        // The tag goes after the segment's own tags (its key, its length), right before its URI.
                        if (index in breaks && !tagged) out.append(DISCONTINUITY).append('\n')
                        out.append(line).append('\n')
                        index++
                        tagged = false
                    }
                }
            }
            return out.toString()
        }

        companion object {
            fun parse(text: String): Window {
                var sequence = 0L
                var discontinuitySequence = 0L
                var discontinuities = 0
                val uris = ArrayList<String>()
                for (raw in text.lineSequence()) {
                    val line = raw.trim()
                    when {
                        line.isEmpty() -> Unit

                        line.startsWith(MEDIA_SEQUENCE) ->
                            sequence =
                                line.substringAfter(':').trim().toLongOrNull() ?: 0L

                        line.startsWith(DISCONTINUITY_SEQUENCE) ->
                            discontinuitySequence =
                                line.substringAfter(':').trim().toLongOrNull() ?: 0L

                        line == DISCONTINUITY -> discontinuities++

                        line.startsWith("#") -> Unit

                        else -> uris += line
                    }
                }
                return Window(text, sequence, discontinuitySequence, uris, discontinuities)
            }
        }
    }

    private companion object {
        const val MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
        const val DISCONTINUITY_SEQUENCE = "#EXT-X-DISCONTINUITY-SEQUENCE:"
        const val DISCONTINUITY = "#EXT-X-DISCONTINUITY"
        const val RECENT = 64
    }
}

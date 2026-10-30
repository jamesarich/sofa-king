package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.contract.RecordingBreak

/** The rules for combining what a recording's detectors found. */
internal object RecordingBreaks {
    /** Guessed breaks closer than this are one break: ads run one after another. */
    const val JOIN_MS = 5_000L

    /**
     * The breaks of a recording: the [marked] ones, which a provider's markers placed exactly, all kept, and the
     * [guessed] ones (learned ads, slates and Comskip), joined where they overlap or lie within [JOIN_MS], less any
     * that overlap a marked break, since the marker is exact.
     */
    fun combine(marked: List<RecordingBreak>, guessed: List<RecordingBreak>): List<RecordingBreak> {
        val exact = join(marked, 0L)
        val rest = join(guessed, JOIN_MS).filter { g -> exact.none { it.startMs < g.endMs && g.startMs < it.endMs } }
        return (exact + rest).sortedBy { it.startMs }
    }

    /** [breaks] in order, those that overlap or lie within [gap] of each other made one. */
    fun join(breaks: List<RecordingBreak>, gap: Long): List<RecordingBreak> {
        val joined = ArrayList<RecordingBreak>()
        for (next in breaks.filter { it.endMs > it.startMs }.sortedBy { it.startMs }) {
            val last = joined.lastOrNull()
            if (last != null && next.startMs <= last.endMs + gap) {
                joined[joined.lastIndex] = RecordingBreak(last.startMs, maxOf(last.endMs, next.endMs))
            } else {
                joined += next
            }
        }
        return joined
    }
}

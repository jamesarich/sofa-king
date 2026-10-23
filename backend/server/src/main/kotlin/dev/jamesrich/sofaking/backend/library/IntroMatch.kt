package dev.jamesrich.sofaking.backend.library

import kotlin.math.roundToLong

/** A span of an episode, in milliseconds from its start. */
data class IntroSpan(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/** Where two episodes share audio: [first] in the one, [second] in the other. */
data class SharedSpan(val first: IntroSpan, val second: IntroSpan)

/**
 * Finds the intro two episodes of a show share by comparing their Chromaprint fingerprints: the points FFmpeg's
 * `chromaprint` muxer writes, one 32-bit word for each [POINT_MS] of audio. Exact words both episodes hold vote for how
 * far one is shifted against the other; at the best few shifts, a point matches when at most [MAX_BIT_ERRORS] of its
 * bits differ, and the shared audio is the longest run of matching points with no gap over [MAX_GAP_MS], at least
 * [MIN_DENSITY] of its points matching, from [MIN_INTRO_MS] to [MAX_INTRO_MS] long by default. End credits, read
 * from each episode's last minutes, run up to [MAX_CREDITS_MS].
 */
object IntroMatch {
    /** One point covers a third of Chromaprint's 4096-sample frame at 11,025 Hz. */
    const val POINT_MS = 4096.0 / 3 / 11_025 * 1_000
    const val MAX_BIT_ERRORS = 6
    const val MAX_GAP_MS = 2_000L
    const val MIN_DENSITY = 0.6
    const val MIN_INTRO_MS = 15_000L
    const val MAX_INTRO_MS = 120_000L
    const val MAX_CREDITS_MS = 300_000L

    /** How many of the most voted shifts are tried, each with its neighbours. */
    private const val SHIFTS = 8
    private const val NEIGHBOURS = 2

    /** The longest stretch of audio [a] and [b] share, from [minMs] to [maxMs] long; null when they share none. */
    fun shared(a: IntArray, b: IntArray, minMs: Long = MIN_INTRO_MS, maxMs: Long = MAX_INTRO_MS): SharedSpan? {
        val positions = HashMap<Int, MutableList<Int>>(b.size * 2)
        b.forEachIndexed { j, word -> positions.getOrPut(word) { ArrayList(1) } += j }
        val votes = HashMap<Int, Int>()
        a.forEachIndexed { i, word -> positions[word]?.forEach { j -> votes.merge(j - i, 1, Int::plus) } }
        val shifts = votes.entries.sortedByDescending { it.value }.take(SHIFTS)
            .flatMap { (shift, _) -> (shift - NEIGHBOURS)..(shift + NEIGHBOURS) }.distinct()
        var best: Run? = null
        for (shift in shifts) {
            val run = longestRun(a, b, shift, minMs, maxMs) ?: continue
            if (best == null || run.length > best.length) best = run
        }
        return best?.let {
            SharedSpan(span(it.start, it.end), span(it.start + it.shift, it.end + it.shift))
        }
    }

    private class Run(val start: Int, val end: Int, val shift: Int) {
        val length: Int get() = end - start + 1
    }

    /** With [b] shifted [shift] points against [a], the longest run that passes the bounds. */
    private fun longestRun(a: IntArray, b: IntArray, shift: Int, minMs: Long, maxMs: Long): Run? {
        val from = maxOf(0, -shift)
        val until = minOf(a.size, b.size - shift)
        val maxGap = (MAX_GAP_MS / POINT_MS).toInt()
        val minPoints = (minMs / POINT_MS).toInt()
        val maxPoints = (maxMs / POINT_MS).toInt()
        var best: Run? = null
        var start = -1
        var last = -1
        var matches = 0
        fun close() {
            if (start < 0) return
            val length = last - start + 1
            if (length in minPoints..maxPoints && matches >= MIN_DENSITY * length && (best == null || length > best!!.length)) best = Run(start, last, shift)
        }
        for (i in from until until) {
            if (Integer.bitCount(a[i] xor b[i + shift]) > MAX_BIT_ERRORS) continue
            if (start >= 0 && i - last > maxGap) {
                close()
                start = -1
            }
            if (start < 0) {
                start = i
                matches = 0
            }
            last = i
            matches++
        }
        close()
        return best
    }

    private fun span(start: Int, end: Int) = IntroSpan((start * POINT_MS).roundToLong(), ((end + 1) * POINT_MS).roundToLong())

    /**
     * An episode's intro from what it shares with several others of its show: the span the most others agree with
     * (overlapping it by at least half the shorter of the two), starting at the agreeing spans' middle start and
     * ending at their middle end, rounded down where two are in the middle, so a stretch only some episodes share
     * is not skipped. With [minAgreeing] above 1 a span only one other episode shares, such as a recap, is no intro.
     */
    fun intro(spans: List<IntroSpan>, minAgreeing: Int): IntroSpan? {
        if (spans.isEmpty()) return null
        val groups = spans.map { span -> spans.filter { agree(span, it) } }
        val group = groups.maxBy { it.size }
        if (group.size < minAgreeing) return null
        val start = lowerMedian(group.map { it.startMs })
        val end = lowerMedian(group.map { it.endMs })
        return IntroSpan(start, end).takeIf { it.lengthMs >= MIN_INTRO_MS }
    }

    private fun agree(a: IntroSpan, b: IntroSpan): Boolean {
        val overlap = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
        return overlap * 2 >= minOf(a.lengthMs, b.lengthMs)
    }

    private fun lowerMedian(values: List<Long>): Long = values.sorted()[(values.size - 1) / 2]
}

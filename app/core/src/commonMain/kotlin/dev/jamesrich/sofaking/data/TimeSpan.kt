package dev.jamesrich.sofaking.data

/** A range of a player's timeline, from [startSeconds] to [endSeconds]. */
data class TimeSpan(val startSeconds: Double, val endSeconds: Double) {
    /** [endSeconds] less [nowSeconds] in milliseconds when [nowSeconds] is inside the range, else null. */
    internal fun aheadOfMs(nowSeconds: Double): Long? =
        if (nowSeconds >= startSeconds && nowSeconds <= endSeconds) secondsToMs(endSeconds - nowSeconds) else null
}

/** The end of the range in [ranges] that [nowSeconds] is in, less [nowSeconds], in ms; null outside them all. */
fun loadedAheadMs(nowSeconds: Double, ranges: List<TimeSpan>): Long? {
    if (!nowSeconds.isFinite()) return null
    return ranges.firstNotNullOfOrNull { it.aheadOfMs(nowSeconds) }?.coerceAtLeast(0L)
}

/**
 * The item's length in milliseconds: the player's own [durationSeconds] when it is a finite, positive number, else
 * the end of the last of [seekable], which a growing recording reports instead; null when neither says.
 */
fun durationFromRanges(durationSeconds: Double?, seekable: List<TimeSpan>): Long? {
    val seconds = durationSeconds?.takeIf { it.isFinite() && it > 0 }
        ?: seekable.lastOrNull()?.endSeconds?.takeIf { it.isFinite() && it > 0 }
    return seconds?.let(::secondsToMs)?.takeIf { it > 0L }
}

private fun secondsToMs(seconds: Double): Long = (seconds * MS_PER_SECOND).toLong()

private const val MS_PER_SECOND = 1_000

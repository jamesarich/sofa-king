package dev.jamesrich.sofaking.ui.player

import kotlin.time.Instant

/** Fraction of a program elapsed at [now], clamped to 0..1. */
internal fun programProgress(start: Instant, stop: Instant, now: Instant): Float {
    val total = (stop - start).inWholeMilliseconds
    if (total <= 0L) return 0f
    val elapsed = (now - start).inWholeMilliseconds
    return (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

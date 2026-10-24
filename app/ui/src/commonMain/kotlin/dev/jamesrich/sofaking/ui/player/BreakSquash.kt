package dev.jamesrich.sofaking.ui.player

import kotlin.math.cos
import kotlin.math.exp

/**
 * How squashed the break screen's mark is against a wall [seconds] after hitting it: pressed in, then a quick wobble
 * back out, and 1 from [SQUASH_SECONDS] on.
 */
internal fun squashAfter(seconds: Double): Float = if (seconds < 0 || seconds >= SQUASH_SECONDS) {
    1f
} else {
    (1 - SQUASH_DEPTH * exp(-seconds * SQUASH_DAMPING) * cos(seconds * SQUASH_WOBBLE)).toFloat()
}

/** How far a hit presses the mark in, how fast the wobble dies away and how fast it wobbles, in radians a second. */
private const val SQUASH_DEPTH = 0.2
private const val SQUASH_DAMPING = 9.0
private const val SQUASH_WOBBLE = 28.0
internal const val SQUASH_SECONDS = 0.6

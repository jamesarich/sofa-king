package dev.jamesrich.sofaking.ui.player

/**
 * How long to wait before reconnect [attempt] (0-based) after a stream fails or ends. The server answers
 * 502 or 503 for a few seconds while it switches sources, so the first tries come quickly and later ones
 * settle at [RECONNECT_CAP_MS] for as long as the channel stays on screen.
 */
internal fun reconnectDelayMs(attempt: Int): Long = RECONNECT_STEPS_MS.getOrElse(attempt) { RECONNECT_CAP_MS }

private val RECONNECT_STEPS_MS = listOf(2_000L, 4_000L, 8_000L, 15_000L)
internal const val RECONNECT_CAP_MS = 30_000L

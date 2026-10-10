package dev.jamesrich.sofaking.ui.player

/**
 * Spots a live connection that has stopped delivering. Dispatcharr can stop feeding one client while the
 * channel keeps flowing, and a fresh connection then gets its buffered seconds at once; left alone, the
 * player drains its whole buffer and freezes for several seconds before Media3 reconnects.
 */
internal class StallWatch(
    private val stalledAfterMs: Long = STALLED_AFTER_MS,
    private val reconnectBelowMs: Long = RECONNECT_BELOW_MS,
) {
    private var lastBufferedPositionMs = Long.MIN_VALUE
    private var lastProgressAtMs = 0L

    /**
     * Called on a timer while the player is meant to be playing. True when the download has not moved for
     * [stalledAfterMs] and less than [reconnectBelowMs] is left to play, so reconnecting costs only what the
     * old connection would never have delivered.
     */
    fun shouldReconnect(nowMs: Long, bufferedPositionMs: Long, bufferedDurationMs: Long): Boolean {
        // Any change counts as progress: a reconnect or Media3's own reset moves the position back to zero.
        if (bufferedPositionMs != lastBufferedPositionMs) {
            lastBufferedPositionMs = bufferedPositionMs
            lastProgressAtMs = nowMs
            return false
        }
        if (nowMs - lastProgressAtMs < stalledAfterMs || bufferedDurationMs > reconnectBelowMs) return false
        reset()
        return true
    }

    /** Forget the last reading, for a new stream or after a pause. */
    fun reset() {
        lastBufferedPositionMs = Long.MIN_VALUE
    }

    companion object {
        /** Dispatcharr sends a burst about every 2 s, so twice that without one is a stall. */
        const val STALLED_AFTER_MS = 4_000L
        const val RECONNECT_BELOW_MS = 3_000L
    }
}

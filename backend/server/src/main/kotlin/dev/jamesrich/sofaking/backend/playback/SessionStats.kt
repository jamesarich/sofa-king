package dev.jamesrich.sofaking.backend.playback

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What the relay saw of one session's media, for the portal's Watching now: the [bytes] sent its player, the
 * [segments] served, the [stalls] (an upstream that refused or dropped a media request before anything was answered),
 * and the [switches] of source the session made.
 */
class SessionStats(start: Instant) {
    val bytes = AtomicLong()
    val segments = AtomicLong()
    val stalls = AtomicLong()
    val switches = AtomicInteger()

    private var sampledAt = start
    private var sampledBytes = 0L
    private var rate = 0

    /**
     * The rate the player was sent media at, in kilobits a second: over the time since the last reading at least
     * [WINDOW] old, else the last reading.
     */
    @Synchronized fun bitrateKbps(now: Instant): Int {
        val elapsed = Duration.between(sampledAt, now)
        if (elapsed < WINDOW && rate > 0) return rate
        val millis = elapsed.toMillis()
        if (millis <= 0) return rate
        val sent = bytes.get()
        rate = ((sent - sampledBytes) * BITS_PER_BYTE / millis).toInt()
        if (elapsed >= WINDOW) {
            sampledAt = now
            sampledBytes = sent
        }
        return rate
    }

    private companion object {
        val WINDOW: Duration = Duration.ofSeconds(10)
        const val BITS_PER_BYTE = 8L
    }
}

package dev.jamesrich.sofaking.ui.player

import kotlin.time.Duration.Companion.milliseconds

/** A pause longer than this resumes at the live edge rather than where it stopped. */
internal const val LIVE_RESUME_AFTER_MS = 30_000L

/** True when playback should jump to the live edge before it resumes. */
internal fun shouldResumeAtLive(pausedAtMs: Long?, nowMs: Long): Boolean =
    pausedAtMs != null && nowMs - pausedAtMs > LIVE_RESUME_AFTER_MS

/** What the pause pill says: how far behind live the picture is, and whether resuming now jumps to the live edge. */
internal data class PauseStatus(val behindSeconds: Int, val resumesLive: Boolean)

internal fun pauseStatus(pausedAtMs: Long, nowMs: Long): PauseStatus {
    val pausedFor = (nowMs - pausedAtMs).coerceAtLeast(0L)
    return PauseStatus(pausedFor.milliseconds.inWholeSeconds.toInt(), shouldResumeAtLive(pausedAtMs, nowMs))
}

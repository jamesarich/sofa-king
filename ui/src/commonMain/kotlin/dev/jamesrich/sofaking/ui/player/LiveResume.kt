package dev.jamesrich.sofaking.ui.player

/** A pause longer than this resumes at the live edge rather than where it stopped. */
internal const val LIVE_RESUME_AFTER_MS = 30_000L

/** True when playback should jump to the live edge before it resumes. */
internal fun shouldResumeAtLive(pausedAtMs: Long?, nowMs: Long): Boolean =
    pausedAtMs != null && nowMs - pausedAtMs > LIVE_RESUME_AFTER_MS

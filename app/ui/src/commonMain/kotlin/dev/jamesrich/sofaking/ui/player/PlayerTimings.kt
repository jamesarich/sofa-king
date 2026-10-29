package dev.jamesrich.sofaking.ui.player

/** How long a side card (the stream card, the audio and subtitles card) stays after its last key or tap. */
internal const val PANEL_IDLE_MS = 15_000L

/** A toast that confirms a state change: Live, volume, a caption setting. */
internal const val STATE_TOAST_MS = 2_000L

/** A notice that carries news to read: a skipped break or intro, a failed subtitle, a record or zoom result. */
internal const val NOTICE_TOAST_MS = 3_000L

/**
 * True while a player's bar must stay up instead of leaving after [BAR_IDLE_MS]: playback is [paused], a scrub is in
 * progress, the stream [failed] (the bar carries the way to retry), or an intro skip is counting down.
 */
internal fun barHeld(paused: Boolean, scrubbing: Boolean, failed: Boolean, autoSkip: Boolean): Boolean =
    paused || scrubbing || failed || autoSkip

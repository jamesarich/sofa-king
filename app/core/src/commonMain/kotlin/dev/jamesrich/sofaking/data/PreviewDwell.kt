package dev.jamesrich.sofaking.data

/** How long focus rests on one channel before its preview unmutes. */
const val UNMUTE_AFTER_MS = 5_000L

/** How long before the preview opens full screen by itself it starts counting down the seconds. */
const val OPEN_COUNTDOWN_MS = 3_000L

/**
 * How long focus rests on a channel before its preview tunes: long enough to skip rows passed under a held D-pad
 * (repeats every 50-150 ms), since each tune opens a session and cold-starts a relay.
 */
const val PREVIEW_DEBOUNCE_MS = 350L

/**
 * The preview's rest timers, on the caller's monotonic clock in milliseconds. Focus resting on one channel
 * unmutes its preview after [UNMUTE_AFTER_MS] and opens it full screen after [fullScreenAfterMs]; null means it
 * never opens, counting down the last [OPEN_COUNTDOWN_MS] in [opensInSeconds] so the viewer sees it coming. A key press
 * or a focus move restarts both and mutes again. [suppressedChannelId] is the channel
 * the full screen was just left on: it unmutes as usual but never reopens itself until another channel has
 * taken focus. Touch and mouse input restart the rest too, and also stop the full screen opening by itself until
 * the next key press: focus sits on a cell while a finger scrolls the grid, and the user is reading, not resting.
 */
class PreviewDwell(
    suppressedChannelId: String? = null,
    private val fullScreenAfterMs: Long? = AutoOpenDelay.AFTER_10_SECONDS.afterMs,
    pointerDriven: Boolean = false,
) {
    /** The channel focus rests on, or null while focus is off the grid (chips, search). */
    var channelId: String? = null
        private set

    var suppressedChannelId: String? = suppressedChannelId
        private set

    private var restedSinceMs = 0L

    /** True from a touch or mouse press until the next key press; the full screen never opens by itself meanwhile. */
    var pointerDriven: Boolean = pointerDriven
        private set

    /** Focus landed on [channelId] (null when it left the grid) at [nowMs]; another channel lifts the suppression. */
    fun onFocus(channelId: String?, nowMs: Long) {
        if (channelId != null && channelId != suppressedChannelId) suppressedChannelId = null
        this.channelId = channelId
        restedSinceMs = nowMs
    }

    /** A key pressed at [nowMs]: the rest starts over, and the full screen may open by itself again. */
    fun onKey(nowMs: Long) {
        restedSinceMs = nowMs
        pointerDriven = false
    }

    /** A touch or mouse press or scroll at [nowMs]: the rest starts over, and the full screen waits for a key. */
    fun onPointer(nowMs: Long) {
        restedSinceMs = nowMs
        pointerDriven = true
    }

    /** The rest starts over at [nowMs] without any input, as when the app comes back to the front. */
    fun restart(nowMs: Long) {
        restedSinceMs = nowMs
    }

    fun isMuted(nowMs: Long): Boolean = channelId == null || nowMs - restedSinceMs < UNMUTE_AFTER_MS

    fun opensFullScreen(nowMs: Long): Boolean = untilFullScreenMs(nowMs)?.let { it <= 0 } ?: false

    /**
     * The whole seconds left before the full screen opens by itself, through its last [OPEN_COUNTDOWN_MS]: 3, 2, 1.
     * Null before then, and whenever it will not open.
     */
    fun opensInSeconds(nowMs: Long): Int? {
        val left = untilFullScreenMs(nowMs) ?: return null
        return if (left in 1..OPEN_COUNTDOWN_MS) ((left + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt() else null
    }

    /**
     * Milliseconds until [isMuted], [opensInSeconds] or [opensFullScreen] next changes, or null when none will.
     */
    fun nextTransitionMs(nowMs: Long): Long? {
        if (channelId == null) return null
        val rested = nowMs - restedSinceMs
        if (rested < UNMUTE_AFTER_MS) return UNMUTE_AFTER_MS - rested
        val left = untilFullScreenMs(nowMs)
        return when {
            left == null || left <= 0 -> null

            left > OPEN_COUNTDOWN_MS -> left - OPEN_COUNTDOWN_MS

            // To the next whole second; the last of them opens the full screen.
            else -> (left - 1) % MS_PER_SECOND + 1
        }
    }

    /** Milliseconds until the full screen opens by itself, zero or less once it is due; null when it never will. */
    private fun untilFullScreenMs(nowMs: Long): Long? {
        val after = fullScreenAfterMs ?: return null
        if (pointerDriven || channelId == null || channelId == suppressedChannelId) return null
        return after - (nowMs - restedSinceMs)
    }
}

private const val MS_PER_SECOND = 1_000L

package dev.jamesrich.sofaking.ui.preview

/** How long focus rests on one channel before its preview unmutes, and before it opens full screen. */
internal const val UNMUTE_AFTER_MS = 5_000L
internal const val FULL_SCREEN_AFTER_MS = 10_000L

/**
 * The preview's rest timers, on the caller's monotonic clock in milliseconds. Focus resting on one channel
 * unmutes its preview after [unmuteAfterMs] and opens it full screen after [fullScreenAfterMs]; a key press
 * or a focus move restarts both and mutes again. [suppressedChannelId] is the channel the full screen was
 * just left on: it unmutes as usual but never reopens itself until another channel has taken focus.
 */
internal class PreviewDwell(
    suppressedChannelId: String? = null,
    private val unmuteAfterMs: Long = UNMUTE_AFTER_MS,
    private val fullScreenAfterMs: Long = FULL_SCREEN_AFTER_MS,
) {
    /** The channel focus rests on, or null while focus is off the grid (chips, search). */
    var channelId: String? = null
        private set

    var suppressedChannelId: String? = suppressedChannelId
        private set

    private var restedSinceMs = 0L

    /** Focus landed on [channelId] (null when it left the grid) at [nowMs]; another channel lifts the suppression. */
    fun onFocus(channelId: String?, nowMs: Long) {
        if (channelId != null && channelId != suppressedChannelId) suppressedChannelId = null
        this.channelId = channelId
        restedSinceMs = nowMs
    }

    /** A key pressed at [nowMs]: the rest starts over. */
    fun onKey(nowMs: Long) {
        restedSinceMs = nowMs
    }

    fun isMuted(nowMs: Long): Boolean = channelId == null || nowMs - restedSinceMs < unmuteAfterMs

    fun opensFullScreen(nowMs: Long): Boolean =
        channelId != null && channelId != suppressedChannelId && nowMs - restedSinceMs >= fullScreenAfterMs

    /** Milliseconds until [isMuted] or [opensFullScreen] next changes, or null when neither will. */
    fun nextTransitionMs(nowMs: Long): Long? {
        val id = channelId ?: return null
        val rested = nowMs - restedSinceMs
        if (rested < unmuteAfterMs) return unmuteAfterMs - rested
        if (id != suppressedChannelId && rested < fullScreenAfterMs) return fullScreenAfterMs - rested
        return null
    }
}

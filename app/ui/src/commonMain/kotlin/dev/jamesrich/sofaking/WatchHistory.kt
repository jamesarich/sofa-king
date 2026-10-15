package dev.jamesrich.sofaking

import kotlinx.serialization.Serializable

/** The channel being watched and the one before it, kept across trips to the guide and configuration changes so Last works. */
@Serializable
internal data class WatchHistory(val watching: String? = null, val previous: String? = null) {
    /** [guideId] started playing; the channel it replaced becomes the previous one. */
    fun tuned(guideId: String): WatchHistory = if (guideId == watching) this else WatchHistory(guideId, watching)

    /** The channel Last returns to when the player opens on [guideId]. */
    fun previousFor(guideId: String): String? = if (guideId == watching) previous else watching
}

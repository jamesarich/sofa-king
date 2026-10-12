package dev.jamesrich.sofaking

/** The channel being watched and the one before it, held by the activity so Last works across trips to the guide. */
internal data class WatchHistory(val watching: String? = null, val previous: String? = null) {
    /** [guideId] started playing; the channel it replaced becomes the previous one. */
    fun tuned(guideId: String): WatchHistory = if (guideId == watching) this else WatchHistory(guideId, watching)

    /** The channel Last returns to when the player opens on [guideId]. */
    fun previousFor(guideId: String): String? = if (guideId == watching) previous else watching
}

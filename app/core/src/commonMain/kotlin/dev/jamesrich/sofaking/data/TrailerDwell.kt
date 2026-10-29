package dev.jamesrich.sofaking.data

/** How long a movie's or show's page rests before its trailer plays behind the title. */
const val TRAILER_AFTER_MS = 3_000L

/**
 * The trailer of [detail]'s movie or show that plays behind its page, or null: a trailer extra of an item kids mode
 * allows while [kids] says so. Other pages (a season, an episode, music) have none.
 */
fun trailerOf(detail: MediaItemDetail, kids: Boolean): MediaItem? {
    val item = detail.item
    if (item.kind != ItemKind.MOVIE && item.kind != ItemKind.SHOW) return null
    if (kids && !KidsMode.allows(item)) return null
    return detail.extras.firstOrNull { it.kind == ItemKind.EXTRA && it.extra == ExtraKind.TRAILER }
}

/** What a page's trailer is doing. */
enum class TrailerPhase {
    /** Waiting for the page to rest. */
    RESTING,

    /** Its session is open and its picture shows. */
    PLAYING,

    /** It ended, failed or lost the player: it does not start again for this page. */
    DONE,
}

/**
 * The rest timer of a page's trailer, on the caller's monotonic clock in milliseconds. The page must rest for
 * [restMs] with no key or touch before the trailer [isDue]; any input restarts the rest. It is due only while the app
 * is in front, the page is the one showing, and [PlaybackSessions] holds nothing and no music is queued, since a
 * trailer never takes the player from anything. Once playing it is not restarted by input; the background, the page
 * being left or anything else taking the player [stopped] it, and a trailer that [finished] does not start again.
 */
class TrailerDwell(private val restMs: Long = TRAILER_AFTER_MS, done: Boolean = false) {
    var phase: TrailerPhase = if (done) TrailerPhase.DONE else TrailerPhase.RESTING
        private set

    private var restedSinceMs = 0L

    /**
     * A key or touch at [nowMs], which may be read late: the rest starts over from it, never from before the rest it
     * already has. A trailer already playing carries on.
     */
    fun onInput(nowMs: Long) {
        restedSinceMs = maxOf(restedSinceMs, nowMs)
    }

    /** The rest starts over at [nowMs] without any input, as when the page or the app comes back to the front. */
    fun restart(nowMs: Long) {
        restedSinceMs = nowMs
    }

    /**
     * Whether the trailer should open at [nowMs]: the page has rested, and nothing else may take the player. [playable]
     * is a trailer to play, [inFront] the app being started with this page on top, [held] a session held by the app
     * and [music] a song queued or held.
     */
    fun isDue(nowMs: Long, playable: Boolean, inFront: Boolean, held: Boolean, music: Boolean): Boolean =
        phase == TrailerPhase.RESTING && playable && inFront && !held && !music && nowMs - restedSinceMs >= restMs

    /** Milliseconds until the rest is over, or null when the trailer is playing, done or has nothing to wait for. */
    fun nextCheckMs(nowMs: Long): Long? {
        if (phase != TrailerPhase.RESTING) return null
        return (restMs - (nowMs - restedSinceMs)).coerceAtLeast(0)
    }

    /** The trailer's session opened and its picture shows. */
    fun started() {
        if (phase == TrailerPhase.RESTING) phase = TrailerPhase.PLAYING
    }

    /** The trailer stopped for a reason that may pass, as the app leaving the front: it rests again from [nowMs]. */
    fun stopped(nowMs: Long) {
        if (phase != TrailerPhase.PLAYING) return
        phase = TrailerPhase.RESTING
        restedSinceMs = nowMs
    }

    /** The trailer ended, failed or lost the player; it does not start again for this page. */
    fun finished() {
        phase = TrailerPhase.DONE
    }
}

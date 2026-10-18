package dev.jamesrich.sofaking.contract

/** Values the server sends and clients branch on. They stay strings on the wire: a client reads an unknown one as its fallback. */
object Wire {
    object Intent {
        const val PREVIEW = "preview"
        const val WATCH = "watch"
    }

    object GuideStatus {
        const val UNASSIGNED = "unassigned"
        const val EMPTY = "empty"
        const val AVAILABLE = "available"
        const val STALE = "stale"

        /** A guide query's row for a channel the lineup no longer lists: no programs, and the catalog has changed. */
        const val REMOVED = "removed"
    }

    /** `ApiError.code` values a client acts on. */
    object ErrorCode {
        const val AUTH_REQUIRED = "AUTH_REQUIRED"
        const val CHANNEL_NOT_FOUND = "CHANNEL_NOT_FOUND"
        const val SESSION_EXPIRED = "SESSION_EXPIRED"
        const val STREAM_CHANGED = "STREAM_CHANGED"
        const val RESYNC_REQUIRED = "RESYNC_REQUIRED"
        const val CAPACITY_EXHAUSTED = "CAPACITY_EXHAUSTED"

        /** `POST /api/v1/pair` with a code that is wrong, used up or expired. */
        const val PAIRING_INVALID = "PAIRING_INVALID"
    }

    /** What a library holds. */
    object LibraryKind {
        const val MOVIES = "movies"
        const val SHOWS = "shows"
        const val VIDEOS = "videos"
    }

    /** What a library item is; shows and seasons are folders, the others play. */
    object ItemKind {
        const val MOVIE = "movie"
        const val SHOW = "show"
        const val SEASON = "season"
        const val EPISODE = "episode"
        const val VIDEO = "video"
    }

    /** The library home's rows. */
    object ShelfId {
        const val CONTINUE = "continue"
        const val RECENT = "recent"
    }

    /** A pairing code is this many decimal digits. */
    const val PAIRING_CODE_LENGTH = 6

    /** One guide query names at most this many channels, over at most this many hours. */
    const val GUIDE_MAX_CHANNELS = 200
    const val GUIDE_MAX_HOURS = 24
}

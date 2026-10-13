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
    }

    /** `ApiError.code` values a client acts on. */
    object ErrorCode {
        const val AUTH_REQUIRED = "AUTH_REQUIRED"
        const val CHANNEL_NOT_FOUND = "CHANNEL_NOT_FOUND"
        const val SESSION_EXPIRED = "SESSION_EXPIRED"
        const val STREAM_CHANGED = "STREAM_CHANGED"
        const val RESYNC_REQUIRED = "RESYNC_REQUIRED"
        const val CAPACITY_EXHAUSTED = "CAPACITY_EXHAUSTED"
    }

    /** One guide query names at most this many channels, over at most this many hours. */
    const val GUIDE_MAX_CHANNELS = 200
    const val GUIDE_MAX_HOURS = 24
}

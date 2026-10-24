package dev.jamesrich.sofaking.contract

/**
 * Values the server sends and clients branch on. They stay strings on the wire: a client reads
 * an unknown one as its fallback.
 */
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

    /** `Recording.state`: scheduled, running, kept, or not recorded and why in `reason`. */
    object RecordingState {
        const val SCHEDULED = "scheduled"
        const val RECORDING = "recording"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
    }

    /** How far the server got looking for a recording's commercial breaks. */
    object CommercialScan {
        const val SCANNING = "scanning"
        const val DONE = "done"
        const val FAILED = "failed"
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

        /** A trailer, featurette or other extra, listed in its movie's or show's [LibraryItemDetail.extras]. */
        const val EXTRA = "extra"
    }

    /** What kind of extra an [ItemKind.EXTRA] item is, from its folder or its name; a client shows one it does not know as an extra. */
    object ExtraKind {
        const val TRAILER = "trailer"
        const val FEATURETTE = "featurette"
        const val BEHIND_THE_SCENES = "behind_the_scenes"
        const val DELETED_SCENE = "deleted_scene"
        const val INTERVIEW = "interview"
        const val SCENE = "scene"
        const val SHORT = "short"
        const val OTHER = "other"
    }

    /** How a VOD session plays its file. */
    object VodMode {
        /** The file as is, with byte ranges. */
        const val DIRECT = "direct"

        /** The streams as they are, in an HLS container. */
        const val REMUX = "remux"

        /** The video as it is and the audio converted. */
        const val AUDIO = "audio"

        /** Video and audio converted. */
        const val TRANSCODE = "transcode"
    }

    /** What a [MediaSegment] of a library item holds. */
    object SegmentKind {
        /** The opening titles and theme an episode shares with others of its show. */
        const val INTRO = "intro"

        /** The end credits an episode shares with others of its show; an app that does not know the kind ignores it. */
        const val CREDITS = "credits"
    }

    /** The library home's rows. */
    object ShelfId {
        const val CONTINUE = "continue"
        const val RECENT = "recent"
    }

    /**
     * How a server advertises itself on the LAN over mDNS (DNS-SD): this service type, the
     * API's port, and a TXT record.
     */
    object Discovery {
        const val SERVICE_TYPE = "_sofaking._tcp"

        /** The TXT key holding the server's product version, such as `0.1.0-dev.330`. */
        const val TXT_VERSION = "version"
    }

    /** A pairing code is this many decimal digits. */
    const val PAIRING_CODE_LENGTH = 6

    /** One guide query names at most this many channels, over at most this many hours. */
    const val GUIDE_MAX_CHANNELS = 200
    const val GUIDE_MAX_HOURS = 24
}

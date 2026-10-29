package dev.jamesrich.sofaking.contract

import kotlinx.serialization.Serializable

// Media libraries under /api/v1/libraries and /api/v1/library. The same rule as the live TV types: add a field with
// a default, give a field no client reads a default, and never rename or remove one.

/**
 * A library as the app lists it; [kind] is a [Wire.LibraryKind] value and [items] counts its movies, shows or videos.
 */
@Serializable data class Library(val id: String, val name: String, val kind: String, val items: Int = 0)

/** The household's place in an item: where it stopped, and whether it was watched to the end. */
@Serializable data class PlayState(
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val watched: Boolean = false,
    val updatedAt: String = "",
    /** Taken off Continue watching by hand; the next play clears it. */
    val dismissed: Boolean = false,
)

/**
 * One movie, show, season, episode, video or extra ([kind] is a [Wire.ItemKind] value). [poster] and [backdrop] are
 * server-relative `/artwork/KEY` paths a client fetches without credentials, like logos. An episode carries its
 * show's title and id; a show or season counts [children] and how many episodes are [unwatched].
 */
@Serializable data class LibraryItem(
    val id: String,
    val libraryId: String,
    val kind: String,
    val title: String,
    val parentId: String? = null,
    val showId: String? = null,
    val showTitle: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeEnd: Int? = null,
    val aired: String? = null,
    val overview: String? = null,
    val durationMs: Long? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val addedAt: String = "",
    val genres: List<String> = emptyList(),
    val contentRating: String? = null,
    val rating: Double? = null,
    /** The video's height in pixels, for a 4K or HD badge; null for a show or season. */
    val videoHeight: Int? = null,
    val hdr: Boolean = false,
    val children: Int = 0,
    val unwatched: Int = 0,
    val playState: PlayState? = null,
    /** For an [Wire.ItemKind.EXTRA], its [Wire.ExtraKind] value. */
    val extra: String? = null,
    /** The movie's or show's TMDB id, from its file name, its `.nfo` or the TMDB match; null when none is known. */
    val tmdbId: Int? = null,
    /** The TheTVDB id, from the name, the `.nfo` or TMDB's record of a show. */
    val tvdbId: Int? = null,
    /** The IMDb id (`tt…`), from the name, the `.nfo` or TMDB's record. */
    val imdbId: String? = null,
    /**
     * For an album or a track, its album artist's id and name; for a track whose own artist differs, as on a
     * compilation, [trackArtist] names that one.
     */
    val artistId: String? = null,
    val artist: String? = null,
    val trackArtist: String? = null,
    /** For a track, its album's title (its album is [parentId]), its number on its disc and the disc's number. */
    val album: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    /** For a song, album or artist: whether the household likes it, how often the song was played and when last. */
    val favorite: Boolean = false,
    val plays: Int = 0,
    val lastPlayedAt: String? = null,
)

/**
 * One page of a library's items, in the order asked for; [next] is the cursor for the page after, null on the last, and
 * [genres] every genre in the library, for a filter. The first page in title order also counts the items under each
 * first letter in [letters], in order, so a client can jump to one. A search names in [people] the cast and directors
 * whose names hold the query, the most credited first, for the titles it found by them.
 */
@Serializable data class LibraryPage(
    val items: List<LibraryItem>,
    val total: Int = 0,
    val next: String? = null,
    val genres: List<String> = emptyList(),
    val letters: List<LetterCount>? = null,
    val people: List<String> = emptyList(),
)

/**
 * A run of [count] items in title order whose titles start with [letter]: an upper-case letter, or `#` for a digit
 * or a symbol. The runs follow the items' order, so their counts added up give where each starts.
 */
@Serializable data class LetterCount(val letter: String, val count: Int)

/** A row of the library home ([id] is a [Wire.ShelfId] value), for one library when [libraryId] is set. */
@Serializable
data class LibraryShelf(val id: String, val title: String, val items: List<LibraryItem>, val libraryId: String? = null)

/** The library home: Continue watching, the Watchlist, then Recently added per library, and every library. */
@Serializable data class LibraryHome(val shelves: List<LibraryShelf>, val libraries: List<Library> = emptyList())

/**
 * An audio or subtitle track the item offers; [index] is the stream's index in its file, or from 1000 a subtitle
 * file beside it. A subtitle whose [codec] is pictures (`hdmv_pgs_subtitle`, `dvd_subtitle`, `dvb_subtitle`) has no
 * text, so a player shows it by asking for it in [OpenVodPlayback.subtitleIndex].
 */
@Serializable data class MediaTrack(
    val index: Int,
    val codec: String,
    val language: String? = null,
    val title: String? = null,
    val channels: Int? = null,
    val default: Boolean = false,
    val forced: Boolean = false,
)

/**
 * A span of a movie, episode or video from [startMs] to [endMs] that a player offers to skip; [kind]
 * is a [Wire.SegmentKind] value.
 */
@Serializable data class MediaSegment(val kind: String, val startMs: Long, val endMs: Long)

/**
 * An item with what its page shows: for a show its seasons, for a season or a show without seasons its episodes,
 * for a show the episode to play [next]; for a file its tracks and the [segments] the server found in it, such as
 * an episode's intro.
 */
@Serializable data class LibraryItemDetail(
    val item: LibraryItem,
    val children: List<LibraryItem> = emptyList(),
    val next: LibraryItem? = null,
    val tagline: String? = null,
    val studio: String? = null,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val audio: List<MediaTrack> = emptyList(),
    val subtitles: List<MediaTrack> = emptyList(),
    val container: String? = null,
    val videoCodec: String? = null,
    val segments: List<MediaSegment> = emptyList(),
    /**
     * Thumbnails to show over the seek bar while scrubbing; null until the server has made them, or for a short
     * file.
     */
    val scrub: ScrubThumbnails? = null,
    /** A movie's or show's trailers, featurettes and other extras, trailers first. */
    val extras: List<LibraryItem> = emptyList(),
    /**
     * For a movie, show, video or artist, others in its library that share its genres, the most shared first; for an
     * artist, the artists ListenBrainz's listeners play alongside them come first.
     */
    val related: List<LibraryItem> = emptyList(),
    /** For an artist, their songs ListenBrainz's listeners play most, most played first. */
    val popular: List<LibraryItem> = emptyList(),
)

/**
 * A playable item's scrub thumbnails: JPEG [sheets] (URLs, as `poster` is) of [columns] by [rows] tiles, each
 * [tileWidth] by [tileHeight] pixels, left to right and then down. Tile `n` overall, on sheet `n / (columns * rows)`,
 * shows the file at `n * intervalMs`; there are [frames] in all.
 */
@Serializable data class ScrubThumbnails(
    val intervalMs: Long,
    val tileWidth: Int,
    val tileHeight: Int,
    val columns: Int,
    val rows: Int,
    val frames: Int,
    val sheets: List<String>,
)

/**
 * `PUT /api/v1/library/items/{id}/play-state`: where playback stopped; [watched] set by hand wins over the position.
 */
@Serializable
data class UpdatePlayState(
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val watched: Boolean? = null,
    /** True takes the item off Continue watching, false puts it back; either leaves its place and watched flag. */
    val dismissed: Boolean? = null,
)

/** The body of a request to like (`favorite` true) or unlike a song, album or artist. */
@Serializable
data class UpdateFavorite(val favorite: Boolean)

/**
 * What a player can play as is: containers (`mp4`, `mkv`, `webm`, `ts`), video codecs (`h264`, `hevc`, `vp9`, `av1`)
 * and audio codecs (`aac`, `ac3`, `eac3`, `mp3`, `opus`, `flac`, `dts`, `truehd`), the tallest video it decodes, and
 * whether it shows HDR. What it cannot play the server remuxes or converts. An empty profile plays
 * only what the server converts.
 */
@Serializable data class ClientProfile(
    val containers: List<String> = emptyList(),
    val videoCodecs: List<String> = emptyList(),
    val audioCodecs: List<String> = emptyList(),
    val maxHeight: Int? = null,
    val hdr: Boolean = false,
    /**
     * The video codecs it also decodes at more than 8 bits a sample, such as `hevc` for HEVC Main 10. Null for a
     * player that does not say, which the server takes as every codec in [videoCodecs] but `h264`.
     */
    val deepColor: List<String>? = null,
)

/**
 * `POST /api/v1/vod-sessions`: play the movie, episode or video [itemId], or the recording [recordingId], from
 * [startMs], with the audio stream whose index is [audioIndex] (the file's default when null). [subtitleIndex] names
 * a picture subtitle track the server draws into the picture, which converts the stream. With [wholePlaylist] a
 * converted item comes as a playlist of the whole item that seeks anywhere, as a player that cannot ask for a restart
 * needs: its session's [VodSession.startMs] is 0, and the player seeks to [startMs] itself. [intent] is a
 * [Wire.Intent] value: a `preview` session of a library item (a trailer playing behind a page) holds a short lease and
 * yields its conversion slot to a `watch` open; a server that does not know the field plays it as `watch`, which
 * [VodSession.intent] then says.
 */
@Serializable data class OpenVodPlayback(
    val itemId: String = "",
    val profile: ClientProfile = ClientProfile(),
    val startMs: Long = 0,
    val audioIndex: Int? = null,
    val recordingId: String? = null,
    val subtitleIndex: Int? = null,
    val wholePlaylist: Boolean = false,
    val intent: String = Wire.Intent.WATCH,
)

/** `PATCH /api/v1/vod-sessions/{id}`: renew the lease, and with [startMs] restart a converted stream there. */
@Serializable data class UpdateVodPlayback(val startMs: Long? = null)

/**
 * A library file being played. [mode] is a [Wire.VodMode] value. The descriptor's protocol is `file` for direct play,
 * which seeks on its own, or `hls` for the others, whose media time 0 is [startMs] into the item: a seek past what the
 * stream holds asks for a restart there with [UpdateVodPlayback]. [durationMs] is the item's whole length. A recording
 * has [recordingId] and an empty [itemId]; while it records, its stream grows and
 * [durationMs] is the length it will have. [intent] is the [Wire.Intent] the server holds the session for; a client
 * that asked for `preview` and reads `watch` is talking to a server that ignored it.
 */
@Serializable data class VodSession(
    val id: String,
    val itemId: String,
    val mode: String,
    val descriptor: PlaybackDescriptor,
    val startMs: Long = 0,
    val durationMs: Long? = null,
    val expiresAt: String = "",
    val recordingId: String? = null,
    val intent: String = Wire.Intent.WATCH,
)

/**
 * An entry of the household's watchlist: a library [item] to watch ([kind] [Wire.WatchlistKind.ITEM], which an older
 * server's library may no longer hold, so [item] is then null), or a guide [title] to watch for
 * ([Wire.WatchlistKind.TITLE]) on [channelId] only when set, with the [next] airing of it the guide holds in the
 * coming week, which may be on now.
 */
@Serializable data class WatchlistEntry(
    val id: String,
    val kind: String,
    val title: String,
    val itemId: String? = null,
    val channelId: String? = null,
    val addedAt: String = "",
    val item: LibraryItem? = null,
    val next: Program? = null,
)

/** The household's watchlist, newest first. */
@Serializable data class Watchlist(val entries: List<WatchlistEntry> = emptyList())

/**
 * Adds a library item ([kind] [Wire.WatchlistKind.ITEM] with [itemId]) or a guide title ([Wire.WatchlistKind.TITLE]
 * with [title], on [channelId] only when set) to the watchlist. Adding what the list already holds returns that entry.
 */
@Serializable data class AddWatchlistEntry(
    val kind: String,
    val itemId: String? = null,
    val title: String? = null,
    val channelId: String? = null,
)

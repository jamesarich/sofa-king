package dev.jamesrich.sofaking.contract

import kotlinx.serialization.Serializable

// Media libraries under /api/v1/libraries and /api/v1/library. The same rule as the live TV types: add a field with
// a default, give a field no client reads a default, and never rename or remove one.

/** A library as the app lists it; [kind] is a [Wire.LibraryKind] value and [items] counts its movies, shows or videos. */
@Serializable data class Library(val id: String, val name: String, val kind: String, val items: Int = 0)

/** The household's place in an item: where it stopped, and whether it was watched to the end. */
@Serializable data class PlayState(val positionMs: Long = 0, val durationMs: Long? = null, val watched: Boolean = false, val updatedAt: String = "")

/**
 * One movie, show, season, episode or video ([kind] is a [Wire.ItemKind] value). [poster] and [backdrop] are
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
)

/** One page of a library's items, in the order asked for; [next] is the cursor for the page after, null on the last, and [genres] every genre in the library, for a filter. */
@Serializable data class LibraryPage(val items: List<LibraryItem>, val total: Int = 0, val next: String? = null, val genres: List<String> = emptyList())

/** A row of the library home ([id] is a [Wire.ShelfId] value), for one library when [libraryId] is set. */
@Serializable data class LibraryShelf(val id: String, val title: String, val items: List<LibraryItem>, val libraryId: String? = null)

/** The library home: Continue watching, then Recently added per library, and every library. */
@Serializable data class LibraryHome(val shelves: List<LibraryShelf>, val libraries: List<Library> = emptyList())

/**
 * An audio or subtitle track the item offers; [index] is the stream's index in its file, or from 1000 a subtitle
 * file beside it. A subtitle whose [codec] is pictures (`hdmv_pgs_subtitle`, `dvd_subtitle`, `dvb_subtitle`) has no
 * text, so a player shows it by asking for it in [OpenVodPlayback.subtitleIndex].
 */
@Serializable data class MediaTrack(val index: Int, val codec: String, val language: String? = null, val title: String? = null, val channels: Int? = null, val default: Boolean = false, val forced: Boolean = false)

/**
 * An item with what its page shows: for a show its seasons, for a season or a show without seasons its episodes,
 * for a show the episode to play [next]; for a file its tracks.
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
)

/** `PUT /api/v1/library/items/{id}/play-state`: where playback stopped; [watched] set by hand wins over the position. */
@Serializable data class UpdatePlayState(val positionMs: Long = 0, val durationMs: Long? = null, val watched: Boolean? = null)

/**
 * What a player can play as is: containers (`mp4`, `mkv`, `webm`, `ts`), video codecs (`h264`, `hevc`, `vp9`, `av1`)
 * and audio codecs (`aac`, `ac3`, `eac3`, `mp3`, `opus`, `flac`, `dts`, `truehd`), the tallest video it decodes, and
 * whether it shows HDR. What it cannot play the server remuxes or converts. An empty profile plays only what the server converts.
 */
@Serializable data class ClientProfile(
    val containers: List<String> = emptyList(),
    val videoCodecs: List<String> = emptyList(),
    val audioCodecs: List<String> = emptyList(),
    val maxHeight: Int? = null,
    val hdr: Boolean = false,
)

/**
 * `POST /api/v1/vod-sessions`: play the movie, episode or video [itemId], or the recording [recordingId], from
 * [startMs], with the audio stream whose index is [audioIndex] (the file's default when null). [subtitleIndex] names
 * a picture subtitle track the server draws into the picture, which converts the stream. With [wholePlaylist] a
 * converted item comes as a playlist of the whole item that seeks anywhere, as a player that cannot ask for a restart
 * needs: its session's [VodSession.startMs] is 0, and the player seeks to [startMs] itself.
 */
@Serializable data class OpenVodPlayback(
    val itemId: String = "",
    val profile: ClientProfile = ClientProfile(),
    val startMs: Long = 0,
    val audioIndex: Int? = null,
    val recordingId: String? = null,
    val subtitleIndex: Int? = null,
    val wholePlaylist: Boolean = false,
)

/** `PATCH /api/v1/vod-sessions/{id}`: renew the lease, and with [startMs] restart a converted stream there. */
@Serializable data class UpdateVodPlayback(val startMs: Long? = null)

/**
 * A library file being played. [mode] is a [Wire.VodMode] value. The descriptor's protocol is `file` for direct play,
 * which seeks on its own, or `hls` for the others, whose media time 0 is [startMs] into the item: a seek past what the
 * stream holds asks for a restart there with [UpdateVodPlayback]. [durationMs] is the item's whole length. A recording
 * has [recordingId] and an empty [itemId]; while it records, its stream grows and [durationMs] is the length it will have.
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
)

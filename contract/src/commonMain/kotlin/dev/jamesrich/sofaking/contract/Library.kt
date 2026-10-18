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

/** One page of a library's items, in the order asked for; [next] is the cursor for the page after, null on the last. */
@Serializable data class LibraryPage(val items: List<LibraryItem>, val total: Int = 0, val next: String? = null)

/** A row of the library home ([id] is a [Wire.ShelfId] value), for one library when [libraryId] is set. */
@Serializable data class LibraryShelf(val id: String, val title: String, val items: List<LibraryItem>, val libraryId: String? = null)

/** The library home: Continue watching, then Recently added per library, and every library. */
@Serializable data class LibraryHome(val shelves: List<LibraryShelf>, val libraries: List<Library> = emptyList())

/** An audio or subtitle track the item offers; [index] is the stream's index in its file. */
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

package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlin.time.Instant

/** What a library holds; [OTHER] stands for a kind a newer server added. */
enum class LibraryKind(internal val wire: String?) {
    MOVIES(Wire.LibraryKind.MOVIES),
    SHOWS(Wire.LibraryKind.SHOWS),
    VIDEOS(Wire.LibraryKind.VIDEOS),
    OTHER(null),
    ;

    internal companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: OTHER
    }
}

/** What an item is. Shows and seasons are folders; the rest play. [OTHER] stands for a kind a newer server added. */
enum class ItemKind(internal val wire: String?) {
    MOVIE(Wire.ItemKind.MOVIE),
    SHOW(Wire.ItemKind.SHOW),
    SEASON(Wire.ItemKind.SEASON),
    EPISODE(Wire.ItemKind.EPISODE),
    VIDEO(Wire.ItemKind.VIDEO),
    OTHER(null),
    ;

    /** True for an item a VOD session can play. */
    val playable: Boolean get() = this == MOVIE || this == EPISODE || this == VIDEO

    internal companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: OTHER
    }
}

/** The orders a library's grid can be read in. */
enum class LibrarySort(internal val wire: String) {
    TITLE("title"),
    ADDED("added"),
    YEAR("year"),
    RATING("rating"),

    /** The most recently watched first. */
    WATCHED("watched"),
}

data class MediaLibrary(
    val id: String,
    val name: String,
    val kind: LibraryKind,
    /** The movies, shows or videos in it. */
    val itemCount: Int,
)

/** The household's place in an item. */
data class PlayProgress(val positionMs: Long, val durationMs: Long?, val watched: Boolean) {
    /** How far in, from 0 to 1, or null when the length is unknown. */
    val fraction: Float? get() = durationMs?.takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) }

    /** Stopped part way, on a first watch or a rewatch: the item resumes rather than starts. */
    val inProgress: Boolean get() = positionMs > 0
}

/**
 * One movie, show, season, episode or video. [poster] and [backdrop] are absolute image URLs that need no token.
 * An episode carries its show's id and title; a show or season counts [children] and its [unwatched] episodes.
 */
data class MediaItem(
    val id: String,
    val libraryId: String,
    val kind: ItemKind,
    val title: String,
    val parentId: String? = null,
    val showId: String? = null,
    val showTitle: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** The last episode of a file that holds several, such as `S01E01-E02`. */
    val episodeEnd: Int? = null,
    /** The air date as the server gives it, `YYYY-MM-DD`. */
    val aired: String? = null,
    val overview: String? = null,
    val durationMs: Long? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val addedAt: Instant? = null,
    val genres: List<String> = emptyList(),
    val contentRating: String? = null,
    /** An audience score out of 10. */
    val rating: Double? = null,
    val videoHeight: Int? = null,
    val hdr: Boolean = false,
    val children: Int = 0,
    val unwatched: Int = 0,
    val progress: PlayProgress? = null,
) {
    /** A label such as `S2 E5` or `S1 E1–E2`; null for anything but an episode with numbers. */
    val episodeLabel: String?
        get() {
            val season = season ?: return null
            val episode = episode ?: return null
            return "S$season E$episode" + (episodeEnd?.let { "–E$it" } ?: "")
        }
}

/** An audio or subtitle stream of a file; [index] is the stream's index in the file. */
data class MediaTrackInfo(
    val index: Int,
    val codec: String,
    val language: String? = null,
    val title: String? = null,
    val channels: Int? = null,
    val default: Boolean = false,
    val forced: Boolean = false,
)

/** An item with what its page shows: a show's seasons or a season's episodes in [children], and what to play [next]. */
data class MediaItemDetail(
    val item: MediaItem,
    val children: List<MediaItem> = emptyList(),
    /** The episode to play next in a show or season, or the item itself to resume. */
    val next: MediaItem? = null,
    val tagline: String? = null,
    val studio: String? = null,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val audio: List<MediaTrackInfo> = emptyList(),
    val subtitles: List<MediaTrackInfo> = emptyList(),
    val container: String? = null,
    val videoCodec: String? = null,
    /** The intro the server found in an episode, which the player offers to skip. */
    val intro: IntroSpan? = null,
    /** The end credits the server found in an episode, which the player offers to skip, or to go on to the next. */
    val credits: IntroSpan? = null,
)

/** An episode's intro or credits, from [startMs] to [endMs] after the start of the file. */
data class IntroSpan(val startMs: Long, val endMs: Long)

/** One row of the library home, such as Continue watching or a library's recent additions. */
data class MediaShelf(
    val id: String,
    val title: String,
    val items: List<MediaItem>,
    /** The library the row is from, or null for a row across libraries. */
    val libraryId: String? = null,
) {
    /** Unique among the home's rows, where every library's Recently added row has the same [id]. */
    val key: String get() = libraryId?.let { "$id:$it" } ?: id
}

data class MediaHome(val shelves: List<MediaShelf>, val libraries: List<MediaLibrary>)

/** One page of a library's items; [next] is the cursor for the page after, null on the last. */
data class MediaPage(
    val items: List<MediaItem>,
    val total: Int,
    val next: String?,
    /** Every genre in the library, for a filter. */
    val genres: List<String> = emptyList(),
    /** Where each first letter starts, on the first page in title order; null otherwise. */
    val letters: List<LetterJump>? = null,
)

/** The first item in title order whose title starts with [letter] is at [offset] in the grid. */
data class LetterJump(val letter: String, val offset: Int)

/**
 * Each letter's first place in title order, from [runs] of letters and how many items each holds, in order; a letter
 * whose items come in more than one run is placed at its first.
 */
fun letterJumps(runs: List<Pair<String, Int>>): List<LetterJump> {
    var offset = 0
    val jumps = LinkedHashMap<String, LetterJump>()
    for ((letter, count) in runs) {
        jumps.getOrPut(letter) { LetterJump(letter, offset) }
        offset += count
    }
    return jumps.values.toList()
}

/**
 * What a player plays as it is: lowercase container names (`mp4`, `mkv`, `webm`, `ts`), FFmpeg codec names
 * (`h264`, `hevc`, `aac`, `ac3`), the tallest picture it shows and whether it shows HDR. The server plays a file
 * directly when it fits, else copies or converts its streams into HLS.
 */
data class PlaybackProfile(
    val containers: List<String> = emptyList(),
    val videoCodecs: List<String> = emptyList(),
    val audioCodecs: List<String> = emptyList(),
    val maxHeight: Int? = null,
    val hdr: Boolean = false,
    /**
     * The video codecs it decodes at more than 8 bits a sample, such as `hevc` for HEVC Main
     * 10; null leaves it to the server.
     */
    val deepColor: List<String>? = null,
    /**
     * True for a player whose own scrubber cannot ask for a conversion to restart: the server then lists the whole
     * item and converts from wherever it seeks.
     */
    val wholePlaylist: Boolean = false,
)

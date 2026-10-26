package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.contract.Wire
import kotlinx.serialization.Serializable

/** How a library's files are named, and so what the scan makes of them. */
enum class LibraryKind(val wire: String, val label: String) {
    /** `Title (Year)/Title (Year).ext` or loose `Title (Year).ext`. */
    MOVIES("movies", "Movies"),

    /** `Show (Year)/Season 01/Show - S01E02 - Title.ext`, or date-based `Show - 2026-10-17 - Title.ext`. */
    SHOWS("shows", "TV shows"),

    /** Any videos, titled by their file names: home videos, concerts, clips. */
    VIDEOS("videos", "Other videos"),

    /** Audio files grouped by their tags into artists and albums, untagged ones by `Artist/Album/Track.ext` folders. */
    MUSIC("music", "Music"),
    ;

    companion object {
        fun of(wire: String): LibraryKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** What an item is; shows, seasons, artists and albums group the others, which are files. */
object ItemKind {
    const val MOVIE = "movie"
    const val SHOW = "show"
    const val SEASON = "season"
    const val EPISODE = "episode"
    const val VIDEO = "video"

    /** A trailer, featurette or other extra of the movie or show it belongs to, listed only on that item's page. */
    const val EXTRA = "extra"

    /** A music library's album artist, and the album and track under it. */
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val TRACK = "track"
}

/**
 * What kind of extra a file is, from its folder or its name; [wire] is its [Wire.ExtraKind] value, in the order a page
 * lists them.
 */
enum class ExtraKind(val wire: String, val label: String) {
    TRAILER(Wire.ExtraKind.TRAILER, "Trailer"),
    FEATURETTE(Wire.ExtraKind.FEATURETTE, "Featurette"),
    BEHIND_THE_SCENES(Wire.ExtraKind.BEHIND_THE_SCENES, "Behind the scenes"),
    DELETED_SCENE(Wire.ExtraKind.DELETED_SCENE, "Deleted scene"),
    INTERVIEW(Wire.ExtraKind.INTERVIEW, "Interview"),
    SCENE(Wire.ExtraKind.SCENE, "Scene"),
    SHORT(Wire.ExtraKind.SHORT, "Short"),
    OTHER(Wire.ExtraKind.OTHER, "Extra"),
}

/** A library as stored, with the outcome of its last scan. */
data class LibraryRecord(
    val id: String,
    val name: String,
    val kind: LibraryKind,
    val path: String,
    val createdAt: String,
    val scannedAt: String? = null,
    val scanOk: Boolean? = null,
    val scanNote: String? = null,
)

/** The `.nfo` facts that have no column of their own. */
@Serializable
data class ItemDetails(
    val tagline: String? = null,
    val genres: List<String> = emptyList(),
    val studio: String? = null,
    val contentRating: String? = null,
    val rating: Double? = null,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    /** The TMDB id a `.nfo` or a `{tmdb-N}` tag in the name gives; it pins the TMDB match. */
    val tmdbId: Int? = null,
    /** The TheTVDB id a `.nfo` or a `{tvdb-N}` tag gives; for a show without a TMDB id, TMDB is asked for its own. */
    val tvdbId: Int? = null,
    /** The IMDb id (`tt…`) a `.nfo` or an `{imdb-tt…}` tag gives, used as [tvdbId] is. */
    val imdbId: String? = null,
    /** Whether a `.nfo` supplied these facts, so they win over TMDB's. */
    val nfo: Boolean = false,
    /** An extra's [ExtraKind] wire value. */
    val extra: String? = null,
    /** A track's own artist, which on a compilation differs from its album's. */
    val artist: String? = null,
    /** A track's number on its disc, and the disc's in the album. */
    val track: Int? = null,
    val disc: Int? = null,
    /** From its tracks' tags: an artist's MusicBrainz id, an album's release's, or a track's recording's. */
    val musicBrainzId: String? = null,
    /** An album's MusicBrainz release group, the album over all its editions. */
    val releaseGroupId: String? = null,
)

/**
 * One movie, show, season, episode, video, artist, album or track. Ids derive from the library and the item's relative
 * path, so a rescan, a removed and re-added library or a restored database gives a file the same id and keeps its
 * watch state.
 * [poster] and [backdrop] are paths relative to the library folder.
 */
data class LibraryItemRecord(
    val id: String,
    val libraryId: String,
    val kind: String,
    val parentId: String? = null,
    val path: String? = null,
    val title: String,
    val sortTitle: String = sortTitle(title),
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeEnd: Int? = null,
    val aired: String? = null,
    val overview: String? = null,
    val durationMs: Long? = null,
    val size: Long? = null,
    val modifiedAt: String? = null,
    val media: MediaInfo? = null,
    val details: ItemDetails? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val addedAt: String,
    val missingSince: String? = null,
)

/** What one scan of a library did. */
data class ScanResult(
    val added: Int,
    val updated: Int,
    val missing: Int,
    val removed: Int,
    val files: Int,
    val unavailable: Boolean = false,
) {
    val note: String get() = if (unavailable) {
        "LIBRARY_UNAVAILABLE"
    } else {
        "$files files, $added new, $updated changed, $missing missing, $removed removed"
    }
}

/**
 * What TMDB says about a movie, show, season or episode; [poster] and [backdrop] are TMDB
 * image paths such as `/abc.jpg`.
 */
@Serializable
data class MetadataFacts(
    val title: String? = null,
    val year: Int? = null,
    val aired: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val genres: List<String> = emptyList(),
    val studio: String? = null,
    val contentRating: String? = null,
    val rating: Double? = null,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    /** For an album or an artist in a Music library, the MusicBrainz release group or artist it was matched to. */
    val musicBrainzId: String? = null,
    /** For an album, the MusicBrainz artist its match is credited to first. */
    val artistId: String? = null,
    /** For an album with no picture of its own, whether the Cover Art Archive holds a front cover for its match. */
    val coverArt: Boolean = false,
    /** For an artist, the Wikidata item MusicBrainz links it to (`Q…`). */
    val wikidata: String? = null,
)

/** One item's stored TMDB lookup, or MusicBrainz lookup for music. */
data class MetadataRecord(
    val itemId: String,
    val tmdbId: Int?,
    val checkedAt: String,
    val facts: MetadataFacts?,
    /** For a movie or show, the TheTVDB and IMDb ids TMDB lists for it, or the ones it was looked up by. */
    val tvdbId: Int? = null,
    val imdbId: String? = null,
)

/**
 * The household's place in one movie, episode or video; [dismissed] takes it off Continue watching until it is played
 * again. A dismissed row with no position and not watched holds only that.
 */
data class PlayStateRecord(
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val watched: Boolean,
    val updatedAt: String,
    val dismissed: Boolean = false,
) {
    /** Started or finished: a row that says where the household is, not only that it was dismissed. */
    val played: Boolean get() = positionMs > 0 || watched
}

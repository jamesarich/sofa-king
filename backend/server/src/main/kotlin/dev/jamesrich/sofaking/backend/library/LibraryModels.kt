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
    ;

    companion object {
        fun of(wire: String): LibraryKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** What an item is; shows and seasons are folders, the others are files. */
object ItemKind {
    const val MOVIE = "movie"
    const val SHOW = "show"
    const val SEASON = "season"
    const val EPISODE = "episode"
    const val VIDEO = "video"

    /** A trailer, featurette or other extra of the movie or show it belongs to, listed only on that item's page. */
    const val EXTRA = "extra"
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
    /** Whether a `.nfo` supplied these facts, so they win over TMDB's. */
    val nfo: Boolean = false,
    /** An extra's [ExtraKind] wire value. */
    val extra: String? = null,
)

/**
 * One movie, show, season, episode or video. Ids derive from the library and the item's relative path, so a rescan,
 * a removed and re-added library or a restored database gives a file the same id and keeps its watch state.
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
)

/** One item's stored TMDB lookup. */
data class MetadataRecord(val itemId: String, val tmdbId: Int?, val checkedAt: String, val facts: MetadataFacts?)

/** The household's place in one movie, episode or video. */
data class PlayStateRecord(
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val watched: Boolean,
    val updatedAt: String,
)

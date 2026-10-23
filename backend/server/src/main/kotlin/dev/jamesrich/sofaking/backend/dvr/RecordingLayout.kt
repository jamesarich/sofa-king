package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.SeasonEpisode
import dev.jamesrich.sofaking.backend.catalog.seasonEpisode
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a recording is named after: the show (or movie) title, the episode title, its numbering, and whether the
 * guide calls it a movie, with the movie's year when the guide gives one.
 */
data class RecordingTitle(
    val show: String,
    val episodeTitle: String? = null,
    val numbering: SeasonEpisode? = null,
    val movie: Boolean = false,
    val year: Int? = null,
) {
    companion object {
        /** The guide calls a program a movie through one of its categories. */
        private val MOVIE_CATEGORIES = setOf("movie", "movies", "film", "feature film")

        fun of(program: GuideProgram): RecordingTitle {
            val movie = program.categories.any { it.trim().lowercase() in MOVIE_CATEGORIES }
            return RecordingTitle(
                show = program.title,
                episodeTitle = program.subTitle,
                numbering = if (movie) null else seasonEpisode(program.episodeNumbers),
                movie = movie,
                year = program.date?.take(4)?.toIntOrNull()?.takeIf { it in 1870..9999 },
            )
        }
    }
}

/**
 * Where recordings go under the recordings folder, in the layout Plex, Jellyfin, Emby and Kodi scan without help:
 *
 * - an episode the guide numbers by season and episode: `TV/Show/Season 01/Show - S01E02 - Episode.ts`
 * - one it does not (an episode number alone, news, sports, talk): `TV/Show/Season 2026/Show - 2026-10-17 - Episode.ts`, the date-based form
 * - a movie: `Movies/Title (Year)/Title (Year).ts`
 *
 * Names keep to characters every file system and SMB share accepts. A recording is written under the hidden
 * `.inprogress` folder at the same relative path and moved into place when it ends, so a scanner never adds a
 * partial file; Plex, Jellyfin and Kodi skip dot folders.
 */
object RecordingLayout {
    const val TV = "TV"
    const val MOVIES = "Movies"
    const val EXTENSION = "ts"
    const val IN_PROGRESS = ".inprogress"
    private const val NAME_LIMIT = 120
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val TIME = DateTimeFormatter.ofPattern("HH.mm", Locale.ROOT)

    /**
     * The recording's path relative to the recordings folder, for an airing starting at [start] in [zone]. A name
     * [taken] already (finished or in progress) gets the start time, then a counter, before the extension.
     */
    fun path(title: RecordingTitle, start: Instant, zone: ZoneId, taken: (Path) -> Boolean = { false }): Path {
        val local = start.atZone(zone)
        val (folder, name) = folderAndName(title, local.toLocalDate())
        val candidates = sequenceOf(name, "$name (${TIME.format(local)})") + generateSequence(2) { it + 1 }.map { "$name (${TIME.format(local)}) ($it)" }
        return candidates.map { folder.resolve("$it.$EXTENSION") }.first { !taken(it) }
    }

    /** Where the recording at [relative] is written while it runs. */
    fun inProgress(relative: Path): Path = Path.of(IN_PROGRESS).resolve(relative)

    private fun folderAndName(title: RecordingTitle, date: LocalDate): Pair<Path, String> {
        val show = clean(title.show).ifEmpty { "Unknown" }
        val episode = title.episodeTitle?.let(::clean)?.takeIf { it.isNotEmpty() && !it.equals(show, ignoreCase = true) }
        if (title.movie) {
            val named = title.year?.let { "$show ($it)" } ?: show
            return Path.of(MOVIES, named) to named
        }
        val season = title.numbering?.season
        val number = title.numbering?.episode
        if (season != null && number != null) {
            val name = listOfNotNull(show, "S%02dE%02d".format(Locale.ROOT, season, number), episode).joinToString(" - ")
            return Path.of(TV, show, "Season %02d".format(Locale.ROOT, season)) to name
        }
        val name = listOfNotNull(show, DATE.format(date), episode).joinToString(" - ")
        return Path.of(TV, show, "Season ${date.year}") to name
    }

    /** [text] without path separators (`Title: Part` reads `Title - Part`), characters Windows and SMB refuse, control characters, or trailing dots. */
    internal fun clean(text: String): String {
        val replaced = text.replace(": ", " - ").map { c ->
            when {
                c < ' ' || c == '\u007f' -> ' '
                c in "/\\|" -> '-'
                c == ':' -> '-'
                c in "*?\"<>" -> ' '
                else -> c
            }
        }.joinToString("")
        val collapsed = replaced.replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ').trimStart('.', ' ')
        return if (collapsed.length <= NAME_LIMIT) collapsed else collapsed.take(NAME_LIMIT).trimEnd('.', ' ')
    }
}

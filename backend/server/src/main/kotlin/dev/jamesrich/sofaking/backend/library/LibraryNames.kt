package dev.jamesrich.sofaking.backend.library

import java.time.LocalDate

/** The video containers a library scan picks up, by lower-case extension. */
internal val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "mov", "avi", "ts", "m2ts", "mts", "webm", "wmv", "mpg", "mpeg", "flv", "ogv", "3gp")

/** Folders Plex and Jellyfin treat as extras or NAS housekeeping; a scan skips them and what they hold. */
private val SKIPPED_FOLDERS = setOf(
    "extras", "featurettes", "behind the scenes", "deleted scenes", "interviews", "scenes", "shorts", "trailers", "samples", "sample",
    "other", "@eadir", "#recycle", "#snapshot", "lost+found", "\$recycle.bin", "system volume information",
)

/** File-name suffixes Plex reads as extras of the movie beside them. */
private val EXTRA_SUFFIXES = listOf("-trailer", "-sample", "-featurette", "-behindthescenes", "-deleted", "-interview", "-scene", "-short", "-other")

internal fun skippedFolder(name: String): Boolean = name.startsWith('.') || name.lowercase() in SKIPPED_FOLDERS

/** Whether [fileName] is a video a scan lists: a known extension, not hidden, not an extra by its suffix. */
internal fun isVideoFile(fileName: String): Boolean {
    if (fileName.startsWith('.')) return false
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension !in VIDEO_EXTENSIONS) return false
    val stem = fileName.substringBeforeLast('.').lowercase()
    return EXTRA_SUFFIXES.none { stem.endsWith(it) }
}

/** A title and the year in its trailing `(1999)`, as Plex and Jellyfin name movie and show folders. */
internal data class TitleYear(val title: String, val year: Int?)

private val YEAR = Regex("""^(.*?)[\s._]*[(\[](\d{4})[)\]](.*)$""")
private val BRACKETED = Regex("""\s*[\[{][^\]}]*[\]}]""")
private val BARE_YEAR = Regex("""^(.+?)[\s._]+((?:19|20)\d{2})(?:[\s._].*)?$""")

/** A collection's place in its order before the title, `01 - ` or `3 - `; a bare two-digit `24 - ` is left alone. */
private val ORDINAL = Regex("""^(?:0\d{1,2}|\d)\s+-\s+(?=\S)""")

/**
 * The title and year of a movie or show name: `Alien (1979)`, `Alien (1979) [1080p]`, `Alien (1979) {tmdb-348}`,
 * `01 - Alien (1979)` and `Alien.1979.1080p.BluRay` all read as Alien, 1979. Dots and underscores become spaces only
 * when the name has no spaces.
 */
internal fun titleYear(raw: String): TitleYear {
    val name = (if (' ' !in raw) raw.replace('.', ' ').replace('_', ' ') else raw).replace(ORDINAL, "")
    YEAR.matchEntire(name)?.let { match ->
        val year = match.groupValues[2].toInt()
        if (year in 1880..2100) return TitleYear(clean(match.groupValues[1]), year)
    }
    BARE_YEAR.matchEntire(name)?.let { match -> return TitleYear(clean(match.groupValues[1]), match.groupValues[2].toInt()) }
    return TitleYear(clean(name), null)
}

/** A release group after the bracketed tags, as Sonarr and Radarr name files: `Title [WEBDL-1080p][x264]-RAWR`. */
private val RELEASE_GROUP = Regex("""(?<=[\]}])\s*-[A-Za-z0-9]+$""")

/** An unbracketed quality at the end, with its release group: `Title WEBDL-1080p`, `Title Bluray-720p Proper-GROUP`. */
private val QUALITY = Regex(
    """(?i)\s+(?:WEB[-. ]?DL|WEB[-. ]?Rip|Blu-?ray|BDRip|BRRip|HDTV|SDTV|DVD(?:Rip)?|Remux|Raw-HD)(?:[-. ](?:480|576|720|1080|2160)p)?(?:\s+(?:Proper|Repack))*(?:-[A-Za-z0-9]+)?$""",
)

private fun clean(title: String): String =
    title.replace(RELEASE_GROUP, "").replace(BRACKETED, "").trim().replace(QUALITY, "").trimEnd('-', '.', '_').trim()

/** An episode's numbers or air date and the title after them, read from its file name. */
internal data class EpisodeName(
    val season: Int?, val episode: Int?, val episodeEnd: Int? = null, val aired: LocalDate? = null, val title: String? = null,
)

private val SEASON_EPISODE = Regex("""(?i)(?:^|[\s._\-\[(])s(\d{1,4})[\s._]?e(\d{1,4})(?:[\s._]?-?[\s._]?e(\d{1,4}))?""")
private val CROSS = Regex("""(?i)(?:^|[\s._\-\[(])(\d{1,2})x(\d{2,3})(?:-(\d{2,3}))?""")
private val AIR_DATE = Regex("""(?:^|[\s._\-\[(])((?:19|20)\d{2})[-._](\d{2})[-._](\d{2})""")
private val SEASON_FOLDER = Regex("""(?i)^(?:season|series|staffel|saison|temporada)[\s._]*(\d{1,4})$""")
private val SPECIALS_FOLDER = Regex("""(?i)^specials?$""")

/** The season a folder named `Season 01`, `Season 2024` or `Specials` (season 0) holds; null for any other folder. */
internal fun seasonOfFolder(name: String): Int? {
    if (SPECIALS_FOLDER.matches(name.trim())) return 0
    return SEASON_FOLDER.matchEntire(name.trim())?.groupValues?.get(1)?.toInt()
}

/**
 * The numbers of an episode file: `Show - S01E02 - Title.mkv`, `Show.S01E02E03.mkv`, `Show 1x02.mkv`, or a
 * date-based `Show - 2026-10-17 - Title.ts` as the DVR names news and talk shows. [stem] is the name without its
 * extension; the title is whatever follows the numbers after ` - `.
 */
internal fun episodeName(stem: String): EpisodeName {
    SEASON_EPISODE.find(stem)?.let { match ->
        val (season, episode, end) = match.destructured
        return EpisodeName(season.toInt(), episode.toInt(), end.toIntOrNull(), title = titleAfter(stem, match.range.last + 1))
    }
    AIR_DATE.find(stem)?.let { match ->
        val (y, m, d) = match.destructured
        val date = runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
        if (date != null) return EpisodeName(null, null, aired = date, title = titleAfter(stem, match.range.last + 1))
    }
    CROSS.find(stem)?.let { match ->
        val (season, episode, end) = match.destructured
        return EpisodeName(season.toInt(), episode.toInt(), end.toIntOrNull(), title = titleAfter(stem, match.range.last + 1))
    }
    return EpisodeName(null, null)
}

private val SAME_DAY_TIME = Regex("""\s*\(\d{2}\.\d{2}\)\s*$""")

/** The text after ` - ` that follows the episode's numbers, without a DVR's same-day ` (HH.MM)` or a bracketed tag. */
private fun titleAfter(stem: String, from: Int): String? {
    val rest = stem.substring(from.coerceAtMost(stem.length))
    val dash = rest.indexOf(" - ")
    if (dash < 0) return null
    return clean(rest.substring(dash + 3).replace(SAME_DAY_TIME, "")).ifEmpty { null }
}

private val TMDB_TAG = Regex("""(?i)[\[{](?:tmdb|tmdbid)[-=](\d+)[\]}]""")

/** The TMDB id a name carries as `{tmdb-348}` or `[tmdbid-348]`, as Plex and Jellyfin read it. */
internal fun tmdbIdIn(name: String): Int? = TMDB_TAG.find(name)?.groupValues?.get(1)?.toIntOrNull()

/** The name a list sorts by: lower case, without a leading English article. */
internal fun sortTitle(title: String): String {
    val lower = title.lowercase().trim()
    for (article in listOf("the ", "a ", "an ")) if (lower.startsWith(article) && lower.length > article.length) return lower.substring(article.length)
    return lower
}

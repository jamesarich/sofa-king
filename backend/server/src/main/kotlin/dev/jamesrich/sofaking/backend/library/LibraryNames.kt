package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.contract.LetterCount

/** The video containers a library scan picks up, by lower-case extension. */
internal val VIDEO_EXTENSIONS =
    setOf("mkv", "mp4", "m4v", "mov", "avi", "ts", "m2ts", "mts", "webm", "wmv", "mpg", "mpeg", "flv", "ogv", "3gp")

/** The audio files a music library scan picks up, by lower-case extension. */
internal val AUDIO_EXTENSIONS =
    setOf("flac", "mp3", "m4a", "aac", "alac", "opus", "ogg", "oga", "wav", "aiff", "aif", "wma", "ape", "wv")

/** Folders Plex and Jellyfin treat as extras, by the [ExtraKind] of what they hold. */
private val EXTRA_FOLDERS = mapOf(
    "trailers" to ExtraKind.TRAILER, "featurettes" to ExtraKind.FEATURETTE,
    "behind the scenes" to ExtraKind.BEHIND_THE_SCENES,
    "deleted scenes" to ExtraKind.DELETED_SCENE, "interviews" to ExtraKind.INTERVIEW, "scenes" to ExtraKind.SCENE,
    "shorts" to ExtraKind.SHORT, "extras" to ExtraKind.OTHER, "other" to ExtraKind.OTHER,
)

/** Samples and NAS housekeeping; a scan skips them and what they hold. */
private val SKIPPED_FOLDERS = setOf(
    "samples",
    "sample",
    "@eadir",
    "#recycle",
    "#snapshot",
    "lost+found",
    "\$recycle.bin",
    "system volume information",
)

/** File-name suffixes Plex reads as extras of the movie beside them. */
private val EXTRA_SUFFIXES = listOf(
    "-trailer" to ExtraKind.TRAILER,
    "-featurette" to ExtraKind.FEATURETTE,
    "-behindthescenes" to ExtraKind.BEHIND_THE_SCENES,
    "-deleted" to ExtraKind.DELETED_SCENE,
    "-interview" to ExtraKind.INTERVIEW,
    "-scene" to ExtraKind.SCENE,
    "-short" to ExtraKind.SHORT,
    "-other" to ExtraKind.OTHER,
)

/** Whether a scan leaves out the folder [name] and what it holds: hidden, samples or housekeeping. */
internal fun skippedFolder(name: String): Boolean = name.startsWith('.') || name.lowercase() in SKIPPED_FOLDERS

/** The kind of extra the folder [name] holds, such as `Trailers`; null for any other folder. */
internal fun extraFolderKind(name: String): ExtraKind? = EXTRA_FOLDERS[name.lowercase()]

/** The kind of extra [fileName] is by its suffix, such as `Alien (1979)-trailer.mkv`, and the name before it. */
internal fun extraSuffix(fileName: String): Pair<ExtraKind, String>? {
    val stem = fileName.substringBeforeLast('.')
    return EXTRA_SUFFIXES.firstOrNull { (suffix, _) -> stem.lowercase().endsWith(suffix) }?.let { (suffix, kind) ->
        kind to
            stem.dropLast(suffix.length)
    }
}

/** Whether [fileName] is a video a scan lists: a known extension, not hidden, not a sample. */
internal fun isVideoFile(fileName: String): Boolean {
    if (fileName.startsWith('.')) return false
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension !in VIDEO_EXTENSIONS) return false
    return !fileName.substringBeforeLast('.').lowercase().endsWith("-sample")
}

/** Whether [fileName] is audio a music library scan lists: a known extension, not hidden. */
internal fun isAudioFile(fileName: String): Boolean =
    !fileName.startsWith('.') && fileName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

/** A title and the year in its trailing `(1999)`, as Plex and Jellyfin name movie and show folders. */
internal data class TitleYear(val title: String, val year: Int?)

private val YEAR = Regex("""^(.*?)[\s._]*[(\[](\d{4})[)\]](.*)$""")

/** The years a year in a name may be; outside them, the number is part of the title. */
private val PLAUSIBLE_YEARS = 1880..2100

/** The year at the start of a date such as `2024-01-15`, or null. */
internal fun yearOf(date: String?): Int? = date?.take(YEAR_DIGITS)?.toIntOrNull()

private const val YEAR_DIGITS = 4

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
    val dated = YEAR.matchEntire(name)?.takeIf { it.groupValues[2].toInt() in PLAUSIBLE_YEARS }
        ?: BARE_YEAR.matchEntire(name)
    return dated?.let { TitleYear(cleanTitle(it.groupValues[1]), it.groupValues[2].toInt()) }
        ?: TitleYear(cleanTitle(name), null)
}

/** A release group after the bracketed tags, as Sonarr and Radarr name files: `Title [WEBDL-1080p][x264]-RAWR`. */
private val RELEASE_GROUP = Regex("""(?<=[\]}])\s*-[A-Za-z0-9]+$""")

/** An unbracketed quality at the end, with its release group: `Title WEBDL-1080p`, `Title Bluray-720p Proper-GROUP`. */
private val QUALITY = Regex(
    """(?i)\s+(?:WEB[-. ]?DL|WEB[-. ]?Rip|Blu-?ray|BDRip|BRRip|HDTV|SDTV|DVD(?:Rip)?|Remux|Raw-HD)(?:[-. """ +
        """](?:480|576|720|1080|2160)p)?(?:\s+(?:Proper|Repack))*(?:-[A-Za-z0-9]+)?$""",
)

/** [title] without the release group, bracketed tags and quality a file name carries. */
internal fun cleanTitle(title: String): String =
    title.replace(RELEASE_GROUP, "").replace(BRACKETED, "").trim().replace(QUALITY, "").trimEnd('-', '.', '_').trim()

private val TMDB_TAG = Regex("""(?i)[\[{](?:tmdb|tmdbid)[-=](\d+)[\]}]""")
private val TVDB_TAG = Regex("""(?i)[\[{](?:tvdb|tvdbid)[-=](\d+)[\]}]""")
private val IMDB_TAG = Regex("""(?i)[\[{](?:imdb|imdbid)[-=](tt\d{5,10})[\]}]""")

/** The ids of TMDB, TheTVDB and IMDb that name an item, any of them null. */
data class ProviderIds(val tmdb: Int? = null, val tvdb: Int? = null, val imdb: String? = null) {
    val isEmpty: Boolean get() = tmdb == null && tvdb == null && imdb == null

    /** These ids, each filled from [other] where this has none. */
    fun or(other: ProviderIds?): ProviderIds =
        if (other == null) this else ProviderIds(tmdb ?: other.tmdb, tvdb ?: other.tvdb, imdb ?: other.imdb)
}

/**
 * The ids a name carries as `{tmdb-348}`, `[tmdbid-348]`, `{tvdb-81189}`, `[tvdbid-81189]`, `{imdb-tt0078748}` or
 * `[imdbid-tt0078748]`, as Plex, Jellyfin and the TRaSH naming schemes for Sonarr and Radarr write them.
 */
internal fun providerIdsIn(name: String): ProviderIds = ProviderIds(
    TMDB_TAG.find(name)?.groupValues?.get(1)?.toIntOrNull(),
    TVDB_TAG.find(name)?.groupValues?.get(1)?.toIntOrNull(),
    IMDB_TAG.find(name)?.groupValues?.get(1)?.lowercase(),
)

/**
 * How many of [titles], in title order, start with each letter in turn: the first letter of each [sortTitle] in upper
 * case, or `#` for a digit or a symbol. Neighbors with the same letter make one run.
 */
internal fun letterRuns(titles: List<String>): List<LetterCount> {
    val runs = ArrayList<LetterCount>()
    for (title in titles) {
        val first = sortTitle(title).firstOrNull()
        val letter = if (first != null && first.isLetter()) first.uppercase() else "#"
        val last = runs.lastOrNull()
        if (last?.letter == letter) {
            runs[runs.size - 1] = last.copy(count = last.count + 1)
        } else {
            runs += LetterCount(letter, 1)
        }
    }
    return runs
}

private val ARTICLES = arrayOf("the ", "a ", "an ")

/** The name a list sorts by: lower case, without a leading English article. */
internal fun sortTitle(title: String): String {
    val lower = title.lowercase().trim()
    for (article in ARTICLES) {
        if (lower.startsWith(article) && lower.length > article.length) {
            return lower.substring(article.length)
        }
    }
    return lower
}

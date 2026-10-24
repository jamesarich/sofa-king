package dev.jamesrich.sofaking.backend.library

import java.time.LocalDate

/** An episode's numbers or air date and the title after them, read from its file name. */
internal data class EpisodeName(
    val season: Int?,
    val episode: Int?,
    val episodeEnd: Int? = null,
    val aired: LocalDate? = null,
    val title: String? = null,
)

private val SEASON_EPISODE =
    Regex("""(?i)(?:^|[\s._\-\[(])s(\d{1,4})[\s._]?e(\d{1,4})(?:[\s._]?-?[\s._]?e(\d{1,4}))?""")
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
internal fun episodeName(stem: String): EpisodeName = SEASON_EPISODE.find(stem)?.let { numbered(stem, it) }
    ?: AIR_DATE.find(stem)?.let { dated(stem, it) }
    ?: CROSS.find(stem)?.let { numbered(stem, it) }
    ?: EpisodeName(null, null)

private fun numbered(stem: String, match: MatchResult): EpisodeName {
    val (season, episode, end) = match.destructured
    val title = titleAfter(stem, match.range.last + 1)
    return EpisodeName(season.toInt(), episode.toInt(), end.toIntOrNull(), title = title)
}

/** The episode aired on the date [match] found; null when it is no real date. */
private fun dated(stem: String, match: MatchResult): EpisodeName? {
    val (y, m, d) = match.destructured
    val date = runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() ?: return null
    return EpisodeName(null, null, aired = date, title = titleAfter(stem, match.range.last + 1))
}

private const val TITLE_DASH = " - "

private val SAME_DAY_TIME = Regex("""\s*\(\d{2}\.\d{2}\)\s*$""")

/** The text after ` - ` that follows the episode's numbers, without a DVR's same-day ` (HH.MM)` or a bracketed tag. */
private fun titleAfter(stem: String, from: Int): String? {
    val rest = stem.substring(from.coerceAtMost(stem.length))
    val dash = rest.indexOf(TITLE_DASH)
    if (dash < 0) return null
    return cleanTitle(rest.substring(dash + TITLE_DASH.length).replace(SAME_DAY_TIME, "")).ifEmpty { null }
}

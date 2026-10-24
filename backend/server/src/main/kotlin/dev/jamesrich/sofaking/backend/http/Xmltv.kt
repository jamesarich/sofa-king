package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val xmltvFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

private fun xmltvTime(instant: Instant): String = xmltvFormat.format(instant) + " +0000"

private val airDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

private fun airDate(instant: Instant): String = airDateFormat.format(instant)

/** One program titled [name] for each hour from the hour [from] falls in up to [to]. */
internal fun placeholders(channelId: String, name: String, from: Instant, to: Instant): List<GuideProgram> =
    generateSequence(from.truncatedTo(ChronoUnit.HOURS)) { it.plus(Duration.ofHours(1)) }
        .takeWhile { it < to }
        .map { start -> GuideProgram(channelId, start, start.plus(Duration.ofHours(1)), name) }
        .toList()

internal const val XMLTV_MOVIE = "Movie"

/** The kinds Plex sorts a program by, in the order a program's categories list them. */
private val PLEX_KINDS = listOf(XMLTV_MOVIE, "Sports", "News", "Children", "Series").map(String::lowercase)

/**
 * One `<programme>` line for the channel [channelId], children in XMLTV DTD order. The categories are the source's
 * own with any Plex kind first, else the ones [xmltvCategories] derives from the channel's [group]. A program the
 * source did not number and that is not a movie gets its start time as `original-air-date`, since Plex files a
 * program without episode numbering as a movie.
 */
internal fun xmltvProgram(channelId: String, program: GuideProgram, group: String?): String = buildString {
    val lang = program.language?.let { " lang=\"${xml(it)}\"" }.orEmpty()
    append(
        "<programme start=\"${xmltvTime(program.startsAt)}\" stop=\"${xmltvTime(program.endsAt)}\" " +
            "channel=\"${xml(channelId)}\">",
    )
    append("<title$lang>${xml(program.title)}</title>")
    program.subTitle?.let { append("<sub-title$lang>${xml(it)}</sub-title>") }
    program.description?.let { append("<desc$lang>${xml(it)}</desc>") }
    program.date?.let { append("<date>${xml(it)}</date>") }
    val derived = xmltvCategories(group)
    appendCategories(program, derived, lang)
    program.icon?.let { append("<icon src=\"${xml(it)}\"/>") }
    appendEpisodeNumbers(program, movie = derived.first() == XMLTV_MOVIE)
    program.previouslyShown?.let { shown ->
        append(shown.start?.let { "<previously-shown start=\"${xml(it)}\"/>" } ?: "<previously-shown/>")
    }
    if (program.isNew) append("<new/>")
    program.rating?.let { rating ->
        append(rating.system?.let { "<rating system=\"${xml(it)}\">" } ?: "<rating>")
        append("<value>${xml(rating.value)}</value></rating>")
    }
    append("</programme>\n")
}

private fun StringBuilder.appendCategories(program: GuideProgram, derived: List<String>, lang: String) {
    if (program.categories.isEmpty()) {
        derived.forEach { append("<category lang=\"en\">${xml(it)}</category>") }
    } else {
        program.categories.sortedBy { category ->
            PLEX_KINDS.indexOf(category.lowercase()).takeIf { it >= 0 } ?: PLEX_KINDS.size
        }.forEach { append("<category$lang>${xml(it)}</category>") }
    }
}

/** The source's episode numbers, else the air date for a program that is not a movie by [movie] or its categories. */
private fun StringBuilder.appendEpisodeNumbers(program: GuideProgram, movie: Boolean) {
    program.episodeNumbers.forEach {
        append("<episode-num system=\"${xml(it.system)}\">${xml(it.value)}</episode-num>")
    }
    val anyMovie = movie || program.categories.any { it.equals(XMLTV_MOVIE, ignoreCase = true) }
    if (program.episodeNumbers.isEmpty() && !anyMovie) {
        append("<episode-num system=\"original-air-date\">${airDate(program.startsAt)}</episode-num>")
    }
}

/**
 * A station group's XMLTV categories: the kind Plex sorts by (Movie, Sports, News, Children or Series), then the group.
 */
internal fun xmltvCategories(group: String?): List<String> {
    val kind = when (group?.lowercase()) {
        "movies" -> XMLTV_MOVIE
        "sports" -> "Sports"
        "news", "local", "local news (us)" -> "News"
        "kids & family" -> "Children"
        else -> "Series"
    }
    return listOfNotNull(kind, group?.takeIf { it.isNotBlank() && !it.equals(kind, ignoreCase = true) })
}

internal fun xml(value: String): String = buildString(value.length) {
    for (char in value) {
        when {
            char == '&' -> append("&amp;")
            char == '<' -> append("&lt;")
            char == '>' -> append("&gt;")
            char == '"' -> append("&quot;")
            char < ' ' && char != '\t' && char != '\n' && char != '\r' -> Unit
            else -> append(char)
        }
    }
}

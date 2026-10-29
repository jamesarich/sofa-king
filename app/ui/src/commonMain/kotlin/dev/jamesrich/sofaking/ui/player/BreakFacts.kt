package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import dev.jamesrich.sofaking.data.BreakFact
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.break_fact_aired
import dev.jamesrich.sofaking.resources.break_fact_aired_on
import dev.jamesrich.sofaking.resources.break_fact_and
import dev.jamesrich.sofaking.resources.break_fact_created_by
import dev.jamesrich.sofaking.resources.break_fact_directed_by
import dev.jamesrich.sofaking.resources.break_fact_episode
import dev.jamesrich.sofaking.resources.break_fact_first_aired
import dev.jamesrich.sofaking.resources.break_fact_first_aired_on
import dev.jamesrich.sofaking.resources.break_fact_rating
import dev.jamesrich.sofaking.resources.break_fact_released
import dev.jamesrich.sofaking.resources.break_fact_released_by
import dev.jamesrich.sofaking.resources.break_fact_starring
import dev.jamesrich.sofaking.resources.break_fact_tagline
import dev.jamesrich.sofaking.resources.library_episodes
import dev.jamesrich.sofaking.resources.library_seasons
import dev.jamesrich.sofaking.resources.next_at
import dev.jamesrich.sofaking.ui.TimeFormatter
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** [facts] in words, one line each, for the break screen to turn through; times in [timeFormat]. */
@Composable
internal fun breakFactLines(facts: List<BreakFact>, timeFormat: TimeFormatter): List<String> =
    facts.map { breakFactLine(it, timeFormat) }

@Composable
private fun breakFactLine(fact: BreakFact, timeFormat: TimeFormatter): String = when (fact) {
    is BreakFact.Episode ->
        stringResource(Res.string.break_fact_episode, listOfNotNull(fact.label, fact.title).joinToString(", "))

    is BreakFact.Aired -> aired(fact)

    is BreakFact.Run -> listOfNotNull(
        fact.seasons?.let { pluralStringResource(Res.plurals.library_seasons, it, it) },
        fact.episodes?.let { pluralStringResource(Res.plurals.library_episodes, it, it) },
    ).joinToString(", ")

    is BreakFact.Starring -> stringResource(Res.string.break_fact_starring, names(fact.names))

    is BreakFact.MadeBy -> stringResource(
        if (fact.movie) Res.string.break_fact_directed_by else Res.string.break_fact_created_by,
        names(fact.names),
    )

    is BreakFact.Tagline -> stringResource(Res.string.break_fact_tagline, fact.text)

    is BreakFact.Rating -> stringResource(Res.string.break_fact_rating, fact.score.toString())

    is BreakFact.Next -> {
        val start = timeFormat.format(Instant.fromEpochMilliseconds(fact.startMs))
        stringResource(Res.string.next_at, fact.title, start)
    }
}

@Composable
private fun aired(fact: BreakFact.Aired): String {
    val studio = fact.studio
    val end = fact.endYear
    return when {
        fact.movie && studio != null -> stringResource(Res.string.break_fact_released_by, fact.year, studio)
        fact.movie -> stringResource(Res.string.break_fact_released, fact.year)
        end != null && studio != null -> stringResource(Res.string.break_fact_aired_on, fact.year, end, studio)
        end != null -> stringResource(Res.string.break_fact_aired, fact.year, end)
        studio != null -> stringResource(Res.string.break_fact_first_aired_on, fact.year, studio)
        else -> stringResource(Res.string.break_fact_first_aired, fact.year)
    }
}

/** `A`, `A and B`, or `A, B and C`. */
@Composable
private fun names(names: List<String>): String = if (names.size < 2) {
    names.joinToString()
} else {
    stringResource(Res.string.break_fact_and, names.dropLast(1).joinToString(", "), names.last())
}

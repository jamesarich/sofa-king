package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.contract.ProgramFacts
import dev.jamesrich.sofaking.contract.Wire
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(ProgramFactsLookup::class.java)

/** How long a title's facts stay before TMDB is asked again; its terms allow caching for up to six months. */
private val FACTS_FOR: Duration = Duration.ofDays(7)

/** How long a title TMDB does not list waits before it is searched again. */
private val NO_FACTS_FOR: Duration = Duration.ofDays(1)

/** How long a lookup that failed waits, so a channel's every break does not ask again. */
private val FAILED_FOR: Duration = Duration.ofMinutes(10)

/** The titles kept; the oldest asked for goes first. */
private const val KEPT_TITLES = 500

/** How many of the cast and of the creators the break screen gets. */
private const val CAST_SHOWN = 3
private const val CREATORS_SHOWN = 2

/** Show statuses that mean it no longer airs, so its last year is a fact. */
private val ENDED = setOf("Ended", "Canceled")

/**
 * What TMDB says about live programs, by their guide titles, for the break screen. A show (or, when [facts] is told
 * the guide files it as a movie, a movie) is searched first and the other kind after; only a title TMDB lists as it
 * is, letters and digits compared, matches, so a guide's `Paid Programming` or `Local News at 5` finds nothing rather
 * than something wrong. Answers are kept in memory, the most recent [KEPT_TITLES] titles, for [FACTS_FOR], a title
 * TMDB does not list for [NO_FACTS_FOR] and a failed lookup for [FAILED_FOR].
 */
class ProgramFactsLookup internal constructor(private val tmdb: TmdbClient, private val clock: Clock) {
    private class Kept(val facts: ProgramFacts, val until: Instant)

    private val kept = object : LinkedHashMap<String, Kept>(KEPT_TITLES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?) = size > KEPT_TITLES
    }

    suspend fun facts(title: String, movie: Boolean): ProgramFacts {
        val name = normalized(title)
        if (name.isEmpty()) return ProgramFacts()
        val key = (if (movie) "movie:" else "show:") + name
        val now = clock.instant()
        val fresh = synchronized(kept) { kept[key]?.takeIf { it.until > now } }
        return fresh?.facts ?: lookUp(title, movie).let { (facts, keptFor) ->
            synchronized(kept) { kept[key] = Kept(facts, now.plus(keptFor)) }
            facts
        }
    }

    /** What TMDB says about [title], and how long to keep the answer. */
    private suspend fun lookUp(title: String, movie: Boolean): Pair<ProgramFacts, Duration> = try {
        val found = if (movie) movie(title) ?: show(title) else show(title) ?: movie(title)
        if (found != null) found to FACTS_FOR else ProgramFacts() to NO_FACTS_FOR
    } catch (e: TmdbFailure) {
        log.info("program facts lookup failed code={}", e.code)
        ProgramFacts() to FAILED_FOR
    }

    private suspend fun show(title: String): ProgramFacts? {
        val id = sameTitle(
            title,
            tmdb.searchShows(title, null).map { Match(it.id, it.name, it.originalName, it.popularity) },
        ) ?: return null
        return tmdb.show(id).programFacts()
    }

    private suspend fun movie(title: String): ProgramFacts? {
        val id = sameTitle(
            title,
            tmdb.searchMovies(title, null).map { Match(it.id, it.title, it.originalTitle, it.popularity) },
        ) ?: return null
        return tmdb.movie(id).programFacts()
    }

    private class Match(val id: Int, val title: String, val originalTitle: String, val popularity: Double)

    /** The most popular of [results] whose title is [title], letters and digits compared. */
    private fun sameTitle(title: String, results: List<Match>): Int? {
        val wanted = normalized(title)
        return results.filter { normalized(it.title) == wanted || normalized(it.originalTitle) == wanted }
            .maxByOrNull { it.popularity }?.id
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}

internal fun TmdbShow.programFacts() = ProgramFacts(
    kind = Wire.ItemKind.SHOW,
    year = yearOf(firstAirDate),
    endYear = if (status in ENDED) yearOf(lastAirDate) else null,
    studio = networks.firstOrNull()?.name?.ifEmpty { null },
    seasons = seasonCount?.takeIf { it > 0 },
    episodes = episodeCount?.takeIf { it > 0 },
    cast = credits?.cast.orEmpty().map { it.name }.filter { it.isNotEmpty() }.take(CAST_SHOWN),
    creators = createdBy.map { it.name }.filter { it.isNotEmpty() }.take(CREATORS_SHOWN),
    tagline = tagline?.ifBlank { null },
    rating = voteAverage.rating(),
)

internal fun TmdbMovie.programFacts() = ProgramFacts(
    kind = Wire.ItemKind.MOVIE,
    year = yearOf(releaseDate),
    studio = companies.firstOrNull()?.name?.ifEmpty { null },
    cast = credits?.cast.orEmpty().map { it.name }.filter { it.isNotEmpty() }.take(CAST_SHOWN),
    creators = credits?.crew.orEmpty().filter { it.job == "Director" }.map { it.name }.take(CREATORS_SHOWN),
    tagline = tagline?.ifBlank { null },
    rating = voteAverage.rating(),
)

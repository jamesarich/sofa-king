package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.contract.Program
import dev.jamesrich.sofaking.contract.UpcomingPrograms
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Duration

/**
 * On Later under `GET /api/v1/guide/on-later`: the movies, sports, kids' programs or premieres the guide holds for
 * the enabled channels over the coming days, soonest first. A program on now counts until it ends.
 */
class OnLaterService internal constructor(
    private val lineup: LineupService,
    private val guides: GuideService,
    private val clock: Clock,
) {
    /** Up to [limit] programs of [kind] (a [Wire.OnLaterKind]) that end within [days] days from now. */
    suspend fun programs(kind: String, days: Int, limit: Int): UpcomingPrograms {
        val matches = MATCHERS[kind]
            ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "kind is movie, sports, kids or new")
        if (days !in DAYS || limit !in LIMITS) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "days is 1 to 7 and limit 1 to 500")
        }
        val now = clock.instant()
        val until = now + Duration.ofDays(days.toLong())
        val found = ArrayList<Pair<String, GuideProgram>>()
        val channels = lineup.entries().filter { it.enabled && it.guide != null }
        for ((guideId, group) in channels.groupBy { it.guide!!.guideId }) {
            val rows = guides.programs(guideId, group.map { it.guide!!.channelId }.distinct(), now, until)
            for (channel in group) {
                rows[channel.guide!!.channelId].orEmpty().filter(matches).forEach { found += channel.id to it }
            }
        }
        val programs: List<Program> = found
            .sortedWith(compareBy({ it.second.startsAt }, { it.first }))
            .take(limit)
            .map { (channel, program) -> program.toWire(channel) }
        return UpcomingPrograms(programs)
    }
}

private val DAYS = 1..7
private val LIMITS = 1..500

private val MATCHERS: Map<String, (GuideProgram) -> Boolean> = mapOf(
    Wire.OnLaterKind.MOVIE to { it.categories.isMovie() },
    Wire.OnLaterKind.SPORTS to { program -> program.categories.any { "sport" in it.lowercase() } },
    Wire.OnLaterKind.KIDS to { program ->
        program.categories.any { category -> category.lowercase().let { "kid" in it || "child" in it } }
    },
    Wire.OnLaterKind.NEW to { it.isPremiere() },
)

/**
 * A premiere: a `Premiere` category, or a new airing of a season's first episode. A guide that marks every airing new
 * without numbering it names no premiere this way.
 */
internal fun GuideProgram.isPremiere(): Boolean =
    categories.any { "premiere" in it.lowercase() } || (isNew && seasonEpisode(episodeNumbers)?.episode == 1)

/**
 * Whether these categories name a movie, as the app's `programKinds` reads them: a `Movie` or `Film` mark with a film
 * genre beside it, and not news or sports, since some guides mark every program on a live feed `Movie`.
 */
internal fun List<String>.isMovie(): Boolean {
    var mark = false
    var genre = false
    for (category in this) {
        val words = category.lowercase()
        if ("news" in words || "sport" in words) return false
        if ("movie" in words || "film" in words) mark = true
        if (words in FILM_CATEGORIES || FILM_GENRES.any { it in words }) genre = true
    }
    return mark && genre
}

/** Whole categories that name a movie. */
private val FILM_CATEGORIES = setOf("movies", "tv movie", "feature film")

private val FILM_GENRES = listOf(
    "drama", "thriller", "horror", "comed", "action", "crime", "romanc", "romantic", "mystery", "fantasy", "adventure",
    "western", "sci-fi", "science fiction", "war", "musical", "biograph", "histor", "family", "animation",
    "documentary", "suspense",
)

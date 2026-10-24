package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What TMDB says about a live program, through the server, for the break screen's facts. */
data class ProgramFacts(
    val movie: Boolean = false,
    /** When a show first aired, or a movie came out. */
    val year: Int? = null,
    /** When a show that has ended last aired. */
    val endYear: Int? = null,
    /** The network a show first aired on, or the studio that made a movie. */
    val studio: String? = null,
    val seasons: Int? = null,
    val episodes: Int? = null,
    /** The first few billed. */
    val cast: List<String> = emptyList(),
    /** Who created a show, or directed a movie. */
    val creators: List<String> = emptyList(),
    val tagline: String? = null,
    /** TMDB's average vote, out of 10. */
    val rating: Double? = null,
)

/**
 * One line the break screen can show about the program on now, each app putting it in its own words: what [BreakFact]
 * picks from the guide and from [ProgramFacts].
 */
sealed interface BreakFact {
    /** The episode on now: its label, such as `S2 E5`, and its title, either or both. */
    data class Episode(val label: String?, val title: String?) : BreakFact

    /** When it aired or came out, until when for a show that has ended, and on which network or from which studio. */
    data class Aired(val movie: Boolean, val year: Int, val endYear: Int?, val studio: String?) : BreakFact

    /** A show's seasons and episodes, either or both. */
    data class Run(val seasons: Int?, val episodes: Int?) : BreakFact

    data class Starring(val names: List<String>) : BreakFact

    /** A show's creators or a movie's directors. */
    data class MadeBy(val movie: Boolean, val names: List<String>) : BreakFact

    data class Tagline(val text: String) : BreakFact

    /** TMDB's average vote, out of 10. */
    data class Rating(val score: Double) : BreakFact

    /** What the channel shows next, at [startMs] since the epoch. */
    data class Next(val title: String, val startMs: Long) : BreakFact
}

/**
 * The facts the break screen turns through while [now] is on, before [next]: the episode, then what TMDB said in
 * [facts], then what is next. Empty when nothing is known.
 */
fun breakFacts(now: Program?, next: Program?, facts: ProgramFacts?): List<BreakFact> = buildList {
    if (now != null && (now.episode != null || now.subTitle != null)) add(BreakFact.Episode(now.episode, now.subTitle))
    if (facts != null) {
        facts.year?.let { year ->
            add(BreakFact.Aired(facts.movie, year, facts.endYear?.takeIf { it > year }, facts.studio))
        }
        if (facts.seasons != null || facts.episodes != null) add(BreakFact.Run(facts.seasons, facts.episodes))
        if (facts.cast.isNotEmpty()) add(BreakFact.Starring(facts.cast))
        if (facts.creators.isNotEmpty()) add(BreakFact.MadeBy(facts.movie, facts.creators))
        facts.tagline?.let { add(BreakFact.Tagline(it)) }
        facts.rating?.let { add(BreakFact.Rating(it)) }
    }
    if (next != null) add(BreakFact.Next(next.title, next.start.toEpochMilliseconds()))
}

/**
 * Asks the server what TMDB says about live programs, a title once while the app runs on one server; a title TMDB
 * does not list, or a server too old to say, gives null. A failed ask is not kept, so the next break asks again.
 */
class ProgramFactsBook internal constructor(private val api: Flow<SofaKingApi?>) {
    private val lock = Mutex()
    private var keptFor: SofaKingApi? = null
    private val kept = HashMap<String, ProgramFacts?>()

    suspend fun of(program: Program): ProgramFacts? {
        val api = api.first() ?: return null
        val movie = ProgramKind.MOVIE in program.kinds
        val key = (if (movie) "movie:" else "show:") + program.title
        val known = lock.withLock {
            if (keptFor !== api) {
                kept.clear()
                keptFor = api
            }
            if (key in kept) Known(kept[key]) else null
        }
        return if (known != null) known.facts else ask(api, program.title, movie, key)
    }

    /** What the server says about [title], kept under [key] unless the ask failed. */
    private suspend fun ask(api: SofaKingApi, title: String, movie: Boolean, key: String): ProgramFacts? {
        val answer = runCatchingCancellable { api.programFacts(title, movie).toFacts() }.recoverCatching { e ->
            // A server from before the facts has no route; it says nothing until it is updated.
            if (e is SofaKingException && e.status == NOT_FOUND) null else throw e
        }
        answer.onSuccess { facts -> lock.withLock { if (keptFor === api) kept[key] = facts } }
        answer.onFailure { log.d { "program facts failed: ${it.message}" } }
        return answer.getOrNull()
    }

    /** A kept answer, which may be that there is nothing to know. */
    private class Known(val facts: ProgramFacts?)

    private companion object {
        const val NOT_FOUND = 404
        val log = Logger.withTag("SofaKing")
    }
}

/** The app's facts from the server's, null when TMDB matched nothing. */
internal fun ProgramFactsDto.toFacts(): ProgramFacts? = if (kind.isEmpty()) {
    null
} else {
    ProgramFacts(
        movie = kind == Wire.ItemKind.MOVIE,
        year = year,
        endYear = endYear,
        studio = studio,
        seasons = seasons,
        episodes = episodes,
        cast = cast,
        creators = creators,
        tagline = tagline,
        rating = rating,
    )
}

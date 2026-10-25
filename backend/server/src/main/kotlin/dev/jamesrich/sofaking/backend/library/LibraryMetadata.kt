package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraryMetadata
import dev.jamesrich.sofaking.backend.store.saveLibraryMetadata
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

private val log = LoggerFactory.getLogger(LibraryMetadata::class.java)

/** How long a match stays before it is fetched again; TMDB's terms allow caching for up to six months. */
private val MATCHED_FOR: Duration = Duration.ofDays(30)

/** How long a title TMDB did not know waits before it is searched again. */
private val UNMATCHED_FOR: Duration = Duration.ofDays(7)

/** Lookups in a row that cannot reach TMDB before a run stops. */
private const val UNREACHABLE_STOP = 3

/** The wait between TMDB calls, well inside its rate limit. */
private val TMDB_PAUSE: Duration = Duration.ofMillis(60)

/** How many of the cast an item keeps, from TMDB or a `.nfo`. */
internal const val CAST_LIMIT = 20

/** What one metadata refresh did, for the scan's job note. [stopped] names the failure that ended it early. */
data class MetadataResult(
    val fetched: Int,
    val matched: Int,
    val unmatched: Int,
    val failed: Int,
    val stopped: String? = null,
) {
    val note: String get() = buildString {
        append("TMDB: $fetched fetched, $matched matched, $unmatched not found")
        if (failed > 0) append(", $failed failed")
        if (stopped != null) append(", stopped: $stopped")
    }
}

/**
 * Fills in what TMDB knows about a library's movies and shows, and a show's seasons and episodes. An id from a
 * `.nfo` or a `{tmdb-N}` tag pins the match; otherwise the title and year are searched. Lookups are kept in the
 * database and fetched again after [MATCHED_FOR]; a refused token, a rate limit or three lookups in a row that cannot
 * reach TMDB end the run, to resume on the next.
 */
class LibraryMetadata internal constructor(
    private val store: BackendStore,
    private val tmdb: TmdbClient?,
    private val clock: Clock,
    private val region: String = "US",
    /** The wait between TMDB calls, well inside its rate limit. */
    private val pause: Duration = TMDB_PAUSE,
) {
    val enabled: Boolean get() = tmdb != null

    suspend fun refresh(library: LibraryRecord, items: List<LibraryItemRecord>): MetadataResult? {
        val client = tmdb
        if (client == null || library.kind == LibraryKind.VIDEOS) return null
        val present = items.filter { it.missingSince == null }
        val run = Run(client, present.groupBy { it.parentId }, store.libraryMetadata(present.map { it.id }))
        for (item in present.filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.SHOW }) {
            if (!run.lookUp(item)) break
        }
        return run.result()
    }

    /** One refresh of a library whose items are under their parents in [children] and were looked up as [known]. */
    private inner class Run(
        private val client: TmdbClient,
        private val children: Map<String?, List<LibraryItemRecord>>,
        private val known: Map<String, MetadataRecord>,
    ) {
        private val now = clock.instant()
        private var fetched = 0
        private var matched = 0
        private var unmatched = 0
        private var failed = 0
        private var unreachable = 0
        private var stopped: String? = null

        fun result() = MetadataResult(fetched, matched, unmatched, failed, stopped)

        /** Looks up the movie or show [item] unless its row is fresh; false once the run has to stop. */
        suspend fun lookUp(item: LibraryItemRecord): Boolean {
            val hint = item.details?.tmdbId
            val row = known[item.id]?.takeIf { row ->
                (hint == null || row.tmdbId == hint) && pinnedAs(item, row) &&
                    Instant.parse(row.checkedAt).plus(if (row.tmdbId == null) UNMATCHED_FOR else MATCHED_FOR) > now
            }
            if (row != null && !hasNewEpisodes(item, row)) return true
            return try {
                val records = if (item.kind == ItemKind.MOVIE) movie(item, hint) else show(item, hint, row)
                fetched++
                unreachable = 0
                store.saveLibraryMetadata(records)
                true
            } catch (e: TmdbFailure) {
                failed(item, e)
            }
        }

        /** Whether [row] was looked up by the TheTVDB and IMDb ids [item] names now, when it names any. */
        private fun pinnedAs(item: LibraryItemRecord, row: MetadataRecord): Boolean {
            val details = item.details ?: return true
            val tvdb = details.tvdbId.takeIf { item.kind == ItemKind.SHOW }
            return (tvdb == null || row.tvdbId == tvdb) && (details.imdbId == null || row.imdbId == details.imdbId)
        }

        /**
         * The TMDB id of the TheTVDB or IMDb id [item] names, from TMDB's `/find`: a show's TheTVDB id first, as Sonarr
         * names shows by it; null when it names neither or TMDB knows neither.
         */
        private suspend fun found(item: LibraryItemRecord): Int? {
            val details = item.details ?: return null
            val show = item.kind == ItemKind.SHOW
            val asked = listOfNotNull(
                details.tvdbId?.takeIf { show }?.let { it.toString() to "tvdb_id" },
                details.imdbId?.let { it to "imdb_id" },
            )
            for ((id, source) in asked) {
                val found = client.find(id, source).also { pause() }
                val match = if (show) found.shows.firstOrNull()?.id else found.movies.firstOrNull()?.id
                if (match != null) return match
            }
            return null
        }

        /** A show that is fresh still fetches the seasons of episodes it has not looked up yet. */
        private fun hasNewEpisodes(item: LibraryItemRecord, row: MetadataRecord): Boolean =
            item.kind == ItemKind.SHOW && row.tmdbId != null &&
                descendants(item.id).any { it.kind == ItemKind.EPISODE && it.episode != null && it.id !in known }

        private suspend fun movie(item: LibraryItemRecord, hint: Int?): List<MetadataRecord> {
            val id = hint ?: found(item) ?: pickMovie(client.searchMovies(item.title, item.year).also { pause() }, item)
            val movie = id?.let { client.movie(it).also { pause() } }
            if (movie != null) matched++ else unmatched++
            val imdb = item.details?.imdbId ?: movie?.imdbId?.ifEmpty { null }
            return listOf(MetadataRecord(item.id, movie?.id, now.toString(), movie?.facts(region), imdbId = imdb))
        }

        /** The show [item] and its seasons; with a [fresh] row only the seasons it has not looked up yet. */
        private suspend fun show(item: LibraryItemRecord, hint: Int?, fresh: MetadataRecord?): List<MetadataRecord> {
            val records = ArrayList<MetadataRecord>()
            val id = if (fresh != null) {
                fresh.tmdbId
            } else {
                hint ?: found(item) ?: pickShow(client.searchShows(item.title, item.year).also { pause() }, item)
            }
            if (fresh == null) {
                val show = id?.let { client.show(it).also { pause() } }
                val tvdb = item.details?.tvdbId ?: show?.externalIds?.tvdbId
                val imdb = item.details?.imdbId ?: show?.externalIds?.imdbId?.ifEmpty { null }
                records += MetadataRecord(item.id, show?.id, now.toString(), show?.facts(region), tvdb, imdb)
            }
            if (id != null) {
                matched++
                records += seasons(id, item, refetchAll = fresh == null)
            } else {
                unmatched++
            }
            return records
        }

        /** Counts [e], and stops the run on a refused token, a rate limit, or TMDB unreachable a few times in a row. */
        private fun failed(item: LibraryItemRecord, e: TmdbFailure): Boolean {
            if (e.code == "TMDB_AUTH" || e.code == "TMDB_RATE_LIMITED") {
                stopped = e.code
            } else if (e.code.startsWith("TMDB_UNREACHABLE") && ++unreachable >= UNREACHABLE_STOP) {
                // With the network down every lookup waits out its timeout; a few in a row end the run until the
                // next scan.
                failed++
                stopped = "TMDB_UNREACHABLE"
            } else {
                log.info("tmdb lookup failed item={} code={}", item.id, e.code)
                failed++
            }
            return stopped == null
        }

        /** One lookup per season that holds numbered episodes: the season's own facts and each episode's. */
        private suspend fun seasons(showId: Int, show: LibraryItemRecord, refetchAll: Boolean): List<MetadataRecord> {
            val episodes = descendants(show.id).filter {
                it.kind == ItemKind.EPISODE && it.season != null && it.episode != null
            }
            val seasonItems = children[show.id].orEmpty().filter {
                it.kind == ItemKind.SEASON
            }.associateBy { it.season }
            val records = ArrayList<MetadataRecord>()
            for ((number, inSeason) in episodes.groupBy { it.season!! }) {
                val wanted = refetchAll || inSeason.any { it.id !in known }
                val season = if (wanted) client.season(showId, number).also { pause() } else null
                if (season != null) records += seasonRecords(showId, season, seasonItems[number], inSeason)
            }
            return records
        }

        /** The facts of [season] for its folder [item], if the show has one, and for each of its [episodes]. */
        private fun seasonRecords(
            showId: Int,
            season: TmdbSeason,
            item: LibraryItemRecord?,
            episodes: List<LibraryItemRecord>,
        ): List<MetadataRecord> {
            val own = item?.let {
                val facts = MetadataFacts(
                    title = season.name.ifEmpty { null },
                    overview = season.overview?.ifEmpty { null },
                    aired = season.airDate,
                    poster = season.posterPath,
                )
                MetadataRecord(it.id, showId, now.toString(), facts)
            }
            val byNumber = season.episodes.associateBy { it.episodeNumber }
            return listOfNotNull(own) + episodes.map { episode ->
                val found = byNumber[episode.episode]
                MetadataRecord(episode.id, found?.let { showId }, now.toString(), found?.let(::episodeFacts))
            }
        }

        private fun descendants(id: String): List<LibraryItemRecord> =
            children[id].orEmpty().flatMap { listOf(it) + descendants(it.id) }
    }

    private fun episodeFacts(episode: TmdbEpisode) = MetadataFacts(
        title = episode.name.ifEmpty { null },
        aired = episode.airDate,
        year = yearOf(episode.airDate),
        overview = episode.overview?.ifEmpty { null },
        runtimeMinutes = episode.runtime,
        poster = episode.stillPath,
        rating = episode.voteAverage.rating(),
    )

    private suspend fun pause() = delay(pause.toMillis())
}

/** Letters and digits only, lower case, so `Alien³` and `Alien 3` or `Mr. Robot` and `Mr Robot` compare equal. */
internal fun normalized(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

/**
 * The result whose title and year agree with [item]'s, else the one whose title does, else
 * a lone result whose year does.
 */
internal fun pickMovie(results: List<TmdbMovieResult>, item: LibraryItemRecord): Int? = pick(
    results.map { Triple(it.id, listOf(it.title, it.originalTitle), yearOf(it.releaseDate)) },
    item,
)

internal fun pickShow(results: List<TmdbShowResult>, item: LibraryItemRecord): Int? = pick(
    results.map { Triple(it.id, listOf(it.name, it.originalName), yearOf(it.firstAirDate)) },
    item,
)

private fun pick(results: List<Triple<Int, List<String>, Int?>>, item: LibraryItemRecord): Int? {
    val title = normalized(item.title)
    val sameTitle = results.filter { (_, titles) -> titles.any { normalized(it) == title } }
    val yearFits = { year: Int? -> item.year == null || year == null || kotlin.math.abs(year - item.year) <= 1 }
    return sameTitle.firstOrNull { (_, _, year) -> year == item.year }?.first
        ?: sameTitle.firstOrNull { (_, _, year) -> yearFits(year) }?.first
        ?: results.singleOrNull()?.takeIf { (_, _, year) -> item.year != null && yearFits(year) }?.first
}

/** A rating rounded to one decimal place. */
internal fun Double?.rating(): Double? =
    this?.takeIf { it > 0 }?.let { (it * RATING_SCALE).roundToInt() / RATING_SCALE.toDouble() }

private const val RATING_SCALE = 10

internal fun TmdbMovie.facts(region: String) = MetadataFacts(
    title = title.ifEmpty { null }, year = yearOf(releaseDate), aired = releaseDate?.ifEmpty { null },
    overview = overview?.ifEmpty {
        null
    },
    tagline = tagline?.ifEmpty { null }, genres = genres.map { it.name }.filter { it.isNotEmpty() },
    studio = companies.firstOrNull()?.name?.ifEmpty { null },
    contentRating = releaseDates?.results?.firstOrNull {
        it.country == region
    }?.dates?.firstOrNull { it.certification.isNotBlank() }?.certification,
    rating = voteAverage.rating(), cast = credits?.cast.orEmpty().map { it.name }.take(CAST_LIMIT),
    directors = credits?.crew.orEmpty().filter { it.job == "Director" }.map { it.name },
    runtimeMinutes = runtime?.takeIf { it > 0 }, poster = posterPath, backdrop = backdropPath,
)

internal fun TmdbShow.facts(region: String) = MetadataFacts(
    title = name.ifEmpty { null }, year = yearOf(firstAirDate), aired = firstAirDate?.ifEmpty { null },
    overview = overview?.ifEmpty {
        null
    },
    tagline = tagline?.ifEmpty { null }, genres = genres.map { it.name }.filter { it.isNotEmpty() },
    studio = networks.firstOrNull()?.name?.ifEmpty { null },
    contentRating = contentRatings?.results?.firstOrNull { it.country == region }?.rating?.ifEmpty { null },
    rating = voteAverage.rating(), cast = credits?.cast.orEmpty().map { it.name }.take(CAST_LIMIT),
    runtimeMinutes = episodeRunTime.firstOrNull(), poster = posterPath, backdrop = backdropPath,
)

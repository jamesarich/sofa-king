package dev.jamesrich.sofaking.data

/** The kinds of program the guide can list on their own, read from the guide's categories. */
enum class ProgramKind { MOVIE, SPORTS, KIDS, NEWS }

/**
 * The kinds [categories] name, in any guide's words: `Sports` or `Sports event`; `Kids` or `Children`; `News`.
 * A movie needs a film genre beside its `Movie` mark (`Movie, Thriller`, `Movies`, `TV Movie`): some guides mark
 * every program on a live feed `Movie` beside the feed's own heading (`Movie, Music`, `Movie, En Español`), and a
 * program filed as news or sports is not a movie either. Unknown categories name none.
 */
fun programKinds(categories: List<String>): Set<ProgramKind> {
    var mask = 0
    var filmMark = false
    var filmGenre = false
    for (category in categories) {
        val words = category.lowercase()
        if ("movie" in words || "film" in words) filmMark = true
        if (words == "movies" || words == "tv movie" || words == "feature film" || FILM_GENRES.any { it in words }) filmGenre = true
        if ("sport" in words) mask = mask or ProgramKind.SPORTS.bit
        if ("kid" in words || "child" in words) mask = mask or ProgramKind.KIDS.bit
        if ("news" in words) mask = mask or ProgramKind.NEWS.bit
    }
    if (filmMark && filmGenre && mask and (ProgramKind.NEWS.bit or ProgramKind.SPORTS.bit) == 0) mask = mask or ProgramKind.MOVIE.bit
    return kindsOf(mask)
}

private val FILM_GENRES = listOf(
    "drama", "thriller", "horror", "comed", "action", "crime", "romanc", "romantic", "mystery", "fantasy", "adventure",
    "western", "sci-fi", "science fiction", "war", "musical", "biograph", "histor", "family", "animation", "documentary",
    "suspense",
)

internal val ProgramKind.bit: Int get() = 1 shl ordinal

internal fun Set<ProgramKind>.mask(): Int = fold(0) { mask, kind -> mask or kind.bit }

/** One shared set per combination, so a guide of thousands of programs holds a handful of sets. */
internal fun kindsOf(mask: Int): Set<ProgramKind> = KIND_SETS.getOrElse(mask) { emptySet() }

private val KIND_SETS: List<Set<ProgramKind>> = List(1 shl ProgramKind.entries.size) { mask ->
    ProgramKind.entries.filterTo(LinkedHashSet()) { mask and it.bit != 0 }
}

package dev.jamesrich.sofaking.data

/** The kinds of program the guide can list on their own, read from the guide's categories. */
enum class ProgramKind { MOVIE, SPORTS, KIDS, NEWS }

/**
 * The kinds [categories] name, in any guide's words: `Movie`, `Movies` or `Feature Film`; `Sports` or `Sports event`;
 * `Kids` or `Children`; `News`. Unknown categories name none.
 */
fun programKinds(categories: List<String>): Set<ProgramKind> {
    var mask = 0
    for (category in categories) {
        val words = category.lowercase()
        if ("movie" in words || "film" in words) mask = mask or ProgramKind.MOVIE.bit
        if ("sport" in words) mask = mask or ProgramKind.SPORTS.bit
        if ("kid" in words || "child" in words) mask = mask or ProgramKind.KIDS.bit
        if ("news" in words) mask = mask or ProgramKind.NEWS.bit
    }
    return kindsOf(mask)
}

internal val ProgramKind.bit: Int get() = 1 shl ordinal

internal fun Set<ProgramKind>.mask(): Int = fold(0) { mask, kind -> mask or kind.bit }

/** One shared set per combination, so a guide of thousands of programs holds a handful of sets. */
internal fun kindsOf(mask: Int): Set<ProgramKind> = KIND_SETS.getOrElse(mask) { emptySet() }

private val KIND_SETS: List<Set<ProgramKind>> = List(1 shl ProgramKind.entries.size) { mask ->
    ProgramKind.entries.filterTo(LinkedHashSet()) { mask and it.bit != 0 }
}

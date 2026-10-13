package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.PolicyNames
import java.text.Normalizer

/** The name normalization versions this build implements. */
val NAME_VERSIONS: Set<Int> = setOf(1, 2)

private val combiningMarks = Regex("\\p{Mn}+")
private val separators = Regex("[^\\p{L}\\p{Nd}\\p{Mc}]+")

/**
 * The version 1 normalization: NFKD without combining marks, lowercase, `&` and `+` spelled out, separators collapsed
 * to one space, then a leading `the` and a trailing `hd` dropped. Depends on [name] alone, never on the catalog.
 */
fun baseName(name: String): String {
    val decomposed = combiningMarks.replace(Normalizer.normalize(name, Normalizer.Form.NFKD), "").lowercase(java.util.Locale.ROOT)
    val spelled = decomposed.replace("&", " and ").replace("+", " plus ")
    var words = separators.replace(spelled, " ").trim().split(' ').filter { it.isNotEmpty() }
    if (words.size > 1 && words.first() == "the") words = words.drop(1)
    if (words.size > 1 && words.last() == "hd") words = words.dropLast(1)
    return words.joinToString(" ")
}

/** The normalized name under [version]: version 1 is [baseName]; version 2 is [baseName] without spaces. */
fun normalizedName(name: String, version: Int): String = baseName(name).let { if (version >= 2) it.replace(" ", "") else it }

/**
 * Station keys: the normalized name through [aliases], then through [learned]. A learned target that is itself an
 * alias resolves through it, so an alias added later still holds.
 */
class StationKeys(private val aliases: Map<String, String> = emptyMap(), val version: Int = 1, private val learned: Map<String, String> = emptyMap()) {
    init {
        require(version in NAME_VERSIONS) { "Unknown names.version $version" }
    }

    /** The normalized name, before aliases. */
    fun normalize(name: String): String = normalizedName(name, version)

    fun key(name: String): String = resolve(normalize(name))

    /** The station key a normalized name joins. */
    fun resolve(normalized: String): String {
        val aliased = aliases[normalized] ?: normalized
        return learned[aliased]?.let { aliases[it] ?: it } ?: aliased
    }
}

/** True when [never] lists the pair [a], [b] in either order. */
fun neverMerges(never: List<List<String>>, a: String, b: String): Boolean = never.any { it.size == 2 && ((it[0] == a && it[1] == b) || (it[0] == b && it[1] == a)) }

/** The learned aliases a run applies: those a [PolicyNames.never] pair does not block and an alias does not override. */
fun effectiveLearned(names: PolicyNames): Map<String, String> =
    names.learned.filter { (from, to) -> from !in names.aliases && !neverMerges(names.never, from, to) }

fun stationKeys(names: PolicyNames): StationKeys = StationKeys(names.aliases, names.version, effectiveLearned(names))

private val LOOSE_FILLER = setOf("tv", "channel", "network", "live", "the", "hd")

/**
 * The loose form of a name, for finding stations that may be one: base words without the filler words
 * tv, channel, network, live, the and hd, and without a trailing `by <words>`, then without spaces. Null when
 * nothing is left. Never a key: a loose match merges only on schedule evidence.
 */
fun looseName(name: String): String? {
    var words = baseName(name).split(' ').filter { it.isNotEmpty() }
    val by = words.lastIndexOf("by")
    if (by > 0 && by < words.size - 1) words = words.subList(0, by)
    return words.filter { it !in LOOSE_FILLER }.joinToString("").ifEmpty { null }
}

private val ALIAS_SUFFIXES = listOf(" tv", " channel")

/**
 * Pairs of [keys] that likely name one station: one is the other plus a trailing ` tv` or ` channel`, or the two
 * differ only by spaces (a dropped leading `the` counts). Each reads `<key>: possible alias of <other key>`.
 * Pairs [never] lists are left out.
 */
fun suggestedAliases(keys: Collection<String>, never: List<List<String>> = emptyList()): List<String> {
    val present = keys.toSortedSet()
    val suggestions = sortedSetOf<String>()
    fun suggest(key: String, other: String) { if (!neverMerges(never, key, other)) suggestions += "$key: possible alias of $other" }
    for (key in present) {
        for (suffix in ALIAS_SUFFIXES) {
            if (key.endsWith(suffix)) key.removeSuffix(suffix).takeIf { it.isNotEmpty() && it in present }?.let { suggest(key, it) }
        }
    }
    val byCompact = present.groupBy { it.replace(" ", "") }
    fun spaces(value: String) = value.count { it == ' ' }
    for (key in present) {
        val compact = key.replace(" ", "")
        for (other in byCompact[compact].orEmpty()) if (spaces(other) < spaces(key)) suggest(key, other)
        for (other in byCompact["the$compact"].orEmpty()) suggest(key, other)
    }
    return suggestions.toList()
}

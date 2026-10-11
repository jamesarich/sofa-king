package dev.jamesrich.sofaking.backend.catalog

import java.text.Normalizer

/** The name normalization versions this build implements. */
val NAME_VERSIONS: Set<Int> = setOf(1)

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

/** Station keys: `aliases[baseName(name)] ?: baseName(name)`. */
class StationKeys(private val aliases: Map<String, String> = emptyMap(), val version: Int = 1) {
    init {
        require(version in NAME_VERSIONS) { "Unknown names.version $version" }
    }

    /** The normalized name, before aliases. */
    fun normalize(name: String): String = baseName(name)

    fun key(name: String): String = normalize(name).let { aliases[it] ?: it }
}

private val ALIAS_SUFFIXES = listOf(" tv", " channel")

/**
 * Pairs of [keys] that likely name one station: one is the other plus a trailing ` tv` or ` channel`, or the two
 * differ only by spaces (a dropped leading `the` counts). Each reads `<key>: possible alias of <other key>`.
 */
fun suggestedAliases(keys: Collection<String>): List<String> {
    val present = keys.toSortedSet()
    val suggestions = sortedSetOf<String>()
    for (key in present) {
        for (suffix in ALIAS_SUFFIXES) {
            if (key.endsWith(suffix)) key.removeSuffix(suffix).takeIf { it.isNotEmpty() && it in present }?.let { suggestions += "$key: possible alias of $it" }
        }
    }
    val byCompact = present.groupBy { it.replace(" ", "") }
    fun spaces(value: String) = value.count { it == ' ' }
    for (key in present) {
        val compact = key.replace(" ", "")
        for (other in byCompact[compact].orEmpty()) if (spaces(other) < spaces(key)) suggestions += "$key: possible alias of $other"
        for (other in byCompact["the$compact"].orEmpty()) suggestions += "$key: possible alias of $other"
    }
    return suggestions.toList()
}

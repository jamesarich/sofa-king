package dev.jamesrich.sofaking.backend.catalog

import java.text.Normalizer

/** The name normalization versions this build implements. */
val NAME_VERSIONS: Set<Int> = setOf(1)

private val combiningMarks = Regex("\\p{Mn}+")
private val separators = Regex("[^\\p{L}\\p{Nd}\\p{Mc}]+")
private val SUFFIXES = listOf("tv", "channel")

/**
 * Steps 1 to 3 of the version 1 normalization: NFKD without combining marks, lowercase, `&` and `+` spelled out,
 * separators collapsed to one space, then a leading `the` and a trailing `hd` dropped. Never returns an empty
 * string for a name with a letter or digit in it.
 */
fun baseName(name: String): String {
    val decomposed = combiningMarks.replace(Normalizer.normalize(name, Normalizer.Form.NFKD), "").lowercase(java.util.Locale.ROOT)
    val spelled = decomposed.replace("&", " and ").replace("+", " plus ")
    var words = separators.replace(spelled, " ").trim().split(' ').filter { it.isNotEmpty() }
    if (words.size > 1 && words.first() == "the") words = words.drop(1)
    if (words.size > 1 && words.last() == "hd") words = words.dropLast(1)
    return words.joinToString(" ")
}

/**
 * Station keys over one catalog. The trailing `tv` / `channel` strip (step 4) depends on every other candidate
 * name, so a key is only meaningful against the catalog it was built from.
 */
class StationKeys(catalogNames: Collection<String>, private val aliases: Map<String, String> = emptyMap(), val version: Int = 1) {
    init {
        require(version in NAME_VERSIONS) { "Unknown names.version $version" }
    }

    /** For each remainder, the suffixes it carries in the catalog; the bare remainder counts as the empty suffix. */
    private val suffixesByRemainder: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        for (base in catalogNames.mapTo(HashSet(), ::baseName)) {
            val split = splitSuffix(base)
            if (split == null) getOrPut(base) { HashSet() } += "" else getOrPut(split.first) { HashSet() } += split.second
        }
    }

    /** The normalized name, before aliases. */
    fun normalize(name: String): String {
        val base = baseName(name)
        val (remainder, suffix) = splitSuffix(base) ?: return base
        val longEnough = remainder.contains(' ') || remainder.length >= 5
        val others = suffixesByRemainder[remainder].orEmpty() - suffix
        return if (longEnough && others.isEmpty()) remainder else base
    }

    /** `aliases[normalize(name)] ?: normalize(name)`. */
    fun key(name: String): String = normalize(name).let { aliases[it] ?: it }

    private fun splitSuffix(base: String): Pair<String, String>? {
        val space = base.lastIndexOf(' ')
        if (space <= 0) return null
        val suffix = base.substring(space + 1)
        return if (suffix in SUFFIXES) base.substring(0, space) to suffix else null
    }
}

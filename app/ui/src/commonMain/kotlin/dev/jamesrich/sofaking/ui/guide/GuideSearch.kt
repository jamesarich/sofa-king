package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.ui.formatNumber
import kotlin.time.Instant

/** Matching for the guide search row. Case- and accent-insensitive. */
internal object GuideSearch {
    /** How many upcoming matches a channel's "Later" line shows. */
    const val MAX_UPCOMING = 3

    /** Lowercased with accents removed, so "Télé" and "tele" compare equal. */
    fun normalize(text: String): String = buildString(text.length) {
        for (char in text.lowercase()) {
            // Combining marks (a decomposed accent) are dropped; precomposed letters fold to their base letter.
            if (char in '̀'..'ͯ') continue
            append(FOLDS[char] ?: char)
        }
    }

    /**
     * True when [query] is blank, or it appears in the channel [name] or the airing program
     * [programTitle], or it is the start of the channel [number].
     */
    fun matches(query: String, number: Double, name: String, programTitle: String?): Boolean {
        val needle = normalize(query.trim())
        if (needle.isEmpty()) return true
        return normalize(number.formatNumber()).startsWith(needle) ||
            normalize(name).contains(needle) ||
            (programTitle != null && normalize(programTitle).contains(needle))
    }

    /**
     * The programs of one channel that start after [after] and before [before] whose title or episode title
     * holds [query], in start order, at most [MAX_UPCOMING]; empty for a blank query. The airing program is the
     * grid's own match, so it is left out.
     */
    fun upcoming(query: String, programs: List<Program>, after: Instant, before: Instant): List<Program> {
        val needle = normalize(query.trim())
        if (needle.isEmpty()) return emptyList()
        return programs.asSequence()
            .filter { it.start > after && it.start < before }
            .filter {
                normalize(it.title).contains(needle) ||
                    (it.subTitle?.let { sub -> normalize(sub).contains(needle) } == true)
            }
            .sortedBy { it.start }
            .take(MAX_UPCOMING)
            .toList()
    }

    /**
     * The Latin letters with diacritics that NFD would decompose, folded to their base letter; Kotlin has no
     * common Unicode normalizer. Covers Latin-1 Supplement and Latin Extended-A, which is what channel and
     * program names use.
     */
    private val FOLDS: Map<Char, Char> = buildMap {
        fun fold(base: Char, accented: String) = accented.forEach { put(it, base) }
        fold('a', "àáâãäåāăą")
        fold('c', "çćĉċč")
        fold('d', "ď")
        fold('e', "èéêëēĕėęě")
        fold('g', "ĝğġģ")
        fold('h', "ĥ")
        fold('i', "ìíîïĩīĭįİ")
        fold('j', "ĵ")
        fold('k', "ķ")
        fold('l', "ĺļľ")
        fold('n', "ñńņňŉ")
        fold('o', "òóôõöōŏő")
        fold('r', "ŕŗř")
        fold('s', "śŝşš")
        fold('t', "ţť")
        fold('u', "ùúûüũūŭůűų")
        fold('w', "ŵ")
        fold('y', "ýÿŷ")
        fold('z', "źżž")
    }
}

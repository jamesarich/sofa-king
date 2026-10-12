package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.ui.formatNumber
import java.text.Normalizer
import java.util.Locale

/** Matching for the guide search row. Case- and accent-insensitive. */
internal object GuideSearch {
    private val combiningMarks = Regex("\\p{Mn}+")

    /** Lowercased with accents removed, so "Télé" and "tele" compare equal. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.ROOT)

    /**
     * True when [query] is blank, or it appears in the channel [name] or the airing programme
     * [programmeTitle], or it is the start of the channel [number].
     */
    fun matches(query: String, number: Double, name: String, programmeTitle: String?): Boolean {
        val needle = normalize(query.trim())
        if (needle.isEmpty()) return true
        return normalize(number.formatNumber()).startsWith(needle) ||
            normalize(name).contains(needle) ||
            (programmeTitle != null && normalize(programmeTitle).contains(needle))
    }
}

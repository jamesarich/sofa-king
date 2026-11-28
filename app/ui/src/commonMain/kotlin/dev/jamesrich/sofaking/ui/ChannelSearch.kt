package dev.jamesrich.sofaking.ui

import dev.jamesrich.sofaking.data.SearchText

/** Matching a typed query against channels, for the guide's search row and multiview's picker. */
internal object ChannelSearch {
    /** Lowercased with accents removed, so "Télé" and "tele" compare equal. */
    fun normalize(text: String): String = SearchText.fold(text)

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
}

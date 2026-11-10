package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.ui.ChannelSearch
import kotlin.time.Instant

/** Matching for the guide search row. Case- and accent-insensitive. */
internal object GuideSearch {
    /** How many upcoming matches a channel's "Later" line shows. */
    const val MAX_UPCOMING = 3

    /** Lowercased with accents removed, so "Télé" and "tele" compare equal. */
    fun normalize(text: String): String = ChannelSearch.normalize(text)

    /**
     * True when [query] is blank, or it appears in the channel [name] or the airing program
     * [programTitle], or it is the start of the channel [number].
     */
    fun matches(query: String, number: Double, name: String, programTitle: String?): Boolean =
        ChannelSearch.matches(query, number, name, programTitle)

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
}

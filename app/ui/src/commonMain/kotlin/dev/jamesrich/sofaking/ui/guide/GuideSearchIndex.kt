package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.ui.formatNumber
import kotlin.time.Instant

/**
 * The guide search's text, normalized once per lineup and guide, so a keystroke normalizes only the query and then
 * scans plain strings. Matching is the same as [GuideSearch.matches] and [GuideSearch.upcoming].
 */
internal class GuideSearchIndex(
    channels: List<Channel>,
    guide: Map<String, List<Program>>,
    normalize: (String) -> String = GuideSearch::normalize,
) {
    private class Entry(val number: String, val name: String, val programs: List<IndexedProgram>)

    private class IndexedProgram(val program: Program, val title: String, val subTitle: String?)

    private val entries: Map<String, Entry> = HashMap<String, Entry>(channels.size * 2).also { map ->
        for (channel in channels) {
            val programs = guide[channel.guideId].orEmpty().map {
                IndexedProgram(it, normalize(it.title), it.subTitle?.let(normalize))
            }
            map[channel.guideId] = Entry(normalize(channel.number.formatNumber()), normalize(channel.name), programs)
        }
    }

    /**
     * True when [needle] (already normalized and trimmed) is empty, or it is the start of the [channel]'s number,
     * inside its name, or inside the title of the program airing at [at].
     */
    fun matches(needle: String, channel: Channel, at: Instant): Boolean {
        if (needle.isEmpty()) return true
        val entry = entries[channel.guideId] ?: return false
        if (entry.number.startsWith(needle) || entry.name.contains(needle)) return true
        val airing = entry.programs.firstOrNull { it.program.start <= at && it.program.stop > at }
        return airing != null && airing.title.contains(needle)
    }

    /** The [channel]'s programs between [after] and [before] that match [needle], as [GuideSearch.upcoming]. */
    fun upcoming(needle: String, channel: Channel, after: Instant, before: Instant): List<Program> {
        if (needle.isEmpty()) return emptyList()
        val entry = entries[channel.guideId] ?: return emptyList()
        return entry.programs.asSequence()
            .filter { it.program.start > after && it.program.start < before }
            .filter { it.title.contains(needle) || it.subTitle?.contains(needle) == true }
            .map { it.program }
            .sortedBy { it.start }
            .take(GuideSearch.MAX_UPCOMING)
            .toList()
    }

    companion object {
        /** The query as the index compares it. */
        fun needle(query: String, normalize: (String) -> String = GuideSearch::normalize): String =
            normalize(query.trim())
    }
}

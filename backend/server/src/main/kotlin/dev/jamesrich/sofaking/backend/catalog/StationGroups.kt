package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyUnmappedCategory
import dev.jamesrich.sofaking.backend.setup.DefaultGroups

/**
 * The group of each station in a run: its override, else a name pattern, else its leading entry's group, else its
 * sources' vote, else the default, else the leading channel's raw group.
 */
internal class StationGroups(private val policy: LineupPolicy) {
    private val weak = policy.groups.weak.mapTo(HashSet()) { it.trim().lowercase() }
    private val builtIn = if (policy.groups.builtIn) DefaultGroups.load() else null
    private val namePatterns =
        policy.groups.patterns.mapNotNull { p -> p.name?.let { Regex(it, RegexOption.IGNORE_CASE) to p.group } }
    private val rawPatterns =
        policy.groups.patterns.mapNotNull { p -> p.rawGroup?.let { Regex(it, RegexOption.IGNORE_CASE) to p.group } }
    private val byKey = HashMap<String, String?>()

    fun groupOf(draft: Draft): String? = byKey.getOrPut(draft.key) {
        draft.override?.group ?: nameGroup(draft) ?: entryGroup(draft)
            ?: votedGroup(draft) ?: policy.groups.default ?: draft.leading?.let(::rawOf)
    }

    /**
     * Raw categories of any source that nothing maps and that are not weak, by provider and lowercase category, for
     * the stations whose group the vote decides (no override, name pattern or leading entry's group settles it), most
     * stations first; and how many stations they come from.
     */
    fun unmapped(drafts: List<Draft>): Pair<List<PolicyUnmappedCategory>, Int> {
        val rows = drafts.flatMap(::unmappedRows)
        val categories = rows.groupBy { it.provider to it.category }.map { (category, rows) ->
            PolicyUnmappedCategory(category.first, category.second, rows.size, rows.map { it.name }.sorted().first())
        }.sortedWith(
            compareByDescending<PolicyUnmappedCategory> {
                it.stations
            }.thenBy { it.provider }.thenBy { it.category },
        )
        return categories to rows.distinctBy { it.key }.size
    }

    private data class Unmapped(val provider: String, val category: String, val key: String, val name: String)

    private fun unmappedRows(draft: Draft): List<Unmapped> {
        val leading = draft.leading ?: return emptyList()
        val settled = draft.override?.group != null || nameGroup(draft) != null || entryGroup(draft) != null
        if (settled) return emptyList()
        val name = draft.override?.name ?: leading.candidate.channel.name
        return draft.voters.mapNotNull { member ->
            rawOf(member)?.takeIf { raw -> mapped(member, raw) == null && !isWeak(member, raw) }?.let { raw ->
                Unmapped(member.candidate.providerId, raw.lowercase(), draft.key, name)
            }
        }.distinct()
    }

    private fun rawOf(member: Member): String? =
        member.candidate.channel.tags.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun isWeak(member: Member, raw: String): Boolean =
        raw.lowercase() in weak || "${member.candidate.providerId}:${raw.lowercase()}" in weak ||
            builtIn?.isWeak(raw) == true

    /** The policy's own map and patterns first; the shipped map only names what they leave out. */
    private fun mapped(member: Member, raw: String): String? =
        policy.groups.map["${member.candidate.providerId}:${raw.lowercase()}"]
            ?: policy.groups.map[raw.lowercase()]
            ?: rawPatterns.firstOrNull { it.first.containsMatchIn(raw) }?.second
            ?: builtIn?.groupOf(raw)

    private fun nameGroup(draft: Draft): String? = (draft.override?.name ?: draft.leading?.candidate?.channel?.name)
        ?.let { name -> namePatterns.firstOrNull { it.first.containsMatchIn(name) }?.second }

    private fun entryGroup(draft: Draft): String? = draft.leading?.entry?.let { policy.providers[it].group }

    private fun votedGroup(draft: Draft): String? {
        val cast = draft.voters.mapNotNull { member ->
            val raw = rawOf(member) ?: return@mapNotNull null
            mapped(member, raw)?.let { Triple(member.candidate.providerId, it, isWeak(member, raw)) }
        }
        return elect(cast.filter { !it.third }.map { it.first to it.second })
            ?: elect(cast.map { it.first to it.second })
    }

    /**
     * Each provider counts once per group; a tie goes to the group whose first vote comes earliest in leading order.
     */
    private fun elect(votes: List<Pair<String, String>>): String? {
        val tally = votes.distinct().groupingBy { it.second }.eachCount()
        val best = tally.values.maxOrNull() ?: return null
        return votes.first { tally[it.second] == best }.second
    }
}

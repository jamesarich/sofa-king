package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.PolicyNumbering
import dev.jamesrich.sofaking.backend.api.StationOverride

/**
 * Numbers a run's stations. Pass one reserves every number the file holds, including a station that has just left,
 * and the numbers missing stations still hold; pass two assigns the rest, in the station's group block when
 * numbering.groups names its group. An [unverified] station holds a floor number that is never written.
 */
internal class StationNumbering(
    private val numbering: PolicyNumbering,
    private val groupOf: (Draft) -> String?,
    private val unverified: Set<String>,
) {
    private val used = HashSet<String>()
    private val starts = numbering.groups.values.sorted()

    /** The numbers pass two gave, key to number, written back to the file. */
    val assigned = sortedMapOf<String, String>()

    /** Server-numbered stations that followGroup moved into their group's block, key to new number. */
    val renumbered = sortedMapOf<String, String>()

    fun number(drafts: List<Draft>, overrides: Collection<StationOverride>) {
        for (override in overrides) override.number?.let { used += canonicalNumber(it) }
        for (draft in drafts.filter { it.number == null && it.missing?.lastNumber != null }.sortedBy { it.key }) {
            val last = canonicalNumber(draft.missing!!.lastNumber!!)
            if (used.add(last)) draft.number = last
        }
        val released = if (numbering.followGroup) release(drafts) else emptyMap()
        val pending = drafts.filter { it.number == null }.sortedWith(
            compareBy<Draft> { it.leading?.entry ?: Int.MAX_VALUE }.thenBy(NUMBER_ORDER) { it.leading?.number }
                .thenBy { it.key },
        )
        for (draft in pending) {
            val old = released[draft.key]
            if (old != null) renumber(draft, old) else assign(draft)
        }
    }

    /**
     * With followGroup, a server-numbered station outside its group's block competes for a number in it. Its old
     * number stays reserved for this run, and it keeps that number when the block is full.
     */
    private fun release(drafts: List<Draft>): Map<String, String> {
        val serverNumbered = numbering.assigned.toHashSet()
        val released = HashMap<String, String>()
        for (draft in drafts) {
            val number = draft.number
            if (number != null && draft.key in serverNumbered && outsideBlock(draft, number)) {
                released[draft.key] = number
                draft.number = null
            }
        }
        return released
    }

    /** Whether live, verified [draft] holds [number] outside its group's block. */
    private fun outsideBlock(draft: Draft, number: String): Boolean {
        if (!draft.baseEnabled || draft.missing != null || draft.key in unverified) return false
        val start = numbering.groups[groupOf(draft)] ?: return false
        val value = numberValue(number) ?: return false
        return value < start.toBigDecimal() || value >= blockEnd(start).toBigDecimal()
    }

    private fun renumber(draft: Draft, old: String) {
        val start = numbering.groups.getValue(groupOf(draft)!!)
        val number = nextFreeIn(used, start, blockEnd(start)) ?: old
        used += number
        draft.number = number
        if (number != old) renumbered[draft.key] = number
    }

    private fun assign(draft: Draft) {
        val provider = draft.leading?.candidate?.channel?.displayNumber?.takeIf { it.matches(STATION_NUMBER) }
            ?.let(::canonicalNumber)
        // An unverified station holds a floor number that is never written, so its block keeps for a verified station.
        val temporary = draft.baseEnabled && draft.key in unverified
        // Stations the policy keeps off number from the floor, so group blocks hold only what viewers can see.
        val block = if (draft.baseEnabled) numbering.groups[groupOf(draft)] else null
        val number = when {
            temporary -> nextFree(used, numbering.floor)

            block != null -> nextFreeIn(used, block, blockEnd(block)) ?: nextFree(used, numbering.floor)

            numbering.groups.isEmpty() && numbering.keepProviderNumbers && provider != null && provider !in used ->
                provider

            else -> nextFree(used, numbering.floor)
        }
        used += number
        draft.number = number
        if (!temporary) assigned[draft.key] = number
    }

    /** A block ends at its size or where the next group's block starts, whichever is first. */
    private fun blockEnd(start: Int): Int = minOf(
        start + numbering.groupSize,
        starts.firstOrNull { it > start } ?: Int.MAX_VALUE,
    )
}

package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.StationOverride
import java.time.Duration
import kotlin.math.abs
import kotlin.math.min

/** How far ahead schedules are compared. */
val LEARN_WINDOW: Duration = Duration.ofHours(12)

private val START_TOLERANCE: Duration = Duration.ofMinutes(5)

/**
 * What schedule evidence decided in one run. [learned] maps each new `from` key to the station key it joins;
 * [suggestions] are loose pairs no schedule could settle; [findings] are merges the evidence
 * wanted but the policy blocked.
 */
class Learning(
    val learned: Map<String, String> = emptyMap(),
    val suggestions: List<String> = emptyList(),
    val findings: List<String> = emptyList(),
)

/**
 * Pairs of live stations, each sorted, whose channels share a loose name ([looseName]) and that [never] does not
 * keep apart. Sorted, so the evidence is read in one order.
 */
fun loosePairs(result: BuildResult, never: List<List<String>>): List<Pair<String, String>> {
    val live = result.stations.filter { it.offeringIds.isNotEmpty() }.mapTo(HashSet()) { it.key }
    val byLoose = sortedMapOf<String, java.util.TreeSet<String>>()
    for (candidate in result.candidates) {
        val station = candidate.station?.takeIf { candidate.outcome != "skipped" && it in live }
        val loose = station?.let { looseName(candidate.name) }
        if (station != null && loose != null) byLoose.getOrPut(loose) { java.util.TreeSet() } += station
    }
    val pairs = sortedSetOf<Pair<String, String>>(compareBy({ it.first }, { it.second }))
    for (stations in byLoose.values) {
        val keys = stations.toList()
        for (i in keys.indices) {
            for (j in i + 1 until keys.size) {
                if (!neverMerges(never, keys[i], keys[j])) {
                    pairs +=
                        keys[i] to keys[j]
                }
            }
        }
    }
    return pairs.toList()
}

/** Titles compare lowercase, letters and digits only. */
private fun titleKey(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

/** Programs of [a] that [b] airs too: the same title starting within five minutes, each program matched once. */
fun matchingPrograms(a: List<GuideProgram>, b: List<GuideProgram>): Int {
    val pool = b.map {
        titleKey(it.title) to it.startsAt
    }.filter { it.first.isNotEmpty() }.sortedBy { it.second }.toMutableList()
    var matched = 0
    for (program in a.sortedWith(compareBy({ it.startsAt }, { it.title }))) {
        val title = titleKey(program.title)
        if (title.isEmpty()) continue
        val index = pool.indexOfFirst { (other, starts) ->
            other == title && abs(Duration.between(starts, program.startsAt).toSeconds()) <= START_TOLERANCE.toSeconds()
        }
        if (index >= 0) {
            pool.removeAt(index)
            matched++
        }
    }
    return matched
}

/** Two schedules are one channel's when at least three programs and 60% of the smaller schedule match. */
fun sameSchedule(a: List<GuideProgram>, b: List<GuideProgram>): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    val matched = matchingPrograms(a, b)
    return matched >= SAME_SCHEDULE_PROGRAMS && matched >= SAME_SCHEDULE_SHARE * min(a.size, b.size)
}

private const val SAME_SCHEDULE_PROGRAMS = 3
private const val SAME_SCHEDULE_SHARE = 0.6

/** An override that holds nothing but a number, which a learned alias may drop with its station. */
internal fun numberOnly(override: StationOverride?): Boolean =
    override == null || override.copy(number = null) == StationOverride()

/**
 * Decides the loose pairs of [result] from [schedules] (station key to its guide's programs in the window; a key
 * without an entry has no guide). Pairs whose schedules match merge, by component, into the station with the most
 * sources (ties: the first key); pairs with no schedule to compare are suggestions. A station whose override holds
 * more than a number is never merged away, nor is a component that a [LineupPolicy.names] never pair spans.
 */
fun learnAliases(result: BuildResult, policy: LineupPolicy, schedules: Map<String, List<GuideProgram>>): Learning {
    val never = policy.names.never
    val sources = result.stations.associate { it.key to it.offeringIds.size }
    val target = compareBy<String> { sources[it] ?: 0 }.thenByDescending { it }
    val suggestions = sortedSetOf<String>()
    val matched = ArrayList<Pair<String, String>>()
    for ((a, b) in loosePairs(result, never)) {
        val left = schedules[a]
        val right = schedules[b]
        if (left.isNullOrEmpty() || right.isNullOrEmpty()) {
            val (from, to) = if (target.compare(a, b) >= 0) b to a else a to b
            suggestions += "$from: possible alias of $to"
        } else if (sameSchedule(left, right)) {
            matched += a to b
        }
    }
    val learned = sortedMapOf<String, String>()
    val findings = ArrayList<String>()
    for (members in components(matched)) {
        val into = members.maxWith(target)
        val apart = members.flatMap { a ->
            members.filter { b -> a < b && neverMerges(never, a, b) }.map { b -> "$a and $b" }
        }
        if (apart.isNotEmpty()) {
            findings +=
                "$into: schedules match ${members.filter { it != into }.joinToString()}, but names.never keeps " +
                "${apart.joinToString()} apart; not merged"
        } else {
            for (from in members.filter { it != into }) {
                val blocked = mergeBlocked(from, policy)
                if (blocked == null) {
                    learned[from] = into
                } else {
                    findings += "$from: schedule matches $into, but $blocked; not merged"
                    suggestions += "$from: possible alias of $into"
                }
            }
        }
    }
    return Learning(learned, suggestions.toList(), findings.sorted())
}

/** The connected groups of [pairs], each sorted, in the order of their first keys. */
private fun components(pairs: List<Pair<String, String>>): List<List<String>> {
    val parent = HashMap<String, String>()
    fun root(key: String): String = parent[key]?.takeIf { it != key }?.let(::root) ?: key
    for ((a, b) in pairs) {
        val (ra, rb) = root(a) to root(b)
        if (ra != rb) {
            val (low, high) = listOf(ra, rb).sorted()
            parent[high] = low
        }
    }
    return parent.keys.plus(parent.values).distinct().groupBy(::root).values.map { it.sorted() }.sortedBy { it.first() }
}

/** Why the station [from] may not merge into another under [policy]; null when it may. */
private fun mergeBlocked(from: String, policy: LineupPolicy): String? {
    val override = policy.stations[from]
    // A number the household set is never released, so a merge would lose it; one the
    // server wrote goes with the key.
    val handNumbered = override?.number != null && from !in policy.numbering.assigned
    return when {
        !numberOnly(override) -> "its station override holds more than a number"
        handNumbered -> "its number was set by hand"
        else -> null
    }
}

/**
 * [existing] without [drop], plus [added], each target resolved through the rest so no entry chains. Entries that
 * resolve to themselves are left out.
 */
fun mergeLearned(
    existing: Map<String, String>,
    added: Map<String, String>,
    drop: Set<String> = emptySet(),
): Map<String, String> {
    val all = existing.filterKeys { it !in drop } + added
    fun final(key: String): String {
        var value = key
        repeat(all.size + 1) { value = all[value] ?: return value }
        return value
    }
    return all.mapValues { final(it.value) }.filter { it.key != it.value }.toSortedMap()
}

/** [policy] with [added] learned and [drop] removed, and the number-only overrides of the merged-away keys dropped. */
fun withLearned(policy: LineupPolicy, added: Map<String, String>, drop: Set<String> = emptySet()): LineupPolicy =
    policy.copy(
        names = policy.names.copy(learned = mergeLearned(policy.names.learned, added, drop)),
        stations = policy.stations.filterKeys { key -> key !in added || !numberOnly(policy.stations[key]) },
    )

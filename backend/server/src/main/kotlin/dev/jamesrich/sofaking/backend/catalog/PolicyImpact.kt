package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyStation

/** One station's field before and after an edit. */
internal class Moved(val name: String, val before: String?, val after: String?)

/** What an edit does to the stations a run builds, by field. */
internal class Impact(
    val enabledBefore: Int,
    val enabledAfter: Int,
    val group: List<Moved>,
    val number: List<Moved>,
    val name: List<Moved>,
    val turnedOff: List<Moved>,
    val turnedOn: List<Moved>,
    val added: List<String>,
    val removed: List<String>,
) {
    val isEmpty: Boolean get() = group.isEmpty() && number.isEmpty() && name.isEmpty() && turnedOff.isEmpty() &&
        turnedOn.isEmpty() && added.isEmpty() && removed.isEmpty()
}

internal fun impactOf(before: List<PolicyStation>, after: List<PolicyStation>): Impact {
    val old = before.associateBy { it.key }
    val new = after.associateBy { it.key }
    val both = new.keys.filter { it in old }.sorted()
    fun moved(field: (PolicyStation) -> String?) = both.mapNotNull { key ->
        val a = old.getValue(key)
        val b = new.getValue(key)
        if (field(a) != field(b)) Moved(b.name, field(a), field(b)) else null
    }
    return Impact(
        before.count { it.enabled }, after.count { it.enabled },
        moved { it.group }, moved { it.number }, moved { it.name },
        both.filter {
            old.getValue(it).enabled && !new.getValue(it).enabled
        }.map { Moved(new.getValue(it).name, "on", "off (${new.getValue(it).disabledReason ?: "hidden"})") },
        both.filter {
            !old.getValue(it).enabled && new.getValue(it).enabled
        }.map { Moved(new.getValue(it).name, "off", "on") },
        (new.keys - old.keys).sorted().map {
            new.getValue(it).name
        },
        (old.keys - new.keys).sorted().map { old.getValue(it).name },
    )
}

/** What [candidate] would do to the stations a run builds, against the policy as it is now; writes nothing. */
internal suspend fun PolicyService.impact(candidate: LineupPolicy): Impact =
    impactOf(run().stations, run(LoadedPolicy(file.path, candidate, "candidate")).stations)

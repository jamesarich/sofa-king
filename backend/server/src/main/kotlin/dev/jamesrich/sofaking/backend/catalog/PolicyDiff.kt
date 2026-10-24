package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.PolicyChange
import dev.jamesrich.sofaking.backend.api.PolicyFieldChange
import dev.jamesrich.sofaking.backend.api.PolicyPreview
import dev.jamesrich.sofaking.backend.api.PolicyRegrouping
import dev.jamesrich.sofaking.backend.api.PolicyRemoval
import dev.jamesrich.sofaking.backend.api.PolicyStation
import java.time.Instant

/** One change-log entry per field that differs between the lineup before and after a run, matched by entry ID. */
internal fun changesBetween(
    before: List<LineupEntryRecord>,
    after: List<LineupEntryRecord>,
    keyById: Map<String, String>,
    reasonById: Map<String, String?>,
    keys: StationKeys,
    cause: String,
): List<PolicyChange> {
    val old = before.associateBy { it.id }
    val new = after.associateBy { it.id }
    val actions = ArrayList<PolicyChange>()
    for (entry in after) {
        val key = keyById[entry.id] ?: keys.key(entry.name)
        val previous = old[entry.id]
        fun change(field: String, from: String?, to: String?, why: String = cause) {
            if (from != to) actions += PolicyChange(entry.id, entry.name, field, from, to, why, key)
        }
        if (previous == null) {
            change("station", null, entry.number)
            continue
        }
        change("number", previous.number, entry.number)
        change("name", previous.name, entry.name)
        change("group", previous.group, entry.group)
        change("logo", previous.logo, entry.logo)
        change(
            "enabled",
            previous.enabled.toString(),
            entry.enabled.toString(),
            reasonById[entry.id]?.let { "$cause: $it" } ?: cause,
        )
        change("guide", previous.guide.label(), entry.guide.label())
        change("sources", previous.offeringIds.joinToString(","), entry.offeringIds.joinToString(","))
    }
    for (entry in before) {
        if (entry.id !in new) {
            actions += PolicyChange(entry.id, entry.name, "station", entry.number, null, cause, keys.key(entry.name))
        }
    }
    return actions
}

/**
 * Compares a run with the current lineup. An entry maps to the stations its offerings land in, else to the station
 * its name keys to. Field changes are listed for entries that map one to one.
 */
internal fun previewDiff(
    result: BuildResult,
    entries: List<LineupEntryRecord>,
    now: Instant,
    revision: String,
): PolicyPreview {
    val matching = Matching(result, entries)
    val byId = entries.associateBy { it.id }
    val added = result.stations.map { it.key }.filter { it !in matching.stationEntries }.sorted()
    val removed = matching.entryStations.filterValues { it.isEmpty() }.keys
        .map { id -> byId.getValue(id).let { PolicyRemoval(result.keys.key(it.name), it.id, it.name) } }
        .sortedWith(compareBy({ it.key }, { it.entryId }))
    val merges = matching.stationEntries.filterValues { it.size > 1 }.toSortedMap()
        .map { (key, ids) -> PolicyRegrouping(listOf(key), ids.sorted()) }
    val splits = matching.entryStations.filterValues { it.size > 1 }
        .map { (id, keys) -> PolicyRegrouping(keys, listOf(id)) }.sortedBy { it.entryIds.first() }
    val changes = matching.pairs().flatMap { (entryId, station) -> fieldChanges(byId.getValue(entryId), station) }
        .sortedWith(compareBy({ it.key }, { it.field }))
    val counts = sortedMapOf(
        "added" to added.size,
        "removed" to removed.size,
        "merges" to merges.size,
        "splits" to splits.size,
        "findings" to result.findings.size,
    )
    for ((field, items) in changes.groupBy { it.field }) counts["changed:$field"] = items.size
    return PolicyPreview(
        now.toString(), revision, result.stations.size, result.stations.count { it.enabled },
        added, removed, changes, merges, splits, counts, result.findings, result.suggestedAliases,
    )
}

/** Which stations of a run each lineup entry maps to, and which entries each station takes. */
private class Matching(result: BuildResult, entries: List<LineupEntryRecord>) {
    private val stationByKey = result.stations.associateBy { it.key }

    /** Entry ID to the keys of its stations, sorted; empty when it maps to none. */
    val entryStations: Map<String, List<String>>

    /** Station key to the IDs of the entries that map to it. */
    val stationEntries: Map<String, List<String>>

    init {
        val stationByOffering = result.stations.flatMap { station -> station.offeringIds.map { it to station.key } }
            .toMap()
        entryStations = entries.sortedBy { it.id }.associate { entry ->
            val viaSources = entry.offeringIds.mapNotNull { stationByOffering[it] }.distinct().sorted()
            entry.id to viaSources.ifEmpty { listOfNotNull(result.keys.key(entry.name).takeIf { it in stationByKey }) }
        }
        stationEntries = entryStations.flatMap { (entryId, keys) -> keys.map { it to entryId } }
            .groupBy({ it.first }, { it.second })
    }

    /** The entries that map one to one, each with its station. */
    fun pairs(): List<Pair<String, PolicyStation>> = entryStations.mapNotNull { (entryId, keys) ->
        keys.singleOrNull()?.takeIf { stationEntries[it]?.size == 1 }?.let { entryId to stationByKey.getValue(it) }
    }
}

/** The fields that differ between [entry] and the station it maps to. */
private fun fieldChanges(entry: LineupEntryRecord, station: PolicyStation): List<PolicyFieldChange> = listOf(
    Triple("id", entry.id, station.id),
    Triple("number", entry.number, station.number),
    Triple("name", entry.name, station.name),
    Triple("group", entry.group, station.group),
    Triple("logo", entry.logo, station.logo),
    Triple("enabled", entry.enabled.toString(), station.enabled.toString()),
    Triple("guide", entry.guide.label(), station.guide.label()),
    Triple("sources", entry.offeringIds.joinToString(","), station.offeringIds.joinToString(",")),
).filter { (_, before, after) -> before != after }
    .map { (field, before, after) -> PolicyFieldChange(station.key, entry.id, field, before, after) }

private fun GuideMapping?.label(): String? = this?.let { "${it.guideId}/${it.channelId}" }

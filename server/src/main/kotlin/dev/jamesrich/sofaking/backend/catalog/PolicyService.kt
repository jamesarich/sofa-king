package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.store.BackendStore
import java.time.Clock
import java.time.Instant

/**
 * The loaded lineup policy, read-only: runs the [StationBuilder] over the current feeds, guides and health, and
 * compares the result with the lineup the server serves. Nothing here writes the lineup.
 */
class PolicyService(
    private val loaded: LoadedPolicy,
    private val feeds: FeedService,
    private val guides: GuideService,
    private val lineup: LineupService,
    private val store: BackendStore,
    private val clock: Clock,
) {
    val policy: LineupPolicy get() = loaded.policy

    fun document(): PolicyDocument = PolicyDocument(loaded.revision, loaded.path.toString(), loaded.policy)

    /** One run over the current snapshot, with no remembered availability. */
    suspend fun run(): BuildResult {
        val candidates = feeds.channels().map { channel -> FeedCandidate(channel, feeds.providerOf(channel.id).orEmpty(), feeds.offeringsOf(channel.id)) }
        return StationBuilder.build(
            BuildInput(loaded.policy, candidates, guides::guidesListing, feeds.configs.mapTo(HashSet()) { it.id }, store.allHealth(), emptyMap(), clock.instant()),
        )
    }

    suspend fun preview(): PolicyPreview = previewDiff(run(), lineup.entries(), clock.instant(), loaded.revision)

    suspend fun explain(key: String): StationExplanation {
        val result = run()
        return StationExplanation(
            key,
            result.stations.firstOrNull { it.key == key },
            loaded.policy.stations[key]?.sources.orEmpty(),
            result.candidates.filter { it.key == key || it.station == key },
            result.findings.filter { it.startsWith("$key: ") },
        )
    }
}

/**
 * Compares a run with the current lineup. An entry maps to the stations its offerings land in, else to the station
 * its name keys to. Field changes are listed for entries that map one to one.
 */
internal fun previewDiff(result: BuildResult, entries: List<LineupEntryRecord>, now: Instant, revision: String): PolicyPreview {
    val stationByKey = result.stations.associateBy { it.key }
    val stationByOffering = HashMap<String, String>()
    for (station in result.stations) for (id in station.offeringIds) stationByOffering[id] = station.key
    val entryStations = entries.sortedBy { it.id }.associate { entry ->
        val viaSources = entry.offeringIds.mapNotNull { stationByOffering[it] }.distinct().sorted()
        entry.id to viaSources.ifEmpty { listOfNotNull(result.keys.key(entry.name).takeIf { it in stationByKey }) }
    }
    val stationEntries = HashMap<String, MutableList<String>>()
    for ((entryId, keys) in entryStations) for (key in keys) stationEntries.getOrPut(key) { mutableListOf() } += entryId
    val byId = entries.associateBy { it.id }

    val added = result.stations.map { it.key }.filter { it !in stationEntries }.sorted()
    val removed = entryStations.filterValues { it.isEmpty() }.keys.map { id -> byId.getValue(id).let { PolicyRemoval(result.keys.key(it.name), it.id, it.name) } }
        .sortedWith(compareBy({ it.key }, { it.entryId }))
    val merges = stationEntries.filterValues { it.size > 1 }.toSortedMap().map { (key, ids) -> PolicyRegrouping(listOf(key), ids.sorted()) }
    val splits = entryStations.filterValues { it.size > 1 }.map { (id, keys) -> PolicyRegrouping(keys, listOf(id)) }.sortedBy { it.entryIds.first() }

    val changes = ArrayList<PolicyFieldChange>()
    for ((entryId, keys) in entryStations) {
        val key = keys.singleOrNull() ?: continue
        if (stationEntries[key]?.size != 1) continue
        val entry = byId.getValue(entryId)
        val station = stationByKey.getValue(key)
        fun field(name: String, before: String?, after: String?) { if (before != after) changes += PolicyFieldChange(key, entryId, name, before, after) }
        field("id", entry.id, station.id)
        field("number", entry.number, station.number)
        field("name", entry.name, station.name)
        field("group", entry.group, station.group)
        field("logo", entry.logo, station.logo)
        field("enabled", entry.enabled.toString(), station.enabled.toString())
        field("guide", entry.guide?.let { "${it.guideId}/${it.channelId}" }, station.guide?.let { "${it.guideId}/${it.channelId}" })
        field("sources", entry.offeringIds.joinToString(","), station.offeringIds.joinToString(","))
    }
    changes.sortWith(compareBy({ it.key }, { it.field }))
    val counts = sortedMapOf(
        "added" to added.size, "removed" to removed.size, "merges" to merges.size, "splits" to splits.size, "findings" to result.findings.size,
    )
    for ((field, items) in changes.groupBy { it.field }) counts["changed:$field"] = items.size
    return PolicyPreview(
        now.toString(), revision, result.stations.size, result.stations.count { it.enabled },
        added, removed, changes, merges, splits, counts, result.findings,
    )
}

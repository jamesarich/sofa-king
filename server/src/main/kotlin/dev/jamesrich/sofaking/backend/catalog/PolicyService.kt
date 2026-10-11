package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.recordCurationChanges
import dev.jamesrich.sofaking.backend.store.saveStationAvailability
import dev.jamesrich.sofaking.backend.store.stationAvailability
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as Trigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val checkJson = Json { ignoreUnknownKeys = true }

/**
 * Builds the lineup from the policy file: runs the [StationBuilder] over the current feeds, guides, health and the
 * remembered availability, writes the lineup when it differs, and writes pass-two numbers back into the policy.
 */
class PolicyService(
    val file: PolicyFile,
    private val feeds: FeedService,
    private val guides: GuideService,
    private val lineup: LineupService,
    private val store: BackendStore,
    private val clock: Clock,
    /** The HDHomeRun devices that split the lineup by range; null when they do not. */
    private val hdhr: HdhrSizing? = null,
    /** Quiet time after a trigger before the run, so a burst of changes is one run. */
    private val debounceMillis: Long = 10_000,
    private val intervalSeconds: Long = 900,
) {
    private val log = LoggerFactory.getLogger(PolicyService::class.java)
    private val runMutex = Mutex()
    private val trigger = Trigger<String>(Trigger.CONFLATED)
    private val probePlan = java.util.concurrent.atomic.AtomicReference<Map<String, Int>>(emptyMap())

    /**
     * The offerings the last apply's stations reference, each with its probe tier: 0 for the first source of an
     * unverified station, 1 for its other sources, 2 for the rest. Empty until the first apply.
     */
    fun probeTargets(): Map<String, Int> = probePlan.get()

    val policy: LineupPolicy get() = file.current().policy

    fun document(): PolicyDocument = file.current().let { PolicyDocument(it.revision, it.path.toString(), it.policy) }

    /**
     * One run over the current snapshot and the remembered availability; writes nothing. Stations whose names match
     * loosely are compared by schedule first, and the aliases that evidence learns are built with.
     */
    suspend fun run(loaded: LoadedPolicy = file.current()): BuildResult {
        val candidates = feeds.channels().map { channel -> FeedCandidate(channel, feeds.providerOf(channel.id).orEmpty(), feeds.offeringsOf(channel.id)) }
        val providerIds = feeds.configs.mapTo(HashSet()) { it.id }
        val health = store.allHealth()
        val previous = store.stationAvailability()
        val now = clock.instant()
        // A first policy run counts the lineup it replaces as verified, so a cutover never hides channels already watched.
        val seed = if (previous.isEmpty()) lineup.entries().filter { it.enabled && it.createdBy != "policy" }.flatMapTo(HashSet()) { it.offeringIds } else emptySet()
        fun build(policy: LineupPolicy) = StationBuilder.build(BuildInput(policy, candidates, guides::guidesListing, providerIds, health, previous, now, seed))
        val first = build(loaded.policy)
        val learning = learnAliases(first, loaded.policy, schedules(first, loaded.policy, now))
        return if (learning.learned.isEmpty()) first.with(learning) else build(withLearned(loaded.policy, learning.learned)).with(learning)
    }

    /** The programmes in the learning window of every station in a loose pair that has a guide, by station key. */
    private suspend fun schedules(result: BuildResult, policy: LineupPolicy, now: Instant): Map<String, List<GuideProgramme>> {
        val keys = loosePairs(result, policy.names.never).flatMap { listOf(it.first, it.second) }.toSet()
        val mapped = result.stations.filter { it.key in keys && it.guide != null }.associate { it.key to it.guide!! }
        val byGuide = mapped.values.groupBy({ it.guideId }, { it.channelId })
        val programmes = byGuide.mapValues { (guideId, channels) -> guides.programmes(guideId, channels.toSet(), now, now.plus(LEARN_WINDOW)) }
        return mapped.mapValues { (_, guide) -> programmes[guide.guideId]?.get(guide.channelId).orEmpty() }
    }

    suspend fun preview(): PolicyPreview {
        val loaded = file.current()
        val result = run(loaded)
        return previewDiff(result, lineup.entries(), clock.instant(), loaded.revision).copy(
            findings = findingsOf(result), learned = result.learned, devicesNeeded = hdhr?.needed(enabledChannels(result)), devicesConfigured = hdhr?.devices,
            unmappedCategories = result.unmappedCategories,
        )
    }

    suspend fun findings(): PolicyFindings {
        val loaded = file.current()
        val result = run(loaded)
        return PolicyFindings(clock.instant().toString(), loaded.revision, findingsOf(result), result.suggestedAliases, result.unmappedCategories)
    }

    suspend fun explain(key: String): StationExplanation {
        val result = run()
        return StationExplanation(
            key,
            result.stations.firstOrNull { it.key == key },
            file.current().policy.stations[key]?.sources.orEmpty(),
            result.candidates.filter { it.key == key || it.station == key },
            result.findings.filter { it.startsWith("$key: ") },
        )
    }

    /** Enabled channels the devices serve: one per number. */
    private fun enabledChannels(result: BuildResult): Int = result.stations.filter { it.enabled }.distinctBy { it.number }.size

    private fun findingsOf(result: BuildResult): List<String> {
        val sizing = hdhr ?: return result.findings
        val enabled = enabledChannels(result)
        val needed = sizing.needed(enabled)
        if (needed <= sizing.devices) return result.findings
        return (
            result.findings +
                "hdhr: $enabled enabled stations need $needed devices of ${sizing.deviceSize} channels, ${sizing.devices} configured; add ports to SOFA_HDHR_EXTRA_PORTS"
            ).sorted()
    }

    /**
     * Builds and writes the lineup in one transaction when it differs, remembers availability, logs each change with
     * [cause], then writes the numbers pass two assigned into the policy so a rebuild needs nothing else.
     */
    suspend fun apply(cause: String): PolicyApplyResult = runMutex.withLock {
        val started = clock.instant()
        val loaded = file.current()
        // Numbers assigned now are written into the policy for good, so a partial catalog must never be built from.
        val waiting = feeds.providers().filter { it.lastSuccessAt == null && it.errorCode == null }.map { it.id }.sorted()
        if (waiting.isNotEmpty() || feeds.channels().none { feeds.offeringsOf(it.id).isNotEmpty() }) {
            val reason = if (waiting.isNotEmpty()) "waiting for the first import of ${waiting.joinToString()}" else "no feed has a live channel yet"
            log.info("lineup policy run skipped: {}", reason)
            return@withLock PolicyApplyResult(
                started.toString(), loaded.revision, false, lineup.entries().size, lineup.entries().count { it.enabled },
                findings = listOf("feeds: $reason; the lineup is left as it is"),
            )
        }
        val result = run(loaded)
        probePlan.set(probeTiers(result))
        val findings = findingsOf(result).toMutableList()
        val current = lineup.entries()
        val currentById = current.associateBy { it.id }
        val next = ArrayList<LineupEntryRecord>()
        val keyById = HashMap<String, String>()
        val reasonById = HashMap<String, String?>()
        for (station in result.stations) {
            if (station.id in keyById) continue
            keyById[station.id] = station.key
            reasonById[station.id] = station.disabledReason
            val unavailable = station.disabledReason == "unavailable"
            val record = LineupEntryRecord(
                station.id, station.number, station.name, station.group, station.logo, station.enabled, station.guide, station.offeringIds,
                createdBy = "policy",
                autoDisabledAt = if (unavailable) result.availability[station.key]?.unavailableSince else null,
                autoDisabledReason = if (unavailable) "unavailable" else null,
            )
            next += try {
                lineup.validated(record)
            } catch (e: ApiFailure) {
                findings += "${station.key}: left as it was: ${e.message}"
                currentById[station.id] ?: continue
            }
        }
        val changes = changesBetween(current, next, keyById, reasonById, result.keys, cause)
        val changed = next != current
        if (changed) lineup.mutate { entries -> entries.clear(); entries.addAll(next) }
        val now = clock.instant()
        store.saveStationAvailability(result.availability, now)
        store.recordCurationChanges(changes, now)

        val numbers = result.assigned.filterKeys { loaded.policy.stations[it]?.number == null }
        val reclaim = result.reclaimed.filterTo(HashSet()) { it in loaded.policy.numbering.assigned }
        val names = loaded.policy.names
        val blocked = names.learned.filter { (from, to) -> neverMerges(names.never, from, to) }.keys
        var revision = loaded.revision
        if (numbers.isNotEmpty() || reclaim.isNotEmpty() || result.learned.isNotEmpty() || blocked.isNotEmpty()) {
            val what = "${numbers.size} assigned numbers, ${reclaim.size} reclaimed numbers and ${result.learned.size} learned aliases"
            try {
                val learned = mergeLearned(names.learned, result.learned, blocked)
                revision = file.edit(null) { tree -> withNumbers(withLearnedNames(tree, learned, result.learned.keys), numbers, reclaim) }.revision
            } catch (e: ApiFailure) {
                findings += "policy: $what not written back: ${e.message}"
                log.warn("lineup policy write-back failed code={} numbers={} learned={}", e.code, numbers.size, result.learned.size)
            } catch (e: java.io.IOException) {
                findings += "policy: $what not written back: ${e.javaClass.simpleName}"
                log.warn("lineup policy write-back failed class={} numbers={} learned={}", e.javaClass.simpleName, numbers.size, result.learned.size)
            }
        }
        for ((from, to) in result.learned) log.info("lineup policy learned alias from={} to={}", from, to)
        log.info(
            "lineup policy run cause={} stations={} changed={} changes={} assigned={} learned={} findings={} durationMs={}",
            cause, next.size, changed, changes.size, numbers.size, result.learned.size, findings.size, Duration.between(started, clock.instant()).toMillis(),
        )
        PolicyApplyResult(
            now.toString(), revision, changed, next.size, next.count { it.enabled }, changes.size, numbers.toSortedMap(), findings.sorted(), result.suggestedAliases,
            result.learned, hdhr?.needed(enabledChannels(result)), hdhr?.devices, result.unmappedCategories,
        )
    }

    /** Writes [bytes] as the whole policy when [ifMatch] is the loaded revision, then applies it. */
    suspend fun replace(ifMatch: String, bytes: ByteArray): PolicyWriteResult {
        val before = file.current().policy
        val written = file.replace(ifMatch, bytes).policy
        // A number a person changed or removed in the new file is theirs now; a reclaim must never delete it.
        val taken = written.numbering.assigned.filterTo(HashSet()) { key ->
            written.stations[key]?.number.let { it == null || it != before.stations[key]?.number }
        }
        if (taken.isNotEmpty()) file.edit(null) { tree -> withAssignedKeys(tree, add = emptySet(), remove = taken) }
        val applied = apply("policy")
        return PolicyWriteResult(document(), applied)
    }

    /** The station key a lineup entry stands for: the station with its ID, else the key its name gives. */
    private suspend fun keyOf(entryId: String): String {
        run().stations.firstOrNull { it.id == entryId }?.let { return it.key }
        val entry = lineup.entry(entryId) ?: throw ApiFailure(404, "LINEUP_ENTRY_NOT_FOUND", "Unknown lineup entry")
        return stationKeys(policy.names).key(entry.name)
    }

    /** Writes [request] as the override of the entry's station, then applies. */
    suspend fun updateStation(entryId: String, request: UpdateLineupEntry, ifMatch: String?): LineupEntryRecord? {
        if (request.position != null || request.pin.isNotEmpty() || request.unpin.isNotEmpty()) {
            throw ApiFailure(400, "INVALID_REQUEST", "position, pin and unpin do not apply to a lineup built from the policy")
        }
        val key = keyOf(entryId)
        val pins = request.offerings?.let { ids ->
            ids.map { id ->
                val offering = feeds.offering(id) ?: throw ApiFailure(404, "SOURCE_NOT_FOUND", "Unknown source $id")
                val channel = feeds.channel(offering.channelId)
                SourcePin(offering.providerId, channel?.legacyGuideId, channel?.name)
            }.distinctBy(::pinIdentity)
        }
        file.edit(ifMatch) { tree ->
            val edited = editStation(tree, key) { station ->
                request.name?.let { station["name"] = JsonPrimitive(it) }
                request.number?.let { station["number"] = JsonPrimitive(it) }
                if (request.clearGroup) station.remove("group") else request.group?.let { station["group"] = JsonPrimitive(it) }
                if (request.clearLogo) station.remove("logo") else request.logo?.let { station["logo"] = JsonPrimitive(it) }
                if (request.clearGuide) station.remove("guide") else request.guide?.let { station["guide"] = Json.encodeToJsonElement(GuideMapping.serializer(), it) }
                request.enabled?.let { station["enabled"] = JsonPrimitive(it) }
                pins?.let { station["sources"] = JsonArray(it.map(::pinJson)) }
            }
            // A number set by hand is never the server's, so it is never reclaimed.
            if (request.number != null) withAssignedKeys(edited, add = emptySet(), remove = setOf(key)) else edited
        }
        apply("patch")
        return lineup.entry(entryId)
    }

    /** Writes `enabled: false` for the entry's station, then applies. */
    suspend fun disableStation(entryId: String, ifMatch: String?) {
        val key = keyOf(entryId)
        file.edit(ifMatch) { tree -> editStation(tree, key) { it["enabled"] = JsonPrimitive(false) } }
        apply("delete")
    }

    /**
     * The cutover gate over a preview build. A legacy channel passes when exactly one station carries every one of its
     * sources (or of its replacement pins), counting a station's live sources and its override pins.
     */
    suspend fun check(request: PolicyCheckRequest): PolicyCheckReport {
        val loaded = file.current()
        val result = run(loaded)
        val carried = HashMap<String, MutableSet<String>>()
        for (station in result.stations) {
            val identities = carried.getOrPut(station.key) { HashSet() }
            for (id in station.offeringIds) {
                val offering = feeds.offering(id) ?: continue
                val channel = feeds.channel(offering.channelId) ?: continue
                channel.legacyGuideId?.let { identities += pinIdentity(SourcePin(offering.providerId, it)) }
                identities += pinIdentity(SourcePin(offering.providerId, name = channel.name))
            }
            loaded.policy.stations[station.key]?.sources?.forEach { identities += pinIdentity(it) }
        }
        val everywhere = carried.values.flatten().toSet()
        val channels = request.channels.map { channel ->
            val replacement = request.replacements[channel.name]?.let(::replacementOf)
            val wanted = (replacement?.sources?.map(::pinIdentity) ?: channel.sources.map { pinIdentity(SourcePin(it.provider, it.tvgId, it.channelName)) }).distinct()
            val stations = if (wanted.isEmpty()) emptyList() else carried.filterValues { it.containsAll(wanted) }.keys.sorted()
            val missing = wanted.filter { it !in everywhere }.sorted()
            val reason = when {
                wanted.isEmpty() -> "the channel lists no source"
                stations.size == 1 -> "carried by ${stations.single()}" + if (replacement != null) " (replacement pins)" else ""
                stations.isEmpty() -> "no station carries every source"
                else -> "${stations.size} stations carry every source"
            }
            PolicyCheckChannel(channel.name, channel.number, stations.size == 1, stations, missing, reason)
        }
        val duplicateNumbers = result.stations.groupBy { canonicalNumber(it.number) }.filterValues { it.size > 1 }.keys.sorted()
        val duplicateIds = result.stations.groupBy { it.id }.filterValues { it.size > 1 }.keys.sorted()
        return PolicyCheckReport(channels.all { it.pass } && duplicateNumbers.isEmpty() && duplicateIds.isEmpty(), loaded.revision, channels, duplicateNumbers, duplicateIds)
    }

    /** Asks the loop to run soon; requests before it runs coalesce into one. */
    fun requestRun(cause: String) {
        trigger.trySend(cause)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            val cause = select<String> {
                trigger.onReceive { it }
                onTimeout(intervalSeconds * 1000) { "interval" }
            }
            delay(debounceMillis)
            val coalesced = trigger.tryReceive().getOrNull()
            try { apply(coalesced ?: cause) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.warn("lineup policy run failed class={} message=\"{}\"", e.javaClass.simpleName, redactedMessage(e))
            }
        }
    }
}

/** HDHomeRun devices that split a policy lineup by range: [devices] of [deviceSize] channels each. */
data class HdhrSizing(val devices: Int, val deviceSize: Int) {
    /** Devices [enabled] channels need. */
    fun needed(enabled: Int): Int = (enabled + deviceSize - 1) / deviceSize
}

private fun replacementOf(element: JsonElement): PolicyReplacement = when (element) {
    is JsonArray -> PolicyReplacement(sources = checkJson.decodeFromJsonElement(ListSerializer(SourcePin.serializer()), element))
    else -> checkJson.decodeFromJsonElement(PolicyReplacement.serializer(), element)
}

private fun pinJson(pin: SourcePin): JsonObject = buildJsonObject {
    put("provider", pin.provider)
    pin.tvgId?.let { put("tvgId", it) }
    pin.name?.let { put("name", it) }
}

private fun editStation(tree: JsonObject, key: String, edit: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
    val stations = (tree["stations"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    val station = (stations[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    edit(station)
    stations[key] = JsonObject(station)
    return JsonObject(tree.toMutableMap().apply { put("stations", JsonObject(stations.toSortedMap())) })
}

/**
 * Each offering [result]'s stations reference, with its probe tier: 0 for the first source of a station no source
 * has verified, 1 for that station's other sources, 2 for every other offering. Stations the policy turns off
 * never rank above 2.
 */
internal fun probeTiers(result: BuildResult): Map<String, Int> {
    val tiers = HashMap<String, Int>()
    for (station in result.stations) {
        val unverified = result.availability[station.key]?.verifiedAt == null && station.disabledReason != "policy"
        station.offeringIds.forEachIndexed { index, id ->
            tiers.merge(id, if (!unverified) 2 else if (index == 0) 0 else 1, ::minOf)
        }
    }
    return tiers
}

/**
 * [tree] with `names.learned` replaced by [learned], and the station override of each of [mergedAway] dropped when
 * it holds nothing but a number, since that key is no longer a station.
 */
internal fun withLearnedNames(tree: JsonObject, learned: Map<String, String>, mergedAway: Set<String>): JsonObject {
    val names = (tree["names"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    if (learned.isEmpty()) names.remove("learned") else names["learned"] = JsonObject(learned.toSortedMap().mapValues { JsonPrimitive(it.value) })
    val next = tree.toMutableMap().apply { put("names", JsonObject(names)) }
    val stations = (tree["stations"] as? JsonObject)?.toMutableMap()
    val dropped = HashSet<String>()
    if (stations != null && mergedAway.isNotEmpty()) {
        for (key in mergedAway) {
            val station = stations[key] ?: continue
            val override = runCatching { checkJson.decodeFromJsonElement(StationOverride.serializer(), station) }.getOrNull()
            if (override != null && numberOnly(override)) { stations.remove(key); dropped += key }
        }
        next["stations"] = JsonObject(stations.toSortedMap())
    }
    return withAssignedKeys(JsonObject(next), add = emptySet(), remove = dropped)
}

/**
 * [tree] with each of [numbers] written as `stations.<key>.number` where none is set, and each [reclaimed] key's
 * number removed (its station entry too, once empty). Both change `numbering.assigned` to match.
 */
internal fun withNumbers(tree: JsonObject, numbers: Map<String, String>, reclaimed: Set<String>): JsonObject {
    var next = tree
    for (key in reclaimed.toSortedSet()) next = withoutNumber(next, key)
    for ((key, number) in numbers.toSortedMap()) next = editStation(next, key) { station -> if ("number" !in station) station["number"] = JsonPrimitive(number) }
    return withAssignedKeys(next, add = numbers.keys, remove = reclaimed)
}

/** [tree] without `stations.<key>.number`; the station entry goes too when nothing else is left in it. */
private fun withoutNumber(tree: JsonObject, key: String): JsonObject {
    val stations = (tree["stations"] as? JsonObject)?.toMutableMap() ?: return tree
    val station = (stations[key] as? JsonObject)?.toMutableMap() ?: return tree
    if (station.remove("number") == null) return tree
    if (station.isEmpty()) stations.remove(key) else stations[key] = JsonObject(station)
    return JsonObject(tree.toMutableMap().apply { put("stations", JsonObject(stations.toSortedMap())) })
}

/** [tree] with [add] and without [remove] in `numbering.assigned`, sorted; the list goes when it empties. */
internal fun withAssignedKeys(tree: JsonObject, add: Set<String>, remove: Set<String>): JsonObject {
    val numbering = (tree["numbering"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    val keys = (numbering["assigned"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toMutableSet()
    if (keys.containsAll(add) && keys.none { it in remove }) return tree
    keys += add
    keys -= remove
    if (keys.isEmpty()) numbering.remove("assigned") else numbering["assigned"] = JsonArray(keys.sorted().map { JsonPrimitive(it) })
    val next = tree.toMutableMap()
    if (numbering.isEmpty()) next.remove("numbering") else next["numbering"] = JsonObject(numbering)
    return JsonObject(next)
}

/** One change-log action per field that differs between the lineup before and after a run, matched by entry ID. */
internal fun changesBetween(
    before: List<LineupEntryRecord>, after: List<LineupEntryRecord>, keyById: Map<String, String>, reasonById: Map<String, String?>,
    keys: StationKeys, cause: String,
): List<CurationAction> {
    val old = before.associateBy { it.id }
    val new = after.associateBy { it.id }
    val actions = ArrayList<CurationAction>()
    for (entry in after) {
        val key = keyById[entry.id] ?: keys.key(entry.name)
        val previous = old[entry.id]
        fun change(field: String, from: String?, to: String?, why: String = cause) {
            if (from != to) actions += CurationAction("policy", entry.id, entry.name, field, from, to, why, key = key)
        }
        if (previous == null) { change("station", null, entry.number); continue }
        change("number", previous.number, entry.number)
        change("name", previous.name, entry.name)
        change("group", previous.group, entry.group)
        change("logo", previous.logo, entry.logo)
        change("enabled", previous.enabled.toString(), entry.enabled.toString(), reasonById[entry.id]?.let { "$cause: $it" } ?: cause)
        change("guide", previous.guide?.let { "${it.guideId}/${it.channelId}" }, entry.guide?.let { "${it.guideId}/${it.channelId}" })
        change("sources", previous.offeringIds.joinToString(","), entry.offeringIds.joinToString(","))
    }
    for (entry in before) if (entry.id !in new) actions += CurationAction("policy", entry.id, entry.name, "station", entry.number, null, cause, key = keys.key(entry.name))
    return actions
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
        added, removed, changes, merges, splits, counts, result.findings, result.suggestedAliases,
    )
}

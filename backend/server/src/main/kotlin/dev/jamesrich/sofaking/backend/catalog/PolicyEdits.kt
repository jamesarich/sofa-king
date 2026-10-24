package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.api.PolicyWriteResult
import dev.jamesrich.sofaking.backend.api.SourcePin
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The changes to the policy file a person asks for, each written and then applied by [service]. */
class PolicyEdits internal constructor(
    private val service: PolicyService,
    private val feeds: FeedService,
    private val lineup: LineupService,
) {
    private val file get() = service.file

    /** Writes [bytes] as the whole policy when [ifMatch] is the loaded revision, then applies it. */
    suspend fun replace(ifMatch: String, bytes: ByteArray): PolicyWriteResult {
        val before = file.current().policy
        val written = file.replace(ifMatch, bytes).policy
        // A number a person changed or removed in the new file is theirs now; a reclaim must never delete it.
        val taken = written.numbering.assigned.filterTo(HashSet()) { key ->
            written.stations[key]?.number.let { it == null || it != before.stations[key]?.number }
        }
        if (taken.isNotEmpty()) file.edit(null) { tree -> withAssignedKeys(tree, add = emptySet(), remove = taken) }
        val applied = service.apply("policy")
        return PolicyWriteResult(service.document(), applied)
    }

    /** Writes [request] as the override of the entry's station, then applies. */
    suspend fun updateStation(entryId: String, request: UpdateLineupEntry, ifMatch: String?): LineupEntryRecord? {
        if (request.position != null) {
            throw ApiFailure(
                HttpStatusCode.BadRequest,
                "INVALID_REQUEST",
                "position does not apply to a lineup built from the policy",
            )
        }
        val key = keyOf(entryId)
        val pins = request.offerings?.let(::pinsOf)
        file.edit(ifMatch) { tree ->
            val edited = editStation(tree, key) { station -> station.update(request, pins) }
            // A number set by hand is never the server's, so it is never reclaimed.
            if (request.number != null) withAssignedKeys(edited, add = emptySet(), remove = setOf(key)) else edited
        }
        service.apply("patch")
        return lineup.entry(entryId)
    }

    /**
     * Keeps [a] and [b] apart for good: adds the pair to `names.never` and drops a learned alias between them, then
     * applies. A pair already listed is not added twice.
     */
    suspend fun neverMerge(a: String, b: String, ifMatch: String?): PolicyApplyResult {
        if (a.isBlank() || b.isBlank() || a == b) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "names.never takes two different keys")
        }
        file.edit(ifMatch) { tree -> editNames(tree) { names -> names.keepApart(setOf(a, b)) } }
        return service.apply("never-merge")
    }

    /**
     * Adds `names.aliases[from] = to`, then applies. An override keyed by [from] cannot stay under an alias: one
     * that holds only a number goes, as with a learned alias; any other moves to [to], keeping [to]'s number.
     */
    suspend fun addAlias(from: String, to: String, ifMatch: String?): PolicyApplyResult {
        if (from.isBlank() || to.isBlank()) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "An alias takes two keys")
        }
        file.edit(ifMatch) { tree ->
            editNames(withOverrideMoved(tree, from, to)) { names ->
                val aliases = (names["aliases"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                aliases[from] = JsonPrimitive(to)
                names["aliases"] = JsonObject(aliases.toSortedMap())
            }
        }
        return service.apply("alias")
    }

    /** Writes `enabled: false` for the entry's station, then applies. */
    suspend fun disableStation(entryId: String, ifMatch: String?) {
        val key = keyOf(entryId)
        file.edit(ifMatch) { tree -> editStation(tree, key) { it["enabled"] = JsonPrimitive(false) } }
        service.apply("delete")
    }

    /** The station key a lineup entry stands for: the station with its ID, else the key its name gives. */
    private suspend fun keyOf(entryId: String): String {
        service.run().stations.firstOrNull { it.id == entryId }?.let { return it.key }
        val entry =
            lineup.entry(entryId)
                ?: throw ApiFailure(HttpStatusCode.NotFound, "LINEUP_ENTRY_NOT_FOUND", "Unknown lineup entry")
        return stationKeys(service.policy.names).key(entry.name)
    }

    /** A pin for each source of [ids], one per identity. */
    private fun pinsOf(ids: List<String>): List<SourcePin> = ids.map { id ->
        val offering =
            feeds.published.offering(id)
                ?: throw ApiFailure(HttpStatusCode.NotFound, "SOURCE_NOT_FOUND", "Unknown source $id")
        val channel = feeds.published.channel(offering.channelId)
        SourcePin(offering.providerId, channel?.legacyGuideId, channel?.name)
    }.distinctBy(::pinIdentity)
}

/** This station override with the fields [request] sets or clears, and [pins] as its sources when given. */
private fun MutableMap<String, JsonElement>.update(request: UpdateLineupEntry, pins: List<SourcePin>?) {
    request.name?.let { this["name"] = JsonPrimitive(it) }
    request.number?.let { this["number"] = JsonPrimitive(it) }
    setOrClear("group", request.group?.let(::JsonPrimitive), request.clearGroup)
    setOrClear("logo", request.logo?.let(::JsonPrimitive), request.clearLogo)
    setOrClear(
        "guide",
        request.guide?.let {
            Json.encodeToJsonElement(GuideMapping.serializer(), it)
        },
        request.clearGuide,
    )
    request.enabled?.let { this["enabled"] = JsonPrimitive(it) }
    pins?.let { this["sources"] = JsonArray(it.map(::pinJson)) }
}

/** Removes [field] when [clear] says so, else sets it to [value] when there is one. */
private fun MutableMap<String, JsonElement>.setOrClear(field: String, value: JsonElement?, clear: Boolean) {
    if (clear) {
        remove(field)
    } else if (value != null) {
        this[field] = value
    }
}

/** These names with [pair] in `never`, listed once, and without a learned alias between them. */
private fun MutableMap<String, JsonElement>.keepApart(pair: Set<String>) {
    val never = (this["never"] as? JsonArray).orEmpty().toMutableList()
    val listed = never.any { listed -> (listed as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() == pair }
    if (!listed) never.add(JsonArray(pair.map(::JsonPrimitive)))
    this["never"] = JsonArray(never)
    val learned = (this["learned"] as? JsonObject)?.filterNot { (from, to) ->
        setOf(from, to.jsonPrimitive.content) == pair
    }
    if (learned.isNullOrEmpty()) remove("learned") else this["learned"] = JsonObject(learned)
}

/**
 * [tree] without the station override of [from]: one that holds only a number goes; any other moves to [to],
 * keeping [to]'s number, unless [to] has settings of its own.
 */
private fun withOverrideMoved(tree: JsonObject, from: String, to: String): JsonObject {
    val stations = (tree["stations"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    val merged = stations.remove(from) as? JsonObject
    if (merged != null && !numberOnlyOverride(merged)) {
        val kept = stations[to] as? JsonObject
        if (kept != null && !numberOnlyOverride(kept)) {
            throw ApiFailure(
                HttpStatusCode.Conflict,
                "STATION_CONFLICT",
                "\"$from\" and \"$to\" both have station settings; combine them under \"$to\" first",
            )
        }
        val number = kept?.get("number")?.takeUnless { it is JsonNull }
        stations[to] = if (number == null) merged else JsonObject(merged + ("number" to number))
    }
    return JsonObject(tree.toMutableMap().apply { put("stations", JsonObject(stations.toSortedMap())) })
}

private fun pinJson(pin: SourcePin): JsonObject = buildJsonObject {
    put("provider", pin.provider)
    pin.tvgId?.let { put("tvgId", it) }
    pin.name?.let { put("name", it) }
}

/** A station override in the file whose only setting is its number; nulls and the defaults count as unset. */
private fun numberOnlyOverride(station: JsonObject): Boolean = station.all { (field, value) ->
    field == "number" || value is JsonNull || (value is JsonArray && value.isEmpty()) ||
        (field == "required" && value == JsonPrimitive(false))
}

package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.StationOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private val overrideJson = Json { ignoreUnknownKeys = true }

/** [tree] with its `names` as [edit] leaves them. */
internal fun editNames(tree: JsonObject, edit: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
    val names = (tree["names"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    edit(names)
    return JsonObject(tree.toMutableMap().apply { put("names", JsonObject(names)) })
}

/** [tree] with the override of station [key] as [edit] leaves it, created when there is none. */
internal fun editStation(tree: JsonObject, key: String, edit: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
    val stations = (tree["stations"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    val station = (stations[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    edit(station)
    stations[key] = JsonObject(station)
    return JsonObject(tree.toMutableMap().apply { put("stations", JsonObject(stations.toSortedMap())) })
}

/**
 * [tree] with `names.learned` replaced by [learned], and the station override of each of [mergedAway] dropped when
 * it holds nothing but a number, since that key is no longer a station.
 */
internal fun withLearnedNames(tree: JsonObject, learned: Map<String, String>, mergedAway: Set<String>): JsonObject {
    val names = (tree["names"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    if (learned.isEmpty()) {
        names.remove("learned")
    } else {
        names["learned"] =
            JsonObject(learned.toSortedMap().mapValues { JsonPrimitive(it.value) })
    }
    val next = tree.toMutableMap().apply { put("names", JsonObject(names)) }
    val stations = (tree["stations"] as? JsonObject)?.toMutableMap()
    val dropped = HashSet<String>()
    if (stations != null && mergedAway.isNotEmpty()) {
        for (key in mergedAway) {
            val station = stations[key] ?: continue
            val override = runCatching {
                overrideJson.decodeFromJsonElement(StationOverride.serializer(), station)
            }.getOrNull()
            if (override != null && numberOnly(override)) {
                stations.remove(key)
                dropped += key
            }
        }
        next["stations"] = JsonObject(stations.toSortedMap())
    }
    return withAssignedKeys(JsonObject(next), add = emptySet(), remove = dropped)
}

/**
 * [tree] with each of [numbers] written as `stations.<key>.number` where none is set, and each [reclaimed] key's
 * number removed (its station entry too, once empty). Both change `numbering.assigned` to match. Each [moved] key's
 * number is replaced, old to new, only while the file still holds the old one.
 */
internal fun withNumbers(
    tree: JsonObject,
    numbers: Map<String, String>,
    reclaimed: Set<String>,
    moved: Map<String, Pair<String, String>> = emptyMap(),
): JsonObject {
    var next = tree
    for (key in reclaimed.toSortedSet()) next = withoutNumber(next, key)
    for ((key, number) in numbers.toSortedMap()) {
        next =
            editStation(next, key) { station -> if ("number" !in station) station["number"] = JsonPrimitive(number) }
    }
    for ((key, change) in moved.toSortedMap()) {
        next = editStation(next, key) { station ->
            if ((station["number"] as? JsonPrimitive)?.content == change.first) {
                station["number"] = JsonPrimitive(change.second)
            }
        }
    }
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
    if (keys.isEmpty()) {
        numbering.remove("assigned")
    } else {
        numbering["assigned"] =
            JsonArray(keys.sorted().map { JsonPrimitive(it) })
    }
    val next = tree.toMutableMap()
    if (numbering.isEmpty()) next.remove("numbering") else next["numbering"] = JsonObject(numbering)
    return JsonObject(next)
}

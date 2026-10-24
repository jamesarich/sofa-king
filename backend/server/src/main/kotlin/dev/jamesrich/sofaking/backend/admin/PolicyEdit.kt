package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.CandidateFilter
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.html.A
import kotlinx.html.a
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.s
import kotlinx.html.section
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One edit to the policy file's JSON, named for the notice and the confirm step, and the page [section] it belongs to.
 */
internal class PolicyEdit(val describe: String, val section: String, val change: (JsonObject) -> JsonObject)

/** Refuses an edit the form got wrong, with a 400 that says why. */
internal fun invalidEdit(message: String): Nothing =
    throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", message)

internal fun Parameters.text(name: String, what: String = name): String =
    this[name]?.trim()?.takeIf { it.isNotEmpty() } ?: invalidEdit("$what is required")

internal fun Parameters.index(): Int = this["index"]?.toIntOrNull() ?: invalidEdit("index is required")

/** [tree] with the object at [key] replaced by [edit] of it, created when missing. */
internal fun JsonObject.withObject(key: String, edit: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
    val inner = (this[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    edit(inner)
    return JsonObject(toMutableMap().apply { put(key, JsonObject(inner)) })
}

internal fun MutableMap<String, JsonElement>.objectAt(key: String, edit: (MutableMap<String, JsonElement>) -> Unit) {
    val inner = (this[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    edit(inner)
    if (inner.isEmpty()) remove(key) else put(key, JsonObject(inner))
}

internal fun MutableMap<String, JsonElement>.arrayAt(key: String, edit: (MutableList<JsonElement>) -> Unit) {
    val inner = (this[key] as? JsonArray)?.toMutableList() ?: mutableListOf()
    edit(inner)
    if (inner.isEmpty()) remove(key) else put(key, JsonArray(inner))
}

internal fun category(raw: String): String = raw.trim().lowercase()

/**
 * The provider-entry edits the providers page offers: order, the default for stations an entry leads, add and remove.
 */
internal fun providersEdit(form: Parameters, policy: LineupPolicy): PolicyEdit {
    val entries = ProviderEntryEdits(policy.providers)
    return when (val op = form["op"]) {
        "move" -> entries.move(form)
        "enabled" -> entries.enabled(form)
        "add" -> entries.add(form)
        "remove" -> entries.remove(form)
        else -> invalidEdit("Unknown edit ${op ?: ""}".trim())
    }
}

/** The edits to the policy's provider [entries], each checked against the entries the page showed. */
private class ProviderEntryEdits(private val entries: List<PolicyProvider>) {
    fun move(form: Parameters): PolicyEdit {
        val index = form.index()
        val entry = entryAt(index)
        val to = if (form["to"] == "up") index - 1 else index + 1
        if (to !in entries.indices) invalidEdit("That entry is already ${if (to < 0) "first" else "last"}")
        return edit("move ${entry.id} ${if (to < index) "up" else "down"}") { list ->
            list.add(to, list.removeAt(index))
        }
    }

    fun enabled(form: Parameters): PolicyEdit {
        val index = form.index()
        val entry = entryAt(index)
        val enabled = form["enabled"]?.toBooleanStrictOrNull() ?: invalidEdit("enabled must be true or false")
        return edit("${if (enabled) "show" else "hide"} the stations ${entry.id} leads") { list ->
            val item = (list[index] as? JsonObject)?.toMutableMap() ?: invalidEdit("That entry is not an object")
            if (enabled) item.remove("enabled") else item["enabled"] = JsonPrimitive(false)
            list[index] = JsonObject(item)
        }
    }

    fun add(form: Parameters): PolicyEdit {
        val id = form.text("provider", "A provider")
        return edit("take every channel of $id last") { list -> list.add(buildJsonObject { put("id", id) }) }
    }

    fun remove(form: Parameters): PolicyEdit {
        val index = form.index()
        val entry = entryAt(index)
        return edit("remove the ${filterSummary(entry.include, entry.exclude)} entry for ${entry.id}") { list ->
            list.removeAt(index)
        }
    }

    private fun entryAt(index: Int) =
        entries.getOrNull(index) ?: invalidEdit("That provider entry is gone; reload the page")

    private fun edit(describe: String, change: (MutableList<JsonElement>) -> Unit) =
        PolicyEdit(describe, "entries") { tree ->
            val list = (tree["providers"] as? JsonArray)?.toMutableList() ?: mutableListOf()
            change(list)
            JsonObject(tree.toMutableMap().apply { put("providers", JsonArray(list)) })
        }
}

internal fun filterSummary(include: CandidateFilter?, exclude: CandidateFilter?): String {
    fun describe(filter: CandidateFilter) = listOfNotNull(
        filter.groups.takeIf { it.isNotEmpty() }?.let { "groups ${it.joinToString()}" },
        filter.groupPatterns.takeIf { it.isNotEmpty() }?.let { "groups like ${it.joinToString()}" },
        filter.names.takeIf { it.isNotEmpty() }?.let { "names ${it.joinToString()}" },
        filter.namePatterns.takeIf { it.isNotEmpty() }?.let { "names like ${it.joinToString()}" },
    ).joinToString("; ")
    val taken = include?.let(::describe)?.takeIf { it.isNotEmpty() } ?: "every channel"
    val left = exclude?.let(::describe)?.takeIf { it.isNotEmpty() }
    return if (left == null) taken else "$taken, except $left"
}

/**
 * The bulk edit the Channels page offers over the selected stations, by key: hide, show or set the group of each,
 * written into their overrides in one policy edit. [op] and [group] come from the form.
 */
internal fun bulkEdit(op: String?, group: String?, keys: List<String>): PolicyEdit {
    if (keys.isEmpty()) invalidEdit("Select at least one channel")
    val count = "${keys.size} ${if (keys.size == 1) "channel" else "channels"}"
    fun each(describe: String, change: (MutableMap<String, JsonElement>) -> Unit) =
        PolicyEdit(describe, "bulk") { tree ->
            val stations = (tree["stations"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            for (key in keys) {
                val station = (stations[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                change(station)
                if (station.isEmpty()) stations.remove(key) else stations[key] = JsonObject(station)
            }
            JsonObject(tree.toMutableMap().apply { put("stations", JsonObject(stations.toSortedMap())) })
        }
    return when (op) {
        "hide" -> each("hide $count") { it["enabled"] = JsonPrimitive(false) }

        "show" -> each("show $count") { it["enabled"] = JsonPrimitive(true) }

        "group" -> {
            val target = group?.trim().orEmpty()
            if (target.isEmpty()) {
                each("let the policy group $count") { it.remove("group") }
            } else {
                each("move $count to $target") { it["group"] = JsonPrimitive(target) }
            }
        }

        else -> invalidEdit("Unknown edit ${op ?: ""}".trim())
    }
}

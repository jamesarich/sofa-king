package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.providers.array
import dev.jamesrich.sofaking.backend.providers.get
import dev.jamesrich.sofaking.backend.providers.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The element at a dotted [path] such as `display.epg.title` or `images.logo.0.url`: an object key, or an index when
 * the segment is a number and the element an array. A missing step reads as null; an empty path is the element.
 */
internal fun JsonElement?.at(path: String): JsonElement? {
    if (path.isEmpty()) return this
    var node = this
    for (segment in path.split('.')) {
        val index = segment.toIntOrNull()
        node = if (index != null && node is JsonArray) node.getOrNull(index) else node[segment]
        if (node == null) return null
    }
    return node
}

/** The key under which an item read through a `*` path carries the object that holds its array. */
internal const val PARENT = "^"

/**
 * The items at [path]; a `*` segment steps into each element of an array, and an object item read that way gets
 * the object that held its array as [PARENT], so `categories.*.channels.*` lists the channels with `^.name` the
 * name of the category each is in. Without a `*` the items are the array at [path].
 */
internal fun JsonElement?.itemsAt(path: String): List<JsonElement> {
    if ('*' !in path) return at(path).array
    var nodes: List<Pair<JsonElement, JsonObject?>> = listOfNotNull(this?.let { it to null })
    for (segment in path.split('.')) {
        nodes = nodes.flatMap { (node, holder) ->
            if (segment == "*") {
                (node as? JsonArray).orEmpty().map { it to holder }
            } else {
                listOfNotNull(node.at(segment)?.let { it to ((node as? JsonObject) ?: holder) })
            }
        }
    }
    return nodes.map { (node, holder) ->
        if (node is JsonObject && holder != null) JsonObject(node + (PARENT to holder)) else node
    }
}

/** The trimmed text of the primitive at [path], null when it is missing, blank or not a primitive. */
internal fun JsonElement?.textAt(path: String): String? = at(path).string

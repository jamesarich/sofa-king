package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.providers.get
import dev.jamesrich.sofaking.backend.providers.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

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

/** The trimmed text of the primitive at [path], null when it is missing, blank or not a primitive. */
internal fun JsonElement?.textAt(path: String): String? = at(path).string

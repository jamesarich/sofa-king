package dev.jamesrich.sofaking.backend.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The input schemas of the tools: one property per argument, each built by the helper for its type.

/** A tool's input schema from its [properties], each built by [string], [integer] or [choice]. */
internal fun schema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()) = ToolSchema(
    properties = JsonObject(properties.toMap()),
    required = required.ifEmpty { null },
)

internal fun string(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun boolean(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun number(description: String, range: ClosedFloatingPointRange<Double>) = buildJsonObject {
    put("type", "number")
    put("description", description)
    put("minimum", range.start)
    put("maximum", range.endInclusive)
}

internal fun integer(description: String, range: IntRange, default: Int) = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", range.first)
    put("maximum", range.last)
    put("default", default)
}

internal fun choice(description: String, values: List<String>) = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

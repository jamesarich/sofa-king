package dev.jamesrich.sofaking.backend.http

import java.io.File
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Fails when [body] drops or retypes a field of the frozen fixture [name] in the contract's wire fixtures, which
 * released apps decode. A field that is null in the fixture only has to stay present.
 */
internal fun assertKeepsWireFixture(name: String, body: String) {
    val dir = System.getProperty("sofaking.wireFixtures") ?: error("sofaking.wireFixtures is not set")
    val fixture = Json.parseToJsonElement(File(dir, name).readText())
    val missing = shape(fixture) - shape(Json.parseToJsonElement(body))
    assertTrue(missing.isEmpty(), "$name: the response no longer carries $missing")
}

private fun shape(element: JsonElement, path: String = "$"): Set<String> = setOf(path) + when (element) {
    is JsonObject -> element.flatMap { (key, value) -> shape(value, "$path.$key") }.toSet() + "$path:object"
    is JsonArray -> element.flatMap { shape(it, "$path[]") }.toSet() + "$path:array"
    JsonNull -> emptySet()
    is JsonPrimitive -> setOf("$path:" + if (element.isString) "string" else if (element.booleanOrNull != null) "boolean" else "number")
}

package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.contract.AdBreakState
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Bootstrap
import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.contract.CrashReport
import dev.jamesrich.sofaking.contract.GuideWindow
import dev.jamesrich.sofaking.contract.HouseholdPreferences
import dev.jamesrich.sofaking.contract.Library
import dev.jamesrich.sofaking.contract.LibraryHome
import dev.jamesrich.sofaking.contract.LibraryItem
import dev.jamesrich.sofaking.contract.LibraryItemDetail
import dev.jamesrich.sofaking.contract.LibraryPage
import dev.jamesrich.sofaking.contract.MultiviewSession
import dev.jamesrich.sofaking.contract.OpenVodPlayback
import dev.jamesrich.sofaking.contract.PairingResult
import dev.jamesrich.sofaking.contract.PlayState
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.ProgramFacts
import dev.jamesrich.sofaking.contract.RecordingList
import dev.jamesrich.sofaking.contract.RecordingRuleList
import dev.jamesrich.sofaking.contract.UpcomingPrograms
import dev.jamesrich.sofaking.contract.UpdatePlayState
import dev.jamesrich.sofaking.contract.ViewerPreferences
import dev.jamesrich.sofaking.contract.VodSession
import dev.jamesrich.sofaking.contract.Watchlist
import dev.jamesrich.sofaking.contract.WatchlistEntry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares the OpenAPI document's schemas with the contract types the wire fixtures pin, by shape: each property's
 * type, whether it may be null, whether it must be present, and the items of a list. Descriptions, examples, formats,
 * defaults and enum lists are not compared; an enum is a string.
 */
@OptIn(ExperimentalSerializationApi::class)
class ContractSchemaTest {
    private val schemas: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/api/openapi.json")).readText(),
    ).jsonObject.getValue("components").jsonObject.getValue("schemas").jsonObject

    /** The contract types `WireFixturesTest` and `CrashReportFixtureTest` decode, and the request types it encodes. */
    private val fixtureTypes: List<KSerializer<*>> = listOf(
        AdBreakState.serializer(), ApiError.serializer(), Bootstrap.serializer(), Catalog.serializer(),
        CrashReport.serializer(), GuideWindow.serializer(), HouseholdPreferences.serializer(),
        Library.serializer(), LibraryHome.serializer(), LibraryItem.serializer(), LibraryItemDetail.serializer(),
        LibraryPage.serializer(), MultiviewSession.serializer(), OpenVodPlayback.serializer(),
        PairingResult.serializer(), PlayState.serializer(), PlaybackSession.serializer(), ProgramFacts.serializer(),
        RecordingList.serializer(), RecordingRuleList.serializer(), UpcomingPrograms.serializer(),
        UpdatePlayState.serializer(), ViewerPreferences.serializer(), VodSession.serializer(),
        Watchlist.serializer(), WatchlistEntry.serializer(),
    )

    /** Differences the document keeps on purpose, each `Schema.path` and why. A stale entry fails the test. */
    private val allowed: Map<String, String> = emptyMap()

    private fun name(serializer: KSerializer<*>) = serializer.descriptor.serialName.substringAfterLast('.')

    private fun typeShape(
        descriptor: SerialDescriptor,
        path: String,
        out: MutableMap<String, String>,
        stack: List<String>,
    ) {
        var inner = descriptor
        while (inner.isInline && inner.elementsCount == 1) inner = inner.getElementDescriptor(0)
        val nullable = if (descriptor.isNullable || inner.isNullable) "?" else ""
        val scalar = scalarName(inner.kind)
        when {
            scalar != null -> out[path] = scalar + nullable

            inner.kind == StructureKind.LIST -> {
                out[path] = "array$nullable"
                typeShape(inner.getElementDescriptor(0), "$path[]", out, stack)
            }

            inner.kind == StructureKind.MAP -> {
                out[path] = "object$nullable"
                typeShape(inner.getElementDescriptor(1), "$path{}", out, stack)
            }

            inner.kind == StructureKind.CLASS -> classShape(inner, path, nullable, out, stack)

            else -> out[path] = "any$nullable"
        }
    }

    private fun scalarName(kind: SerialKind): String? = when (kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR, SerialKind.ENUM -> "string"
        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> "integer"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
        PrimitiveKind.BOOLEAN -> "boolean"
        else -> null
    }

    private fun classShape(
        descriptor: SerialDescriptor,
        path: String,
        nullable: String,
        out: MutableMap<String, String>,
        stack: List<String>,
    ) {
        val own = descriptor.serialName.substringAfterLast('.')
        if (own in stack) {
            out[path] = "recursive $own$nullable"
        } else {
            out[path] = "object$nullable"
            for (i in 0 until descriptor.elementsCount) {
                val property = "$path.${descriptor.getElementName(i)}"
                if (!descriptor.isElementOptional(i)) out["$property#required"] = "yes"
                typeShape(descriptor.getElementDescriptor(i), property, out, stack + own)
            }
        }
    }

    private fun documentShape(node: JsonObject, path: String, out: MutableMap<String, String>, stack: List<String>) {
        val ref = (node["\$ref"] as? JsonPrimitive)?.content?.substringAfterLast('/')
        val alternatives = node["oneOf"] as? JsonArray
        when {
            ref == null && alternatives == null -> typedShape(node, path, out, stack)
            ref != null && ref in stack -> out[path] = "recursive $ref"
            ref != null -> documentShape(schemas.getValue(ref).jsonObject, path, out, stack + ref)
            else -> alternativesShape(checkNotNull(alternatives), path, out, stack)
        }
    }

    /** `oneOf` of a schema and null is that schema, nullable; any other `oneOf` is not compared. */
    private fun alternativesShape(
        alternatives: JsonArray,
        path: String,
        out: MutableMap<String, String>,
        stack: List<String>,
    ) {
        val others = alternatives.map { it.jsonObject }.filter { (it["type"] as? JsonPrimitive)?.content != "null" }
        if (others.size == 1 && others.size < alternatives.size) {
            documentShape(others.single(), path, out, stack)
            out[path] = out.getValue(path) + "?"
        } else {
            out[path] = "any"
        }
    }

    private fun typedShape(node: JsonObject, path: String, out: MutableMap<String, String>, stack: List<String>) {
        val types = when (val type = node["type"]) {
            is JsonPrimitive -> listOf(type.content)
            is JsonArray -> type.map { (it as JsonPrimitive).content }
            else -> if (node.containsKey("properties")) listOf("object") else emptyList()
        }
        val nullable = if ("null" in types) "?" else ""
        val base = types.filter { it != "null" }.sorted().joinToString("|").ifEmpty { "any" }
        out[path] = base + nullable
        if (base == "array") node["items"]?.let { documentShape(it.jsonObject, "$path[]", out, stack) }
        if (base == "object") propertiesShape(node, path, out, stack)
    }

    private fun propertiesShape(node: JsonObject, path: String, out: MutableMap<String, String>, stack: List<String>) {
        val required = (node["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
        (node["properties"] as? JsonObject)?.forEach { (property, schema) ->
            if (property in required) out["$path.$property#required"] = "yes"
            documentShape(schema.jsonObject, "$path.$property", out, stack)
        }
        (node["additionalProperties"] as? JsonObject)?.let { documentShape(it, "$path{}", out, stack) }
    }

    private fun differences(name: String, serializer: KSerializer<*>, document: JsonObject): List<String> {
        val fromType = sortedMapOf<String, String>()
        val fromDocument = sortedMapOf<String, String>()
        typeShape(serializer.descriptor, name, fromType, emptyList())
        documentShape(document, name, fromDocument, listOf(name))
        return (fromType.keys + fromDocument.keys).sorted().mapNotNull { path ->
            val type = fromType[path]
            val documented = fromDocument[path]
            when {
                type == documented -> null
                documented == null -> "$path: the contract has $type, the document has nothing"
                type == null -> "$path: the document has $documented, the contract has nothing"
                else -> "$path: the contract has $type, the document has $documented"
            }
        }
    }

    private fun allDifferences(): List<String> = fixtureTypes.flatMap { serializer ->
        val name = name(serializer)
        val schema = schemas[name] as? JsonObject ?: return@flatMap listOf("$name: the document has no such schema")
        differences(name, serializer, schema)
    }

    @Test fun `the document's schemas have the shape of the contract types the fixtures pin`() {
        val found = allDifferences()
        val unexplained = found.filter { line -> allowed.keys.none { line.startsWith("$it:") } }
        assertTrue(
            unexplained.isEmpty(),
            "${unexplained.size} differences between the contract and the OpenAPI document:\n" +
                unexplained.joinToString("\n"),
        )
    }

    @Test fun `every allowed difference still exists`() {
        val found = allDifferences()
        val stale = allowed.keys.filter { key -> found.none { it.startsWith("$key:") } }
        assertTrue(stale.isEmpty(), "allowed differences that no longer differ: $stale")
    }

    @Test fun `a changed schema is reported property by property`() {
        val schema = schemas.getValue("Bootstrap").jsonObject
        val properties = schema.getValue("properties").jsonObject
        val extra = JsonObject(mapOf("type" to JsonPrimitive("string")))
        val broken = JsonObject(schema + ("properties" to JsonObject(properties - "apiVersion" + ("extra" to extra))))
        assertEquals(
            listOf(
                "Bootstrap.apiVersion: the contract has integer, the document has nothing",
                "Bootstrap.extra: the document has string, the contract has nothing",
            ),
            differences("Bootstrap", Bootstrap.serializer(), broken).filter {
                it.startsWith("Bootstrap.apiVersion") || it.startsWith("Bootstrap.extra")
            },
        )
    }
}

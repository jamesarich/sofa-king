package dev.jamesrich.sofaking.backend.admin

import kotlinx.html.A
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.map
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One operation of the API reference: its method, path, what it does, who it takes, its parameters and answers. */
internal class ApiOperation(
    val method: String,
    val path: String,
    val summary: String,
    val description: String?,
    val tags: List<String>,
    val security: List<String>,
    val parameters: List<String>,
    val requestBody: String?,
    val responses: List<Pair<String, String>>,
)

/** One tag's operations in the order the spec lists them. */
internal class ApiSection(val tag: String, val description: String?, val operations: List<ApiOperation>)

/** The server's OpenAPI document and the reference the portal renders from it. */
internal class ApiReference(
    val title: String,
    val version: String,
    val description: String?,
    val sections: List<ApiSection>,
    val json: String,
)

private const val SPEC_RESOURCE = "api/openapi.json"
private val METHODS = listOf("get", "post", "put", "patch", "delete")

/** The OpenAPI document from the server's resources; the reference page is built from it once. */
internal val apiReference: ApiReference by lazy {
    val json = checkNotNull(ApiReference::class.java.classLoader.getResourceAsStream(SPEC_RESOURCE)) {
        "Missing $SPEC_RESOURCE"
    }.use { it.readAllBytes().decodeToString() }
    parseApiReference(json)
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Resolves a `$ref` to the components object it names; anything else is returned as is. */
private fun JsonObject.resolve(root: JsonObject): JsonObject {
    val ref = text("\$ref") ?: return this
    return ref.removePrefix("#/").split('/').fold(root as JsonElement) { node, part ->
        (node as JsonObject).getValue(part)
    } as JsonObject
}

/** A schema's short name: the referenced component's, or `array of X`, or its type. */
private fun schemaName(schema: JsonObject?): String? {
    val ref = schema?.text("\$ref")
    return when {
        schema == null -> null
        ref != null -> ref.substringAfterLast('/')
        schema.text("type") == "array" -> "array of " + (schemaName(schema["items"] as? JsonObject) ?: "items")
        else -> schema.text("type")
    }
}

internal fun parseApiReference(json: String): ApiReference {
    val root = Json.parseToJsonElement(json).jsonObject
    val info = root.getValue("info").jsonObject
    val tags = root["tags"]?.jsonArray.orEmpty().map { it.jsonObject }
    val operations = root.getValue("paths").jsonObject.flatMap { (path, item) ->
        val byMethod = item.jsonObject
        METHODS.mapNotNull { method ->
            val op = byMethod[method]?.jsonObject ?: return@mapNotNull null
            val security = op["security"]?.jsonArray?.flatMap { it.jsonObject.keys }
            val parameters = op["parameters"]?.jsonArray.orEmpty().map {
                it.jsonObject.resolve(root)
            }.map { parameter ->
                val schema = parameter["schema"] as? JsonObject
                val range =
                    listOfNotNull(
                        schema?.text("minimum")?.let {
                            "min $it"
                        },
                        schema?.text("maximum")?.let { "max $it" },
                        schema?.text("default")?.let { "default $it" },
                    )
                val values = schema?.get("enum")?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                buildString {
                    append(parameter.text("name"))
                    append(" (").append(parameter.text("in"))
                    if (parameter["required"]?.jsonPrimitive?.booleanOrNull == true) append(", required")
                    append(")")
                    parameter.text("description")?.let { append(": ").append(it) }
                    if (!values.isNullOrEmpty()) append(": ").append(values.joinToString(" | "))
                    if (range.isNotEmpty()) append(" [").append(range.joinToString(", ")).append("]")
                }
            }
            val body = op["requestBody"]?.jsonObject?.get(
                "content",
            )?.jsonObject?.values?.firstOrNull()?.jsonObject?.get("schema")?.let {
                schemaName(it.jsonObject)
            }
            val responses = op["responses"]?.jsonObject.orEmpty().map { (status, response) ->
                val resolved = response.jsonObject.resolve(root)
                val schema = resolved["content"]?.jsonObject?.values?.firstOrNull()?.jsonObject?.get("schema")?.let {
                    schemaName(it.jsonObject)
                }
                status to
                    listOfNotNull(resolved.text("description")?.takeIf { it.isNotEmpty() }, schema).joinToString(": ")
            }
            ApiOperation(
                method.uppercase(), path, op.text("summary").orEmpty(), op.text("description"),
                op["tags"]?.jsonArray?.map {
                    it.jsonPrimitive.content
                }.orEmpty(),
                when {
                    security == null -> listOf("inherited")
                    security.isEmpty() -> emptyList()
                    else -> security
                },
                parameters, body, responses,
            )
        }
    }
    val sections = tags.map { tag ->
        val name = tag.text("name").orEmpty()
        ApiSection(name, tag.text("description"), operations.filter { name in it.tags })
    } + operations.filter { op ->
        tags.none { it.text("name") in op.tags }
    }.takeIf { it.isNotEmpty() }?.let { listOf(ApiSection("Other", null, it)) }.orEmpty()
    return ApiReference(
        info.text("title").orEmpty(),
        info.text("version").orEmpty(),
        info.text("description"),
        sections,
        json,
    )
}

internal fun apiPage(chrome: Chrome, reference: ApiReference): String = layout("API", chrome, "/admin/api") {
    pageHead("API", reference.description)
    tabs(chrome, "/admin/api", SYSTEM_TABS)
    div("summary") {
        span {
            val operations = reference.sections.sumOf { it.operations.size }
            +"${reference.title}, version ${reference.version}, $operations operations"
        }
        span("muted small") {
            a("/admin/api/openapi.json") {
                attributes["download"] = "sofa-king-openapi.json"
                +"Download the OpenAPI document"
            }
            +" to load into any OpenAPI tool."
        }
    }
    nav {
        attributes["aria-label"] = "Sections"
        div("chips") {
            for (section in reference.sections) {
                a(
                    "#api-${section.tag.lowercase().replace(' ', '-')}",
                    classes = "btn small quiet",
                ) {
                    +section.tag
                }
            }
        }
    }
    for (section in reference.sections) {
        panel(section.tag, id = "api-${section.tag.lowercase().replace(' ', '-')}") {
            if (section.description != null) p("muted small") { +section.description }
            for (op in section.operations) {
                div("api-op") {
                    span("method") { +op.method }
                    span("path") {
                        code { +op.path }
                        +" "
                        +op.summary
                    }
                    div("detail") {
                        if (op.description != null) p("mt-0") { +op.description }
                        ul {
                            li {
                                +"Takes: "
                                +(
                                    if (op.security.isEmpty()) {
                                        "no token"
                                    } else {
                                        op.security.joinToString {
                                            it.replace("Token", " token")
                                        }
                                    }
                                    )
                            }
                            if (op.parameters.isNotEmpty()) {
                                li {
                                    +"Parameters: "
                                    +op.parameters.joinToString("; ")
                                }
                            }
                            if (op.requestBody != null) {
                                li {
                                    +"Body: "
                                    +op.requestBody
                                }
                            }
                            li {
                                +"Answers: "
                                +op.responses.joinToString("; ") { (status, text) ->
                                    if (text.isEmpty()) status else "$status $text"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

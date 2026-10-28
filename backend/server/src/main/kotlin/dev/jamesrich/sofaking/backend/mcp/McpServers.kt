package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.logging.EventRing
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Clock

/** What the MCP tools read beyond the runtime: the clock, the running revision, and the portal's attention items. */
internal class McpContext(
    val runtime: LiveRuntime,
    val clock: Clock,
    val revision: String,
    /** The home page's attention items; null when the server runs without the portal. */
    val attention: (suspend () -> List<McpAttention>)?,
    val log: EventRing = EventRing.shared,
)

/** The MCP servers the endpoint answers with: [reads] for a `read` token, [admin] with the actions too. */
internal class McpServers(val reads: Server, val admin: Server) {
    companion object {
        fun create(context: McpContext): McpServers = McpServers(
            reads = server(context.revision) { readTools(context) },
            admin = server(context.revision) {
                readTools(context)
                actionTools(context)
            },
        )
    }
}

private const val INSTRUCTIONS =
    "Sofa King is a self-hosted media center: live TV channels from providers, a program guide, a DVR and media " +
        "libraries, served to the Sofa King apps. These tools read its state for setup and upkeep: start with " +
        "server_status and needs_attention, then imports, source_problems, jobs and recent_log to see why. Tools " +
        "that change something are listed only to the admin token."

private fun server(revision: String, tools: Server.() -> Unit): Server = Server(
    Implementation(name = "sofa-king", version = revision, title = "Sofa King"),
    ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    INSTRUCTIONS,
).apply(tools)

/** Tool results as JSON, the API's field names, leaving out what is null. */
internal val toolJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

private val log = LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.mcp.McpTools")

/**
 * Adds a tool that answers [run]'s value as JSON text. A refusal answers its code and message as an error result, as
 * the API would; any other failure only its class, since a message can carry an upstream URL.
 */
internal inline fun <reified T> Server.tool(
    name: String,
    description: String,
    schema: ToolSchema = ToolSchema(),
    annotations: ToolAnnotations,
    crossinline run: suspend (Arguments) -> T,
) {
    addTool(name, description, schema, toolAnnotations = annotations) { request ->
        runCatchingCancellable { run(Arguments(request.arguments ?: JsonObject(emptyMap()))) }.fold(
            onSuccess = { CallToolResult(listOf(TextContent(toolJson.encodeToString(it)))) },
            onFailure = { failure -> failed(name, failure) },
        )
    }
}

internal fun failed(tool: String, failure: Throwable): CallToolResult {
    val text = if (failure is ApiFailure) {
        "${failure.code}: ${failure.message}"
    } else {
        log.warn("MCP tool failed tool={} class={}", tool, failure.javaClass.simpleName)
        "INTERNAL_ERROR: the tool failed (${failure.javaClass.simpleName})"
    }
    return CallToolResult(listOf(TextContent(text)), isError = true)
}

internal val READ_ONLY = ToolAnnotations(readOnlyHint = true, openWorldHint = false)

/** A tool's arguments, read the way the API reads its query parameters: a bad value is a 400-style refusal. */
internal class Arguments(private val values: JsonObject) {
    fun string(name: String): String? = values[name]?.takeIf { it is JsonPrimitive && it.isString }
        ?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }

    fun int(name: String, default: Int, range: IntRange): Int {
        val raw = values[name] ?: return default
        val value = (raw as? JsonPrimitive)?.intOrNull
        if (value == null || value !in range) invalid("$name is ${range.first} to ${range.last}")
        return value
    }
}

internal fun invalid(message: String): Nothing = throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", message)

/** A tool's input schema from its [properties], each built by [string], [integer] or [choice]. */
internal fun schema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()) = ToolSchema(
    properties = JsonObject(properties.toMap()),
    required = required.ifEmpty { null },
)

internal fun string(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
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

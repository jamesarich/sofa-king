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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
                privateReadTools(context)
                actionTools(context)
                sourceActionTools(context)
                policyActionTools(context)
            },
        )
    }
}

private const val INSTRUCTIONS =
    "Sofa King is a self-hosted media center: live TV channels from providers, a program guide, a DVR and media " +
        "libraries, served to the Sofa King apps. These tools read its state for setup and upkeep.\n" +
        "Triage in this order: server_status for the counts, then needs_attention for what the operator must fix. " +
        "A provider or guide with no channels or an old lastSuccessAt failed to fetch: read imports, then jobs for " +
        "the last note. Channels that play badly have failing streams: source_problems lists the sources that are " +
        "not healthy and source_health explains one. recent_log level=error has the server's own reason.\n" +
        "Ids chain: lineup lists entry ids (the id channel, on_later and the recording tools use); source_problems " +
        "and channel list source ids (the id source_health takes) and the entries that use them; imports lists " +
        "provider and guide ids (guide_channels takes a guide id); jobs lists job ids; libraries lists library " +
        "ids; explain_station takes the stationKey a lineup row shows. A providerChannelId is a provider's " +
        "channel, not a lineup entry; channel accepts either. Lists are capped: when truncated is true or " +
        "nextOffset is set, call again with offset or a narrower filter.\n" +
        "Tools that change something are listed only to the admin token. A destructive one refuses without " +
        "confirm true: say what it does first. Lineup edits take the revision from preview_policy and preview " +
        "with dryRun (the default) before they write; a stale revision answers POLICY_CHANGED. Results never " +
        "include stream URLs, tokens or request headers."

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
            onSuccess = { CallToolResult(listOf(TextContent(scrub(toolJson.encodeToString(it))))) },
            onFailure = { failure -> failed(name, failure) },
        )
    }
}

internal fun failed(tool: String, failure: Throwable): CallToolResult {
    val text = if (failure is ApiFailure) {
        "${failure.code}: ${failure.message}"
    } else {
        log.warn("MCP tool failed tool={} class={}", tool, failure.javaClass.simpleName)
        "INTERNAL_ERROR: the tool failed (${failure.javaClass.simpleName}); see recent_log level=error"
    }
    return CallToolResult(listOf(TextContent(scrub(text))), isError = true)
}

private val URL_PATTERN = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>\\]+""")
private val TOKEN_PATTERN = Regex("""\bsk_[A-Za-z0-9_-]{10,}|(?i:bearer)\s+[A-Za-z0-9._~+/=-]{8,}""")

/** A last guard on every result: a URL or token that reached a field anyway never leaves the server. */
internal fun scrub(text: String): String =
    text.replace(URL_PATTERN, "[url removed]").replace(TOKEN_PATTERN, "[token removed]")

internal val READ_ONLY = ToolAnnotations(readOnlyHint = true, openWorldHint = false)

/** A tool's arguments, read the way the API reads its query parameters: a bad value is a 400-style refusal. */
internal class Arguments(private val values: JsonObject) {
    fun string(name: String): String? = values[name]?.takeIf { it is JsonPrimitive && it.isString }
        ?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }

    /** The member of [allowed] that [name] names without regard to case; null when absent, a refusal for another. */
    fun choice(name: String, allowed: Collection<String>): String? {
        val value = string(name) ?: return null
        return allowed.firstOrNull { it.equals(value, ignoreCase = true) }
            ?: invalid("$name is ${allowed.joinToString(", ")}")
    }

    /** A true or false value; null when absent, a refusal for anything else. */
    fun boolean(name: String): Boolean? {
        val raw = values[name] ?: return null
        return (raw as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid("$name is true or false")
    }

    /** A number; null when absent, a refusal for anything else. */
    fun number(name: String): Double? {
        val raw = values[name] ?: return null
        return (raw as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: invalid("$name is a number")
    }

    fun int(name: String, default: Int, range: IntRange): Int {
        val raw = values[name] ?: return default
        val value = (raw as? JsonPrimitive)?.intOrNull
        if (value == null || value !in range) invalid("$name is ${range.first} to ${range.last}")
        return value
    }
}

/** Refuses a destructive [action] unless the call said `confirm: true`. */
internal fun Arguments.confirmed(action: String) {
    if (boolean(CONFIRM) != true) {
        throw ApiFailure(
            HttpStatusCode.BadRequest,
            "CONFIRMATION_REQUIRED",
            "Nothing was done: $action. Tell the operator what it does, then call again with confirm true",
        )
    }
}

internal const val CONFIRM = "confirm"

internal fun invalid(message: String): Nothing = throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", message)

package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.SavedSecret
import dev.jamesrich.sofaking.backend.http.info
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.setup.SetupService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.Serializable
import java.net.URI

// First-run setup, listed only to the admin token. Nothing here returns an API key or a token: setup_status reports
// whether each is set, and a provider is described by its kind, host and interval, never its URL or headers.

private const val SERVICE = "service"
private val SERVICES = SavedSecret.entries.associateBy { it.prefix.lowercase() }
private val KINDS = LibraryKind.entries.map { it.wire }

/** A write that leaves the configuration changed and, for some, the server restarting. */
private val SETUP_WRITE = ToolAnnotations(
    readOnlyHint = false,
    destructiveHint = true,
    idempotentHint = false,
    openWorldHint = true,
)

private val LIBRARY_WRITE = ToolAnnotations(
    readOnlyHint = false,
    destructiveHint = false,
    idempotentHint = false,
    openWorldHint = false,
)

@Serializable internal class SetupProvider(
    val id: String,
    val kind: String,
    val service: String? = null,
    val host: String? = null,
    val refreshSeconds: Long,
)

@Serializable internal class SetupGuide(
    val id: String,
    val host: String? = null,
    val provider: String? = null,
    val refreshSeconds: Long,
)

/** Whether a key is set, and whether the environment sets it, which leaves set_api_key refused. */
@Serializable internal class ApiKeyState(val service: String, val set: Boolean, val fromEnvironment: Boolean)

@Serializable internal class SetupFolders(val roots: List<String>, val folders: List<String>)

@Serializable internal class SetupStatus(
    val hasSourceFile: Boolean,
    val providers: List<SetupProvider>,
    val guides: List<SetupGuide>,
    val hasPolicy: Boolean,
    val canWriteStarterPolicy: Boolean,
    val keys: List<ApiKeyState>,
    val libraryFolders: SetupFolders? = null,
)

@Serializable internal class SetupChange(val done: Boolean, val restarting: Boolean, val message: String)

internal fun Server.setupTools(context: McpContext) {
    val setup = context.runtime.setup ?: return
    statusTool(context, setup)
    keyTool(setup)
    starterTool(setup)
    libraryTool(context)
}

private fun Server.statusTool(context: McpContext, setup: SetupService) {
    tool(
        "setup_status",
        "Use first when setting a server up: the providers and guides in the source file (id, kind, host and " +
            "interval, never a URL, header or token), whether a lineup policy exists or a starter one can be " +
            "written, whether the TMDB, fanart.tv and ListenBrainz keys are set and whether the environment sets " +
            "them, and the folders a library may use. Never returns a key.",
        annotations = READ_ONLY,
    ) {
        val document = setup.document()
        SetupStatus(
            setup.hasSources,
            document.providers.map {
                SetupProvider(it.id, it.type, it.service, hostOf(it.playlistUrl ?: it.guideUrl), it.refreshInterval)
            },
            document.guides.map { SetupGuide(it.id, hostOf(it.url), it.provider, it.refreshSeconds) },
            setup.hasPolicy,
            setup.canWriteStarterPolicy,
            SERVICES.map { (name, secret) -> ApiKeyState(name, setup.isSet(secret), setup.fromEnvironment(secret)) },
            context.runtime.libraries?.let { libraries ->
                SetupFolders(
                    libraries.roots.map { it.root.toString() },
                    libraries.folders.choices().map { it.toString() },
                )
            },
        )
    }
}

private fun Server.keyTool(setup: SetupService) {
    tool(
        "set_api_key",
        "Save the TMDB read access token, fanart.tv key or ListenBrainz token the operator gives you, for the " +
            "libraries' details and artwork. The service is asked to accept it first. The server then restarts: " +
            "every open stream ends and it is back in about a minute. Needs confirm true. Refused with " +
            "X_FROM_ENVIRONMENT when the environment sets it, X_REJECTED when the service refuses it and " +
            "X_UNREACHABLE when it cannot be asked, X being TMDB, FANART or LISTENBRAINZ; nothing is saved then. " +
            "The value is never shown again.",
        schema(
            SERVICE to choice("Which key", SERVICES.keys.toList()),
            "value" to string("The key or token"),
            CONFIRM to boolean("Required true"),
            required = listOf(SERVICE, "value", CONFIRM),
        ),
        SETUP_WRITE,
    ) { arguments ->
        val secret = arguments.choice(SERVICE, SERVICES.keys)?.let { SERVICES.getValue(it) }
            ?: invalid("service is ${SERVICES.keys.joinToString(", ")}")
        val value = arguments.string("value") ?: invalid("value is required")
        arguments.confirmed("the server would restart, ending every open stream, to use the ${secret.service} key")
        setup.save(secret, value)
        SetupChange(
            true,
            true,
            "Saved the ${secret.service} ${secret.noun}. The server is restarting. ${secret.effect}",
        )
    }
}

private fun Server.starterTool(setup: SetupService) {
    tool(
        "write_starter_policy",
        "Write the starter lineup policy for the providers in the source file: native services in priority order, " +
            "the shipped groups and number blocks, and stations that come on as their sources are verified. The " +
            "server then restarts to read it. Needs confirm true. Refused with POLICY_EXISTS when a policy is " +
            "configured and NO_SOURCES when there are no providers.",
        schema(CONFIRM to boolean("Required true"), required = listOf(CONFIRM)),
        SETUP_WRITE,
    ) { arguments ->
        arguments.confirmed("the server would restart, ending every open stream, to read the new lineup policy")
        setup.writeStarterPolicy()
        SetupChange(true, true, "Wrote the starter lineup policy. The server is restarting to read it")
    }
}

private fun Server.libraryTool(context: McpContext) {
    val libraries = context.runtime.libraries ?: return
    tool(
        "add_library",
        "Add a media library: a name, a kind and a folder from setup_status libraryFolders.folders. The server " +
            "scans it at once; libraries shows how the scan went. A folder another library uses is refused.",
        schema(
            "name" to string("The library's name"),
            "kind" to choice("What the folder holds", KINDS),
            "path" to string("A folder from setup_status libraryFolders.folders"),
            required = listOf("name", "kind", "path"),
        ),
        LIBRARY_WRITE,
    ) { arguments ->
        val name = arguments.string("name") ?: invalid("name is required")
        val kind =
            arguments.choice("kind", KINDS)?.let(LibraryKind::of) ?: invalid("kind is ${KINDS.joinToString(", ")}")
        val path = arguments.string("path") ?: invalid("path is required; see setup_status libraryFolders")
        libraries.create(name, kind, path).info(emptyMap())
    }
}

/** The host of [url], or null when it has none or names a `${NAME}` reference. */
private fun hostOf(url: String?): String? = url
    ?.let { runCatchingCancellable { URI(it).host }.getOrNull() }
    ?.takeIf { '$' !in it && '{' !in it }

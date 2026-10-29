package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.UpdateSource
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.http.queueProbe
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.Serializable

// The live TV actions on a stream source and a playing session, listed only to the admin token.

private const val SOURCE_ID = "sourceId"
private const val SESSION_ID = "sessionId"
private const val ENABLED = "enabled"

/** Changes one source's state; asking for the state it is in changes nothing. */
private val SOURCE_CHANGE = ToolAnnotations(
    readOnlyHint = false,
    destructiveHint = true,
    idempotentHint = true,
    openWorldHint = false,
)

@Serializable internal class SourceChanged(
    val sourceId: String,
    val manuallyDisabled: Boolean,
    val status: String,
    val nextCheckAt: String? = null,
)

@Serializable internal class SessionSwitched(
    val sessionId: String,
    val channelId: String,
    val providerId: String,
    val sourceId: String,
    val sourceSwitches: Int,
)

internal fun Server.sourceActionTools(context: McpContext) {
    enabledTool(context.runtime)
    probeTool(context.runtime)
    switchTool(context.runtime)
}

private fun Server.enabledTool(runtime: LiveRuntime) {
    tool(
        "set_source_enabled",
        "Turn one stream source off or on by hand, as the channel page's Disable and Enable do: a disabled source is " +
            "never selected or probed, and sessions playing it move on. Disabling needs confirm true. Takes a " +
            "sourceId from source_problems or channel; source_health shows the result.",
        schema(
            SOURCE_ID to string("The source's id, from source_problems or channel"),
            ENABLED to boolean("true puts the source back in selection, false takes it out"),
            CONFIRM to boolean("Required true to disable a source"),
            required = listOf(SOURCE_ID, ENABLED),
        ),
        SOURCE_CHANGE,
    ) { arguments ->
        val id = arguments.string(SOURCE_ID) ?: invalid("sourceId is required; call source_problems for source ids")
        val enabled = arguments.boolean(ENABLED) ?: invalid("enabled is required")
        if (!enabled) arguments.confirmed("the source would stop being selected or probed and its sessions would move")
        val source = runtime.feeds.updateSource(id, UpdateSource(manuallyDisabled = !enabled))
        runtime.sessions.healthChanged(source.id)
        SourceChanged(source.id, source.manuallyDisabled, source.status, source.nextCheckAt)
    }
}

private fun Server.probeTool(runtime: LiveRuntime) {
    tool(
        "probe_source",
        "Put one stream source at the front of the probe queue, as the channel page's Probe now does, so the next " +
            "free worker checks it whether or not it is due. It answers at once: call source_health a minute " +
            "later, when nextCheckAt and recentProbes have moved. Takes a sourceId from source_problems or channel.",
        schema(SOURCE_ID to string("The source's id, from source_problems or channel"), required = listOf(SOURCE_ID)),
        UPKEEP,
    ) { arguments ->
        val id = arguments.string(SOURCE_ID) ?: invalid("sourceId is required; call source_problems for source ids")
        queueProbe(runtime, id)
        ActionResult(true, "Queued a probe of $id; call source_health after nextCheckAt moves to read the result")
    }
}

private fun Server.switchTool(runtime: LiveRuntime) {
    tool(
        "switch_session_source",
        "Move a playing live session to its channel's next source, as the portal's Switch source does: the player " +
            "restarts on it. Takes a sessionId from watching_now. When the channel has no other source, channel " +
            "shows its sources.",
        schema(SESSION_ID to string("The session's id, from watching_now"), required = listOf(SESSION_ID)),
        UPKEEP.copy(idempotentHint = false),
    ) { arguments ->
        val id = arguments.string(SESSION_ID) ?: invalid("sessionId is required; call watching_now for session ids")
        try {
            runtime.sessions.media.switchSource(id)
        } catch (failure: ApiFailure) {
            throw if (failure.code == "NO_OTHER_SOURCE") {
                ApiFailure(
                    failure.status,
                    failure.code,
                    "${failure.message}; call channel with the session's channelId to inspect its sources",
                    cause = failure,
                )
            } else {
                failure
            }
        }
        val session = runtime.sessions.snapshot().firstOrNull { it.id == id }
            ?: throw ApiFailure(HttpStatusCode.Gone, "SESSION_ENDED", "The session ended; call watching_now")
        SessionSwitched(id, session.channelId, session.providerId, session.offeringId, session.sourceSwitches)
    }
}

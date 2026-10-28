package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.update.UpdateChecker
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations

// The tools that change something, listed only to the admin token: what the portal's Jobs, Libraries and About pages
// offer. None deletes anything; update_now restarts the server when a newer image is published.

/** Starts work the server would do on its own schedule anyway; asking twice does no more than once. */
private val UPKEEP = ToolAnnotations(
    readOnlyHint = false,
    destructiveHint = false,
    idempotentHint = true,
    openWorldHint = false,
)

internal fun Server.actionTools(context: McpContext) {
    val runtime = context.runtime
    tool(
        "run_job",
        "Start a background job now, as the portal's Jobs page does: a provider import, a guide refresh, the " +
            "lineup policy, Plex sync, a library scan of every library, the logo sweep or a backup. jobs lists the " +
            "ids and which can run now.",
        schema(JOB to string("The job's id, from jobs"), required = listOf(JOB)),
        UPKEEP,
    ) { arguments ->
        val id = arguments.string(JOB) ?: invalid("job is required")
        if (!runtime.jobs.runNow(id)) {
            throw ApiFailure(HttpStatusCode.NotFound, "JOB_NOT_FOUND", "No job with that id can be started")
        }
        ActionResult(true, "Started $id; jobs shows when it ends and how it went")
    }
    runtime.libraries?.let { libraries ->
        tool(
            "scan_library",
            "Queue a scan of one library, or of every library without one, so new, changed and removed files show. " +
                "libraries lists the ids and how the last scan went.",
            schema(LIBRARY to string("The library's id, from libraries; leave out for every library")),
            UPKEEP,
        ) { arguments ->
            val id = arguments.string(LIBRARY)
            val name = id?.let { libraries.library(it).name }
            libraries.requestScan(id)
            ActionResult(true, "Queued a scan of ${name ?: "every library"}")
        }
    }
    runtime.updates?.let { updates ->
        tool(
            "check_for_update",
            "Ask the image registry now whether a newer build is published, and answer the update status.",
            annotations = UPKEEP.copy(openWorldHint = true),
        ) { updateInfo(updates.check()) }
        tool(
            "update_now",
            "Ask the host's updater to install the newest published image now. The server restarts if one is newer, " +
                "dropping every stream that plays; check update_status and watching_now first.",
            annotations = UPKEEP.copy(destructiveHint = true),
        ) { requestUpdate(updates) }
    }
}

private suspend fun requestUpdate(updates: UpdateChecker): ActionResult = when {
    updates.state().updater == null ->
        throw ApiFailure(
            HttpStatusCode.Conflict,
            "NO_UPDATER",
            "The host's updater has not reported, so there is nothing to ask",
        )

    updates.requestUpdate() -> ActionResult(
        true,
        "Asked the updater to run; the server restarts if a newer image is published",
    )

    else -> throw ApiFailure(
        HttpStatusCode.InternalServerError,
        "DATA_DIRECTORY_UNWRITABLE",
        "Could not write the update request",
    )
}

private const val JOB = "job"
private const val LIBRARY = "library"

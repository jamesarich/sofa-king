package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.api.HistoryEntry
import dev.jamesrich.sofaking.backend.api.HistoryQuery
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.playback.HistoryKind
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.time.Instant
import java.time.format.DateTimeParseException

// Read tools only the admin token is listed. The admin API does not open these routes to a read token either: what
// each device and player address watched, and how the Plex integration is wired.

internal fun Server.privateReadTools(context: McpContext) {
    historyTool(context)
    plexTool(context)
}

private fun Server.historyTool(context: McpContext) {
    val runtime = context.runtime
    tool(
        "playback_history",
        "Use to see what was watched and by whom: ended playback sessions, newest first, as the admin API's history " +
            "lists them. Each has kind (live or vod), the paired device (clientId) or player address, the lineup " +
            "entry (channelId), the source's provider, how it ended (reason), when and how many bytes. Admin " +
            "token only: it names what each device and address watched.",
        schema(
            "since" to string("Only sessions that ended after this ISO-8601 instant"),
            "kind" to choice("Only this kind", listOf(HistoryKind.LIVE, HistoryKind.VOD)),
            "client" to string("Only this paired device's client id, or this player address"),
            LIMIT to integer("How many sessions", 1..MAX_PAGE, DEFAULT_PAGE),
        ),
        READ_ONLY,
    ) { arguments ->
        val history = runtime.history
            ?: throw ApiFailure(HttpStatusCode.NotFound, "HISTORY_NOT_AVAILABLE", "This server keeps no history")
        val limit = arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)
        val since = arguments.string("since")?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                invalid("since must be an ISO-8601 instant")
            }
        }
        val query =
            HistoryQuery(
                since,
                arguments.choice("kind", listOf(HistoryKind.LIVE, HistoryKind.VOD)),
                arguments.string("client"),
                limit + 1,
            )
        val entries: List<HistoryEntry> = history.list(query)
        Listed(entries.take(limit), entries.size > limit)
    }
}

private fun Server.plexTool(context: McpContext) {
    val runtime = context.runtime
    tool(
        "plex_status",
        "Use when Plex shows no or stale channels: the last Plex sync (when, why, any error), the DVRs it reloaded, " +
            "each HDHomeRun device it mapped with the channels saved, devices Plex does not hold, and findings. " +
            "Admin token only.",
        schema(LIMIT to integer("How many items of each list", 1..MAX_PAGE, DEFAULT_PAGE)),
        READ_ONLY,
    ) { arguments ->
        val plex = runtime.plex ?: throw ApiFailure(
            HttpStatusCode.NotFound,
            "PLEX_NOT_CONFIGURED",
            "Plex sync is not configured; set SOFA_PLEX_URL and SOFA_PLEX_TOKEN",
        )
        val limit = arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)
        val status = plex.status()
        PlexView(
            status.syncMinutes,
            status.lastSyncAt,
            status.lastCause,
            status.lastError,
            status.dvrs.capped(limit),
            status.devices.capped(limit),
            status.missingDevices.capped(limit),
            status.findings.capped(limit),
        )
    }
}

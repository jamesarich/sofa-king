package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.HistoryQuery
import dev.jamesrich.sofaking.backend.api.ServerEvent
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.playback.HistoryKind
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.onSubscription
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.time.Duration.Companion.seconds

/** The admin event stream, which compression would hold back. */
internal const val EVENT_STREAM_PATH = "/api/v1/admin/events/stream"

/** The event the stream sends first, once it is listening, so a client knows nothing before it was missed. */
internal const val READY_EVENT = "ready"

private const val DEFAULT_HISTORY_LIMIT = 200
private const val MAX_HISTORY_LIMIT = 1000
private val HEARTBEAT = 15.seconds
private val eventJson = Json { encodeDefaults = true }

/**
 * `GET /api/v1/admin/history`, the ended playback sessions, and `GET /api/v1/admin/events/stream`, the server's events
 * as server-sent events: one named for each [ServerEvent.type], a comment every 15 seconds to keep proxies from closing
 * an idle stream, and [READY_EVENT] first. `?types=` takes a comma-separated list of the types to send.
 */
internal fun Route.historyRoutes(runtime: LiveRuntime, clock: Clock) {
    get("/api/v1/admin/history") {
        val history = runtime.history
            ?: throw ApiFailure(HttpStatusCode.NotFound, "HISTORY_NOT_AVAILABLE", "This server keeps no history")
        call.respond(history.list(historyQuery(call.request.queryParameters)))
    }
    sse(EVENT_STREAM_PATH) {
        val types = call.request.queryParameters["types"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.toSet()
        heartbeat { period = HEARTBEAT }
        runtime.events.events
            .onSubscription {
                send(event(ServerEvent(READY_EVENT, clock.instant().toString(), JsonObject(emptyMap()))))
            }
            .collect { if (types == null || it.type in types) send(event(it)) }
    }
}

private fun event(event: ServerEvent) = ServerSentEvent(eventJson.encodeToString(event), event.type)

private fun historyQuery(parameters: Parameters): HistoryQuery {
    val since = parameters["since"]?.let {
        try {
            Instant.parse(it)
        } catch (_: DateTimeParseException) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "since must be an ISO-8601 instant")
        }
    }
    val kind = parameters["kind"]?.also {
        if (it != HistoryKind.LIVE && it != HistoryKind.VOD) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "kind must be live or vod")
        }
    }
    val limit = parameters["limit"]?.let {
        it.toIntOrNull()?.takeIf { n -> n in 1..MAX_HISTORY_LIMIT }
            ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "limit must be 1 to $MAX_HISTORY_LIMIT")
    } ?: DEFAULT_HISTORY_LIMIT
    return HistoryQuery(since, kind, parameters["client"]?.takeIf { it.isNotBlank() }, limit)
}

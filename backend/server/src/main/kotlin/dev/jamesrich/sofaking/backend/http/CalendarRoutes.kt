package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.dvr.recordingsCalendar
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Clock

private val CALENDAR = ContentType("text", "calendar").withCharset(Charsets.UTF_8)

/**
 * `/output/recordings.ics`, the scheduled and running recordings for a calendar app to subscribe to. Calendar apps
 * send no headers, so the output token or a `read` token travels in the query, as the other outputs' tokens do.
 */
internal fun Route.calendarRoutes(
    runtime: LiveRuntime,
    clock: Clock,
    config: OutputConfig,
    apiToken: String?,
    adminToken: String? = null,
) {
    get("/output/recordings.ics") {
        authorizeCalendar(call, runtime, config.token, apiToken, adminToken)
        val dvr = runtime.dvr
            ?: throw ApiFailure(HttpStatusCode.NotFound, "RECORDINGS_NOT_AVAILABLE", "This server does not record")
        call.respondText(recordingsCalendar(dvr.recordings().recordings, clock.instant()), CALENDAR)
    }
}

/** A `read` token in the query or as a bearer, else what [authorizeOutput] takes. */
private suspend fun authorizeCalendar(
    call: ApplicationCall,
    runtime: LiveRuntime,
    outputToken: String?,
    apiToken: String?,
    adminToken: String?,
) {
    val presented = call.request.queryParameters["token"] ?: call.bearer()
    if (runtime.tokens.resolve(presented)?.scope == TokenScope.READ) return
    authorizeOutput(call, outputToken, apiToken, adminToken)
}

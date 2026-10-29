package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.ClientCrash
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.diagnostics.scrubbed
import dev.jamesrich.sofaking.backend.store.addClientCrash
import dev.jamesrich.sofaking.backend.store.clientCrashes
import dev.jamesrich.sofaking.backend.store.viewer
import dev.jamesrich.sofaking.backend.store.wholeSeconds
import dev.jamesrich.sofaking.contract.CrashReport
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Clock
import java.util.UUID

/** Crash reports at `POST /api/v1/diagnostics/crashes`, counted per remote address. */
internal val CRASH_REPORT_LIMIT = RateLimitName("crash-report")
internal const val CRASH_REPORTS_PER_MINUTE = 10

/** Where an app posts a crash. A device's `api` token opens it; a `read` token does not, since it is a POST. */
internal const val CRASH_REPORT_PATH = "/api/v1/diagnostics/crashes"

private const val DEFAULT_CRASH_LIMIT = 20
private const val MAX_CRASH_LIMIT = 100

/**
 * Crash diagnostics: an app posts the last crash it kept (`POST /api/v1/diagnostics/crashes`, answered 204, behind a
 * device's API token and a rate limit) and the admin lists what the server holds
 * (`GET /api/v1/admin/diagnostics/crashes`, `?limit=` 1 to 100, newest first, open to a `read` token). Reports are
 * scrubbed again on arrival, never leave the server, and only the newest hundred are kept.
 */
internal fun Route.diagnosticsRoutes(runtime: LiveRuntime, clock: Clock) {
    rateLimit(CRASH_REPORT_LIMIT) {
        post(CRASH_REPORT_PATH) {
            val report = scrubbed(call.appBody<CrashReport>())
            if (report.exception.isEmpty()) {
                throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "A crash report names its exception")
            }
            val clientId = call.attributes.getOrNull(PresentedClientKey)
            val named = clientId?.let { runtime.store.viewer(it)?.name }?.takeIf { it.isNotEmpty() }
            runtime.store.addClientCrash(
                ClientCrash(
                    id = report.id.ifEmpty { UUID.randomUUID().toString() },
                    clientId = clientId,
                    clientName = named ?: report.device,
                    receivedAt = clock.instant().wholeSeconds(),
                    occurredAt = report.occurredAt,
                    exception = report.exception,
                    message = report.message,
                    frames = report.frames,
                    appVersion = report.appVersion,
                    platform = report.platform,
                    osVersion = report.osVersion,
                    device = report.device,
                ),
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
    get("/api/v1/admin/diagnostics/crashes") {
        val limit = call.request.queryParameters["limit"]?.let {
            it.toIntOrNull()?.takeIf { n -> n in 1..MAX_CRASH_LIMIT }
                ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "limit must be 1 to $MAX_CRASH_LIMIT")
        } ?: DEFAULT_CRASH_LIMIT
        call.respond(runtime.store.clientCrashes(limit))
    }
}

package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.dvr.Dvr
import dev.jamesrich.sofaking.contract.CreateRecordingRule
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.RecordingRuleList
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.UpdateRecording
import dev.jamesrich.sofaking.contract.UpdateRecordingRule
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** How far ahead a time window may start. */
internal val MANUAL_HORIZON: Duration = Duration.ofDays(14)

/** How many one-off recordings may wait to run before another time window is refused. */
internal const val MAX_SCHEDULED_ONE_OFFS = 100

/** Schedules [request] after the limits on a time window, the same for the route and the MCP tool. */
internal suspend fun Dvr.scheduleChecked(request: ScheduleRecording, now: Instant): Recording {
    if (request.endsAt != null) checkManualLimits(this, request, now)
    return schedule(request)
}

/** Refuses a time window that starts beyond [MANUAL_HORIZON] or would be one more than [MAX_SCHEDULED_ONE_OFFS]. */
private suspend fun checkManualLimits(dvr: Dvr, request: ScheduleRecording, now: Instant) {
    // An unparseable startsAt is left to the DVR, which names the field.
    val start = runCatching { Instant.parse(request.startsAt) }.getOrNull()
    if (start != null && start > now.plus(MANUAL_HORIZON)) {
        throw ApiFailure(
            HttpStatusCode.BadRequest,
            "INVALID_REQUEST",
            "startsAt must be within ${MANUAL_HORIZON.toDays()} days",
        )
    }
    val waiting = dvr.recordings().recordings.count {
        it.state == Wire.RecordingState.SCHEDULED && it.ruleId == null
    }
    if (waiting >= MAX_SCHEDULED_ONE_OFFS) {
        throw ApiFailure(
            HttpStatusCode.Conflict,
            "SCHEDULE_FULL",
            "At most $MAX_SCHEDULED_ONE_OFFS one-off recordings can wait; delete one first",
        )
    }
}

/** The DVR under `/api/v1`, behind the API token: recordings and the series rules that schedule them. */
internal fun Route.recordingRoutes(dvr: Dvr, clock: Clock) {
    route("/api/v1/recordings") {
        get { call.respond(dvr.recordings()) }
        post {
            val request = call.appBody<ScheduleRecording>()
            call.respond(HttpStatusCode.Created, dvr.scheduleChecked(request, clock.instant()))
        }
        get("/{id}") { call.respond(dvr.recording(call.parameters["id"]!!)) }
        patch("/{id}") { call.respond(dvr.update(call.parameters["id"]!!, call.appBody<UpdateRecording>())) }
        delete("/{id}") {
            dvr.delete(call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/api/v1/recording-rules") {
        get { call.respond(RecordingRuleList(dvr.rules.list())) }
        post { call.respond(HttpStatusCode.Created, dvr.rules.create(call.appBody<CreateRecordingRule>())) }
        patch("/{id}") { call.respond(dvr.rules.update(call.parameters["id"]!!, call.appBody<UpdateRecordingRule>())) }
        delete("/{id}") {
            dvr.rules.delete(call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

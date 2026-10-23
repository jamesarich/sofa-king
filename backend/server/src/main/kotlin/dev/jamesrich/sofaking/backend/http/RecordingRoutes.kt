package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.dvr.Dvr
import dev.jamesrich.sofaking.contract.CreateRecordingRule
import dev.jamesrich.sofaking.contract.RecordingRuleList
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.UpdateRecording
import dev.jamesrich.sofaking.contract.UpdateRecordingRule
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** The DVR under `/api/v1`, behind the API token: recordings and the series rules that schedule them. */
internal fun Route.recordingRoutes(dvr: Dvr) {
    route("/api/v1/recordings") {
        get { call.respond(dvr.recordings()) }
        post { call.respond(HttpStatusCode.Created, dvr.schedule(call.appBody<ScheduleRecording>())) }
        get("/{id}") { call.respond(dvr.recording(call.parameters["id"]!!)) }
        patch("/{id}") { call.respond(dvr.update(call.parameters["id"]!!, call.appBody<UpdateRecording>())) }
        delete("/{id}") { dvr.delete(call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }
    }
    route("/api/v1/recording-rules") {
        get { call.respond(RecordingRuleList(dvr.rules())) }
        post { call.respond(HttpStatusCode.Created, dvr.createRule(call.appBody<CreateRecordingRule>())) }
        patch("/{id}") { call.respond(dvr.updateRule(call.parameters["id"]!!, call.appBody<UpdateRecordingRule>())) }
        delete("/{id}") { dvr.deleteRule(call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }
    }
}

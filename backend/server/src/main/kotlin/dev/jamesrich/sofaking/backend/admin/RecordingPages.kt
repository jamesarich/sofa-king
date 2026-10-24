package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.dvr.Dvr
import dev.jamesrich.sofaking.backend.dvr.Retention
import dev.jamesrich.sofaking.contract.CreateRecordingRule
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.UpdateRecording
import dev.jamesrich.sofaking.contract.UpdateRecordingRule
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.date
import io.ktor.http.parameters
import io.ktor.http.path
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.path
import io.ktor.server.routing.route
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** The Recordings page: rules, manual recordings, and protecting or deleting what was recorded. */
internal class RecordingPages(private val portal: Portal) {
    private val runtime get() = portal.runtime

    fun install(route: Route) = with(portal) {
        route.get("/recordings") {
            val dvr = runtime.dvr
            if (dvr == null) seeOther(call, "/admin") else call.html(recordingsPage(chrome(), recordingsView(dvr)))
        }
        route.recording("/recordings/rules") { dvr, session, form ->
            try {
                val rule = dvr.rules.create(
                    CreateRecordingRule(
                        form["title"].orEmpty(),
                        form["channelId"]?.takeIf { it.isNotBlank() },
                        form["newOnly"] == "true",
                        formInt(form, "keep", "Keep"),
                        formInt(form, "startPadding", "Start early", 1) * 60,
                        formInt(form, "endPadding", "Run late", 2) * 60,
                        formInt(form, "priority", "Priority", 0),
                    ),
                )
                session.flash.set(
                    Flash("Added the rule for ${rule.title}; the schedule updates in a moment.", target = rule.id),
                )
            } catch (e: ApiFailure) {
                session.flash.set(Flash("Not added: ${e.message}", Tone.BAD))
            }
            seeOther(call, "/admin/recordings#rules")
        }
        route.recording("/recordings/rules/{id}") { dvr, session, form -> updateRule(call, dvr, session, form) }
        route.recording("/recordings/manual") { dvr, session, form -> schedule(call, dvr, session, form) }
        route.recording("/recordings/rules/{id}/delete") { dvr, session, _ ->
            try {
                dvr.rules.delete(call.parameters["id"]!!)
                session.flash.set(Flash("Deleted the rule and its scheduled recordings; what it recorded stays."))
            } catch (_: ApiFailure) {
                session.flash.set(Flash("That rule is already gone.", Tone.WARN))
            }
            seeOther(call, "/admin/recordings#rules")
        }
        route.recording("/recordings/{id}/protect") { dvr, session, form -> protect(call, dvr, session, form) }
        route.recording("/recordings/{id}/delete") { dvr, session, _ -> delete(call, dvr, session) }
    }

    /** An action that needs recordings on; without them it goes home. */
    private fun Route.recording(path: String, body: suspend RoutingContext.(Dvr, AdminSession, Parameters) -> Unit) =
        with(portal) {
            action(path) { session, form ->
                val dvr = runtime.dvr
                if (dvr == null) seeOther(call, "/admin") else body(dvr, session, form)
            }
        }

    private suspend fun updateRule(call: ApplicationCall, dvr: Dvr, session: AdminSession, form: Parameters) {
        val id = call.parameters["id"]!!
        try {
            val channel = form["channelId"]?.takeIf { it.isNotBlank() }
            val rule = dvr.rules.update(
                id,
                UpdateRecordingRule(
                    channelId = channel,
                    anyChannel = channel == null,
                    newOnly = form["newOnly"] == "true",
                    keep = formInt(form, "keep", "Keep"),
                    startPaddingSeconds = formInt(form, "startPadding", "Start early") * 60,
                    endPaddingSeconds = formInt(form, "endPadding", "Run late") * 60,
                    priority = formInt(form, "priority", "Priority"),
                ),
            )
            session.flash.set(
                Flash("Saved the rule for ${rule.title}; the schedule updates in a moment.", target = rule.id),
            )
        } catch (e: ApiFailure) {
            session.flash.set(Flash("Not saved: ${e.message}", Tone.BAD, target = id))
        }
        seeOther(call, "/admin/recordings#rules")
    }

    private suspend fun schedule(call: ApplicationCall, dvr: Dvr, session: AdminSession, form: Parameters) {
        try {
            val start = try {
                LocalDateTime.parse(form["start"].orEmpty()).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: DateTimeParseException) {
                throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Pick a start date and time")
            }
            val minutes = formInt(form, "minutes", "Minutes")
            if (minutes !in MANUAL_MINUTES) {
                throw ApiFailure(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    "Minutes must be 1 to ${MANUAL_MINUTES.last}",
                )
            }
            val recording = dvr.schedule(
                ScheduleRecording(
                    form["channelId"].orEmpty(),
                    start.toString(),
                    start.plusSeconds(minutes * 60L).toString(),
                    form["title"]?.takeIf { it.isNotBlank() },
                ),
            )
            session.flash.set(Flash("Scheduled ${recording.title}.", target = recording.id))
        } catch (e: ApiFailure) {
            session.flash.set(Flash("Not scheduled: ${e.message}", Tone.BAD))
        }
        seeOther(call, "/admin/recordings#upcoming")
    }

    private suspend fun protect(call: ApplicationCall, dvr: Dvr, session: AdminSession, form: Parameters) {
        val id = call.parameters["id"]!!
        try {
            val recording = dvr.update(id, UpdateRecording(protected = form["protected"] == "true"))
            val message = if (recording.protected) {
                "Protected ${recording.title}; cleanup keeps it."
            } else {
                "Cleanup may delete ${recording.title} again."
            }
            session.flash.set(Flash(message, target = id))
        } catch (_: ApiFailure) {
            session.flash.set(Flash("That recording is gone.", Tone.WARN))
        }
        seeOther(call, "/admin/recordings#recorded")
    }

    private suspend fun delete(call: ApplicationCall, dvr: Dvr, session: AdminSession) {
        val id = call.parameters["id"]!!
        try {
            val recording = dvr.recording(id)
            dvr.delete(id)
            val message = when (recording.state) {
                Wire.RecordingState.SCHEDULED -> "Canceled ${recording.title}."
                Wire.RecordingState.RECORDING -> "Stopped ${recording.title}; what it recorded is kept."
                else -> "Deleted ${recording.title}."
            }
            session.flash.set(Flash(message))
        } catch (_: ApiFailure) {
            session.flash.set(Flash("That recording is already gone.", Tone.WARN))
        }
        seeOther(call, "/admin/recordings")
    }

    /** The recordings and rules, with the channels a rule may name and what cleanup keeps. */
    private suspend fun recordingsView(dvr: Dvr): RecordingsView {
        val enabled = runtime.lineup.entries().filter { it.enabled }
            .sortedBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }
        val values = runtime.settings.current
        return RecordingsView(
            dvr.recordings(),
            dvr.rules.list(),
            enabled.filter { it.guide != null }.associate { it.id to "${it.number} ${it.name}" },
            dvr.root.toString(),
            Retention(values.recordingsFreeGib, values.recordingsKeepDays),
            enabled.associate { it.id to "${it.number} ${it.name}" },
        )
    }
}

/** A manual recording's length, up to 12 hours. */
internal val MANUAL_MINUTES = 1..720

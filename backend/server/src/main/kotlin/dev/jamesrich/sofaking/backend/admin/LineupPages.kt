package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.api.UpdateSource
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parameters
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.query
import io.ktor.server.routing.route

/** The Channels list and each channel's page, and the edits made from them. */
internal class LineupPages(private val portal: Portal, editor: PolicyEditor) {
    private val runtime get() = portal.runtime
    private val reader = LineupReader(portal)
    private val bulk = BulkEdit(portal, reader, editor)

    fun install(route: Route) = with(portal) {
        route.get("/lineup") {
            val view = reader.lineupView(LineupFilter.from(call.request.queryParameters))
            val selectAll = call.request.queryParameters["select"] == "page"
            call.html(lineupPage(chrome(), if (selectAll) LineupView(view, selectAll = true) else view))
        }
        route.get("/lineup/{id}") {
            val view = reader.stationView(call.parameters["id"]!!, LineupFilter.from(call.request.queryParameters))
            // 404 would be replaced by the API's JSON error page.
            if (view == null) {
                call.html(goneStationPage(chrome()), HttpStatusCode.Gone)
            } else {
                call.html(stationPage(chrome(), view))
            }
        }
        route.action("/lineup/{id}/visibility") { session, form ->
            val enabled = form["enabled"]?.toBooleanStrictOrNull()
                ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "enabled must be true or false")
            changeEntry(call, session, form, if (enabled) "Showing" else "Hid") { UpdateLineupEntry(enabled = enabled) }
        }
        route.action("/lineup/{id}/edit") { session, form ->
            changeEntry(call, session, form, "Saved") { entry ->
                val name = form["name"]?.trim().orEmpty()
                val number = form["number"]?.trim().orEmpty()
                val group = form["group"]?.trim()
                UpdateLineupEntry(
                    name = name.takeIf { it.isNotEmpty() && it != entry.name },
                    number = number.takeIf { it.isNotEmpty() && it != entry.number },
                    group = group?.takeIf { it.isNotEmpty() && it != entry.group },
                    clearGroup = group != null && group.isEmpty() && entry.group != null,
                )
            }
        }
        route.action("/lineup/{id}/sources/{offeringId}/disable") { session, form -> disable(call, session, form) }
        route.action("/lineup/{id}/sources/{offeringId}/probe") { session, form -> probe(call, session, form) }
        route.action("/lineup/bulk") { session, form -> bulk.edit(call, session, form) }
    }

    /**
     * Changes one lineup entry the way `PATCH /api/v1/admin/lineup/{id}` does: through the policy, or through the
     * lineup without one. Portal edits touch one station's fields, so they apply to the file as it is now rather than
     * to the revision the page showed; background runs write the file often.
     */
    private suspend fun changeEntry(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        verb: String,
        request: (LineupEntryRecord) -> UpdateLineupEntry,
    ) {
        val id = call.parameters["id"]!!
        val filter = LineupFilter.from(form)
        val name = runtime.lineup.entry(id)?.name
        val failure = try {
            update(id, request)
            null
        } catch (e: ApiFailure) {
            e
        }
        val query = queryOf(filter)
        if (failure == null) {
            session.flash.set(Flash("$verb ${runtime.lineup.entry(id)?.name ?: name}.", Tone.OK, id))
            seeOther(call, if (form["from"] == "station") "/admin/lineup/$id$query" else "/admin/lineup$query")
            return
        }
        val fromStation = form["from"] == "station" || form["name"] != null
        val station = if (fromStation) reader.stationView(id, filter, failure.message) else null
        if (station != null) {
            call.html(stationPage(portal.chrome(call, session), station), failure.status)
        } else {
            val flash = Flash("Not saved: ${failure.message}", Tone.BAD, id)
            call.html(lineupPage(portal.chrome(call, session, flash), reader.lineupView(filter)), failure.status)
        }
    }

    private suspend fun update(id: String, request: (LineupEntryRecord) -> UpdateLineupEntry) {
        val entry = runtime.lineup.entry(id)
            ?: throw ApiFailure(
                HttpStatusCode.NotFound,
                "LINEUP_ENTRY_NOT_FOUND",
                "That station is no longer in the lineup",
            )
        val update = request(entry)
        if (update == UpdateLineupEntry()) return
        val policy = runtime.policy
        if (policy == null) {
            runtime.lineup.update(id, update)
        } else {
            policy.edits.updateStation(id, update, null)
                ?: throw ApiFailure(HttpStatusCode.NotFound, "LINEUP_ENTRY_NOT_FOUND", "The station left the lineup")
        }
    }

    private suspend fun disable(call: ApplicationCall, session: AdminSession, form: Parameters) {
        val id = call.parameters["id"]!!
        val offeringId = call.parameters["offeringId"]!!
        val filter = LineupFilter.from(form)
        val disabled = form["disabled"]?.toBooleanStrictOrNull()
            ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "disabled must be true or false")
        val back = "/admin/lineup/$id${queryOf(filter)}"
        val view = reader.stationView(id, filter)
        val source = view?.sources?.firstOrNull { it.id == offeringId }
        when {
            view == null -> call.html(goneStationPage(portal.chrome(call, session)), HttpStatusCode.Gone)

            source == null -> {
                session.flash.set(Flash("That source no longer carries the channel.", Tone.WARN))
                seeOther(call, back)
            }

            disabled && form["confirm"] != "yes" ->
                call.html(stationPage(portal.chrome(call, session), StationView(view, disableConfirm = source)))

            else -> {
                runtime.feeds.updateSource(offeringId, UpdateSource(manuallyDisabled = disabled))
                runtime.sessions.healthChanged(offeringId)
                val verb = if (disabled) "Disabled" else "Enabled"
                session.flash.set(Flash("$verb the source from ${source.provider}.", Tone.OK, offeringId))
                seeOther(call, back)
            }
        }
    }

    private suspend fun probe(call: ApplicationCall, session: AdminSession, form: Parameters) {
        val id = call.parameters["id"]!!
        val offeringId = call.parameters["offeringId"]!!
        val probes = runtime.probes
        session.flash.set(
            when {
                probes == null -> Flash("Probes are off on this server.", Tone.WARN, offeringId)

                probes.probeNow(offeringId) ->
                    Flash("Queued a probe; the health updates when it is done.", Tone.OK, offeringId)

                else -> Flash("A probe of that source is already running, or it is gone.", Tone.WARN, offeringId)
            },
        )
        seeOther(call, "/admin/lineup/$id${queryOf(LineupFilter.from(form))}")
    }
}

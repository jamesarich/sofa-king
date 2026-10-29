package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.dvr.Dvr
import dev.jamesrich.sofaking.backend.dvr.PRIORITY_RANGE
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.RecordingRule
import dev.jamesrich.sofaking.contract.UpdateRecordingRule
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.Duration
import java.time.Instant

/** How far back the Wanted page looks for recordings that failed. */
private val MISSED_FOR: Duration = Duration.ofDays(14)

/** What the DVR wanted and did not get: the recent failures, and the scheduled recordings that lose their slot. */
internal class WantedView(
    val missed: List<Recording>,
    val conflicts: List<Recording>,
    val rules: Map<String, RecordingRule>,
) {
    /** Whether another rule ties or outranks [rule], and its priority can still go up. */
    fun canPutFirst(rule: RecordingRule): Boolean = rule.priority < PRIORITY_RANGE.last &&
        rules.values.any { it.id != rule.id && it.priority >= rule.priority }
}

/** The Recordings section's Wanted page and its one action, putting a rule first. */
internal class WantedPages(private val portal: Portal) {
    fun install(route: Route) = with(portal) {
        route.get("/recordings/wanted") {
            val dvr = runtime.dvr
            if (dvr == null) seeOther(call, "/admin") else call.html(wantedPage(chrome(), view(dvr)))
        }
        route.action("/recordings/wanted/rules/{id}/first") { session, _ ->
            val dvr = runtime.dvr ?: return@action seeOther(call, "/admin")
            val id = call.parameters["id"]!!
            val flash = try {
                val rules = dvr.rules.list()
                val own = rules.firstOrNull { it.id == id }?.priority ?: 0
                val top = rules.filter { it.id != id }.maxOfOrNull { it.priority } ?: own
                val priority = maxOf(own, top + 1).coerceAtMost(PRIORITY_RANGE.last)
                val rule = dvr.rules.update(id, UpdateRecordingRule(priority = priority))
                Flash("${rule.title} now has priority $priority; the schedule updates in a moment.", target = id)
            } catch (e: ApiFailure) {
                Flash("Not changed: ${e.message}", Tone.BAD)
            }
            session.flash.set(flash)
            seeOther(call, "/admin/recordings/wanted#conflicts")
        }
        route.action("/recordings/wanted/{id}/delete") { session, form ->
            val dvr = runtime.dvr ?: return@action seeOther(call, "/admin")
            val id = call.parameters["id"]!!
            val recording = try {
                dvr.recording(id)
            } catch (_: ApiFailure) {
                session.flash.set(Flash("That recording is already gone.", Tone.WARN))
                return@action seeOther(call, "/admin/recordings/wanted")
            }
            val scheduled = recording.state == Wire.RecordingState.SCHEDULED
            val ask = Ask(
                "Cancel ${recording.title}?",
                "It loses its slot to other recordings and is not recorded. Schedule it by hand to bring it back.",
                "/admin/recordings/wanted/$id/delete",
                "/admin/recordings/wanted#conflicts",
                "Cancel recording",
            )
            val remove: suspend () -> Unit = {
                val message = try {
                    dvr.delete(id)
                    if (scheduled) "Canceled ${recording.title}." else "Deleted ${recording.title}."
                } catch (_: ApiFailure) {
                    "That recording is already gone."
                }
                session.flash.set(Flash(message))
                seeOther(call, "/admin/recordings/wanted")
            }
            if (scheduled) {
                call.confirmed(session, form, ask, { wantedPage(it, view(dvr)) }, remove)
            } else {
                remove()
            }
        }
    }

    private suspend fun view(dvr: Dvr): WantedView {
        val now = portal.clock.instant()
        val recordings = dvr.recordings().recordings
        val rules = dvr.rules.list()
        val since = now.minus(MISSED_FOR)
        val missed = recordings.filter {
            it.state == Wire.RecordingState.FAILED && (startOf(it)?.isAfter(since) ?: false)
        }.sortedByDescending { startOf(it) }
        val conflicts = recordings.filter { it.conflict && it.state == Wire.RecordingState.SCHEDULED }
            .sortedBy { startOf(it) }
        return WantedView(missed, conflicts, rules.associateBy { it.id })
    }
}

private fun startOf(recording: Recording): Instant? = runCatching { Instant.parse(recording.startsAt) }.getOrNull()

/** Why a recording failed, in words, from its reason code. */
internal fun missedWhy(reason: String?): String = when (reason) {
    "MISSED" -> "It never started: the server was down, or every recording slot was taken"
    Dvr.DISK_FULL -> "The recordings disk filled up"
    "NO_MEDIA" -> "The channel sent nothing to record"
    null -> "It failed"
    else -> "The channel's source failed ($reason)"
}

internal fun wantedPage(chrome: Chrome, view: WantedView): String =
    layout("Wanted", chrome, "/admin/recordings/wanted") {
        pageHead(
            "Wanted",
            "What the DVR meant to record and did not: the recordings that failed in the last " +
                "${MISSED_FOR.toDays()} days, and the scheduled ones that lose their slot to others airing at once.",
        )
        tabs(chrome, "/admin/recordings/wanted", RECORDING_TABS)
        conflictsPanel(chrome, view)
        missedPanel(chrome, view)
    }

private fun FlowContent.conflictsPanel(chrome: Chrome, view: WantedView) = panel("Conflicts", id = "conflicts") {
    if (view.conflicts.isEmpty()) {
        p("muted mt-0") { +"Every scheduled recording has a slot." }
        return@panel
    }
    p("muted mt-0") {
        +(
            "A one-off recording outranks every rule, a rule with a higher priority outranks a lower one, and the " +
                "earlier-made recording wins a tie. Put a rule first to give its recordings the slot."
            )
    }
    wantedTable(chrome, view, view.conflicts, "Recordings that lose their slot", "When" to "Action") { recording ->
        val rule = recording.ruleId?.let(view.rules::get)
        when {
            rule == null -> wantedDelete(chrome, recording, "Cancel recording")

            !view.canPutFirst(rule) -> span("muted small") { +"Already first" }

            else -> postForm("/admin/recordings/wanted/rules/${rule.id}/first", chrome.session) {
                button(type = ButtonType.submit, classes = "small") {
                    attributes["aria-label"] = "Put ${rule.title} first"
                    +"Put the rule first"
                }
            }
        }
    }
}

private fun FlowContent.missedPanel(chrome: Chrome, view: WantedView) = panel("Missed", id = "missed") {
    if (view.missed.isEmpty()) {
        p("muted mt-0") { +"Nothing failed in the last ${MISSED_FOR.toDays()} days." }
        return@panel
    }
    wantedTable(chrome, view, view.missed, "Recordings that failed", "Aired" to "Why") { recording ->
        span("small") { +missedWhy(recording.reason) }
        wantedDelete(chrome, recording, "Delete")
    }
}

private fun FlowContent.wantedDelete(chrome: Chrome, recording: Recording, label: String) =
    postForm("/admin/recordings/wanted/${recording.id}/delete", chrome.session) {
        button(type = ButtonType.submit, classes = "small") {
            attributes["aria-label"] = "$label ${recording.title}"
            +label
        }
    }

private fun FlowContent.wantedTable(
    chrome: Chrome,
    view: WantedView,
    recordings: List<Recording>,
    label: String,
    columns: Pair<String, String>,
    last: FlowContent.(Recording) -> Unit,
) = div("scroll") {
    table {
        caption("sr") { +label }
        thead {
            tr {
                col("Program")
                col("Channel")
                col(columns.first)
                col("Rule")
                col(columns.second)
            }
        }
        tbody {
            for (recording in recordings) {
                tr(flashClass(chrome, recording.ruleId.orEmpty())) {
                    th {
                        attributes["scope"] = "row"
                        +recordingName(recording)
                    }
                    td { +recording.channelName.ifEmpty { recording.channelId } }
                    td("nowrap") { ago(recording.startsAt, chrome.now) }
                    td {
                        val rule = recording.ruleId?.let(view.rules::get)
                        if (rule == null) {
                            span("muted") { +"–" }
                        } else {
                            +rule.title
                            br()
                            span("muted small") { +"priority ${rule.priority}" }
                        }
                    }
                    td { last(recording) }
                }
            }
        }
    }
}

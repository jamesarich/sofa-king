package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.contract.Recording
import io.ktor.http.Parameters
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.li
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.ul
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** How many days the agenda covers. */
private const val AGENDA_DAYS = 30L
private const val WEEK_DAYS = 7L

private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** One recording on the calendar, with the rule that scheduled it when there is one. */
internal class CalendarEntry(val recording: Recording, val starts: Instant, val ends: Instant, val rule: String?)

/**
 * The recordings calendar: [agenda] lists [AGENDA_DAYS] days from [start], else the week from [start] shows as seven
 * days; times are in [zone].
 */
internal class CalendarView(
    val entries: List<CalendarEntry>,
    val start: LocalDate,
    val today: LocalDate,
    val agenda: Boolean,
    val zone: ZoneId,
) {
    val days: Long get() = if (agenda) AGENDA_DAYS else WEEK_DAYS

    /** The entries that start on [day], earliest first. */
    fun on(day: LocalDate): List<CalendarEntry> =
        entries.filter { it.starts.atZone(zone).toLocalDate() == day }.sortedBy { it.starts }

    companion object {
        /**
         * The view [query] asks for: `view=agenda` or the week, from `start` (a date) or today; [recordings] become
         * entries with their rules' titles from [rules].
         */
        fun of(
            query: Parameters,
            recordings: List<Recording>,
            rules: Map<String, String>,
            now: Instant,
            zone: ZoneId,
        ): CalendarView {
            val today = now.atZone(zone).toLocalDate()
            val start = query["start"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
            val entries = recordings.mapNotNull { recording ->
                val starts = runCatching { Instant.parse(recording.startsAt) }.getOrNull() ?: return@mapNotNull null
                val ends = runCatching { Instant.parse(recording.endsAt) }.getOrNull() ?: starts
                CalendarEntry(recording, starts, ends, recording.ruleId?.let(rules::get))
            }
            return CalendarView(entries, start, today, query["view"] == "agenda", zone)
        }
    }
}

internal fun calendarPage(chrome: Chrome, view: CalendarView): String =
    layout("Calendar", chrome, "/admin/recordings/calendar") {
        pageHead(
            "Calendar",
            "When the DVR records: the recordings series rules and you scheduled, the ones running and the ones " +
                "done, by day, in ${view.zone.id} time.",
        )
        tabs(chrome, "/admin/recordings/calendar", RECORDING_TABS)
        calendarNav(view)
        if (view.agenda) agenda(view) else week(view)
        panel("Subscribe", id = "subscribe") {
            p("mt-0") {
                +"Calendar apps take the same schedule from "
                code { +"/output/recordings.ics?token=TOKEN" }
                +" on this server, with a read token from "
                a("/admin/settings#tokens") { +"Settings, Access tokens" }
                +". The app refreshes it on its own."
            }
        }
    }

private fun FlowContent.calendarNav(view: CalendarView) = nav("actions calendar-nav") {
    attributes["aria-label"] = "Calendar"
    val kind = if (view.agenda) "agenda" else "week"
    fun href(start: LocalDate, viewKind: String = kind) = "/admin/recordings/calendar?view=$viewKind&start=$start"
    a(href(view.start.minusDays(view.days)), classes = "btn small") { +"Earlier" }
    a(href(view.today), classes = "btn small") { +"Today" }
    a(href(view.start.plusDays(view.days)), classes = "btn small") { +"Later" }
    span("muted small") {
        +" ${dayLabel(view.start)} to ${dayLabel(view.start.plusDays(view.days - 1))} "
    }
    if (view.agenda) {
        a(href(view.start, "week"), classes = "btn small quiet") { +"Week" }
    } else {
        a(href(view.start, "agenda"), classes = "btn small quiet") { +"Agenda" }
    }
}

private fun FlowContent.week(view: CalendarView) = div("calendar") {
    for (offset in 0 until view.days) {
        val day = view.start.plusDays(offset)
        section("day" + if (day == view.today) " today" else "") {
            h3 { +dayLabel(day) }
            val entries = view.on(day)
            if (entries.isEmpty()) {
                p("muted small") { +"Nothing" }
            } else {
                ul("events") { for (entry in entries) li { event(entry, view.zone) } }
            }
        }
    }
}

private fun FlowContent.agenda(view: CalendarView) = panel(null) {
    val days = (0 until view.days).map { view.start.plusDays(it) }.filter { view.on(it).isNotEmpty() }
    if (days.isEmpty()) {
        p("muted mt-0") { +"Nothing recorded or scheduled in these ${view.days} days." }
        return@panel
    }
    for (day in days) {
        section("agenda-day") {
            h3 { +dayLabel(day) }
            ul("events") { for (entry in view.on(day)) li { event(entry, view.zone) } }
        }
    }
}

/** One recording: its time, what it is, its channel and rule, and its state's chip, tinted by the state. */
private fun FlowContent.event(entry: CalendarEntry, zone: ZoneId) {
    val (state, tone) = stateChip(entry.recording)
    div("event ${tone.css}") {
        span("small num") {
            +"${TIME.format(entry.starts.atZone(zone))}–${TIME.format(entry.ends.atZone(zone))} "
        }
        chip(state, tone)
        div { +recordingName(entry.recording) }
        div("muted small") {
            +entry.recording.channelName
            entry.rule?.let { +" · rule: $it" }
        }
    }
}

/** A day as "Mon, Oct 26". */
private fun dayLabel(day: LocalDate): String = "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)}, " +
    "${day.month.getDisplayName(TextStyle.SHORT, Locale.US)} ${day.dayOfMonth}"

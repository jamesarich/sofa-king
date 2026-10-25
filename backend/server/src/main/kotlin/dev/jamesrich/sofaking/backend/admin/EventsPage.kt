package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.logging.LogEvent
import io.ktor.http.formUrlEncode
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.q
import kotlinx.html.role
import kotlinx.html.s
import kotlinx.html.searchInput
import kotlinx.html.select
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import java.time.Instant

internal fun eventsPage(chrome: Chrome, view: EventsView): String = layout("Events", chrome, "/admin/system/events") {
    val now = chrome.now
    pageHead(
        "Events",
        "The newest ${thousands(view.capacity)} log lines at INFO and above since the server started, newest first. " +
            "The same lines go to the container's log.",
    )
    tabs(chrome, "/admin/system/events", SYSTEM_TABS)
    logPanel(chrome, view)
    eventFilters(view)
    val query = listOf("level" to view.level, "q" to view.q).filter { it.second.isNotEmpty() }
        .takeIf { it.isNotEmpty() }?.let { "?" + it.formUrlEncode() }.orEmpty()
    div {
        id = "events"
        attributes["hx-get"] = "/admin/system/events$query"
        attributes["hx-trigger"] = "every 10s"
        attributes["hx-select"] = "#events"
        attributes["hx-target"] = "this"
        attributes["hx-swap"] = "outerHTML"
        div("summary") {
            attributes["role"] = "status"
            span { +"${thousands(view.events.size)} of ${thousands(view.kept)} kept lines" }
            span("muted small") {
                +"Refreshes every 10 s with JavaScript; "
                a("/admin/system/events$query") { +"refresh now" }
                +"."
            }
        }
        if (view.events.isEmpty()) {
            div("panel") {
                p("muted") {
                    +(
                        if (view.kept == 0) {
                            "Nothing has been logged yet."
                        } else {
                            "No line matches these filters."
                        }
                        )
                }
            }
        } else {
            eventTable(view.events, now)
        }
    }
}

private fun FlowContent.eventFilters(view: EventsView) =
    form(action = "/admin/system/events", method = FormMethod.get, classes = "filters events-filters") {
        attributes["hx-trigger"] = "submit, change, input changed delay:400ms from:find input[type=search]"
        attributes["hx-sync"] = "this:replace"
        div("field wide") {
            label {
                htmlFor = "q"
                +"Search"
            }
            searchInput(name = "q") {
                id = "q"
                value = view.q
                placeholder = "Logger or message"
                attributes["autocomplete"] =
                    "off"
            }
        }
        div("field") {
            label {
                htmlFor = "f-level"
                +"Level"
            }
            select {
                id = "f-level"
                name = "level"
                for ((value, label) in listOf(
                    "" to "Info and above",
                    "warn" to "Warnings and errors",
                    "error" to "Errors only",
                )) {
                    option {
                        this.value = value
                        selected = value == view.level
                        +label
                    }
                }
            }
        }
        div("actions") {
            button(type = ButtonType.submit) { +"Filter" }
            if (view.level.isNotEmpty() || view.q.isNotEmpty()) {
                a("/admin/system/events", classes = "btn quiet") { +"Clear" }
            }
        }
    }

private fun FlowContent.eventTable(events: List<LogEvent>, now: Instant) = div("panel tight") {
    div("scroll") {
        table("events") {
            caption("sr") { +"Log events, newest first" }
            thead {
                tr {
                    col("When")
                    col("Level")
                    col("Logger")
                    col("Message")
                }
            }
            tbody {
                for (event in events) {
                    tr {
                        td("nowrap") { ago(event.at.toString(), now) }
                        td {
                            chip(
                                event.level,
                                when (event.level) {
                                    "ERROR" -> Tone.BAD
                                    "WARN" -> Tone.WARN
                                    else -> Tone.OFF
                                },
                            )
                        }
                        td("mono small nowrap") {
                            attributes["title"] = event.logger
                            +event.logger.substringAfterLast('.')
                        }
                        td("message") {
                            +event.message
                            if (event.requestId != null) {
                                span("muted small mono") {
                                    +" "
                                    +event.requestId
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

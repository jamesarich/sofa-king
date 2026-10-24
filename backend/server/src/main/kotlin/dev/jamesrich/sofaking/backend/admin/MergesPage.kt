package dev.jamesrich.sofaking.backend.admin

import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.map
import kotlinx.html.p
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul

internal fun mergesPage(chrome: Chrome, view: MergesView?, error: String? = null): String =
    layout("Merges", chrome, "/admin/merges") {
        pageHead(
            "Merges",
            "Stations that are probably one channel under two names. Accepting writes an alias into the policy; " +
                "dismissing or never merging keeps the pair apart for good. Each choice applies the policy.",
        )
        if (view == null) {
            noPolicy("Merging stations")
            return@layout
        }
        tabs(chrome, "/admin/merges", CHANNEL_TABS)
        notice(error?.let { "Not saved: $it" })
        suggestedPanel(chrome, view)
        panel("Open findings", id = "findings", aside = { span("muted") { +plural(view.findings.size, "finding") } }) {
            if (view.findings.isEmpty()) {
                p("muted") { +"No open findings." }
            } else {
                ul { for (finding in view.findings) li { +finding } }
            }
        }
        if (view.unmapped.isNotEmpty()) {
            div("notice warn") {
                +"${plural(view.unmapped.size, "category", "categories")} map to no group. "
                a("/admin/groups#unmapped") { +"Map them on Groups" }
            }
        }
        div("cols") {
            learnedPanel(chrome, view)
            panel("Kept apart") {
                if (view.never.isEmpty()) {
                    p("muted") { +"No pair is kept apart." }
                } else {
                    ul { for (pair in view.never) li { +pair.joinToString(" and ") } }
                }
            }
        }
    }

/** A button that posts one pair of station keys to [action]. */
private fun FlowContent.pairForm(
    chrome: Chrome,
    view: MergesView,
    action: String,
    pair: Pair<String, String>,
    label: String,
    classes: String,
) = postForm(action, chrome.session, keepScroll = true) {
    val (from, to) = pair
    hiddenInput(name = "revision") { value = view.revision }
    hiddenInput(name = "from") { value = from }
    hiddenInput(name = "to") { value = to }
    button(type = ButtonType.submit, classes = classes) {
        attributes["aria-label"] = "$label $from and $to"
        +label
    }
}

private fun FlowContent.suggestedPanel(chrome: Chrome, view: MergesView) = panel(
    "Suggested aliases",
    id = "suggested",
    aside = { span("muted") { +plural(view.suggestions.size, "suggestion") } },
) {
    if (view.suggestions.isEmpty()) {
        p("muted") {
            +(
                "No suggestions. Runs suggest a pair when two live stations' names differ only by a trailing " +
                    "TV or Channel, or match loosely without a schedule to compare."
                )
        }
    } else {
        div("scroll") {
            table {
                caption("sr") { +"Suggested aliases" }
                thead {
                    tr {
                        col("Station key")
                        col("Probably the same as")
                        col("Decide")
                    }
                }
                tbody {
                    for (pair in view.suggestions) {
                        tr {
                            td { code { +pair.first } }
                            td { code { +pair.second } }
                            td {
                                div("actions") {
                                    pairForm(chrome, view, "/admin/merges/accept", pair, "Accept", "small primary")
                                    pairForm(chrome, view, "/admin/merges/dismiss", pair, "Dismiss", "small")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.learnedPanel(chrome: Chrome, view: MergesView) =
    panel("Learned aliases", aside = { span("muted") { +plural(view.learned.size, "alias", "aliases") } }) {
        if (view.learned.isEmpty()) {
            p("muted") { +"No station has been merged by schedule." }
        } else {
            div("scroll") {
                table {
                    caption("sr") { +"Learned aliases" }
                    thead {
                        tr {
                            col("From")
                            col("Into")
                            col("Action", "sr")
                        }
                    }
                    tbody {
                        for (pair in view.learned.toList()) {
                            tr {
                                td { code { +pair.first } }
                                td { code { +pair.second } }
                                td { pairForm(chrome, view, "/admin/merges/never", pair, "Never merge", "small") }
                            }
                        }
                    }
                }
            }
        }
    }

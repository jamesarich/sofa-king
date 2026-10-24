package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.checkBoxInput
import kotlinx.html.classes
import kotlinx.html.dir
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.hidden
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.meta
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.role
import kotlinx.html.select
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr

internal fun lineupPage(chrome: Chrome, view: LineupView): String = layout("Channels", chrome, "/admin/lineup") {
    pageHead(
        "Channels",
        "${thousands(view.enabled)} on of ${thousands(view.total)}. " +
            if (view.revision != null) {
                "Edits are written into the lineup policy and applied at once."
            } else {
                "Edits change the lineup directly."
            },
    )
    if (view.revision != null) tabs(chrome, "/admin/lineup", CHANNEL_TABS)
    lineupFilters(view)
    lineupSummary(view)
    if (view.rows.isEmpty()) {
        div("panel") {
            p { +"No channel matches these filters." }
            a("/admin/lineup") { +"Show every channel" }
        }
    } else {
        div("panel tight") {
            // The row checkboxes belong to this form through their form attribute, so the
            // row actions stay their own forms.
            bulkForm(view, chrome.session)
            div("scroll") { lineupTable(chrome, view) }
        }
    }
    if (view.pages > 1) pager(view)
    confirmDialog(chrome, view.confirm)
}

private fun FlowContent.lineupSummary(view: LineupView) = div("summary") {
    attributes["role"] = "status"
    span {
        +(
            "${thousands(view.matches)} of ${thousands(view.total)} channels" +
                if (view.pages > 1) " · page ${view.filter.page} of ${view.pages}" else ""
            )
    }
    span("muted small legend") {
        span("swatch bad") { attributes["aria-hidden"] = "true" }
        +" no healthy source "
        span("swatch warn") { attributes["aria-hidden"] = "true" }
        +" no programs ahead"
    }
}

private fun DIV.bulkForm(view: LineupView, session: AdminSession) =
    form(action = "/admin/lineup/bulk", method = FormMethod.post, classes = "bulk") {
        val filter = view.filter
        id = "bulk"
        attributes["aria-label"] = "Selected channels"
        hiddenInput(name = "csrf") { value = session.csrf }
        view.revision?.let { revision -> hiddenInput(name = "revision") { value = revision } }
        filterFields(filter)
        span("label") { +"Selected:" }
        bulkButton("hide", "Hide")
        bulkButton("show", "Show")
        label("sr") {
            htmlFor = "bulk-group"
            +"Group"
        }
        select {
            id = "bulk-group"
            name = "target"
            option {
                value = ""
                +(if (view.revision != null) "Policy decides" else "No group")
            }
            for (group in view.groupChoices) {
                option {
                    value = group
                    +group
                }
            }
        }
        bulkButton("group", "Set group")
        span("muted small") {
            val query = filter.query()
            if (view.selectAll) {
                a("/admin/lineup" + query.let { if (it.isEmpty()) "" else "?$it" }) { +"Clear selection" }
            } else {
                a("/admin/lineup?" + listOf(query, "select=page").filter { it.isNotEmpty() }.joinToString("&")) {
                    +"Select all on this page"
                }
            }
        }
    }

private fun FORM.bulkButton(op: String, label: String) = button(type = ButtonType.submit, classes = "small") {
    name = "op"
    value = op
    +label
}

private fun FlowContent.lineupTable(chrome: Chrome, view: LineupView) = table("stack") {
    val filter = view.filter
    caption("sr") { +"Channels" }
    thead {
        tr {
            th(classes = "sel") {
                attributes["scope"] = "col"
                span("sr") { +"Select" }
            }
            fun TR.sortable(key: String, label: String, classes: String? = null) = th(classes = classes) {
                attributes["scope"] = "col"
                val sorted = filter.sort == key
                if (sorted) attributes["aria-sort"] = if (filter.dir == "desc") "descending" else "ascending"
                val descending = sorted && filter.dir != "desc"
                val next = filter.copy(sort = key, dir = if (descending) "desc" else "asc", page = 1)
                a("/admin/lineup?" + next.query(), classes = "sort") { +label }
            }
            sortable("number", "No.", "num")
            sortable("name", "Channel")
            sortable("group", "Group", "wide-only")
            sortable("sources", "Sources", "wide-only num")
            sortable("guide", "Guide", "wide-only")
            sortable("state", "State")
            col("Actions")
        }
    }
    tbody {
        // The notice about a row the operator just changed outranks its tint.
        for (row in view.rows) {
            tr(flashClass(chrome, row.entry.id) ?: row.tint?.let { "tint-${it.css}" }) { lineupRow(chrome, view, row) }
        }
    }
}

private fun TR.lineupRow(chrome: Chrome, view: LineupView, row: StationRow) {
    val entry = row.entry
    id = "row-${entry.id}"
    val edit = "/admin/lineup/${entry.id}" + view.filter.query().let { if (it.isEmpty()) "" else "?$it" }
    td("sel") {
        attributes["data-cell"] = "select"
        checkBoxInput(name = "id") {
            value = entry.id
            attributes["form"] = "bulk"
            attributes["aria-label"] = "Select ${entry.name}"
            checked = view.selectAll || entry.id in view.selected
        }
    }
    td("num") {
        attributes["data-cell"] = "number"
        +entry.number
    }
    td {
        attributes["data-cell"] = "name"
        a(edit, classes = "rowlink") { +entry.name }
        if (row.required) {
            +" "
            chip("Required", Tone.WARN)
        }
        if (row.overridden) {
            +" "
            chip("Override", Tone.OFF)
        }
    }
    detailCells(row)
    actionsCell(chrome, view, entry, edit)
}

/** The group, sources, guide and state cells; a narrow screen shows the first three as one line. */
private fun TR.detailCells(row: StationRow) {
    val entry = row.entry
    td("wide-only") { +(entry.group ?: "") }
    td("wide-only num") {
        attributes["title"] = "${row.healthy} healthy of ${entry.offeringIds.size}"
        +entry.offeringIds.size.toString()
        if (entry.offeringIds.isNotEmpty() && row.healthy == 0 && entry.enabled) {
            span("error-text") { +" (0 healthy)" }
        }
    }
    td("wide-only") { guideChip(row.guideStatus).let { (text, tone) -> chip(text, tone) } }
    td("narrow-only") {
        attributes["data-cell"] = "meta"
        +listOfNotNull(
            entry.group,
            plural(entry.offeringIds.size, "source"),
            guideChip(row.guideStatus).first.lowercase(),
        ).joinToString(" · ")
    }
    td {
        attributes["data-cell"] = "state"
        stateChip(row).let { (text, tone) ->
            span("chip ${tone.css}") {
                attributes["title"] = row.state
                +text
            }
        }
    }
}

private fun TR.actionsCell(chrome: Chrome, view: LineupView, entry: LineupEntryRecord, edit: String) {
    td {
        attributes["data-cell"] = "actions"
        div("actions") {
            rowForm("/admin/lineup/${entry.id}/visibility", chrome.session, view) {
                hiddenInput(name = "enabled") { value = (!entry.enabled).toString() }
                button(type = ButtonType.submit, classes = "small") {
                    attributes["aria-label"] = (if (entry.enabled) "Hide " else "Show ") + entry.name
                    +(if (entry.enabled) "Hide" else "Show")
                }
            }
            a(edit, classes = "btn small quiet") {
                attributes["aria-label"] = "Edit ${entry.name}"
                +"Edit"
            }
        }
    }
}

/** A row action: the CSRF token, the policy revision and the filters set, so the page comes back as it was. */
private fun FlowContent.rowForm(action: String, session: AdminSession, view: LineupView, block: FORM.() -> Unit) =
    form(action = action, method = FormMethod.post, classes = "inline") {
        attributes["hx-swap"] = "innerHTML show:none"
        hiddenInput(name = "csrf") { value = session.csrf }
        view.revision?.let { revision -> hiddenInput(name = "revision") { value = revision } }
        filterFields(view.filter)
        block()
    }

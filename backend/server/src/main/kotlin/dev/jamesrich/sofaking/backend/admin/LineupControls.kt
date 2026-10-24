package dev.jamesrich.sofaking.backend.admin

import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.NAV
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.classes
import kotlinx.html.dir
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.hidden
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.map
import kotlinx.html.nav
import kotlinx.html.option
import kotlinx.html.q
import kotlinx.html.s
import kotlinx.html.searchInput
import kotlinx.html.select
import kotlinx.html.span

internal fun FlowContent.lineupFilters(view: LineupView) {
    val filter = view.filter
    form(action = "/admin/lineup", method = FormMethod.get, classes = "filters") {
        attributes["hx-trigger"] = "submit, change, input changed delay:400ms from:find input[type=search]"
        attributes["hx-sync"] = "this:replace"
        div("field wide") {
            label {
                htmlFor = "q"
                +"Search"
            }
            searchInput(name = "q") {
                id = "q"
                value = filter.q
                placeholder = "Name, number or key"
                attributes["autocomplete"] = "off"
            }
        }
        val states = listOf("" to "Any state", "enabled" to "On", "hidden" to "Off") +
            if (view.revision != null) listOf("unverified" to "Unverified") else emptyList()
        filterSelect("f-group", "group", "Group", view.groups.map { it to it }, filter.group, blank = "All groups")
        filterSelect("f-state", "state", "State", states, filter.state)
        val guides = listOf("" to "Any guide", "available" to "Has programs", "missing" to "Missing or ended")
        filterSelect("f-guide", "guide", "Guide", guides, filter.guide)
        if (view.revision != null) {
            div("checks") {
                filterCheck("required", filter.required, "Required only")
                filterCheck("override", filter.override, "Has an override")
                filterCheck("changed", filter.changed, "Changed in the last run")
            }
        }
        if (filter.sort.isNotEmpty()) hiddenInput(name = "sort") { value = filter.sort }
        if (filter.dir.isNotEmpty()) hiddenInput(name = "dir") { value = filter.dir }
        div("actions") {
            button(type = ButtonType.submit) { +"Filter" }
            if (filter != LineupFilter()) a("/admin/lineup", classes = "btn quiet") { +"Clear" }
        }
    }
}

/** A filter's select, from its value and label [choices], with [current] selected; [blank] leads as the empty one. */
private fun FORM.filterSelect(
    id: String,
    name: String,
    label: String,
    choices: List<Pair<String, String>>,
    current: String,
    blank: String? = null,
) = div("field") {
    label {
        htmlFor = id
        +label
    }
    select {
        this.id = id
        this.name = name
        if (blank != null) {
            option {
                value = ""
                +blank
            }
        }
        for ((value, text) in choices) {
            option {
                this.value = value
                selected = value == current
                +text
            }
        }
    }
}

private fun DIV.filterCheck(name: String, checked: Boolean, label: String) = label("check") {
    checkBoxInput(name = name) {
        value = "true"
        this.checked = checked
    }
    +label
}

internal fun FlowContent.pager(view: LineupView) = nav("pager") {
    val filter = view.filter
    attributes["aria-label"] = "Pages"
    pageLink(filter.page > 1, filter.query(filter.page - 1), "prev", "Previous")
    span("muted") { +"Page ${filter.page} of ${view.pages}" }
    pageLink(filter.page < view.pages, filter.query(filter.page + 1), "next", "Next")
}

private fun NAV.pageLink(enabled: Boolean, query: String, rel: String, label: String) {
    if (enabled) {
        a("/admin/lineup?$query", classes = "btn") {
            attributes["rel"] = rel
            +label
        }
    } else {
        span("btn") {
            attributes["aria-disabled"] = "true"
            +label
        }
    }
}

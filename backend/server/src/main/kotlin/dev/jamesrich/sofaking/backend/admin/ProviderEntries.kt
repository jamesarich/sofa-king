package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.PolicyProvider
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.map
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.serialization.json.add

/**
 * The providers page's policy section: the entries in priority order, with the edits that
 * reorder, hide, add and remove them.
 */
internal fun MAIN.providerEntries(chrome: Chrome, view: ProvidersView, errors: EditErrors?, confirm: Confirm?) {
    val entries = view.entries ?: return
    val revision = view.revision ?: return
    val forms = EntryForms(chrome, revision)
    panel("Priority", id = "entries", aside = {
        span("muted") { +"The first entry that takes a channel leads its station" }
    }) {
        sectionErrors(errors, "entries")
        if (entries.isEmpty()) {
            p("muted") { +"The policy has no provider entries, so no channel joins the lineup except through pins." }
        } else {
            entryTable(forms, entries)
        }
        val listed = entries.filter { it.include == null && it.exclude == null }.map { it.id }.toSet()
        val addable = view.rows.map { it.status.id }.filter { it !in listed }.sorted()
        if (addable.isNotEmpty()) addEntryForm(forms, addable)
    }
    confirmDialog(chrome, confirm)
}

/** The forms of the priority section, which post one edit each against the policy [revision] the page showed. */
private class EntryForms(val chrome: Chrome, val revision: String)

private fun FlowContent.entryForm(forms: EntryForms, op: String, classes: String = "inline", block: FORM.() -> Unit) =
    editForm("/admin/providers/edit", forms.chrome, forms.revision, op, classes, block)

private fun FlowContent.entryTable(forms: EntryForms, entries: List<PolicyProvider>) = div("scroll") {
    table {
        caption("sr") { +"Provider entries, in priority order" }
        thead {
            tr {
                col("#", "num")
                col("Provider")
                col("Takes")
                col("Its stations")
                col("Order")
                col("Action", "sr")
            }
        }
        tbody {
            entries.forEachIndexed { index, entry ->
                tr {
                    td("num") { +(index + 1).toString() }
                    th {
                        attributes["scope"] = "row"
                        +entry.id
                    }
                    td("small") { +filterSummary(entry.include, entry.exclude) }
                    td { entryVisibility(forms, index, entry) }
                    td { entryOrder(forms, index, entry, last = index == entries.lastIndex) }
                    td {
                        entryForm(forms, "remove") {
                            hiddenInput(name = "index") { value = index.toString() }
                            button(type = ButtonType.submit, classes = "small quiet") {
                                attributes["aria-label"] = "Remove ${entry.id} entry ${index + 1}"
                                +"Remove"
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.entryVisibility(forms: EntryForms, index: Int, entry: PolicyProvider) =
    entryForm(forms, "enabled", classes = "actions") {
        hiddenInput(name = "index") { value = index.toString() }
        hiddenInput(name = "enabled") { value = (!entry.enabled).toString() }
        if (entry.enabled) chip("On", Tone.OK) else chip("Hidden", Tone.OFF)
        button(type = ButtonType.submit, classes = "small quiet") {
            attributes["aria-label"] = "${if (entry.enabled) "Hide" else "Show"} the stations entry ${index + 1} leads"
            +(if (entry.enabled) "Hide" else "Show")
        }
    }

private fun FlowContent.entryOrder(forms: EntryForms, index: Int, entry: PolicyProvider, last: Boolean) =
    div("actions") {
        for ((to, label) in listOf("up" to "↑", "down" to "↓")) {
            val disabled = (to == "up" && index == 0) || (to == "down" && last)
            entryForm(forms, "move") {
                hiddenInput(name = "index") { value = index.toString() }
                hiddenInput(name = "to") { value = to }
                button(type = ButtonType.submit, classes = "small") {
                    this.disabled = disabled
                    attributes["aria-label"] = "Move ${entry.id} entry ${index + 1} $to"
                    +label
                }
            }
        }
    }

private fun FlowContent.addEntryForm(forms: EntryForms, addable: List<String>) =
    entryForm(forms, "add", classes = "formgrid spaced") {
        div("field") {
            label {
                htmlFor = "e-add"
                +"Take every channel of"
            }
            select {
                name = "provider"
                id = "e-add"
                for (id in addable) {
                    option {
                        value = id
                        +id
                    }
                }
            }
        }
        div { button(type = ButtonType.submit) { +"Add entry last" } }
    }

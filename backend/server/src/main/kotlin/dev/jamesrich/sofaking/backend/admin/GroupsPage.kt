package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyUnmappedCategory
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.dataList
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.map
import kotlinx.html.mark
import kotlinx.html.numberInput
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.select
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

internal class BlockRow(val group: String, val start: Int, val end: Int, val on: Int, val total: Int)

internal class GroupsView(
    val revision: String,
    val policy: LineupPolicy,
    val blocks: List<BlockRow>,
    val groupNames: List<String>,
    val unmapped: List<PolicyUnmappedCategory>,
    val unblocked: List<Pair<String, Int>>,
)

internal fun FlowContent.groupSelect(
    choices: List<String>,
    selected: String? = null,
    id: String? = null,
    empty: String? = null,
) = select {
    name = "group"
    if (id != null) this.id = id
    required = empty == null
    if (empty != null) {
        option {
            value = ""
            this.selected = selected.isNullOrEmpty()
            +empty
        }
    }
    for (group in choices) {
        option {
            value = group
            this.selected = group == selected
            +group
        }
    }
}

/** A form of the groups page: one [op] of the groups edit, against the revision the page showed. */
internal fun FlowContent.groupEdit(
    chrome: Chrome,
    view: GroupsView,
    op: String,
    classes: String = "inline",
    block: FORM.() -> Unit,
) = editForm("/admin/groups/edit", chrome, view.revision, op, classes, block)

internal fun groupsPage(
    chrome: Chrome,
    view: GroupsView?,
    errors: EditErrors? = null,
    confirm: Confirm? = null,
): String = layout("Groups", chrome, "/admin/groups") {
    pageHead(
        "Groups",
        "How stations are grouped and numbered. Each change is written into the lineup policy and applied; a change " +
            "that moves channels asks first.",
    )
    if (view == null) {
        div("notice warn") {
            +"Groups need a lineup policy. Add policy.json to the data directory, or set SOFA_POLICY, and restart."
        }
        return@layout
    }
    tabs(chrome, "/admin/groups", CHANNEL_TABS)
    blocksPanel(chrome, view, errors)
    unmappedPanel(chrome, view, errors)
    mapPanel(chrome, view, errors)
    div("cols") {
        weakPanel(chrome, view, errors)
        patternsPanel(chrome, view, errors)
    }
    confirmDialog(chrome, confirm)
}

private fun FlowContent.blocksPanel(chrome: Chrome, view: GroupsView, errors: EditErrors?) =
    panel("Groups and number blocks", id = "blocks", aside = {
        span("muted") {
            +(
                "Blocks hold up to ${thousands(view.policy.numbering.groupSize)} numbers; off stations number from " +
                    "${thousands(view.policy.numbering.floor)}" +
                    if (view.policy.numbering.followGroup) "; stations follow their group" else ""
                )
        }
    }) {
        sectionErrors(errors, "blocks")
        if (view.blocks.isEmpty()) {
            p("muted") {
                +(
                    "No group has a number block, so numbers come from the providers or from " +
                        "${view.policy.numbering.floor} up."
                    )
            }
        } else {
            div("scroll") {
                table {
                    caption("sr") { +"Number blocks" }
                    thead {
                        tr {
                            col("Group")
                            col("Numbers", "num")
                            col("Channels on", "num")
                            col("First number")
                            col("Action", "sr")
                        }
                    }
                    tbody {
                        for (block in view.blocks) {
                            tr {
                                blockRow(chrome, view, block)
                            }
                        }
                    }
                }
            }
        }
        if (view.unblocked.isNotEmpty()) {
            p("muted small") {
                +"Groups in the lineup without a block: "
                +view.unblocked.joinToString { (group, count) -> "$group (${thousands(count)})" }
            }
        }
        blockForms(chrome, view)
        dataList {
            id = "group-names"
            for (group in view.groupNames) option { value = group }
        }
    }

private fun TR.blockRow(chrome: Chrome, view: GroupsView, block: BlockRow) {
    th {
        attributes["scope"] = "row"
        a(
            "/admin/lineup?group=" + java.net.URLEncoder.encode(
                block.group,
                Charsets.UTF_8,
            ),
            classes = "rowlink",
        ) {
            +block.group
        }
    }
    td("num nowrap") { +"${block.start}–${block.end}" }
    td("num") {
        +"${thousands(block.on)}"
        span("muted") { +" of ${thousands(block.total)}" }
    }
    td {
        groupEdit(chrome, view, "block", classes = "actions") {
            hiddenInput(name = "group") { value = block.group }
            numberInput(name = "start", classes = "w-number") {
                value = block.start.toString()
                min = "1"
                max = "999999"
                required = true
                attributes["aria-label"] = "First number of ${block.group}"
            }
            button(type = ButtonType.submit, classes = "small") { +"Save" }
        }
    }
    td {
        groupEdit(chrome, view, "block-remove") {
            hiddenInput(name = "group") { value = block.group }
            button(type = ButtonType.submit, classes = "small quiet") {
                attributes["aria-label"] =
                    "Remove the block of ${block.group}"
                +"Remove"
            }
        }
    }
}

private fun FlowContent.blockForms(chrome: Chrome, view: GroupsView) = div("stack spaced") {
    groupEdit(chrome, view, "block", classes = "formgrid") {
        div("field") {
            label {
                htmlFor = "b-group"
                +"New group"
            }
            textInput(name = "group") {
                id = "b-group"
                required = true
                maxLength = "128"
                attributes["list"] =
                    "group-names"
                placeholder = "Weather"
            }
        }
        div("field") {
            label {
                htmlFor = "b-start"
                +"First number"
            }
            numberInput(name = "start") {
                id = "b-start"
                required = true
                min = "1"
                max = "999999"
            }
        }
        div { button(type = ButtonType.submit) { +"Add block" } }
    }
    groupEdit(chrome, view, "default", classes = "formgrid") {
        div("field") {
            label {
                htmlFor = "d-group"
                +"Group for stations nothing groups"
            }
            groupSelect(view.groupNames, view.policy.groups.default, "d-group", empty = "Keep the provider's category")
        }
        div { button(type = ButtonType.submit) { +"Save default" } }
    }
}

private fun FlowContent.unmappedPanel(chrome: Chrome, view: GroupsView, errors: EditErrors?) =
    panel("Unmapped categories", id = "unmapped", aside = {
        span("muted") { +plural(view.unmapped.size, "category", "categories") }
    }) {
        sectionErrors(errors, "unmapped")
        if (view.unmapped.isEmpty()) {
            p("muted") { +"Every provider category maps to a group or is weak." }
            return@panel
        }
        p("muted small") {
            +(
                "Categories no map entry or pattern names, from the last run, most stations first. Map one to give " +
                    "its stations a vote, or mark it weak when it names no genre."
                )
        }
        div("scroll") {
            table {
                caption("sr") { +"Unmapped categories" }
                thead {
                    tr {
                        col("Category")
                        col("Provider")
                        col("Stations", "num")
                        col("Example")
                        col("Map to")
                        col("Action", "sr")
                    }
                }
                tbody {
                    for (row in view.unmapped) {
                        tr {
                            unmappedRow(chrome, view, row)
                        }
                    }
                }
            }
        }
    }

private fun TR.unmappedRow(chrome: Chrome, view: GroupsView, row: PolicyUnmappedCategory) {
    th {
        attributes["scope"] = "row"
        +row.category
    }
    td { +row.provider }
    td("num") { +thousands(row.stations) }
    td("muted") { +row.example }
    td {
        groupEdit(chrome, view, "map", classes = "actions") {
            hiddenInput(name = "category") { value = row.category }
            groupSelect(view.groupNames, empty = "Pick a group")
            button(type = ButtonType.submit, classes = "small") {
                attributes["aria-label"] =
                    "Map ${row.category}"
                +"Map"
            }
        }
    }
    td {
        groupEdit(chrome, view, "weak") {
            hiddenInput(name = "category") { value = row.category }
            button(type = ButtonType.submit, classes = "small quiet") {
                attributes["aria-label"] =
                    "Mark ${row.category} weak"
                +"Weak"
            }
        }
    }
}

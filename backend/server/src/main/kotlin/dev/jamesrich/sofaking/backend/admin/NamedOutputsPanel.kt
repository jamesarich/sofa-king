package dev.jamesrich.sofaking.backend.admin

import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.INPUT
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/** The named outputs, each a playlist and guide of some groups and channels, and a form to add or change one. */
internal fun FlowContent.namedOutputs(chrome: Chrome, rows: List<NamedOutputRow>) =
    panel("Named outputs", id = "named-outputs") {
        p("muted") {
            +(
                "A named output is a playlist and guide of chosen groups and channels, so a kids' room or a " +
                    "second player sees only those. Streams stay at the same URLs."
                )
        }
        if (rows.isNotEmpty()) outputTable(chrome, rows)
        postForm("/admin/plex/outputs/save", chrome.session, classes = "stack mt-12") {
            outputField("output-name", "name", "Name", "kids") { maxLength = "32" }
            outputField("output-groups", "groups", "Groups, comma-separated", "Kids, Cartoons") {}
            outputField("output-channels", "channels", "Channel numbers or IDs, comma-separated", "4.1, 702") {}
            div { button(type = ButtonType.submit) { +"Save output" } }
        }
        p("muted small") {
            +"Saving a name that exists replaces that output. Names take lowercase letters, digits and hyphens."
        }
    }

private fun FlowContent.outputTable(chrome: Chrome, rows: List<NamedOutputRow>) = div("scroll") {
    table {
        caption("sr") { +"Named outputs" }
        thead {
            tr {
                col("Name")
                col("Holds")
                col("URLs")
                col("Action")
            }
        }
        tbody {
            for (row in rows) {
                tr {
                    th {
                        attributes["scope"] = "row"
                        +row.output.name
                    }
                    td {
                        val holds = row.output.groups.map { "group $it" } + row.output.channels
                        +holds.joinToString()
                    }
                    td {
                        div { code { +row.playlist } }
                        div { code { +row.guide } }
                    }
                    td {
                        postForm("/admin/plex/outputs/delete", chrome.session) {
                            hiddenInput(name = "name") { value = row.output.name }
                            button(type = ButtonType.submit, classes = "small quiet") {
                                attributes["aria-label"] = "Delete ${row.output.name}"
                                +"Delete"
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.outputField(id: String, name: String, text: String, example: String, block: INPUT.() -> Unit) =
    div("field") {
        label {
            htmlFor = id
            +text
        }
        textInput(name = name) {
            this.id = id
            placeholder = example
            block()
        }
    }

package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.SECONDS_PER_MINUTE
import dev.jamesrich.sofaking.backend.dvr.Dvr
import dev.jamesrich.sofaking.backend.dvr.KEEP_RANGE
import dev.jamesrich.sofaking.backend.dvr.PRIORITY_RANGE
import dev.jamesrich.sofaking.contract.RecordingRule
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.checkBoxInput
import kotlinx.html.classes
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.numberInput
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.select
import kotlinx.html.slot
import kotlinx.html.small
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import java.time.Duration

internal fun FlowContent.rulesPanel(chrome: Chrome, view: RecordingsView) = panel("Series rules", id = "rules") {
    if (view.rules.isNotEmpty()) {
        div("scroll") {
            table {
                caption("sr") { +"Series rules" }
                thead {
                    tr {
                        col("Title")
                        col("Channel")
                        col("Episodes")
                        col("Keep", "num")
                        col("Padding")
                        col("Priority", "num")
                        col("Action")
                    }
                }
                tbody {
                    for (rule in view.rules) {
                        tr(flashClass(chrome, rule.id)) {
                            ruleRow(chrome, view, rule)
                        }
                    }
                }
            }
        }
    }
    addRuleForm(chrome, view)
    p("muted small") {
        +(
            "A rule records every guide airing whose title matches, ignoring case, and skips an episode it " +
                "already holds. Keep deletes the rule's oldest recordings beyond that count. Padding starts " +
                "early and runs late, in minutes; when recordings conflict, the higher priority gets the slot."
            )
    }
}

private fun TR.ruleRow(chrome: Chrome, view: RecordingsView, rule: RecordingRule) {
    id = "rule-${rule.id}"
    th {
        attributes["scope"] = "row"
        +rule.title
    }
    td { +(rule.channelId?.let { view.channels[it] ?: it } ?: "Any channel") }
    td { +(if (rule.newOnly) "New only" else "All airings") }
    td("num") { +(if (rule.keep == 0) "All" else thousands(rule.keep)) }
    td("nowrap") {
        +(
            "${minutes(rule.startPaddingSeconds)} before, " +
                "${minutes(rule.endPaddingSeconds)} after"
            )
    }
    td("num") { +rule.priority.toString() }
    ruleActions(chrome, view, rule)
}

private fun TR.ruleActions(chrome: Chrome, view: RecordingsView, rule: RecordingRule) = td("nowrap") {
    details("inline") {
        summary("small quiet") { +"Edit" }
        postForm(
            "/admin/recordings/rules/${rule.id}",
            chrome.session,
            classes = "stack",
        ) {
            ruleFields("rule-${rule.id}", view, rule)
            div("actions") {
                button(type = ButtonType.submit, classes = "small") {
                    attributes["aria-label"] =
                        "Save the rule for ${rule.title}"
                    +"Save"
                }
            }
        }
    }
    +" "
    postForm(
        "/admin/recordings/rules/${rule.id}/delete",
        chrome.session,
        keepScroll = true,
    ) {
        button(type = ButtonType.submit, classes = "small") {
            attributes["aria-label"] =
                "Delete the rule for ${rule.title}"
            +"Delete"
        }
    }
}

private fun FlowContent.addRuleForm(chrome: Chrome, view: RecordingsView) =
    postForm("/admin/recordings/rules", chrome.session, classes = "stack") {
        div("field") {
            label {
                htmlFor = "rule-title"
                +"Title"
            }
            textInput(name = "title") {
                id = "rule-title"
                required = true
                maxLength = "200"
                placeholder =
                    "As the guide names it"
            }
        }
        ruleFields("rule", view, null)
        div("actions") { button(type = ButtonType.submit, classes = "primary") { +"Add the rule" } }
    }

/**
 * A rule's channel, episodes, keep, padding and priority fields; [rule] fills them for an edit,
 * null for a new rule's defaults.
 */
private fun FORM.ruleFields(prefix: String, view: RecordingsView, rule: RecordingRule?) {
    div("field") {
        label {
            htmlFor = "$prefix-channel"
            +"Channel"
        }
        select {
            id = "$prefix-channel"
            name = "channelId"
            option {
                value = ""
                selected = rule != null && rule.channelId == null
                +"Any channel"
            }
            for ((id, name) in view.channels) {
                option {
                    value = id
                    selected = rule?.channelId == id
                    +name
                }
            }
        }
    }
    numberField("$prefix-keep", "keep", "Keep (0 for all)", (rule?.keep ?: 0).toString(), KEEP_RANGE)
    val start = rule?.startPaddingSeconds?.let(::seconds) ?: Dvr.DEFAULT_START_PADDING
    val end = rule?.endPaddingSeconds?.let(::seconds) ?: Dvr.DEFAULT_END_PADDING
    numberField("$prefix-start", "startPadding", "Start early (minutes)", start.toMinutes().toString(), PADDING_MINUTES)
    numberField("$prefix-end", "endPadding", "Run late (minutes)", end.toMinutes().toString(), PADDING_MINUTES)
    val priorities = "Priority (${PRIORITY_RANGE.first} to ${PRIORITY_RANGE.last})"
    numberField("$prefix-priority", "priority", priorities, (rule?.priority ?: 0).toString(), PRIORITY_RANGE)
    div("field") {
        label {
            checkBoxInput(name = "newOnly") {
                value = "true"
                checked = rule?.newOnly ?: true
            }
            +" New episodes only"
        }
    }
}

/** A required whole-number field in [range], labeled [label] and filled with [value]. */
internal fun FlowContent.numberField(id: String, name: String, label: String, value: String, range: IntRange) =
    div("field") {
        label {
            htmlFor = id
            +label
        }
        numberInput(name = name) {
            this.id = id
            this.value = value
            min = range.first.toString()
            max = range.last.toString()
            required = true
        }
    }

private fun minutes(seconds: Int): String =
    if (seconds % SECONDS_PER_MINUTE == 0) "${seconds / SECONDS_PER_MINUTE} min" else "$seconds s"

private fun seconds(seconds: Int): Duration = Duration.ofSeconds(seconds.toLong())

/** The padding a rule may ask for, in whole minutes. */
private val PADDING_MINUTES = 0..Dvr.MAX_PADDING_SECONDS / SECONDS_PER_MINUTE

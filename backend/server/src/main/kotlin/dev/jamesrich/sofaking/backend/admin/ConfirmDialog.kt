package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.Impact
import dev.jamesrich.sofaking.backend.catalog.Moved
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.dialog
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.hidden
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.map
import kotlinx.html.p
import kotlinx.html.role
import kotlinx.html.s
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.ul

/** An edit waiting for the operator: what it does, and the fields that save it against [revision]. */
internal class Confirm(
    val action: String,
    val describe: String,
    val fields: List<Pair<String, String>>,
    val revision: String,
    val impact: Impact,
    val stale: Boolean,
    val back: String,
)

/** An edit the policy refused, shown in its [section]. */
internal class EditErrors(val section: String, val messages: List<String>)

internal fun FlowContent.sectionErrors(errors: EditErrors?, section: String) {
    if (errors?.section == section) notice("Not saved. The policy would not be valid:", errors.messages)
}

internal fun FlowContent.confirmDialog(chrome: Chrome, confirm: Confirm?) {
    confirm ?: return
    div("modal") {
        div("dialog") {
            attributes["role"] = "dialog"
            attributes["aria-modal"] = "true"
            attributes["aria-labelledby"] = "confirm-title"
            h2 {
                id = "confirm-title"
                +"Save: ${confirm.describe}?"
            }
            if (confirm.stale) {
                notice(
                    "The policy changed since this page loaded, so nothing was saved. This is what the edit does " +
                        "against the policy as it is now.",
                    tone = Tone.WARN,
                )
            }
            impactSummary(confirm.impact)
            confirmForm(chrome, confirm)
        }
    }
}

/** What an edit does to the channels: the count on, then each kind of change with the first few it names. */
private fun FlowContent.impactSummary(impact: Impact) {
    if (impact.isEmpty) {
        p { +"No channel changes: the lineup stays as it is." }
        return
    }
    p { +"Channels on: ${thousands(impact.enabledBefore)} → ${thousands(impact.enabledAfter)}." }
    movedList("turn off", impact.turnedOff)
    movedList("turn on", impact.turnedOn)
    movedList("change group", impact.group)
    movedList("change number", impact.number)
    movedList("change name", impact.name)
    movedList("join the lineup", impact.added.map { Moved(it, null, null) })
    movedList("leave the lineup", impact.removed.map { Moved(it, null, null) })
}

private fun FlowContent.movedList(title: String, items: List<Moved>) {
    if (items.isEmpty()) return
    h3 { +"${plural(items.size, "channel")} $title" }
    ul("small") {
        for (item in items.take(MOVED_SHOWN)) {
            li {
                +item.name
                if (item.before != null || item.after != null) {
                    span("muted") { +": ${item.before ?: "none"} → ${item.after ?: "none"}" }
                }
            }
        }
        if (items.size > MOVED_SHOWN) li("muted") { +"and ${thousands(items.size - MOVED_SHOWN)} more" }
    }
}

private fun FlowContent.confirmForm(chrome: Chrome, confirm: Confirm) =
    form(action = confirm.action, method = FormMethod.post) {
        hiddenInput(name = "csrf") { value = chrome.session.csrf }
        hiddenInput(name = "revision") { value = confirm.revision }
        hiddenInput(name = "confirm") { value = "yes" }
        for ((name, value) in confirm.fields) hiddenInput(name = name) { this.value = value }
        div("actions") {
            a(confirm.back, classes = "btn quiet") {
                attributes["autofocus"] = "autofocus"
                +"Cancel"
            }
            val removes = confirm.impact.turnedOff.isNotEmpty() || confirm.impact.removed.isNotEmpty()
            button(type = ButtonType.submit, classes = if (removes) "danger" else "primary") { +"Save and apply" }
        }
    }

/**
 * A yes-or-no dialog for an action that cannot be undone: [title] asks the question, [block] adds the action's hidden
 * fields and the explanation, and the form posts to [action] with `confirm=yes`; Cancel goes back to [back].
 */
internal fun FlowContent.askDialog(
    chrome: Chrome,
    title: String,
    action: String,
    back: String,
    verb: String,
    danger: Boolean = false,
    block: FORM.() -> Unit,
) {
    div("modal") {
        div("dialog") {
            attributes["role"] = "dialog"
            attributes["aria-modal"] = "true"
            attributes["aria-labelledby"] = "confirm-title"
            h2 {
                id = "confirm-title"
                +title
            }
            form(action = action, method = FormMethod.post) {
                hiddenInput(name = "csrf") { value = chrome.session.csrf }
                hiddenInput(name = "confirm") { value = "yes" }
                block()
                div("actions") {
                    a(back, classes = "btn quiet") {
                        attributes["autofocus"] = "autofocus"
                        +"Cancel"
                    }
                    button(type = ButtonType.submit, classes = if (danger) "danger" else "primary") { +verb }
                }
            }
        }
    }
}

/** A question before an action that cannot be undone: [note] says what it does, [fields] ride along to [action]. */
internal class Ask(
    val title: String,
    val note: String,
    val action: String,
    val back: String,
    val verb: String,
    val fields: List<Pair<String, String>> = emptyList(),
)

internal fun FlowContent.askDialog(chrome: Chrome, ask: Ask) =
    askDialog(chrome, ask.title, ask.action, ask.back, ask.verb, danger = true) {
        for ((name, value) in ask.fields) hiddenInput(name = name) { this.value = value }
        p { +ask.note }
    }

/** A policy edit form: the CSRF token, the revision the page showed, and the [op]. */
internal fun FlowContent.editForm(
    action: String,
    chrome: Chrome,
    revision: String,
    op: String,
    classes: String = "inline",
    block: FORM.() -> Unit,
) = form(action = action, method = FormMethod.post, classes = classes) {
    attributes["hx-swap"] = "innerHTML show:none"
    hiddenInput(name = "csrf") { value = chrome.session.csrf }
    hiddenInput(name = "revision") { value = revision }
    hiddenInput(name = "op") { value = op }
    block()
}

/** How many moved channels a preview names before it counts the rest. */
private const val MOVED_SHOWN = 6

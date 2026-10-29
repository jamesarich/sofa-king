package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.PolicyStation
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

/** One station's field before and after an edit. */
internal class Moved(val name: String, val before: String?, val after: String?)

/** What an edit does to the stations a run builds, by field. */
internal class Impact(
    val enabledBefore: Int,
    val enabledAfter: Int,
    val group: List<Moved>,
    val number: List<Moved>,
    val name: List<Moved>,
    val turnedOff: List<Moved>,
    val turnedOn: List<Moved>,
    val added: List<String>,
    val removed: List<String>,
) {
    val isEmpty: Boolean get() = group.isEmpty() && number.isEmpty() && name.isEmpty() && turnedOff.isEmpty() &&
        turnedOn.isEmpty() && added.isEmpty() && removed.isEmpty()
}

internal fun impactOf(before: List<PolicyStation>, after: List<PolicyStation>): Impact {
    val old = before.associateBy { it.key }
    val new = after.associateBy { it.key }
    val both = new.keys.filter { it in old }.sorted()
    fun moved(field: (PolicyStation) -> String?) = both.mapNotNull { key ->
        val a = old.getValue(key)
        val b = new.getValue(key)
        if (field(a) != field(b)) Moved(b.name, field(a), field(b)) else null
    }
    return Impact(
        before.count { it.enabled }, after.count { it.enabled },
        moved { it.group }, moved { it.number }, moved { it.name },
        both.filter {
            old.getValue(it).enabled && !new.getValue(it).enabled
        }.map { Moved(new.getValue(it).name, "on", "off (${new.getValue(it).disabledReason ?: "hidden"})") },
        both.filter {
            !old.getValue(it).enabled && new.getValue(it).enabled
        }.map { Moved(new.getValue(it).name, "off", "on") },
        (new.keys - old.keys).sorted().map {
            new.getValue(it).name
        },
        (old.keys - new.keys).sorted().map { old.getValue(it).name },
    )
}

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

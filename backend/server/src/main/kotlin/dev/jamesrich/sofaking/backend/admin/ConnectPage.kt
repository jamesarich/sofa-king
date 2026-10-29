package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.NotificationTarget
import dev.jamesrich.sofaking.backend.api.SaveNotificationTarget
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.notify.NotificationTargetsService
import dev.jamesrich.sofaking.backend.notify.NotifyEvent
import dev.jamesrich.sofaking.backend.notify.NotifyKind
import io.ktor.http.Parameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.checkBoxInput
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

private val KIND_LABELS = linkedMapOf(
    NotifyKind.NTFY to "ntfy",
    NotifyKind.APPRISE to "Apprise API",
    NotifyKind.WEBHOOK to "Webhook (JSON)",
)

/** The events a new target starts with ticked: all but the attention items, which Home already shows. */
private val DEFAULT_TICKED = NotifyEvent.ALL - setOf(NotifyEvent.ATTENTION_RAISED, NotifyEvent.ATTENTION_CLEARED)

/** The Connect page: where the server sends notifications, with a test, a switch and the last delivery for each. */
internal class ConnectPages(private val portal: Portal) {
    private val runtime get() = portal.runtime

    fun install(route: Route) = with(portal) {
        route.get("/connect") { call.html(connectPage(chrome(), runtime.notifications?.list())) }
        route.action("/connect/targets") { session, form ->
            session.flash.set(save(form, null))
            seeOther(call, "/admin/connect#targets")
        }
        route.action("/connect/targets/{id}") { session, form ->
            session.flash.set(save(form, call.parameters["id"]!!))
            seeOther(call, "/admin/connect#targets")
        }
        route.action("/connect/targets/{id}/enabled") { session, form ->
            val id = call.parameters["id"]!!
            val on = form["enabled"] == "1"
            session.flash.set(
                attempt(id) {
                    val target = it.update(id, SaveNotificationTarget(enabled = on))
                    Flash(if (on) "Turned ${target.name} on." else "Turned ${target.name} off.", target = id)
                },
            )
            seeOther(call, "/admin/connect#targets")
        }
        route.action("/connect/targets/{id}/test") { session, _ ->
            val id = call.parameters["id"]!!
            session.flash.set(
                attempt(id) {
                    val delivery = it.test(id)
                    if (delivery.ok) {
                        Flash("The test reached it (HTTP ${delivery.status}).", target = id)
                    } else {
                        Flash("The test failed: ${delivery.error}.", Tone.BAD, id)
                    }
                },
            )
            seeOther(call, "/admin/connect#targets")
        }
        route.action("/connect/targets/{id}/delete") { session, form ->
            val id = call.parameters["id"]!!
            val name = runtime.notifications?.let { runCatching { it.get(id).name }.getOrNull() } ?: "the target"
            val ask = Ask(
                "Remove $name?",
                "The server stops sending news there.",
                "/admin/connect/targets/$id/delete",
                "/admin/connect#targets",
                "Remove",
            )
            val page: suspend (Chrome) -> String = { connectPage(it, runtime.notifications?.list()) }
            call.confirmed(session, form, ask, page) {
                session.flash.set(
                    attempt(id) {
                        val removed = it.get(id).name
                        it.delete(id)
                        Flash("Removed $removed.")
                    },
                )
                seeOther(call, "/admin/connect#targets")
            }
        }
    }

    /** Adds a target from [form], or changes [id]'s; a blank secret or URL on a change keeps the one it has. */
    private suspend fun save(form: Parameters, id: String?): Flash = attempt(id) { service ->
        val body = SaveNotificationTarget(
            name = form["name"].orEmpty(),
            kind = form["kind"].orEmpty(),
            url = form["url"]?.takeIf { it.isNotBlank() || id == null }.orEmpty(),
            secret = form["secret"]?.takeIf { it.isNotEmpty() || id == null },
            events = form.getAll("event").orEmpty(),
            enabled = if (id == null) true else null,
        )
        val target = if (id == null) service.create(body) else service.update(id, body)
        Flash(if (id == null) "Added ${target.name}. Send it a test." else "Saved ${target.name}.", target = target.id)
    }

    private suspend fun attempt(id: String?, block: suspend (NotificationTargetsService) -> Flash): Flash {
        val service = runtime.notifications ?: return Flash("Notifications are not available here.", Tone.BAD)
        return try {
            block(service)
        } catch (e: ApiFailure) {
            Flash("Not saved: ${e.message}", Tone.BAD, id)
        }
    }
}

internal fun connectPage(chrome: Chrome, targets: List<NotificationTarget>?): String =
    layout("Connect", chrome, "/admin/connect") {
        pageHead(
            "Connect",
            "Where the server sends its news: recordings, sources, the disk, updates and new library files, to ntfy, " +
                "an Apprise API server or any webhook.",
        )
        tabs(chrome, "/admin/connect", SETTINGS_TABS)
        if (targets == null) {
            div("panel") { p("muted") { +"Notifications are not available on this server." } }
            return@layout
        }
        panel("Notification targets", id = "targets") {
            if (targets.isEmpty()) {
                p("muted") { +"None yet. Add one below, then send it a test." }
            } else {
                targetsTable(chrome, targets)
            }
        }
        panel("Add a target", id = "add") {
            p("mt-0 muted small") {
                +(
                    "Addresses on your network are fine here: unlike a source, a target is your own choice. The " +
                        "secret signs a webhook's body (X-SofaKing-Signature) or is ntfy's access token; the server " +
                        "never shows it again."
                    )
            }
            targetForm(chrome, null)
        }
    }

private fun FlowContent.targetsTable(chrome: Chrome, targets: List<NotificationTarget>) = div("scroll") {
    table {
        caption("sr") { +"Notification targets" }
        thead {
            tr {
                col("Name")
                col("Sends to")
                col("Events")
                col("Last delivery")
                col("Action")
            }
        }
        tbody {
            for (target in targets) {
                tr(flashClass(chrome, target.id)) {
                    id = "target-${target.id}"
                    th {
                        attributes["scope"] = "row"
                        +target.name
                        if (!target.enabled) {
                            +" "
                            // A header cell takes phrasing content only, so the chip is written as its span.
                            span("chip ${Tone.OFF.css}") { +"Off" }
                        }
                    }
                    td {
                        +KIND_LABELS.getValue(target.kind)
                        span("muted small mono") { +" ${target.url}" }
                    }
                    td { +"${target.events.size} of ${NotifyEvent.ALL.size}" }
                    td("nowrap") { lastDelivery(chrome, target) }
                    td { targetActions(chrome, target) }
                }
            }
        }
    }
}

private fun FlowContent.lastDelivery(chrome: Chrome, target: NotificationTarget) {
    val last = target.lastDelivery
    if (last == null) {
        span("muted") { +"none yet" }
        return
    }
    if (last.ok) chip("Sent", Tone.OK) else chip(last.error ?: "Failed", Tone.BAD)
    +" "
    ago(last.at, chrome.now)
}

private fun FlowContent.targetActions(chrome: Chrome, target: NotificationTarget) = div("actions") {
    postForm("/admin/connect/targets/${target.id}/test", chrome.session, keepScroll = true) {
        button(type = ButtonType.submit, classes = "small") {
            attributes["aria-label"] = "Test ${target.name}"
            +"Test"
        }
    }
    postForm("/admin/connect/targets/${target.id}/enabled", chrome.session, keepScroll = true) {
        hiddenInput(name = "enabled") { value = if (target.enabled) "0" else "1" }
        button(type = ButtonType.submit, classes = "small") {
            attributes["aria-label"] = (if (target.enabled) "Turn off " else "Turn on ") + target.name
            +(if (target.enabled) "Turn off" else "Turn on")
        }
    }
    postForm("/admin/connect/targets/${target.id}/delete", chrome.session, keepScroll = true) {
        button(type = ButtonType.submit, classes = "small") {
            attributes["aria-label"] = "Remove ${target.name}"
            +"Remove"
        }
    }
    details {
        summary { +"Edit" }
        targetForm(chrome, target)
    }
}

/** The form that adds a target, or changes [target]. */
private fun FlowContent.targetForm(chrome: Chrome, target: NotificationTarget?) {
    val key = target?.id ?: "new"
    val action = if (target == null) "/admin/connect/targets" else "/admin/connect/targets/${target.id}"
    postForm(action, chrome.session, classes = "runtime connect") {
        textField("$key-name", "name", "Name", target?.name, "Such as Phone or Home Assistant")
        div("field") {
            label {
                htmlFor = "$key-kind"
                +"Kind"
            }
            select {
                id = "$key-kind"
                name = "kind"
                for ((kind, text) in KIND_LABELS) {
                    option {
                        value = kind
                        selected = kind == target?.kind
                        +text
                    }
                }
            }
        }
        urlField(key, target)
        div("field") {
            label {
                htmlFor = "$key-secret"
                +"Secret (optional)"
            }
            passwordInput(name = "secret") {
                id = "$key-secret"
                maxLength = "512"
                attributes["autocomplete"] = "off"
                if (target?.hasSecret == true) placeholder = "Set; leave blank to keep it"
            }
        }
        div("checks") {
            for ((event, text) in NotifyEvent.LABELS) {
                label {
                    checkBoxInput(name = "event") {
                        value = event
                        checked = target?.events?.contains(event) ?: (event in DEFAULT_TICKED)
                    }
                    +" $text"
                }
            }
        }
        div("actions") {
            button(type = ButtonType.submit, classes = "primary") { +(if (target == null) "Add" else "Save") }
        }
    }
}

/** The URL is never filled in: a change shows the masked one as its hint, and a blank field keeps the stored URL. */
private fun FlowContent.urlField(key: String, target: NotificationTarget?) = textField(
    "$key-url",
    "url",
    "URL",
    null,
    if (target == null) {
        "https://ntfy.sh/your-topic or http://192.0.2.10:8000/notify"
    } else {
        "Set to ${target.url}; leave blank to keep it. The path is a secret, so it is never shown."
    },
    required = target == null,
)

private fun FlowContent.textField(
    id: String,
    name: String,
    text: String,
    current: String?,
    hint: String,
    required: Boolean = true,
) = div("field") {
    label {
        htmlFor = id
        +text
    }
    textInput(name = name) {
        this.id = id
        this.required = required
        value = current.orEmpty()
    }
    span("hint") { +hint }
}

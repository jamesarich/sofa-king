package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.update.UpdateState
import dev.jamesrich.sofaking.backend.update.UpdaterReport
import kotlinx.html.ButtonType
import kotlinx.html.DL
import kotlinx.html.FlowContent
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.picture
import kotlinx.html.role
import kotlinx.html.s
import kotlinx.html.small
import kotlinx.html.span
import java.time.Duration
import java.time.Instant

/** "Version 1.4.0, revision abc1234", or whichever of the two is known. */
private fun FlowContent.build(version: String?, revision: String?) {
    val knownVersion = version?.takeIf { it != "unknown" }
    val knownRevision = revision?.takeIf { it != "unknown" }
    when {
        knownVersion != null && knownRevision != null -> {
            +knownVersion
            +" "
            code { +knownRevision }
        }

        knownVersion != null -> +knownVersion

        knownRevision != null -> code { +knownRevision }

        else -> span("muted") { +"unknown build" }
    }
}

/**
 * The update picture: what runs against what is published, the host updater's last word, and the two actions. Check
 * now asks the registry; Update now writes the request the updater watches, after a confirmation.
 */
internal fun FlowContent.updatePanel(chrome: Chrome, update: UpdateState) = panel("Update", id = "update") {
    val now = chrome.now
    val report = update.updater
    val reportedAt = report?.let { runCatching { Instant.parse(it.checkedAt) }.getOrNull() }
    val updaterFresh = reportedAt != null && Duration.between(reportedAt, now) < UPDATER_FRESH
    val requested = update.requestedAt
    updateHeadline(update)
    dl("kv") {
        dt { +"Running" }
        dd { build(update.running.version, update.running.revision) }
        if (update.image != null) {
            dt { +"Published" }
            dd {
                val published = update.published
                if (published == null) {
                    span("muted") { +"not checked yet" }
                } else {
                    build(published.version, published.revision)
                    if (update.checkedAt != null) {
                        +" · checked "
                        ago(update.checkedAt.toString(), now)
                    }
                }
                br()
                span("muted small mono") { +update.image }
            }
        }
        updaterFacts(report, now, updaterFresh)
        if (requested != null) {
            dt { +"Requested" }
            dd {
                +"An update was asked for "
                ago(requested.toString(), now)
                +"; the updater takes it when it next runs."
            }
        }
    }
    updateActions(chrome, update)
}

private fun FlowContent.updateHeadline(update: UpdateState) = when {
    update.image == null -> p("muted") { +"The update check is off (SOFA_UPDATE_IMAGE is empty)." }

    update.current == true -> p("allgood") {
        +"Up to date. "
        span("muted") { +"The published image is the one running." }
    }

    update.current == false -> div("notice warn") {
        attributes["role"] = "status"
        +"An update is available: "
        build(update.published?.version, update.published?.revision)
        +" is published."
    }

    update.error != null -> div("notice warn") {
        attributes["role"] = "status"
        +"The last check failed: ${update.error}"
    }

    update.checkedAt == null -> p("muted") {
        +"Not checked yet. The first check runs two minutes after start, then every five minutes."
    }

    else -> p("muted") { +"The registry names no commit for the published image, so it cannot be compared." }
}

private fun DL.updaterFacts(report: UpdaterReport?, now: Instant, fresh: Boolean) {
    dt { +"Updater" }
    dd {
        when {
            report == null -> {
                +"Has not reported. "
                span("muted") {
                    +(
                        "Install the host's updater to apply updates; its timer checks every 15 minutes and " +
                            "writes its result here."
                        )
                }
            }

            else -> {
                +when (report.outcome) {
                    "up-to-date" -> "Checked and found nothing new"

                    "updated" -> "Installed ${report.candidate ?: "a new image"}"

                    "rolled-back" ->
                        "Tried ${report.candidate ?: "a new image"} and went back to " +
                            "${report.running ?: "the previous one"}"

                    "skipped" -> "Skipped ${report.candidate ?: "the published image"}; it failed before"

                    "failed" -> "Failed"

                    else -> report.outcome
                }
                +" "
                ago(report.checkedAt, now)
                if (!fresh) {
                    +" "
                    chip("Not checking in", Tone.WARN)
                }
                if (report.message != null) {
                    br()
                    span("muted small") { +report.message }
                }
            }
        }
    }
}

private fun FlowContent.updateActions(chrome: Chrome, update: UpdateState) = div("actions mt-14") {
    val report = update.updater
    val requested = update.requestedAt
    if (update.image != null) {
        postForm("/admin/system/update/check", chrome.session, keepScroll = true) {
            button(type = ButtonType.submit) { +"Check now" }
        }
    }
    val canRequest = report != null && requested == null
    if (canRequest) {
        postForm("/admin/system/update/request", chrome.session, keepScroll = true) {
            button(
                type = ButtonType.submit,
                classes = if (update.current == false) {
                    "primary"
                } else {
                    ""
                },
            ) { +"Update now" }
        }
    }
    span("muted small") {
        +when {
            report == null -> "Update now needs the host's updater; see the deploy guide."

            requested != null -> "Update now is waiting for the updater."

            update.current == false ->
                "Update now backs up the database, swaps to the published image and comes " +
                    "back to this one if the new one is not healthy."

            else ->
                "Update now makes the updater pull and compare at once; it changes nothing while the " +
                    "published image is the one running."
        }
    }
}

internal fun MAIN.updateConfirm(chrome: Chrome, update: UpdateState) {
    askDialog(
        chrome,
        "Update the server now?",
        "/admin/system/update/request",
        "/admin/system/about#update",
        "Update",
        danger = false,
    ) {
        p {
            +"The host's updater backs up the database, pulls "
            code { +(update.image ?: "the published image") }
            +" and recreates the container. "
            +(
                "Streams stop for about half a minute while it restarts; you stay signed in. If the new image is not " +
                    "healthy within three minutes, the previous image and its database come back."
                )
        }
        if (update.current != false) {
            p("muted") { +"The published image looks like the one running, so this most likely changes nothing." }
        }
    }
}

/** The updater has checked in when its last report is under this old; the timer runs every 15 minutes. */
private val UPDATER_FRESH: Duration = Duration.ofHours(1)

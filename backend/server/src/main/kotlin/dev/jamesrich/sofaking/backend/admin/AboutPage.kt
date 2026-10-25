package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.ServerStatus
import kotlinx.html.DL
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.textArea
import java.time.Instant

internal fun aboutPage(chrome: Chrome, view: AboutView): String = layout("About", chrome, "/admin/system/about") {
    val now = chrome.now
    val server = view.server
    val status = view.status
    pageHead("About", "This server, whether it is up to date, and a snapshot to paste into a bug report.")
    tabs(chrome, "/admin/system/about", SYSTEM_TABS)
    view.update?.let { updatePanel(chrome, it) }
    div("cols") {
        serverPanel(server, now)
        statusPanel(status, now)
    }
    view.encoders?.let { encoderPanel(chrome, it) }
    snapshotPanel(view.snapshot)
    if (view.confirm && view.update != null) updateConfirm(chrome, view.update)
}

private fun FlowContent.serverPanel(server: AboutServer, now: Instant) = panel("Server") {
    dl("kv") {
        dt { +"Version" }
        dd {
            +server.version
            +" "
            code { +server.revision }
        }
        dt { +"Started" }
        dd {
            ago(server.startedAt, now)
            +" (up ${uptime(Instant.parse(server.startedAt), now)})"
        }
        dt { +"Schema" }
        dd { +"version ${server.schemaVersion}" }
        dt { +"JVM" }
        dd { +server.jvm }
        dt { +"OS" }
        dd { +server.os }
        dt { +"FFmpeg" }
        dd { +server.ffmpeg }
        if (server.dataDirectory != null) {
            dt { +"Data directory" }
            dd { code { +server.dataDirectory } }
        }
        dt { +"Logging" }
        dd { +"${server.logLevel}, ${server.logFormat}" }
        dt { +"Memory" }
        dd {
            +"${thousands(server.memoryUsedMb.toInt())} MB used of ${thousands(server.memoryMaxMb.toInt())} MB"
        }
    }
}

private fun FlowContent.statusPanel(status: ServerStatus, now: Instant) = panel("Status") {
    dl("kv") {
        dt { +"Channels" }
        dd {
            +(
                "${thousands(status.channels.enabled)} on of ${thousands(status.channels.total)}, " +
                    "${thousands(status.channels.eligible)} with a playable source"
                )
        }
        dt { +"Sources" }
        dd {
            +(
                status.sources.byStatus.entries.joinToString(", ") {
                    "${thousands(it.value)} ${it.key.lowercase()}"
                }.ifEmpty { "none" }
                )
            if (status.sources.manuallyDisabled > 0) {
                +"; ${thousands(status.sources.manuallyDisabled)} disabled by hand"
            }
        }
        dt { +"Providers" }
        dd {
            val importing = status.providers.total - status.providers.failing.size - status.providers.pending
            +"${thousands(importing)} of ${thousands(status.providers.total)} importing"
            if (status.providers.failing.isNotEmpty()) {
                +", failing: ${status.providers.failing.joinToString { it.id }}"
            }
        }
        dt { +"Guides" }
        dd {
            +(
                "${thousands(status.guides.total - status.guides.failing.size - status.guides.pending)} of " +
                    "${thousands(status.guides.total)} importing"
                )
            if (status.guides.failing.isNotEmpty()) {
                +", failing: ${status.guides.failing.joinToString { it.id }}"
            }
        }
        serviceFacts(status, now)
    }
}

private fun DL.serviceFacts(status: ServerStatus, now: Instant) {
    dt { +"Policy" }
    dd {
        if (status.policy == null) {
            +"None"
        } else {
            +"revision "
            code { +status.policy.revision }
            +", last run "
            ago(status.policy.lastRunAt, now)
        }
    }
    dt { +"Plex" }
    dd {
        if (status.plex == null) {
            +"Not set up"
        } else {
            +"last sync "
            ago(status.plex.lastSyncAt, now)
            +", ${thousands(status.plex.mapped)} channels mapped"
            if (status.plex.lastError != null) {
                +", failing"
            }
        }
    }
    dt { +"Watching" }
    dd { +plural(status.activeSessions, "session") }
}

private fun FlowContent.snapshotPanel(snapshot: String) = panel("Support snapshot", id = "snapshot", aside = {
    a("/admin/system/snapshot.json", classes = "btn small") {
        attributes["download"] = "sofa-king-snapshot.json"
        +"Download"
    }
}) {
    p("muted") {
        +(
            "The status API's answer with the facts above, every job's last run and what the overview lists. It " +
                "holds no token, URL or secret; copy it into a bug report."
            )
    }
    textArea(classes = "snapshot") {
        attributes["readonly"] = "readonly"
        attributes["aria-label"] = "Support snapshot JSON"
        rows = "14"
        +snapshot
    }
}

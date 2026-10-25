package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.ServerStatus
import kotlinx.html.DL
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.br
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.progress
import kotlinx.html.s
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
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
    if (server.folders.isNotEmpty()) diskPanel(server.folders)
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
        server.tools?.let { tools ->
            dt { +"Comskip" }
            dd { +tools.comskip }
            dt { +"Chromaprint" }
            dd { +tools.chromaprint }
            dt { +"GPU devices" }
            dd { +tools.gpu }
        }
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

/** Each folder the server keeps files in, with its disk's free space and what the server's own files take there. */
private fun FlowContent.diskPanel(folders: List<FolderReport>) = panel("Disk", id = "disk") {
    div("scroll") {
        table {
            caption("sr") { +"Disk space by folder" }
            thead {
                tr {
                    col("Folder")
                    col("Free")
                    col("Disk", "num")
                    col("Used by the server", "num")
                }
            }
            tbody {
                for (folder in folders) {
                    tr {
                        th {
                            attributes["scope"] = "row"
                            +folder.name
                            br()
                            span("muted small mono") { +folder.path }
                        }
                        td("nowrap") { freeSpace(folder) }
                        td("num nowrap") { +(folder.totalBytes?.let(::bytesText) ?: "–") }
                        td("num nowrap") { +(folder.usedBytes?.let(::bytesText) ?: "–") }
                    }
                }
            }
        }
    }
    p("muted small") {
        +(
            "Folders on one disk show the same space. Used by the server counts the recordings; the media folder is " +
                "yours, and the data folder holds the database, caches and backups."
            )
    }
}

private fun FlowContent.freeSpace(folder: FolderReport) {
    val free = folder.freeBytes
    val total = folder.totalBytes
    if (free == null || total == null || total <= 0) {
        span("muted") { +"not readable" }
        return
    }
    val usedPercent = ((total - free) * PERCENT / total).toInt()
    progress {
        attributes["value"] = usedPercent.toString()
        attributes["max"] = PERCENT.toString()
        attributes["aria-label"] = "${folder.name} disk used"
        +"$usedPercent%"
    }
    span("small") { +" ${bytesText(free)} free " }
    if (free * PERCENT < total * LOW_SPACE_PERCENT) chip("Low", Tone.BAD)
}

/** Below this share of its disk free, a folder's free space is marked low. */
private const val LOW_SPACE_PERCENT = 10

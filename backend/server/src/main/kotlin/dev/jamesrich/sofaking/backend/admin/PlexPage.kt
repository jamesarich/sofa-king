package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.HdhrAccessStatus
import dev.jamesrich.sofaking.backend.api.NamedOutput
import dev.jamesrich.sofaking.backend.api.PlexStatus
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.address
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.option
import kotlinx.html.output
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import java.time.Instant

internal class HdhrDevice(val name: String, val address: String, val channels: Int)

/** One named output as the Plex page lists it, its URLs carrying the token as the player outputs show it. */
internal class NamedOutputRow(val output: NamedOutput, val playlist: String, val guide: String)

internal class PlexView(
    val playlist: String,
    val guide: String,
    val tokenConfigured: Boolean,
    val revealed: Boolean,
    val hdhrEnabled: Boolean,
    val devices: List<HdhrDevice>,
    val devicesNeeded: Int?,
    val enabledChannels: Int,
    val tuners: Int,
    val status: PlexStatus?,
    /** Who may use the tuners; null while the emulation is off. */
    val access: HdhrAccessStatus? = null,
    val namedOutputs: List<NamedOutputRow> = emptyList(),
)

internal fun plexPage(chrome: Chrome, view: PlexView): String = layout("Plex", chrome, "/admin/plex") {
    pageHead(
        "Plex and other players",
        "Plex reaches the lineup through emulated HDHomeRun tuners; other players take the M3U playlist and XMLTV " +
            "guide.",
    )
    tabs(chrome, "/admin/plex", PLAYER_TABS)
    panel("Plex sync", id = "sync") {
        val status = view.status
        if (status == null) {
            p("muted") {
                +(
                    "Off. Plex sync keeps Plex's channel maps in step with the lineup: set SOFA_PLEX_URL and " +
                        "SOFA_PLEX_TOKEN in .env and restart."
                    )
            }
        } else {
            plexSync(status, chrome.now)
        }
    }
    panel("HDHomeRun tuners", id = "tuners") {
        if (view.hdhrEnabled) {
            hdhrDevices(view)
        } else {
            p("muted") { +"The HDHomeRun emulation is off (SOFA_HDHR_ENABLED=false)." }
        }
    }
    view.access?.let { access -> panel("Tuner access", id = "access") { tunerAccess(access, chrome) } }
    playerOutputs(chrome, view)
    namedOutputs(chrome, view.namedOutputs)
    plexSetup(view)
}

private fun DIV.plexSync(status: PlexStatus, now: Instant) {
    if (status.lastError != null) notice("The last sync failed: ${status.lastError}")
    dl("kv") {
        dt { +"Last sync" }
        dd {
            if (status.lastSyncAt == null) {
                +"Not yet"
            } else {
                ago(status.lastSyncAt, now)
                +" (${status.lastCause ?: "scheduled"})"
            }
        }
        dt { +"Runs" }
        dd { +"After each lineup change and every ${status.syncMinutes} min" }
        if (status.missingDevices.isNotEmpty()) {
            dt { +"Not in Plex" }
            dd { chip(status.missingDevices.joinToString(), Tone.BAD) }
        }
    }
    if (status.devices.isNotEmpty()) {
        div("scroll mt-12") {
            table {
                caption("sr") { +"Tuners in Plex" }
                thead {
                    tr {
                        col("Tuner")
                        col("Device ID")
                        col("DVR")
                        col("Channels mapped", "num")
                    }
                }
                tbody {
                    for (device in status.devices) {
                        tr {
                            th {
                                attributes["scope"] = "row"
                                +"${device.device + 1}"
                            }
                            td("mono") { +device.deviceId }
                            td { +device.dvr }
                            td("num") {
                                +thousands(device.mapped)
                                if (device.added) {
                                    +" "
                                    chip("added", Tone.OK)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (status.findings.isNotEmpty()) {
        h3 { +"Findings" }
        ul { for (finding in status.findings) li { +finding } }
    }
}

private fun DIV.hdhrDevices(view: PlexView) {
    p {
        +(
            "${thousands(view.enabledChannels)} channels on, across ${plural(view.devices.size, "device")} of up " +
                "to ${view.tuners} streams each"
            )
        if (view.devicesNeeded != null) {
            +"; the lineup needs ${view.devicesNeeded}. "
            if (view.devicesNeeded > view.devices.size) {
                span("error-text") { +"Add ports to SOFA_HDHR_EXTRA_PORTS." }
            }
        } else {
            +"."
        }
    }
    div("scroll") {
        table {
            caption("sr") { +"HDHomeRun devices" }
            thead {
                tr {
                    col("Device")
                    col("Address")
                    col("Channels", "num")
                }
            }
            tbody {
                for (device in view.devices) {
                    tr {
                        th {
                            attributes["scope"] = "row"
                            +device.name
                        }
                        td { code { +device.address } }
                        td("num") { +thousands(device.channels) }
                    }
                }
            }
        }
    }
    p("muted small") {
        +"Addresses use the host this page was reached by; extra devices listen on their own ports."
    }
}

private fun FlowContent.playerOutputs(chrome: Chrome, view: PlexView) = panel("Player outputs", id = "outputs") {
    dl("kv") {
        dt { +"Playlist" }
        dd { code { +view.playlist } }
        dt { +"Guide" }
        dd { code { +view.guide } }
    }
    if (view.tokenConfigured && !view.revealed) {
        div("mt-12") {
            postForm("/admin/plex/reveal", chrome.session) {
                button(type = ButtonType.submit) { +"Reveal the output token" }
            }
        }
    }
    if (!view.tokenConfigured) {
        p("muted") {
            +"Set SOFA_OUTPUT_TOKEN so players and Plex can fetch these URLs when the server takes tokens."
        }
    }
    p("muted small") {
        +"Other players: add the playlist URL as an M3U source and the guide URL as its XMLTV source."
    }
}

private fun FlowContent.plexSetup(view: PlexView) = panel("Set up Plex", id = "setup") {
    ol {
        li {
            +(
                "Allow the Plex server's address under Tuner access, or allow it there after Plex first tries " +
                    "the tuner."
                )
        }
        li {
            +"In Plex Web, open Settings, Live TV & DVR, Set Up Plex DVR, and enter the device address by hand: "
            code { +(view.devices.firstOrNull()?.address ?: "SERVER_HOST:PORT") }
            +". Plex does not find the server on its own."
        }
        li { +"At the guide step, choose the XMLTV option and enter the guide URL above." }
        li {
            +(
                "Map the channels; Plex pairs most by name and number. Add each extra device to the same DVR " +
                    "with its own address and the same guide URL."
                )
        }
        li { +"After a lineup change, run Scan channels for the DVR in Plex, or let Plex sync do it." }
    }
    p("muted small") { +"Each device takes up to ${view.tuners} streams at once; SOFA_HDHR_TUNERS changes that." }
}

/** The addresses that may use the tuners, the ones refused lately with an Allow each, and a form to allow one more. */
private fun DIV.tunerAccess(access: HdhrAccessStatus, chrome: Chrome) {
    if (access.open) {
        p { +"Every address may use the tuners: the server listens on this host only." }
    } else if (access.configured.isEmpty() && access.allowed.isEmpty()) {
        notice(
            "No address may use the tuners yet. Allow the Plex server's address below, or add the tuner in Plex and " +
                "allow the address it is refused from.",
            tone = Tone.WARN,
        )
    }
    if (access.refused.isNotEmpty()) refusedAddresses(access, chrome)
    if (access.allowed.isNotEmpty() || access.configured.isNotEmpty()) allowedAddresses(access, chrome)
    postForm("/admin/plex/allow", chrome.session, classes = "stack mt-12") {
        div("field") {
            label {
                htmlFor = "hdhr-address"
                +"Allow an address"
            }
            textInput(name = "address") {
                id = "hdhr-address"
                required = true
                maxLength = "64"
                placeholder = "10.0.0.20 or 10.0.0.0/24"
            }
        }
        div { button(type = ButtonType.submit) { +"Allow" } }
    }
    p("muted small") {
        +(
            "The tuners take no token, so only these addresses reach them. A change applies at once; Plex in Docker " +
                "on a bridge network asks from its container address."
            )
    }
}

private fun DIV.refusedAddresses(access: HdhrAccessStatus, chrome: Chrome) {
    h3 { +"Refused" }
    div("scroll") {
        table {
            caption("sr") { +"Addresses refused since the server started" }
            thead {
                tr {
                    col("Address")
                    col("Last tried")
                    col("Asked for")
                    col("Action")
                }
            }
            tbody {
                for (client in access.refused) {
                    tr {
                        th {
                            attributes["scope"] = "row"
                            code { +client.address }
                        }
                        td { ago(client.lastAt, chrome.now) }
                        td("mono") { +client.path }
                        td {
                            postForm("/admin/plex/allow", chrome.session) {
                                hiddenInput(name = "address") { value = client.address }
                                button(type = ButtonType.submit, classes = "small") {
                                    attributes["aria-label"] = "Allow ${client.address}"
                                    +"Allow"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DIV.allowedAddresses(access: HdhrAccessStatus, chrome: Chrome) {
    h3 { +"Allowed" }
    ul {
        for (entry in access.configured) {
            li {
                code { +entry }
                +" "
                span("muted small") { +"from SOFA_HDHR_ALLOW" }
            }
        }
        for (entry in access.allowed) {
            li {
                code { +entry }
                +" "
                postForm("/admin/plex/disallow", chrome.session) {
                    hiddenInput(name = "address") { value = entry }
                    button(type = ButtonType.submit, classes = "small quiet") {
                        attributes["aria-label"] = "Remove $entry"
                        +"Remove"
                    }
                }
            }
        }
    }
}

package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.Viewer
import dev.jamesrich.sofaking.backend.pairing.IssuedCode
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.encodeURLParameter
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.address
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.hidden
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.link
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.unsafe

/** The code the page shows, the address a client reaches this server at, and the QR that carries both. */
internal class PairingView(val code: IssuedCode, val serverUrl: String) {
    /** What a phone scans: the Sofa King app's own scheme, so the camera app hands it to the app, which pairs. */
    val link: String get() = pairingLink(serverUrl, code.code)
}

internal class ViewersView(val viewers: List<Viewer>, val pairing: PairingView?, val serverUrl: String)

/** The `sofaking://pair` link for [serverUrl] and [code]; the app reads the same two query parameters. */
internal fun pairingLink(serverUrl: String, code: String): String =
    "sofaking://pair?server=${serverUrl.encodeURLParameter()}&code=${code.encodeURLParameter()}"

/** The code as the viewer reads it from the screen: `123 456`. */
internal fun spacedCode(code: String): String = code.chunked(Wire.PAIRING_CODE_LENGTH / 2).joinToString(" ")

internal fun viewersPage(chrome: Chrome, view: ViewersView): String = layout("Devices", chrome, "/admin/viewers") {
    pageHead(
        "Devices",
        "The devices running the Sofa King app against this server: pair a new one with a code instead of typing the " +
            "API token, and see what each keeps on this server.",
    )
    tabs(chrome, "/admin/viewers", PLAYER_TABS)
    pairPanel(chrome, view.pairing)
    devicesPanel(chrome, view)
}

private fun FlowContent.pairPanel(chrome: Chrome, pairing: PairingView?) = panel("Pair a device", id = "pair") {
    if (pairing == null) {
        p("mt-0") {
            +(
                "A pairing code stands in for the API token for ten minutes. In the app's Settings, enter the " +
                    "server address and the code; a phone can scan the QR code instead."
                )
        }
        div("actions") {
            postForm("/admin/viewers/pair", chrome.session) {
                button(type = ButtonType.submit, classes = "primary") { +"Show a pairing code" }
            }
        }
    } else {
        pairingCode(chrome, pairing)
    }
}

private fun FlowContent.pairingCode(chrome: Chrome, pairing: PairingView) = div("pairing") {
    div("pairing-qr") {
        unsafe { raw(qrSvg(pairing.link, "Pairing code ${pairing.code.code} for ${pairing.serverUrl}")) }
    }
    div("pairing-text") {
        p("mt-0") { +"In the Sofa King app, open Settings and enter:" }
        dl("kv") {
            dt { +"Server address" }
            dd { code { +pairing.serverUrl } }
            dt { +"Pairing code" }
            dd {
                span("pairing-code num") {
                    attributes["aria-label"] =
                        "Pairing code ${pairing.code.code}"
                    +spacedCode(pairing.code.code)
                }
            }
        }
        p("muted small") {
            +"Valid until "
            ago(pairing.code.expiresAt.toString(), chrome.now)
            +(
                ", for one device. A phone with the app installed can scan the QR code with its camera " +
                    "instead."
                )
        }
        div("actions") {
            postForm("/admin/viewers/pair", chrome.session) {
                button(type = ButtonType.submit) { +"New code" }
            }
            postForm("/admin/viewers/cancel", chrome.session) {
                button(type = ButtonType.submit, classes = "quiet") { +"Cancel the code" }
            }
        }
    }
}

private fun FlowContent.devicesPanel(chrome: Chrome, view: ViewersView) = panel("Devices", id = "devices") {
    if (view.viewers.isEmpty()) {
        p("muted mt-0") {
            +(
                "No device has paired or saved preferences yet. Devices set up with the token appear here once " +
                    "they run an app that saves its preferences."
                )
        }
        return@panel
    }
    div("scroll") {
        table {
            caption("sr") { +"Devices" }
            thead {
                tr {
                    col("Device")
                    col("Platform")
                    col("Hidden", "num")
                    col("Favorites", "num")
                    col("Recent", "num")
                    col("Token")
                    col("Preferences saved")
                    col("Last seen")
                    col("First seen")
                    col("Action")
                }
            }
            tbody {
                for (viewer in view.viewers) {
                    tr(flashClass(chrome, viewer.clientId)) {
                        viewerRow(chrome, viewer)
                    }
                }
            }
        }
    }
    p("muted small") {
        +(
            "Hidden channels, the favorites order and the recent list are the device's own; the lineup every " +
                "device sees is on the Channels page. A device that paired holds a token of its own, and Forget " +
                "revokes it with what the server keeps, so the device must pair again. A device on the shared API " +
                "token, set up by hand or paired before devices had their own, keeps working after Forget; pair it " +
                "again to give it its own token."
            )
    }
}

private fun TR.viewerRow(chrome: Chrome, viewer: Viewer) {
    id = "viewer-${viewer.clientId}"
    val preferences = viewer.preferences
    th {
        attributes["scope"] = "row"
        +viewer.name.ifEmpty { "Unnamed device" }
        br
        span("muted small mono") { +viewer.clientId }
    }
    td { +viewer.platform.ifEmpty { "unknown" } }
    td("num") { +(preferences?.hidden?.size?.let(::thousands) ?: "") }
    td("num") { +(preferences?.favorites?.size?.let(::thousands) ?: "") }
    td("num") { +(preferences?.recent?.size?.let(::thousands) ?: "") }
    td("nowrap") {
        if (viewer.ownToken) {
            chip("Its own", Tone.OK)
        } else {
            span("muted") {
                attributes["title"] = "Pair it again to give it a token of its own"
                +"Shared API token"
            }
        }
    }
    td("nowrap") {
        if (preferences == null) {
            span("muted") { +"not yet" }
        } else {
            ago(preferences.updatedAt, chrome.now)
        }
    }
    td("nowrap") { ago(viewer.lastSeenAt, chrome.now) }
    td("nowrap") { ago(viewer.firstSeenAt, chrome.now) }
    td {
        postForm(
            "/admin/viewers/${viewer.clientId}/forget",
            chrome.session,
            keepScroll = true,
        ) {
            button(type = ButtonType.submit, classes = "small") {
                attributes["aria-label"] =
                    "Forget ${viewer.name.ifEmpty { viewer.clientId }}"
                +"Forget"
            }
        }
    }
}

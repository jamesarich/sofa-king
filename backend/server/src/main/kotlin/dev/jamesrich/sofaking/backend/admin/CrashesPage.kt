package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.ClientCrash
import kotlinx.html.TD
import kotlinx.html.caption
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.small
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.tr

/** The crashes the apps reported, [crashes] newest first. */
internal fun crashesPage(chrome: Chrome, crashes: List<ClientCrash>): String =
    layout("Crashes", chrome, "/admin/system/crashes") {
        pageHead(
            "Crashes",
            "The crashes the apps reported to this server, newest first, with the device, the app and system " +
                "versions and the stack frames. Nothing leaves this server; the apps' Send crash reports setting " +
                "turns it off per device.",
        )
        tabs(chrome, "/admin/system/crashes", SYSTEM_TABS)
        panel("Reported crashes", id = "crashes") {
            if (crashes.isEmpty()) {
                p("muted") { +"No app has reported a crash." }
                return@panel
            }
            div("scroll") {
                table {
                    caption("sr") { +"Crashes the apps reported" }
                    thead {
                        tr {
                            col("Received")
                            col("Device")
                            col("App")
                            col("System")
                            col("Crash")
                        }
                    }
                    tbody {
                        for (crash in crashes) {
                            tr {
                                td("nowrap") { ago(crash.receivedAt, chrome.now) }
                                td { +crash.clientName.ifEmpty { crash.device } }
                                td("nowrap") { +"${crash.appVersion} (${crash.platform})" }
                                td("nowrap") { +crash.osVersion }
                                td { crashDetail(crash) }
                            }
                        }
                    }
                }
            }
        }
    }

private fun TD.crashDetail(crash: ClientCrash) {
    details {
        summary {
            +crash.exception
            if (crash.message.isNotEmpty()) small("muted") { +" ${crash.message}" }
        }
        pre("mono small") { +crash.frames.joinToString("\n") }
    }
}

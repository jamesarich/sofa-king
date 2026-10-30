package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.iptvorg.IptvOrgHold
import dev.jamesrich.sofaking.backend.iptvorg.IptvOrgOverview
import dev.jamesrich.sofaking.backend.iptvorg.IptvOrgProposal
import dev.jamesrich.sofaking.backend.iptvorg.IptvOrgStatus
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.html.small
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.tr

internal fun iptvOrgPage(chrome: Chrome, view: IptvOrgOverview, error: String? = null): String =
    layout("iptv-org", chrome, "/admin/iptv-org") {
        pageHead(
            "iptv-org",
            "The iptv-org community database: channel names, logos, categories and a blocklist, and optionally its " +
                "streams. Both parts are off until you turn them on in Settings.",
        )
        tabs(chrome, "/admin/iptv-org", SOURCE_TABS)
        notice(error?.let { "Not saved: $it" })
        statusPanel(chrome, view.status)
        panel("Proposed matches", id = "proposals") {
            if (view.proposals.isEmpty()) {
                p("muted") { +"No station waits for a decision." }
            } else {
                proposalTable(chrome, view.proposals)
            }
        }
        panel("Held out by the blocklist", id = "holds") {
            if (view.holds.isEmpty()) {
                p("muted") { +"No station is held out." }
            } else {
                holdTable(chrome, view.holds)
            }
        }
    }

private fun FlowContent.statusPanel(chrome: Chrome, status: IptvOrgStatus) = panel("Status", id = "status") {
    dl {
        dt { +"Metadata" }
        dd { +(if (status.metadata) "on" else "off") }
        dt { +"Streams" }
        dd { +(if (status.streams) "on" else "off") }
        dt { +"Region" }
        dd { +"${status.region.countries.joinToString()} (${status.region.detail})" }
        dt { +"Last download" }
        dd { +(status.attemptedAt ?: "never") }
        dt { +"Next download" }
        dd { +(status.nextDueAt ?: "none scheduled") }
        dt { +"Held" }
        dd {
            +(
                "${status.channels} channels, ${status.logos} logos, ${status.blocklisted} blocklisted, " +
                    "${status.streamCount} streams"
                )
        }
        dt { +"Matching" }
        dd {
            +(
                "${status.matched} stations matched, ${status.proposals} waiting for a decision, " +
                    "${status.held} held out by the blocklist"
                )
        }
    }
    for (note in status.notes) p("muted small") { +note }
    postForm("/admin/iptv-org/refresh", chrome.session, keepScroll = true) {
        button(type = ButtonType.submit, classes = "small") { +"Download now" }
    }
    p("muted small") {
        +"Turn the parts on or off and set the region under "
        a("/admin/settings#runtime") { +"Settings" }
        +". The server downloads from iptv-org.github.io only while a part is on."
    }
}

private fun FlowContent.holdTable(chrome: Chrome, holds: List<IptvOrgHold>) = div("scroll") {
    table {
        caption("sr") { +"Stations held out by the iptv-org blocklist" }
        thead {
            tr {
                col("Station")
                col("iptv-org channel")
                col("Reason")
                col("Reference")
                col("Decide")
            }
        }
        tbody {
            for (hold in holds) {
                tr {
                    td { +hold.station }
                    td { code { +hold.channelId } }
                    td { +(hold.reason ?: "") }
                    td { +(hold.ref ?: "") }
                    td { decideForm(chrome, hold.station, null, "dismiss", "List it") }
                }
            }
        }
    }
}

private fun FlowContent.proposalTable(chrome: Chrome, proposals: List<IptvOrgProposal>) = div("scroll") {
    table {
        caption("sr") { +"Proposed iptv-org channels" }
        thead {
            tr {
                col("Station")
                col("iptv-org channel")
                col("Basis")
                col("Decide")
            }
        }
        tbody {
            for (proposal in proposals) {
                tr {
                    td { +proposal.name }
                    td {
                        +proposal.channelName
                        small("muted") {
                            +" "
                            code { +proposal.channelId }
                        }
                    }
                    td { +(if (proposal.ambiguous) "${proposal.basis}, ambiguous" else proposal.basis) }
                    td {
                        div("actions") {
                            decideForm(chrome, proposal.station, proposal.channelId, "accept", "Accept")
                            decideForm(chrome, proposal.station, null, "dismiss", "Dismiss")
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.decideForm(
    chrome: Chrome,
    station: String,
    channelId: String?,
    decision: String,
    label: String,
) = postForm("/admin/iptv-org/matches", chrome.session, keepScroll = true) {
    hiddenInput(name = "station") { value = station }
    hiddenInput(name = "decision") { value = decision }
    if (channelId != null) hiddenInput(name = "channelId") { value = channelId }
    button(type = ButtonType.submit, classes = "small") {
        attributes["aria-label"] = "$label for $station"
        +label
    }
}

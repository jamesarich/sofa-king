package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.SourcePin
import kotlinx.html.DIV
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.hidden
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.tr

internal class RequiredRow(val station: PolicyStation, val pins: List<SourcePin>, val health: Map<String, Int>)

internal class RequiredView(val rows: List<RequiredRow>, val ran: Boolean)

internal fun requiredPage(chrome: Chrome, view: RequiredView?): String = layout("Required", chrome, "/admin/required") {
    pageHead(
        "Required channels",
        "Channels the policy keeps whatever happens to their sources. Off ones are listed first.",
    )
    when {
        view == null -> noPolicy("Required channels")

        else -> {
            tabs(chrome, "/admin/required", CHANNEL_TABS)
            requiredChannels(view)
        }
    }
}

private fun MAIN.requiredChannels(view: RequiredView) {
    if (!view.ran) {
        div("panel") { p("muted") { +"No policy run has built the lineup yet." } }
        return
    }
    if (view.rows.isEmpty()) {
        div("panel") {
            p("muted") {
                +(
                    "No station is marked required in the policy. Set \"required\": true in a station override to " +
                        "keep it whatever its sources do."
                    )
            }
        }
        return
    }
    val off = view.rows.count { !it.station.enabled }
    div("tiles") {
        div("tile" + if (off > 0) " bad" else "") {
            span("label") { +"On" }
            span("value") {
                +thousands(view.rows.size - off)
                small { +" of ${thousands(view.rows.size)}" }
            }
        }
        div("tile" + if (view.rows.any { it.station.offeringIds.isEmpty() }) " bad" else "") {
            span("label") { +"With no live source" }
            span("value") { +thousands(view.rows.count { it.station.offeringIds.isEmpty() }) }
        }
    }
    div("panel tight") { div("scroll") { requiredTable(view) } }
}

private fun DIV.requiredTable(view: RequiredView) = table {
    caption("sr") { +"Required channels" }
    thead {
        tr {
            col("No.", "num")
            col("Station")
            col("Pins")
            col("Live sources", "num")
            col("Health")
            col("State")
        }
    }
    tbody {
        val order = compareBy<RequiredRow>({ it.station.enabled }, {
            it.station.number.toDoubleOrNull()
                ?: Double.MAX_VALUE
        })
        for (row in view.rows.sortedWith(order)) {
            tr {
                val station = row.station
                td("num") { +station.number }
                td {
                    a("/admin/lineup/${station.id}", classes = "rowlink") { +station.name }
                    br()
                    span("muted small mono") { +station.key }
                }
                td("small") {
                    if (row.pins.isEmpty()) {
                        span("muted") { +"none" }
                    } else {
                        for (pin in row.pins) {
                            div {
                                +listOfNotNull(pin.provider, pin.tvgId, pin.name).joinToString(" / ")
                            }
                        }
                    }
                }
                td("num") {
                    if (station.offeringIds.isEmpty()) chip("0", Tone.BAD) else +station.offeringIds.size.toString()
                }
                td("small") { +row.health.entries.joinToString(", ") { "${it.value} ${it.key.lowercase()}" } }
                td {
                    if (station.enabled) {
                        chip("On", Tone.OK)
                    } else {
                        chip("Off · ${station.disabledReason ?: "hidden"}", Tone.BAD)
                    }
                }
            }
        }
    }
}

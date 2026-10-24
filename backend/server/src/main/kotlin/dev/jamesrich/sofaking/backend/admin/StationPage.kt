package dev.jamesrich.sofaking.backend.admin

import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.MAIN
import kotlinx.html.TD
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h3
import kotlinx.html.hidden
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.map
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.select
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.thead
import kotlinx.html.tr

internal class SourceLine(
    val id: String,
    val provider: String,
    val channel: String?,
    val protocol: String,
    val status: String,
    val disabled: Boolean = false,
)

internal class StationView(
    val row: StationRow,
    val sources: List<SourceLine>,
    val guideName: String?,
    val filter: LineupFilter,
    val revision: String?,
    val groupChoices: List<String>,
    val error: String? = null,
    /** Whether a probe worker runs, so "Probe now" has somewhere to go. */
    val probes: Boolean = false,
    /** A source the operator asked to disable, waiting for the confirmation. */
    val disableConfirm: SourceLine? = null,
) {
    constructor(
        view: StationView,
        disableConfirm: SourceLine?,
    ) : this(
        view.row, view.sources, view.guideName, view.filter, view.revision, view.groupChoices, view.error,
        view.probes, disableConfirm,
    )
}

internal fun stationPage(chrome: Chrome, view: StationView): String =
    layout(view.row.entry.name, chrome, "/admin/lineup") {
        val entry = view.row.entry
        val back = "/admin/lineup" + view.filter.query().let { if (it.isEmpty()) "" else "?$it" }
        p("mt-16") { a(back) { +"← Channels" } }
        h1 { +"${entry.number} · ${entry.name}" }
        div("chips mb-18") {
            stateChip(view.row).let { (text, tone) -> chip(text, tone) }
            if (view.row.required) chip("Required", Tone.WARN)
            guideChip(view.row.guideStatus).let { (text, tone) -> chip(text, tone) }
            entry.group?.let { chip(it, Tone.OFF) }
        }
        div("cols") {
            stationEdit(chrome, view, back)
            stationVisibility(chrome, view)
        }
        panel("Details") {
            stationDetails(view)
            h3 { +"Sources, in failover order" }
            if (view.sources.isEmpty()) {
                p("muted") { +"No source carries this channel right now." }
            } else {
                div("scroll") { stationSources(chrome, view) }
            }
            p("muted small") {
                +(
                    "A disabled source is never selected or probed until it is enabled again; a session playing it " +
                        "moves to the next source. "
                    )
                +(
                    if (view.probes) {
                        "Probe now puts the source at the front of the probe queue; its health updates " +
                            "within a minute or so."
                    } else {
                        "Probes are off on this server, so there is no Probe now."
                    }
                    )
            }
        }
        view.disableConfirm?.let { disableDialog(chrome, view, it, back) }
    }

private fun FlowContent.stationEdit(chrome: Chrome, view: StationView, back: String) = panel("Edit") {
    val entry = view.row.entry
    if (view.error != null) notice("Not saved: ${view.error}")
    form(action = "/admin/lineup/${entry.id}/edit", method = FormMethod.post, classes = "stack") {
        hiddenInput(name = "csrf") { value = chrome.session.csrf }
        view.revision?.let { revision -> hiddenInput(name = "revision") { value = revision } }
        filterFields(view.filter)
        div("formgrid") {
            div("field span-all") {
                label {
                    htmlFor = "s-name"
                    +"Name"
                }
                textInput(name = "name") {
                    id = "s-name"
                    value = entry.name
                    required = true
                    maxLength = "256"
                }
            }
            div("field") {
                label {
                    htmlFor = "s-number"
                    +"Number"
                }
                textInput(name = "number") {
                    id = "s-number"
                    value = entry.number
                    required = true
                    maxLength = "32"
                    attributes["inputmode"] = "decimal"
                }
            }
            groupField(view)
        }
        if (view.revision != null) {
            p("muted small") {
                +(
                    "Saving writes these fields into the station's override in the policy, then applies " +
                        "it. Pick \"Policy decides\" to let the group map and patterns choose."
                    )
            }
        }
        div("actions") {
            button(type = ButtonType.submit, classes = "primary") { +"Save" }
            a(back, classes = "btn quiet") { +"Cancel" }
        }
    }
}

private fun DIV.groupField(view: StationView) = div("field") {
    val entry = view.row.entry
    label {
        htmlFor = "s-group"
        +"Group"
    }
    // A list, so a typo cannot make a group with no number block.
    select {
        name = "group"
        id = "s-group"
        option {
            value = ""
            selected = entry.group == null
            +(if (view.revision != null) "Policy decides" else "No group")
        }
        for (group in (view.groupChoices + listOfNotNull(entry.group)).distinct().sorted()) {
            option {
                value = group
                selected = group == entry.group
                +group
            }
        }
    }
}

private fun FlowContent.stationVisibility(chrome: Chrome, view: StationView) = panel("Visibility") {
    val entry = view.row.entry
    p("muted") {
        +(
            if (entry.enabled) {
                "On: viewers see this channel in the app, the M3U playlist and Plex."
            } else {
                "Off: hidden from the app, the M3U playlist and Plex."
            }
            )
    }
    form(action = "/admin/lineup/${entry.id}/visibility", method = FormMethod.post) {
        hiddenInput(name = "csrf") { value = chrome.session.csrf }
        view.revision?.let { revision -> hiddenInput(name = "revision") { value = revision } }
        hiddenInput(name = "from") { value = "station" }
        filterFields(view.filter)
        hiddenInput(name = "enabled") { value = (!entry.enabled).toString() }
        button(type = ButtonType.submit, classes = if (entry.enabled) "danger" else "primary") {
            +(if (entry.enabled) "Hide this channel" else "Show this channel")
        }
    }
}

private fun DIV.stationDetails(view: StationView) = dl("kv") {
    val entry = view.row.entry
    view.row.station?.let {
        dt { +"Station key" }
        dd { code { +it.key } }
    }
    dt { +"Guide" }
    dd { +(view.guideName ?: entry.guide?.let { "${it.guideId} / ${it.channelId}" } ?: "None mapped") }
    if (!entry.enabled) {
        dt { +"Why off" }
        dd { +(view.row.station?.disabledReason ?: entry.autoDisabledReason ?: "hidden by hand") }
    }
    dt { +"Made by" }
    dd { +(if (entry.createdBy == "policy") "The lineup policy" else "Hand") }
}

private fun DIV.stationSources(chrome: Chrome, view: StationView) = table {
    caption("sr") { +"Sources, in failover order" }
    thead {
        tr {
            col("#", "num")
            col("Provider")
            col("Channel")
            col("Protocol")
            col("Health")
            col("Actions")
        }
    }
    tbody {
        view.sources.forEachIndexed { index, source ->
            tr(flashClass(chrome, source.id)) {
                td("num") { +(index + 1).toString() }
                td { +source.provider }
                td { +(source.channel ?: "") }
                td { +source.protocol }
                td {
                    val tone = when (source.status) {
                        "HEALTHY" -> Tone.OK
                        "UNVERIFIED", "PROBATION" -> Tone.WARN
                        "WITHDRAWN", "ARCHIVED" -> Tone.OFF
                        else -> Tone.BAD
                    }
                    chip(source.status.lowercase().replaceFirstChar { it.uppercase() }, tone)
                    if (source.disabled) {
                        +" "
                        chip("Disabled by hand", Tone.OFF)
                    }
                }
                td { if (source.status != "WITHDRAWN") sourceActions(chrome, view, source, index + 1) }
            }
        }
    }
}

private fun TD.sourceActions(chrome: Chrome, view: StationView, source: SourceLine, number: Int) = div("actions") {
    val entry = view.row.entry
    postForm("/admin/lineup/${entry.id}/sources/${source.id}/disable", chrome.session, keepScroll = true) {
        filterFields(view.filter)
        hiddenInput(name = "disabled") { value = (!source.disabled).toString() }
        button(type = ButtonType.submit, classes = "small") {
            attributes["aria-label"] =
                (if (source.disabled) "Enable" else "Disable") + " source $number from ${source.provider}"
            +(if (source.disabled) "Enable" else "Disable")
        }
    }
    if (view.probes && !source.disabled && source.status != "ARCHIVED") {
        postForm("/admin/lineup/${entry.id}/sources/${source.id}/probe", chrome.session, keepScroll = true) {
            filterFields(view.filter)
            button(type = ButtonType.submit, classes = "small quiet") {
                attributes["aria-label"] = "Probe source $number from ${source.provider} now"
                +"Probe now"
            }
        }
    }
}

private fun MAIN.disableDialog(chrome: Chrome, view: StationView, source: SourceLine, back: String) = askDialog(
    chrome,
    "Disable source ${view.sources.indexOf(source) + 1} from ${source.provider}?",
    "/admin/lineup/${view.row.entry.id}/sources/${source.id}/disable",
    back,
    "Disable",
    danger = true,
) {
    filterFields(view.filter)
    hiddenInput(name = "disabled") { value = "true" }
    p {
        +(
            "The channel stops using this source until you enable it again. Anyone watching it now moves " +
                "to the next source, or loses the channel when this is the only one."
            )
    }
}

internal fun goneStationPage(chrome: Chrome): String = layout("Channel not found", chrome, "/admin/lineup") {
    pageHead(
        "Channel not found",
        "That channel is no longer in the lineup. A policy run may have merged or removed it.",
    )
    a("/admin/lineup", classes = "btn") { +"Back to channels" }
}

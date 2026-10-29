package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.TBODY
import kotlinx.html.TD
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.select
import kotlinx.html.slot
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.time
import kotlinx.html.title
import kotlinx.html.tr

internal fun recordingsPage(chrome: Chrome, view: RecordingsView): String = layout("DVR", chrome, "/admin/recordings") {
    pageHead(
        "DVR",
        "What the server records from the guide: series rules, the schedule they make, and the recordings on " +
            "disk with the space they take.",
    )
    tabs(chrome, "/admin/recordings", RECORDING_TABS)
    storagePanel(view)
    val upcoming = view.list.recordings.filter {
        it.state == Wire.RecordingState.SCHEDULED || it.state == Wire.RecordingState.RECORDING
    }
    upcomingPanel(chrome, view, upcoming)
    rulesPanel(chrome, view)
    val past = view.list.recordings - upcoming.toSet()
    recordedPanel(chrome, past)
}

private fun FlowContent.storagePanel(view: RecordingsView) = panel("Storage", id = "storage") {
    val storage = view.list.storage
    dl("kv") {
        dt { +"Folder" }
        dd { code { +view.folder } }
        dt { +"Recordings" }
        dd { +bytesText(storage.usedBytes) }
        if (storage.totalBytes > 0) {
            dt { +"Disk" }
            dd { +"${bytesText(storage.freeBytes)} free of ${bytesText(storage.totalBytes)}" }
        }
        dt { +"Cleanup" }
        dd {
            +"Keeps ${view.retention.freeGib} GiB free"
            +(
                if (view.retention.keepDays > 0) {
                    " and recordings for ${plural(view.retention.keepDays, "day")}"
                } else {
                    " with no age limit"
                }
                )
            +", deleting watched recordings, then the oldest, first; protected ones stay. "
            a("/admin/settings#runtime") { +"Change" }
        }
    }
}

private fun FlowContent.upcomingPanel(chrome: Chrome, view: RecordingsView, upcoming: List<Recording>) =
    panel("Upcoming", id = "upcoming") {
        if (upcoming.isEmpty()) {
            p("muted mt-0") {
                +(
                    "Nothing is scheduled. Add a series rule below, record a program from the app's program " +
                        "details, or record a time window here."
                    )
            }
        } else {
            upcomingTable(chrome, upcoming)
        }
        if (upcoming.any { it.conflict }) {
            p("muted small") {
                +(
                    "A conflict has no playback slot when it is due: more recordings overlap than " +
                        "SOFA_MAX_SESSIONS allows. One-offs win over series rules, then the higher rule priority."
                    )
            }
        }
        manualRecording(chrome, view)
    }

private fun FlowContent.upcomingTable(chrome: Chrome, upcoming: List<Recording>) = div("scroll") {
    table {
        caption("sr") { +"Upcoming recordings" }
        thead {
            tr {
                col("Program")
                col("Channel")
                col("Starts")
                col("State")
                col("Action")
            }
        }
        tbody {
            for (recording in upcoming) {
                recordingRow(chrome, recording) {
                    val label = if (recording.state == Wire.RecordingState.RECORDING) {
                        "Stop"
                    } else {
                        "Cancel"
                    }
                    postForm(
                        "/admin/recordings/${recording.id}/delete",
                        chrome.session,
                        keepScroll = true,
                    ) {
                        button(type = ButtonType.submit, classes = "small") {
                            attributes["aria-label"] =
                                "$label ${recording.title}"
                            +label
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.manualRecording(chrome: Chrome, view: RecordingsView) = details("advanced") {
    summary { +"Record a time window" }
    postForm("/admin/recordings/manual", chrome.session, classes = "stack") {
        div("field") {
            label {
                htmlFor = "manual-channel"
                +"Channel"
            }
            select {
                id = "manual-channel"
                name = "channelId"
                required = true
                for ((id, name) in view.allChannels) {
                    option {
                        value = id
                        +name
                    }
                }
            }
        }
        div("field") {
            label {
                htmlFor = "manual-start"
                +"Starts (${view.zone.id})"
            }
            input(InputType.dateTimeLocal, name = "start") {
                id = "manual-start"
                required = true
            }
        }
        numberField("manual-minutes", "minutes", "Minutes", "60", MANUAL_MINUTES)
        div("field") {
            label {
                htmlFor = "manual-title"
                +"Title (the channel's name when blank)"
            }
            textInput(name = "title") {
                id = "manual-title"
                maxLength = "200"
            }
        }
        div("actions") { button(type = ButtonType.submit, classes = "primary") { +"Record" } }
    }
    p("muted small") {
        +(
            "Records the channel for exactly that window, with no padding, whether or not it has a " +
                "guide. Use it for a channel without one, or a program the guide has wrong."
            )
    }
}

private fun FlowContent.recordedPanel(chrome: Chrome, past: List<Recording>) = panel("Recorded", id = "recorded") {
    if (past.isEmpty()) {
        p("muted mt-0") { +"No recordings yet." }
        return@panel
    }
    div("scroll") {
        table {
            caption("sr") { +"Recordings" }
            thead {
                tr {
                    col("Program")
                    col("Channel")
                    col("Aired")
                    col("State")
                    col("Action")
                }
            }
            tbody {
                for (recording in past) {
                    recordingRow(chrome, recording) {
                        if (recording.state == Wire.RecordingState.COMPLETED) {
                            postForm(
                                "/admin/recordings/${recording.id}/protect",
                                chrome.session,
                                keepScroll = true,
                            ) {
                                hiddenInput(name = "protected") { value = (!recording.protected).toString() }
                                button(type = ButtonType.submit, classes = "small") {
                                    +(if (recording.protected) "Unprotect" else "Protect")
                                }
                            }
                            +" "
                        }
                        postForm(
                            "/admin/recordings/${recording.id}/delete",
                            chrome.session,
                            keepScroll = true,
                        ) {
                            button(type = ButtonType.submit, classes = "small") {
                                attributes["aria-label"] =
                                    "Delete ${recording.title}"
                                +"Delete"
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun TBODY.recordingRow(chrome: Chrome, recording: Recording, actions: TD.() -> Unit) =
    tr(flashClass(chrome, recording.id)) {
        id = "recording-${recording.id}"
        th {
            attributes["scope"] = "row"
            +recordingName(recording)
            recording.path?.takeIf { recording.state == Wire.RecordingState.COMPLETED }?.let {
                br
                span("muted small mono") { +it }
            }
        }
        td { +recording.channelName }
        td("nowrap") { ago(recording.startsAt, chrome.now) }
        td {
            val (text, tone) = stateChip(recording)
            chip(text, tone)
            if (recording.protected) {
                +" "
                chip("Protected", Tone.OFF)
            }
            if (recording.state == Wire.RecordingState.COMPLETED) {
                span("muted small") { +" ${bytesText(recording.bytes)}" }
            }
            recording.reason?.let { span("muted small") { +" $it" } }
        }
        td("nowrap") { actions() }
    }

/** A recording's state as the portal words and colors it. */
internal fun stateChip(recording: Recording): Pair<String, Tone> = when (recording.state) {
    Wire.RecordingState.RECORDING -> "Recording" to Tone.OK
    Wire.RecordingState.SCHEDULED -> if (recording.conflict) "Conflict" to Tone.WARN else "Scheduled" to Tone.OFF
    Wire.RecordingState.COMPLETED -> if (recording.reason != null) "Partial" to Tone.WARN else "Recorded" to Tone.OK
    else -> "Failed" to Tone.BAD
}

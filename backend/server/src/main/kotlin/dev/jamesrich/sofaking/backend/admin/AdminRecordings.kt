package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.dvr.Retention
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.RecordingList
import dev.jamesrich.sofaking.contract.RecordingRule
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.html.*

internal class RecordingsView(val list: RecordingList, val rules: List<RecordingRule>, val channels: Map<String, String>, val folder: String, val retention: Retention)

/** A recording's name as a list shows it: the show, then its season and episode or episode title. */
internal fun recordingName(recording: Recording): String {
    val numbering = if (recording.season != null && recording.episode != null) "S%02dE%02d".format(recording.season, recording.episode) else null
    return listOfNotNull(recording.title, numbering, recording.episodeTitle).joinToString(" · ")
}

private fun stateChip(recording: Recording): Pair<String, Tone> = when (recording.state) {
    Wire.RecordingState.RECORDING -> "Recording" to Tone.OK
    Wire.RecordingState.SCHEDULED -> if (recording.conflict) "Conflict" to Tone.WARN else "Scheduled" to Tone.OFF
    Wire.RecordingState.COMPLETED -> if (recording.reason != null) "Partial" to Tone.WARN else "Recorded" to Tone.OK
    else -> "Failed" to Tone.BAD
}

internal fun recordingsPage(chrome: Chrome, view: RecordingsView): String = layout("Recordings", chrome, "/admin/recordings") {
    val now = chrome.now
    pageHead("Recordings", "What the server records from the guide: series rules, the schedule they make, and the recordings on disk with the space they take.")
    val storage = view.list.storage
    panel("Storage", id = "storage") {
        dl("kv") {
            dt { +"Folder" }; dd { code { +view.folder } }
            dt { +"Recordings" }; dd { +bytesText(storage.usedBytes) }
            if (storage.totalBytes > 0) {
                dt { +"Disk" }; dd { +"${bytesText(storage.freeBytes)} free of ${bytesText(storage.totalBytes)}" }
            }
            dt { +"Cleanup" }
            dd {
                +"Keeps ${view.retention.freeGib} GiB free"
                +(if (view.retention.keepDays > 0) " and recordings for ${plural(view.retention.keepDays, "day")}" else " with no age limit")
                +", deleting watched recordings, then the oldest, first; protected ones stay. "
                a("/admin/settings#runtime") { +"Change" }
            }
        }
    }
    val upcoming = view.list.recordings.filter { it.state == Wire.RecordingState.SCHEDULED || it.state == Wire.RecordingState.RECORDING }
    panel("Upcoming", id = "upcoming") {
        if (upcoming.isEmpty()) { p("muted mt-0") { +"Nothing is scheduled. Add a series rule below, or record a program from the app's program details." }; return@panel }
        div("scroll") {
            table {
                caption("sr") { +"Upcoming recordings" }
                thead { tr { col("Program"); col("Channel"); col("Starts"); col("State"); col("Action") } }
                tbody {
                    for (recording in upcoming) recordingRow(chrome, recording, now) {
                        val label = if (recording.state == Wire.RecordingState.RECORDING) "Stop" else "Cancel"
                        postForm("/admin/recordings/${recording.id}/delete", chrome.session, keepScroll = true) {
                            button(type = ButtonType.submit, classes = "small") { attributes["aria-label"] = "$label ${recording.title}"; +label }
                        }
                    }
                }
            }
        }
        if (upcoming.any { it.conflict }) p("muted small") { +"A conflict has no playback slot when it is due: more recordings overlap than SOFA_MAX_SESSIONS allows. One-offs win over series rules, then the higher rule priority." }
    }
    panel("Series rules", id = "rules") {
        if (view.rules.isNotEmpty()) div("scroll") {
            table {
                caption("sr") { +"Series rules" }
                thead { tr { col("Title"); col("Channel"); col("Episodes"); col("Keep", "num"); col("Priority", "num"); col("Action") } }
                tbody {
                    for (rule in view.rules) tr(chrome.flash?.takeIf { it.target == rule.id }?.let { if (it.tone == Tone.BAD) "err-row" else "flash" }) {
                        id = "rule-${rule.id}"
                        th { attributes["scope"] = "row"; +rule.title }
                        td { +(rule.channelId?.let { view.channels[it] ?: it } ?: "Any channel") }
                        td { +(if (rule.newOnly) "New only" else "All airings") }
                        td("num") { +(if (rule.keep == 0) "All" else thousands(rule.keep)) }
                        td("num") { +rule.priority.toString() }
                        td {
                            postForm("/admin/recordings/rules/${rule.id}/delete", chrome.session, keepScroll = true) {
                                button(type = ButtonType.submit, classes = "small") { attributes["aria-label"] = "Delete the rule for ${rule.title}"; +"Delete" }
                            }
                        }
                    }
                }
            }
        }
        postForm("/admin/recordings/rules", chrome.session, classes = "stack") {
            div("field") {
                label { htmlFor = "rule-title"; +"Title" }
                textInput(name = "title") { id = "rule-title"; required = true; maxLength = "200"; placeholder = "As the guide names it" }
            }
            div("field") {
                label { htmlFor = "rule-channel"; +"Channel" }
                select {
                    id = "rule-channel"; name = "channelId"
                    option { value = ""; +"Any channel" }
                    for ((id, name) in view.channels) option { value = id; +name }
                }
            }
            div("field") {
                label { htmlFor = "rule-keep"; +"Keep (0 for all)" }
                numberInput(name = "keep") { id = "rule-keep"; value = "0"; min = "0"; max = "1000"; required = true }
            }
            div("field") {
                label {
                    checkBoxInput(name = "newOnly") { value = "true"; checked = true }
                    +" New episodes only"
                }
            }
            div("actions") { button(type = ButtonType.submit, classes = "primary") { +"Add the rule" } }
        }
        p("muted small") { +"A rule records every guide airing whose title matches, ignoring case, and skips an episode it already holds. Keep deletes the rule's oldest recordings beyond that count." }
    }
    val past = view.list.recordings - upcoming.toSet()
    panel("Recorded", id = "recorded") {
        if (past.isEmpty()) { p("muted mt-0") { +"No recordings yet." }; return@panel }
        div("scroll") {
            table {
                caption("sr") { +"Recordings" }
                thead { tr { col("Program"); col("Channel"); col("Aired"); col("State"); col("Action") } }
                tbody {
                    for (recording in past) recordingRow(chrome, recording, now) {
                        if (recording.state == Wire.RecordingState.COMPLETED) {
                            postForm("/admin/recordings/${recording.id}/protect", chrome.session, keepScroll = true) {
                                hiddenInput(name = "protected") { value = (!recording.protected).toString() }
                                button(type = ButtonType.submit, classes = "small") { +(if (recording.protected) "Unprotect" else "Protect") }
                            }
                            +" "
                        }
                        postForm("/admin/recordings/${recording.id}/delete", chrome.session, keepScroll = true) {
                            button(type = ButtonType.submit, classes = "small") { attributes["aria-label"] = "Delete ${recording.title}"; +"Delete" }
                        }
                    }
                }
            }
        }
    }
}

private fun TBODY.recordingRow(chrome: Chrome, recording: Recording, now: java.time.Instant, actions: TD.() -> Unit) =
    tr(chrome.flash?.takeIf { it.target == recording.id }?.let { if (it.tone == Tone.BAD) "err-row" else "flash" }) {
        id = "recording-${recording.id}"
        th {
            attributes["scope"] = "row"
            +recordingName(recording)
            recording.path?.takeIf { recording.state == Wire.RecordingState.COMPLETED }?.let { br; span("muted small mono") { +it } }
        }
        td { +recording.channelName }
        td("nowrap") { ago(recording.startsAt, now) }
        td {
            val (text, tone) = stateChip(recording)
            chip(text, tone)
            if (recording.protected) { +" "; chip("Protected", Tone.OFF) }
            if (recording.state == Wire.RecordingState.COMPLETED) span("muted small") { +" ${bytesText(recording.bytes)}" }
            recording.reason?.let { span("muted small") { +" $it" } }
        }
        td("nowrap") { actions() }
    }

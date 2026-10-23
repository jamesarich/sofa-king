package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.dvr.Retention
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.RecordingList
import dev.jamesrich.sofaking.contract.RecordingRule
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.InputType
import kotlinx.html.S
import kotlinx.html.TBODY
import kotlinx.html.TD
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.checkBoxInput
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.numberInput
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

/**
 * [channels] are the ones with a guide, which a rule may name; [allChannels] are every enabled one, which a manual
 * recording may name. [zone] is the time zone the manual recording form's times are read in.
 */
internal class RecordingsView(
    val list: RecordingList,
    val rules: List<RecordingRule>,
    val channels: Map<String, String>,
    val folder: String,
    val retention: Retention,
    val allChannels: Map<String, String> = channels,
    val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
)

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
        if (upcoming.isEmpty()) p("muted mt-0") { +"Nothing is scheduled. Add a series rule below, record a program from the app's program details, or record a time window here." }
        else div("scroll") {
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
        details("advanced") {
            summary { +"Record a time window" }
            postForm("/admin/recordings/manual", chrome.session, classes = "stack") {
                div("field") {
                    label { htmlFor = "manual-channel"; +"Channel" }
                    select {
                        id = "manual-channel"; name = "channelId"; required = true
                        for ((id, name) in view.allChannels) option { value = id; +name }
                    }
                }
                div("field") {
                    label { htmlFor = "manual-start"; +"Starts (${view.zone.id})" }
                    input(InputType.dateTimeLocal, name = "start") { id = "manual-start"; required = true }
                }
                div("field") {
                    label { htmlFor = "manual-minutes"; +"Minutes" }
                    numberInput(name = "minutes") { id = "manual-minutes"; value = "60"; min = "1"; max = "720"; required = true }
                }
                div("field") {
                    label { htmlFor = "manual-title"; +"Title (the channel's name when blank)" }
                    textInput(name = "title") { id = "manual-title"; maxLength = "200" }
                }
                div("actions") { button(type = ButtonType.submit, classes = "primary") { +"Record" } }
            }
            p("muted small") { +"Records the channel for exactly that window, with no padding, whether or not it has a guide. Use it for a channel without one, or a program the guide has wrong." }
        }
    }
    panel("Series rules", id = "rules") {
        if (view.rules.isNotEmpty()) div("scroll") {
            table {
                caption("sr") { +"Series rules" }
                thead { tr { col("Title"); col("Channel"); col("Episodes"); col("Keep", "num"); col("Padding"); col("Priority", "num"); col("Action") } }
                tbody {
                    for (rule in view.rules) tr(chrome.flash?.takeIf { it.target == rule.id }?.let { if (it.tone == Tone.BAD) "err-row" else "flash" }) {
                        id = "rule-${rule.id}"
                        th { attributes["scope"] = "row"; +rule.title }
                        td { +(rule.channelId?.let { view.channels[it] ?: it } ?: "Any channel") }
                        td { +(if (rule.newOnly) "New only" else "All airings") }
                        td("num") { +(if (rule.keep == 0) "All" else thousands(rule.keep)) }
                        td("nowrap") { +"${minutes(rule.startPaddingSeconds)} before, ${minutes(rule.endPaddingSeconds)} after" }
                        td("num") { +rule.priority.toString() }
                        td("nowrap") {
                            details("inline") {
                                summary("small quiet") { +"Edit" }
                                postForm("/admin/recordings/rules/${rule.id}", chrome.session, classes = "stack") {
                                    ruleFields("rule-${rule.id}", view, rule)
                                    div("actions") { button(type = ButtonType.submit, classes = "small") { attributes["aria-label"] = "Save the rule for ${rule.title}"; +"Save" } }
                                }
                            }
                            +" "
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
            ruleFields("rule", view, null)
            div("actions") { button(type = ButtonType.submit, classes = "primary") { +"Add the rule" } }
        }
        p("muted small") { +"A rule records every guide airing whose title matches, ignoring case, and skips an episode it already holds. Keep deletes the rule's oldest recordings beyond that count. Padding starts early and runs late, in minutes; when recordings conflict, the higher priority gets the slot." }
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

/** The whole number a recordings form sent as [name], [default] when it sent none, or a 400 naming the field as the form labels it. */
internal fun formInt(form: io.ktor.http.Parameters, name: String, label: String, default: Int? = null): Int {
    val text = form[name]?.trim()
    if (text.isNullOrEmpty() && default != null) return default
    return text?.toIntOrNull() ?: throw dev.jamesrich.sofaking.backend.catalog.ApiFailure(400, "INVALID_REQUEST", "$label must be a whole number")
}

private fun minutes(seconds: Int): String = if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"

/** A rule's channel, episodes, keep, padding and priority fields; [rule] fills them for an edit, null for a new rule's defaults. */
private fun FORM.ruleFields(prefix: String, view: RecordingsView, rule: RecordingRule?) {
    div("field") {
        label { htmlFor = "$prefix-channel"; +"Channel" }
        select {
            id = "$prefix-channel"; name = "channelId"
            option { value = ""; selected = rule != null && rule.channelId == null; +"Any channel" }
            for ((id, name) in view.channels) option { value = id; selected = rule?.channelId == id; +name }
        }
    }
    div("field") {
        label { htmlFor = "$prefix-keep"; +"Keep (0 for all)" }
        numberInput(name = "keep") { id = "$prefix-keep"; value = (rule?.keep ?: 0).toString(); min = "0"; max = "1000"; required = true }
    }
    div("field") {
        label { htmlFor = "$prefix-start"; +"Start early (minutes)" }
        numberInput(name = "startPadding") { id = "$prefix-start"; value = ((rule?.startPaddingSeconds ?: 60) / 60).toString(); min = "0"; max = "60"; required = true }
    }
    div("field") {
        label { htmlFor = "$prefix-end"; +"Run late (minutes)" }
        numberInput(name = "endPadding") { id = "$prefix-end"; value = ((rule?.endPaddingSeconds ?: 120) / 60).toString(); min = "0"; max = "60"; required = true }
    }
    div("field") {
        label { htmlFor = "$prefix-priority"; +"Priority (-100 to 100)" }
        numberInput(name = "priority") { id = "$prefix-priority"; value = (rule?.priority ?: 0).toString(); min = "-100"; max = "100"; required = true }
    }
    div("field") {
        label {
            checkBoxInput(name = "newOnly") { value = "true"; checked = rule?.newOnly ?: true }
            +" New episodes only"
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

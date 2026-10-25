package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.ActiveSession
import dev.jamesrich.sofaking.backend.library.VodActivity
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.html.A
import kotlinx.html.AUDIO
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.audio
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr

/** One live session on the overview: the session, its channel's name and what that channel airs now. */
internal class SessionRow(val active: ActiveSession, val channel: String?, val onNow: String?)

/** A library file or recording being played, with what the portal calls it. */
internal class PlayingRow(val activity: VodActivity, val title: String)

/** A session the operator asked to stop, waiting for the confirmation. */
internal class StopConfirm(val row: SessionRow)

/** Home's and Activity's Watching now: the live sessions, then the library files and recordings playing. */
internal fun FlowContent.watchingPanel(chrome: Chrome, sessions: List<SessionRow>, playing: List<PlayingRow>) = panel(
    "Watching now",
    id = "watching",
    aside = { span("muted") { +plural(sessions.size + playing.size, "session") } },
) {
    if (sessions.isEmpty() && playing.isEmpty()) {
        p("muted") { +"Nobody is watching. The sofa is all yours." }
    } else if (sessions.isNotEmpty()) {
        liveSessions(chrome, sessions)
    }
    if (playing.isNotEmpty()) playing(chrome, playing)
    p("muted small") {
        +(
            "Sent counts the bytes relayed to the player over a live session, at the rate of the last 10 seconds " +
                "or so. Stalls are media requests the source refused or dropped; switches are moves to another " +
                "source. Switch moves the session to the channel's next source now: the app follows at once, " +
                "another player sees its stream end once. Direct play serves the file as it is; remux and " +
                "transcode run FFmpeg, and transcodes count toward the conversion limit. Stop ends the session; " +
                "the player sees its stream end and may open a new one."
            )
    }
}

private fun DIV.liveSessions(chrome: Chrome, sessions: List<SessionRow>) = div("scroll") {
    val now = chrome.now
    table {
        caption("sr") { +"Watching now" }
        thead {
            tr {
                col("Channel")
                col("On now")
                col("Player")
                col("Watching for")
                col("Sent", "num")
                col("Source")
                col("Lease ends")
                col("Actions", "sr")
            }
        }
        tbody {
            for (row in sessions) {
                val active = row.active
                tr(flashClass(chrome, active.id)) {
                    td {
                        if (row.channel != null) {
                            a("/admin/lineup/${active.channelId}", classes = "rowlink") { +row.channel }
                        } else {
                            +active.channelId
                        }
                        if (active.busy) {
                            +" "
                            chip("streaming", Tone.OK)
                        }
                        br()
                        span("muted small") { +"via ${active.providerId}" }
                    }
                    td { if (row.onNow != null) +row.onNow else span("muted") { +"No guide" } }
                    td {
                        +(if (active.external) "M3U or Plex" else "Sofa King app")
                        if (active.client != null) {
                            br()
                            span("muted small mono") { +active.client }
                        }
                    }
                    td("nowrap") { +since(active.startedAt, now) }
                    td("num nowrap") {
                        +bytesText(active.bytes)
                        br()
                        span("muted small") { +rateText(active.bitrateKbps) }
                    }
                    td("nowrap") { sourceHealth(active) }
                    td("nowrap") { ago(active.expiresAt, now) }
                    td("nowrap") {
                        val channel = row.channel ?: active.channelId
                        switchButton("/admin/sessions/${active.id}/switch", chrome, "Switch the source of $channel")
                        +" "
                        stopButton("/admin/sessions/${active.id}/stop", chrome, "Stop the session on $channel")
                    }
                }
            }
        }
    }
}

private fun DIV.playing(chrome: Chrome, playing: List<PlayingRow>) = div("scroll") {
    val now = chrome.now
    table {
        caption("sr") { +"Playing from libraries and recordings" }
        thead {
            tr {
                col("Playing")
                col("How")
                col("Watching for")
                col("Lease ends")
                col("Action", "sr")
            }
        }
        tbody {
            for (row in playing) {
                val active = row.activity
                tr(flashClass(chrome, active.id)) {
                    td {
                        +row.title
                        br()
                        span("muted small") { +(if (active.recordingId != null) "Recording" else "Library") }
                    }
                    td {
                        chip(
                            when (active.mode) {
                                Wire.VodMode.DIRECT -> "direct play"
                                Wire.VodMode.REMUX -> "remux"
                                Wire.VodMode.AUDIO -> "audio converted"
                                else -> "transcode"
                            },
                            if (active.mode == Wire.VodMode.TRANSCODE) Tone.WARN else Tone.OK,
                        )
                    }
                    td("nowrap") { +since(active.startedAt.toString(), now) }
                    td("nowrap") { ago(active.expiresAt.toString(), now) }
                    td { stopButton("/admin/vod/${active.id}/stop", chrome, "Stop playing ${row.title}") }
                }
            }
        }
    }
}

/** The session's stalls and source switches, a warning tone once it has stalled. */
private fun FlowContent.sourceHealth(active: ActiveSession) {
    if (active.stalls == 0L) {
        span("muted") { +"no stalls" }
    } else {
        chip(plural(active.stalls.toInt(), "stall"), Tone.WARN)
    }
    if (active.sourceSwitches > 0) {
        br()
        span("muted small") { +plural(active.sourceSwitches, "switch", "switches") }
    }
    if (active.segmentsServed > 0) {
        br()
        span("muted small") { +plural(active.segmentsServed.toInt(), "segment") }
    }
}

/** A rate in kilobits a second, as kb/s or Mb/s. */
internal fun rateText(kbps: Int): String = when {
    kbps <= 0 -> "idle"
    kbps < KBPS_PER_MBPS -> "$kbps kb/s"
    else -> "%.1f Mb/s".format(java.util.Locale.US, kbps.toDouble() / KBPS_PER_MBPS)
}

private const val KBPS_PER_MBPS = 1000

private fun FlowContent.switchButton(action: String, chrome: Chrome, label: String) =
    postForm(action, chrome.session, keepScroll = true) {
        button(type = ButtonType.submit, classes = "small quiet") {
            attributes["aria-label"] = label
            +"Switch"
        }
    }

private fun FlowContent.stopButton(action: String, chrome: Chrome, label: String) =
    postForm(action, chrome.session, keepScroll = true) {
        button(type = ButtonType.submit, classes = "small quiet") {
            attributes["aria-label"] = label
            +"Stop"
        }
    }

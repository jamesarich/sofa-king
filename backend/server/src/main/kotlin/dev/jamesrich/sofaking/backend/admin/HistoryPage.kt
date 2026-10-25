package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.HistoryEntry
import dev.jamesrich.sofaking.backend.api.HistoryQuery
import dev.jamesrich.sofaking.backend.playback.HistoryKind
import dev.jamesrich.sofaking.backend.playback.HistoryReason
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.viewers
import io.ktor.http.Parameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.Duration
import java.time.Instant

/** The periods the History page offers, in days; zero is everything kept. */
private val HISTORY_PERIODS = listOf(
    1 to "Last 24 hours",
    7 to "Last 7 days",
    30 to "Last 30 days",
    0 to "Everything kept",
)
private const val DEFAULT_PERIOD = 7
private const val HISTORY_ROWS = 500

/** An ended session with what the portal calls what it played and who played it. */
internal class HistoryRow(val entry: HistoryEntry, val what: String, val device: String)

internal class HistoryView(
    val rows: List<HistoryRow>,
    val kind: String,
    val device: String,
    val days: Int,
    /** The paired devices to filter by, as client id and name. */
    val devices: List<Pair<String, String>>,
    val available: Boolean,
)

/** The History page under Players: the playback sessions that ended, newest first, with filters. */
internal class HistoryPages(private val portal: Portal) {
    private val runtime get() = portal.runtime

    fun install(route: Route) = with(portal) {
        route.get("/history") { call.html(historyPage(chrome(), historyView(call.request.queryParameters))) }
    }

    private suspend fun historyView(parameters: Parameters): HistoryView {
        val kind = parameters["kind"]?.takeIf { it == HistoryKind.LIVE || it == HistoryKind.VOD }.orEmpty()
        val device = parameters["device"]?.trim().orEmpty()
        val days = parameters["days"]?.toIntOrNull()?.takeIf { d -> HISTORY_PERIODS.any { it.first == d } }
            ?: DEFAULT_PERIOD
        val viewers = runtime.store.viewers()
        val names = viewers.associate { it.clientId to it.name.ifEmpty { it.clientId } }
        val history = runtime.history
            ?: return HistoryView(emptyList(), kind, device, days, names.toList(), available = false)
        val since = if (days == 0) null else portal.clock.instant().minus(Duration.ofDays(days.toLong()))
        val entries = history.list(HistoryQuery(since, kind.ifEmpty { null }, device.ifEmpty { null }, HISTORY_ROWS))
        val what = playedNames(entries)
        val rows = entries.map { entry ->
            val who = entry.clientId?.let { names[it] ?: it } ?: entry.address ?: "Unknown"
            HistoryRow(entry, what(entry), who)
        }
        return HistoryView(rows, kind, device, days, names.toList(), available = true)
    }

    /** What each of [entries] played, as the portal's other pages name it: the channel, recording or library item. */
    private suspend fun playedNames(entries: List<HistoryEntry>): (HistoryEntry) -> String {
        val channels = runtime.lineup.entries().associate { it.id to it.name }
        val items = runtime.libraries?.catalog?.snapshot()?.views.orEmpty()
        val recordings = if (entries.any { it.recordingId != null }) {
            runCatchingCancellable { runtime.dvr?.recordings()?.recordings }.getOrNull().orEmpty().associateBy { it.id }
        } else {
            emptyMap()
        }
        return { entry ->
            when {
                entry.channelId != null -> channels[entry.channelId] ?: entry.channelId

                entry.recordingId != null -> recordings[entry.recordingId]
                    ?.let { listOfNotNull(it.title, it.episodeTitle).joinToString(" · ") } ?: "Removed recording"

                else -> items[entry.itemId]?.item?.let { item ->
                    val episode = item.season?.let { s -> item.episode?.let { e -> "S${s}E$e" } }
                    listOfNotNull(item.showTitle, episode, item.title).joinToString(" · ")
                } ?: "Removed item"
            }
        }
    }
}

internal fun historyPage(chrome: Chrome, view: HistoryView): String = layout("History", chrome, "/admin/history") {
    pageHead(
        "History",
        "Every live channel, library file and recording played here, once its session ended, newest first. The " +
            "server keeps it for SOFA_HISTORY_DAYS days.",
    )
    tabs(chrome, "/admin/history", PLAYER_TABS)
    if (!view.available) {
        div("panel") { p("muted") { +"This server keeps no history." } }
        return@layout
    }
    historyFilters(view)
    if (view.rows.isEmpty()) {
        div("panel") { p("muted") { +"No session ended in this period, or none matches these filters." } }
    } else {
        historyTable(view.rows, chrome.now)
    }
}

private fun FlowContent.historyFilters(view: HistoryView) =
    form(action = "/admin/history", method = FormMethod.get, classes = "filters") {
        attributes["hx-trigger"] = "submit, change"
        attributes["hx-sync"] = "this:replace"
        filterSelect(
            "f-kind",
            "kind",
            "Kind",
            listOf("" to "Everything", HistoryKind.LIVE to "Live TV", HistoryKind.VOD to "Library and recordings"),
            view.kind,
        )
        filterSelect("f-device", "device", "Device", listOf("" to "Every device") + view.devices, view.device)
        filterSelect(
            "f-days",
            "days",
            "Period",
            HISTORY_PERIODS.map {
                it.first.toString() to it.second
            },
            "${view.days}",
        )
        div("actions") {
            button(type = ButtonType.submit) { +"Filter" }
            if (view.kind.isNotEmpty() || view.device.isNotEmpty() || view.days != DEFAULT_PERIOD) {
                a("/admin/history", classes = "btn quiet") { +"Clear" }
            }
        }
    }

private fun FlowContent.filterSelect(
    id: String,
    name: String,
    text: String,
    choices: List<Pair<String, String>>,
    current: String,
) = div("field") {
    label {
        htmlFor = id
        +text
    }
    select {
        this.id = id
        this.name = name
        for ((value, label) in choices) {
            option {
                this.value = value
                selected = value == current
                +label
            }
        }
    }
}

private fun FlowContent.historyTable(rows: List<HistoryRow>, now: Instant) = div("panel tight") {
    div("summary") {
        attributes["role"] = "status"
        span { +plural(rows.size, "session") }
        if (rows.size == HISTORY_ROWS) span("muted small") { +"The newest $HISTORY_ROWS; narrow the filters for more." }
    }
    div("scroll") {
        table {
            caption("sr") { +"Ended playback sessions, newest first" }
            thead {
                tr {
                    col("Ended")
                    col("Played")
                    col("Device")
                    col("How")
                    col("For")
                    col("Sent")
                    col("Ended because")
                }
            }
            tbody { for (row in rows) historyRow(row, now) }
        }
    }
}

private fun kotlinx.html.TBODY.historyRow(row: HistoryRow, now: Instant) = tr {
    val entry = row.entry
    td("nowrap") { ago(entry.endedAt, now) }
    td {
        +row.what
        span("muted small") { +(if (entry.kind == HistoryKind.LIVE) " · Live TV" else " · Library") }
    }
    td {
        +row.device
        if (entry.clientId != null && entry.address != null) span("muted small mono") { +" ${entry.address}" }
    }
    td { chip(entry.mode, if (entry.mode == "transcode") Tone.WARN else Tone.OFF) }
    td("nowrap") {
        val started = runCatching { Instant.parse(entry.startedAt) }.getOrNull()
        val ended = runCatching { Instant.parse(entry.endedAt) }.getOrNull()
        +(if (started != null && ended != null) hoursAndMinutes(Duration.between(started, ended)) else "")
    }
    td("nowrap") { +(entry.bytes?.let(::bytesText) ?: "") }
    td { +reasonText(entry.reason) }
}

private fun reasonText(reason: String): String = when (reason) {
    HistoryReason.RELEASED -> "Stopped by the player"
    HistoryReason.EXPIRED -> "Player went quiet"
    HistoryReason.EVICTED -> "Another player took the slot"
    HistoryReason.STOPPED -> "Server stopped"
    else -> reason
}

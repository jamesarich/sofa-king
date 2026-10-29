package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.PolicyChangeEntry
import dev.jamesrich.sofaking.backend.catalog.CHANGES_LIMIT
import dev.jamesrich.sofaking.backend.jobs.JobStatus
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.progress
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.Duration
import java.time.Instant

/** How often the Activity page reloads itself, in seconds. */
private const val ACTIVITY_REFRESH_SECONDS = 15

/** The change log entries the Changes page shows at first. */
private const val CHANGES_SHOWN = 50

/** How many times more change log entries each "Show more" asks for. */
private const val CHANGES_GROWTH = 4

/** What the server is doing now: who watches, what records, and the background jobs running. */
internal class ActivityView(
    val sessions: List<SessionRow>,
    val playing: List<PlayingRow>,
    val recording: List<Recording>,
    val running: List<JobStatus>,
)

/** The policy change log, newest first; [limit] is how many it asked for. */
internal class ChangesView(val changes: List<PolicyChangeEntry>, val limit: Int, val policy: Boolean)

/** The System section's Activity and Changes pages. */
internal class ActivityPages(private val portal: Portal) {
    private val runtime get() = portal.runtime
    private val overview = OverviewReader(portal)

    fun install(route: Route) = with(portal) {
        route.get("/system/activity") { call.html(activityPage(chrome(), activityView())) }
        route.get("/system/changes") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, CHANGES_LIMIT)
                ?: CHANGES_SHOWN
            val policy = runtime.policy
            call.html(changesPage(chrome(), ChangesView(policy?.changes(limit).orEmpty(), limit, policy != null)))
        }
    }

    private suspend fun activityView(): ActivityView {
        val now = portal.clock.instant()
        val recording = runCatchingCancellable { runtime.dvr?.recordings()?.recordings }.getOrNull().orEmpty()
            .filter { it.state == Wire.RecordingState.RECORDING }
        return ActivityView(
            overview.sessionRows(now),
            overview.playingRows(),
            recording,
            runtime.jobs.snapshot().filter { it.running },
        )
    }
}

internal fun activityPage(chrome: Chrome, view: ActivityView): String =
    layout("Activity", chrome, "/admin/system/activity", refreshSeconds = ACTIVITY_REFRESH_SECONDS) {
        pageHead(
            "Activity",
            "What the server is doing now: who is watching, what is recording, and the background work running, " +
                "with how far it has come. The page reloads every $ACTIVITY_REFRESH_SECONDS seconds.",
        )
        tabs(chrome, "/admin/system/activity", SYSTEM_TABS)
        watchingPanel(chrome, view.sessions, view.playing)
        panel(
            "Recording now",
            id = "recording",
            aside = { a("/admin/recordings") { +"DVR" } },
        ) {
            if (view.recording.isEmpty()) {
                p("muted") { +"Nothing is recording." }
            } else {
                recordingTable(chrome, view.recording)
            }
        }
        panel(
            "Running jobs",
            id = "jobs",
            aside = { a("/admin/system/jobs") { +"Every job" } },
        ) {
            if (view.running.isEmpty()) {
                p("muted") { +"No background job is running." }
            } else {
                runningTable(chrome, view.running)
            }
        }
    }

private fun FlowContent.recordingTable(chrome: Chrome, recordings: List<Recording>) = div("scroll") {
    table {
        caption("sr") { +"Recording now" }
        thead {
            tr {
                col("Program")
                col("Channel")
                col("Done")
                col("Ends")
                col("Size", "num")
            }
        }
        tbody {
            for (recording in recordings) {
                tr {
                    th {
                        attributes["scope"] = "row"
                        +recording.title
                        recording.episodeTitle?.let {
                            br()
                            span("muted small") { +it }
                        }
                    }
                    td { +recording.channelName.ifEmpty { recording.channelId } }
                    td("nowrap") { recordingProgress(recording, chrome.now) }
                    td("nowrap") { ago(recording.endsAt, chrome.now) }
                    td("num nowrap") { +bytesText(recording.bytes) }
                }
            }
        }
    }
}

/** How much of [recording]'s window, its padding included, has passed. */
private fun FlowContent.recordingProgress(recording: Recording, now: Instant) {
    val from = runCatching { Instant.parse(recording.startsAt) }.getOrNull()
        ?.minusSeconds(recording.startPaddingSeconds.toLong())
    val until = runCatching { Instant.parse(recording.endsAt) }.getOrNull()
        ?.plusSeconds(recording.endPaddingSeconds.toLong())
    if (from == null || until == null || !until.isAfter(from)) return
    val total = Duration.between(from, until).seconds
    val done = Duration.between(from, now).seconds.coerceIn(0, total)
    percentBar(((done * PERCENT) / total).toInt(), "${recording.title} recorded")
}

private fun FlowContent.runningTable(chrome: Chrome, jobs: List<JobStatus>) = div("scroll") {
    table {
        caption("sr") { +"Running jobs" }
        thead {
            tr {
                col("Job")
                col("Running for")
                col("Progress")
            }
        }
        tbody {
            for (job in jobs) {
                tr {
                    th {
                        attributes["scope"] = "row"
                        +job.label
                    }
                    td("nowrap") { +since(job.runningSince.toString(), chrome.now) }
                    td {
                        val progress = job.progress
                        val percent = progress?.percent
                        if (percent != null) {
                            percentBar(percent, "${job.label} done")
                            span("muted small") { +" ${thousands(progress.done)} of ${thousands(progress.total)}" }
                        }
                        progress?.step?.let {
                            if (percent != null) br()
                            span("muted small") { +it }
                        }
                        if (progress == null) span("muted") { +"–" }
                    }
                }
            }
        }
    }
}

/** A progress bar at [percent] with its number beside it; [label] names it for a screen reader. */
private fun FlowContent.percentBar(percent: Int, label: String) {
    progress {
        attributes["value"] = percent.toString()
        attributes["max"] = PERCENT.toString()
        attributes["aria-label"] = label
        +"$percent%"
    }
    span("small") { +" $percent%" }
}

internal fun changesPage(chrome: Chrome, view: ChangesView): String =
    layout("Changes", chrome, "/admin/system/changes") {
        pageHead(
            "Changes",
            "What each lineup policy run changed, newest first: the station, the field, before and after, and why.",
        )
        tabs(chrome, "/admin/system/changes", SYSTEM_TABS)
        if (!view.policy) {
            div("panel") { p("muted") { +"No lineup policy is configured, so nothing writes the change log." } }
            return@layout
        }
        changeLogPanel(view, chrome.now)
    }

private fun FlowContent.changeLogPanel(view: ChangesView, now: Instant) = panel("Change log", id = "changes", aside = {
    if (view.limit < CHANGES_LIMIT && view.changes.size >= view.limit) {
        val more = minOf(CHANGES_LIMIT, view.limit * CHANGES_GROWTH)
        a("/admin/system/changes?limit=$more#changes") { +"Show more" }
    }
}) {
    if (view.changes.isEmpty()) {
        p("muted") { +"No policy run has changed the lineup yet." }
        return@panel
    }
    div("scroll") {
        table {
            caption("sr") { +"Policy change log" }
            thead {
                tr {
                    col("When")
                    col("Station")
                    col("Field")
                    col("Before")
                    col("After")
                    col("Cause")
                }
            }
            tbody {
                for (entry in view.changes) {
                    tr {
                        val change = entry.change
                        td("nowrap") { ago(entry.appliedAt, now) }
                        td {
                            if (change.entryId != null) {
                                a("/admin/lineup/${change.entryId}", classes = "rowlink") { +change.entryName }
                            } else {
                                +change.entryName
                            }
                            change.key?.let {
                                br()
                                span("muted small mono") { +it }
                            }
                        }
                        td { +(change.field ?: "station") }
                        td("muted") { +(change.before ?: "–") }
                        td { +(change.after ?: "–") }
                        td("muted small") { +change.reason }
                    }
                }
            }
        }
    }
}

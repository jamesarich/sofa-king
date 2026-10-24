package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.jobs.JobStatus
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.TR
import kotlinx.html.a
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
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.time
import kotlinx.html.tr
import java.time.Instant

internal fun jobsPage(chrome: Chrome, view: JobsView): String = layout("Jobs", chrome, "/admin/system/jobs") {
    pageHead(
        "Jobs",
        "The server's background work: provider and guide imports, lineup policy runs, Plex sync, the logo sweep and " +
            "the update check. Run now starts one without waiting for its turn.",
    )
    tabs(chrome, "/admin/system/jobs", SYSTEM_TABS)
    if (view.jobs.isEmpty()) {
        div("panel") { p("muted") { +"No job has been registered." } }
        return@layout
    }
    div("panel tight") {
        div("scroll") {
            table {
                caption("sr") { +"Background jobs" }
                thead {
                    tr {
                        col("Job")
                        col("Every")
                        col("Last run")
                        col("Took")
                        col("Result")
                        col("Next due")
                        col("Action")
                    }
                }
                tbody {
                    for (job in view.jobs) {
                        tr(flashClass(chrome, job.id)) {
                            jobRow(chrome, job)
                        }
                    }
                }
            }
        }
    }
    p("muted small") {
        +(
            "Imports and guide imports start at once. A policy run or a Plex sync asked for here skips its usual " +
                "quiet time. Each row's result is the job's own summary; the Events page has the lines it logged."
            )
    }
}

private fun TR.jobRow(chrome: Chrome, job: JobStatus) {
    id = "job-${job.id}"
    th {
        attributes["scope"] = "row"
        +job.label
    }
    td("muted") { +(job.every?.let(::period) ?: "on request") }
    td("nowrap") {
        if (job.lastStartedAt == null) {
            span("muted") { +"not yet" }
        } else {
            ago(job.lastStartedAt.toString(), chrome.now)
        }
    }
    td("num nowrap") { +(job.lastDurationMs?.let(::took) ?: "") }
    jobResult(job, chrome.now)
    jobNextDue(job, chrome.now)
    td {
        if (job.canRunNow) {
            postForm("/admin/system/jobs/${job.id}/run", chrome.session, keepScroll = true) {
                button(type = ButtonType.submit, classes = "small") {
                    attributes["aria-label"] = "Run ${job.label} now"
                    +"Run now"
                }
            }
        }
    }
}

private fun TR.jobResult(job: JobStatus, now: Instant) = td {
    when {
        job.running -> {
            chip("Running", Tone.WARN)
            +" "
            span("muted small") { +"since ${relative(job.runningSince!!, now)}" }
        }

        job.lastOk == null -> chip("Waiting", Tone.OFF)

        job.lastOk -> chip("OK", Tone.OK)

        else -> chip("Failed", Tone.BAD)
    }
    if (!job.running && job.lastNote != null) {
        span("muted small") {
            +" "
            +job.lastNote
        }
    }
}

private fun TR.jobNextDue(job: JobStatus, now: Instant) = td("nowrap") {
    when {
        job.running -> span("muted") { +"after this run" }
        job.nextDueAt != null -> ago(job.nextDueAt.toString(), now)
        job.every == null -> span("muted") { +"when asked" }
        else -> span("muted") { +"soon" }
    }
}

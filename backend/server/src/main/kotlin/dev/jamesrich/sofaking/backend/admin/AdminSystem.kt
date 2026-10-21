package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.ServerStatus
import dev.jamesrich.sofaking.backend.jobs.JobStatus
import dev.jamesrich.sofaking.backend.logging.LogEvent
import dev.jamesrich.sofaking.backend.update.UpdateState
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import io.ktor.http.formUrlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The server as the About page and the support snapshot describe it; nothing here is a secret. */
@Serializable class AboutServer(
    val revision: String, val version: String, val startedAt: String, val uptimeSeconds: Long, val schemaVersion: Int,
    val jvm: String, val os: String, val ffmpeg: String, val dataDirectory: String?, val logLevel: String, val logFormat: String,
    val memoryUsedMb: Long, val memoryMaxMb: Long,
)

/** One job's state for the support snapshot. */
@Serializable class JobReport(
    val id: String, val label: String, val everySeconds: Long? = null, val running: Boolean, val lastStartedAt: String? = null,
    val lastDurationMs: Long? = null, val lastOk: Boolean? = null, val lastNote: String? = null, val nextDueAt: String? = null,
)

/** What "Copy support snapshot" renders: the status JSON, the server facts, every job and what the overview lists. */
@Serializable class SupportSnapshot(val takenAt: String, val server: AboutServer, val status: ServerStatus, val jobs: List<JobReport>, val attention: List<String>)

private val snapshotJson = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true }

internal fun JobStatus.report() = JobReport(
    id, label, every?.seconds, running, lastStartedAt?.toString(), lastDurationMs, lastOk, lastNote, nextDueAt?.toString(),
)

internal fun SupportSnapshot.toJson(): String = snapshotJson.encodeToString(this)

internal class JobsView(val jobs: List<JobStatus>)

internal class EventsView(val events: List<LogEvent>, val kept: Int, val capacity: Int, val level: String, val q: String)

/**
 * The About page: the server facts, the status, the snapshot, and the update picture, with [confirm] set while Update
 * now waits for the operator's yes.
 */
internal class AboutView(val server: AboutServer, val status: ServerStatus, val snapshot: String, val update: UpdateState? = null, val confirm: Boolean = false) {
    constructor(view: AboutView, confirm: Boolean) : this(view.server, view.status, view.snapshot, view.update, confirm)
}

/** The updater has checked in when its last report is under this old; the timer runs every 15 minutes. */
private val UPDATER_FRESH: Duration = Duration.ofHours(1)

/** The first line of `ffmpeg -version`, or why it could not be read; [ffmpeg] is the configured path. */
internal suspend fun ffmpegVersion(ffmpeg: String): String = withContext(Dispatchers.IO) {
    try {
        val process = ProcessBuilder(ffmpeg, "-version").redirectErrorStream(true).start()
        // The few lines it prints fit the pipe's buffer, so the exit comes first and an FFmpeg that hangs never holds the page.
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@withContext "not readable at $ffmpeg"
        }
        val line = process.inputStream.bufferedReader().use { it.readLine() }
        line?.takeIf { it.startsWith("ffmpeg") }?.removePrefix("ffmpeg version ")?.substringBefore(" Copyright")?.trim() ?: "not readable at $ffmpeg"
    } catch (e: java.io.IOException) { "not found at $ffmpeg" }
}

/** A duration as "12 min", "6 h" or "1.5 s". */
internal fun period(duration: Duration): String {
    val s = duration.seconds
    return when {
        s >= 86_400 && s % 86_400 == 0L -> "${s / 86_400} d"
        s >= 3600 && s % 3600 == 0L -> "${s / 3600} h"
        s >= 60 -> "${s / 60} min"
        else -> "$s s"
    }
}

internal fun took(ms: Long): String = when {
    ms >= 60_000 -> "${ms / 60_000} min ${ms % 60_000 / 1000} s"
    ms >= 1000 -> "%.1f s".format(java.util.Locale.US, ms / 1000.0)
    else -> "$ms ms"
}

internal fun jobsPage(chrome: Chrome, view: JobsView): String = layout("Jobs", chrome, "/admin/system/jobs") {
    val now = chrome.now
    pageHead("Jobs", "The server's background work: provider and guide imports, lineup policy runs, Plex sync, the logo sweep and the update check. Run now starts one without waiting for its turn.")
    tabs(chrome, "/admin/system/jobs", SYSTEM_TABS)
    if (view.jobs.isEmpty()) { div("panel") { p("muted") { +"No job has been registered." } }; return@layout }
    div("panel tight") {
        div("scroll") {
            table {
                caption("sr") { +"Background jobs" }
                thead { tr { col("Job"); col("Every"); col("Last run"); col("Took"); col("Result"); col("Next due"); col("Action") } }
                tbody {
                    for (job in view.jobs) tr(chrome.flash?.takeIf { it.target == job.id }?.let { if (it.tone == Tone.BAD) "err-row" else "flash" }) {
                        id = "job-${job.id}"
                        th { attributes["scope"] = "row"; +job.label }
                        td("muted") { +(job.every?.let(::period) ?: "on request") }
                        td("nowrap") { if (job.lastStartedAt == null) span("muted") { +"not yet" } else ago(job.lastStartedAt.toString(), now) }
                        td("num nowrap") { +(job.lastDurationMs?.let(::took) ?: "") }
                        td {
                            when {
                                job.running -> { chip("Running", Tone.WARN); +" "; span("muted small") { +"since ${relative(job.runningSince!!, now)}" } }
                                job.lastOk == null -> chip("Waiting", Tone.OFF)
                                job.lastOk -> chip("OK", Tone.OK)
                                else -> chip("Failed", Tone.BAD)
                            }
                            if (!job.running && job.lastNote != null) span("muted small") { +" "; +job.lastNote }
                        }
                        td("nowrap") {
                            when {
                                job.running -> span("muted") { +"after this run" }
                                job.nextDueAt != null -> ago(job.nextDueAt.toString(), now)
                                job.every == null -> span("muted") { +"when asked" }
                                else -> span("muted") { +"soon" }
                            }
                        }
                        td {
                            if (job.canRunNow) postForm("/admin/system/jobs/${job.id}/run", chrome.session, keepScroll = true) {
                                button(type = ButtonType.submit, classes = "small") { attributes["aria-label"] = "Run ${job.label} now"; +"Run now" }
                            }
                        }
                    }
                }
            }
        }
    }
    p("muted small") { +"Imports and guide imports start at once. A policy run or a Plex sync asked for here skips its usual quiet time. Each row's result is the job's own summary; the Events page has the lines it logged." }
}

internal fun eventsPage(chrome: Chrome, view: EventsView): String = layout("Events", chrome, "/admin/system/events") {
    val now = chrome.now
    pageHead("Events", "The newest ${thousands(view.capacity)} log lines at INFO and above since the server started, newest first. The same lines go to the container's log.")
    tabs(chrome, "/admin/system/events", SYSTEM_TABS)
    form(action = "/admin/system/events", method = FormMethod.get, classes = "filters events-filters") {
        attributes["hx-trigger"] = "submit, change, input changed delay:400ms from:find input[type=search]"
        attributes["hx-sync"] = "this:replace"
        div("field wide") {
            label { htmlFor = "q"; +"Search" }
            searchInput(name = "q") { id = "q"; value = view.q; placeholder = "Logger or message"; attributes["autocomplete"] = "off" }
        }
        div("field") {
            label { htmlFor = "f-level"; +"Level" }
            select {
                id = "f-level"; name = "level"
                for ((value, label) in listOf("" to "Info and above", "warn" to "Warnings and errors", "error" to "Errors only")) {
                    option { this.value = value; selected = value == view.level; +label }
                }
            }
        }
        div("actions") {
            button(type = ButtonType.submit) { +"Filter" }
            if (view.level.isNotEmpty() || view.q.isNotEmpty()) a("/admin/system/events", classes = "btn quiet") { +"Clear" }
        }
    }
    val query = listOf("level" to view.level, "q" to view.q).filter { it.second.isNotEmpty() }.let { if (it.isEmpty()) "" else "?" + it.formUrlEncode() }
    div {
        id = "events"
        attributes["hx-get"] = "/admin/system/events$query"
        attributes["hx-trigger"] = "every 10s"
        attributes["hx-select"] = "#events"
        attributes["hx-target"] = "this"
        attributes["hx-swap"] = "outerHTML"
        div("summary") {
            attributes["role"] = "status"
            span { +"${thousands(view.events.size)} of ${thousands(view.kept)} kept lines" }
            span("muted small") { +"Refreshes every 10 s with JavaScript; "; a("/admin/system/events$query") { +"refresh now" }; +"." }
        }
        if (view.events.isEmpty()) div("panel") { p("muted") { +(if (view.kept == 0) "Nothing has been logged yet." else "No line matches these filters.") } }
        else div("panel tight") {
            div("scroll") {
                table("events") {
                    caption("sr") { +"Log events, newest first" }
                    thead { tr { col("When"); col("Level"); col("Logger"); col("Message") } }
                    tbody {
                        for (event in view.events) tr {
                            td("nowrap") { ago(event.at.toString(), now) }
                            td { chip(event.level, when (event.level) { "ERROR" -> Tone.BAD; "WARN" -> Tone.WARN; else -> Tone.OFF }) }
                            td("mono small nowrap") { attributes["title"] = event.logger; +event.logger.substringAfterLast('.') }
                            td("message") { +event.message; if (event.requestId != null) span("muted small mono") { +" "; +event.requestId } }
                        }
                    }
                }
            }
        }
    }
}

internal fun aboutPage(chrome: Chrome, view: AboutView): String = layout("About", chrome, "/admin/system/about") {
    val now = chrome.now
    val server = view.server
    val status = view.status
    pageHead("About", "This server, whether it is up to date, and a snapshot to paste into a bug report.")
    tabs(chrome, "/admin/system/about", SYSTEM_TABS)
    view.update?.let { updatePanel(chrome, it) }
    div("cols") {
        panel("Server") {
            dl("kv") {
                dt { +"Version" }; dd { +server.version; +" "; code { +server.revision } }
                dt { +"Started" }; dd { ago(server.startedAt, now); +" (up ${uptime(Instant.parse(server.startedAt), now)})" }
                dt { +"Schema" }; dd { +"version ${server.schemaVersion}" }
                dt { +"JVM" }; dd { +server.jvm }
                dt { +"OS" }; dd { +server.os }
                dt { +"FFmpeg" }; dd { +server.ffmpeg }
                if (server.dataDirectory != null) { dt { +"Data directory" }; dd { code { +server.dataDirectory } } }
                dt { +"Logging" }; dd { +"${server.logLevel}, ${server.logFormat}" }
                dt { +"Memory" }; dd { +"${thousands(server.memoryUsedMb.toInt())} MB used of ${thousands(server.memoryMaxMb.toInt())} MB" }
            }
        }
        panel("Status") {
            dl("kv") {
                dt { +"Channels" }; dd { +"${thousands(status.channels.enabled)} on of ${thousands(status.channels.total)}, ${thousands(status.channels.eligible)} with a playable source" }
                dt { +"Sources" }; dd { +(status.sources.byStatus.entries.joinToString(", ") { "${thousands(it.value)} ${it.key.lowercase()}" }.ifEmpty { "none" }); if (status.sources.manuallyDisabled > 0) +"; ${thousands(status.sources.manuallyDisabled)} disabled by hand" }
                dt { +"Providers" }; dd { +"${thousands(status.providers.total - status.providers.failing.size - status.providers.pending)} of ${thousands(status.providers.total)} importing"; if (status.providers.failing.isNotEmpty()) +", failing: ${status.providers.failing.joinToString { it.id }}" }
                dt { +"Guides" }; dd { +"${thousands(status.guides.total - status.guides.failing.size - status.guides.pending)} of ${thousands(status.guides.total)} importing"; if (status.guides.failing.isNotEmpty()) +", failing: ${status.guides.failing.joinToString { it.id }}" }
                dt { +"Policy" }; dd { if (status.policy == null) +"None" else { +"revision "; code { +status.policy.revision }; +", last run "; ago(status.policy.lastRunAt, now) } }
                dt { +"Plex" }; dd { if (status.plex == null) +"Not set up" else { +"last sync "; ago(status.plex.lastSyncAt, now); +", ${thousands(status.plex.mapped)} channels mapped"; if (status.plex.lastError != null) +", failing" } }
                dt { +"Watching" }; dd { +plural(status.activeSessions, "session") }
            }
        }
    }
    panel("Support snapshot", id = "snapshot", aside = { a("/admin/system/snapshot.json", classes = "btn small") { attributes["download"] = "sofa-king-snapshot.json"; +"Download" } }) {
        p("muted") { +"The status API's answer with the facts above, every job's last run and what the overview lists. It holds no token, URL or secret; copy it into a bug report." }
        textArea(classes = "snapshot") {
            attributes["readonly"] = "readonly"
            attributes["aria-label"] = "Support snapshot JSON"
            rows = "14"
            +view.snapshot
        }
    }
    if (view.confirm && view.update != null) updateConfirm(chrome, view.update)
}

/** "Version 1.4.0, revision abc1234", or whichever of the two is known. */
private fun FlowContent.build(version: String?, revision: String?) {
    val knownVersion = version?.takeIf { it != "unknown" }
    val knownRevision = revision?.takeIf { it != "unknown" }
    when {
        knownVersion != null && knownRevision != null -> { +knownVersion; +" "; code { +knownRevision } }
        knownVersion != null -> +knownVersion
        knownRevision != null -> code { +knownRevision }
        else -> span("muted") { +"unknown build" }
    }
}

/**
 * The update picture: what runs against what is published, the host updater's last word, and the two actions. Check
 * now asks the registry; Update now writes the request the updater watches, after a confirmation.
 */
private fun FlowContent.updatePanel(chrome: Chrome, update: UpdateState) = panel("Update", id = "update") {
    val now = chrome.now
    val report = update.updater
    val reportedAt = report?.let { runCatching { Instant.parse(it.checkedAt) }.getOrNull() }
    val updaterFresh = reportedAt != null && Duration.between(reportedAt, now) < UPDATER_FRESH
    val requested = update.requestedAt
    when {
        update.image == null -> p("muted") { +"The update check is off (SOFA_UPDATE_IMAGE is empty)." }
        update.current == true -> p("allgood") { +"Up to date. "; span("muted") { +"The published image is the one running." } }
        update.current == false -> div("notice warn") {
            attributes["role"] = "status"
            +"An update is available: "; build(update.published?.version, update.published?.revision); +" is published."
        }
        update.error != null -> div("notice warn") { attributes["role"] = "status"; +"The last check failed: ${update.error}" }
        update.checkedAt == null -> p("muted") { +"Not checked yet. The first check runs two minutes after start, then every five minutes." }
        else -> p("muted") { +"The registry names no commit for the published image, so it cannot be compared." }
    }
    dl("kv") {
        dt { +"Running" }; dd { build(update.running.version, update.running.revision) }
        if (update.image != null) {
            dt { +"Published" }
            dd {
                val published = update.published
                if (published == null) span("muted") { +"not checked yet" }
                else { build(published.version, published.revision); if (update.checkedAt != null) { +" · checked "; ago(update.checkedAt.toString(), now) } }
                br(); span("muted small mono") { +update.image }
            }
        }
        dt { +"Updater" }
        dd {
            when {
                report == null -> { +"Has not reported. "; span("muted") { +"Install the host's updater to apply updates; its timer checks every 15 minutes and writes its result here." } }
                else -> {
                    +when (report.outcome) {
                        "up-to-date" -> "Checked and found nothing new"
                        "updated" -> "Installed ${report.candidate ?: "a new image"}"
                        "rolled-back" -> "Tried ${report.candidate ?: "a new image"} and went back to ${report.running ?: "the previous one"}"
                        "skipped" -> "Skipped ${report.candidate ?: "the published image"}; it failed before"
                        "failed" -> "Failed"
                        else -> report.outcome
                    }
                    +" "; ago(report.checkedAt, now)
                    if (!updaterFresh) { +" "; chip("Not checking in", Tone.WARN) }
                    if (report.message != null) { br(); span("muted small") { +report.message } }
                }
            }
        }
        if (requested != null) { dt { +"Requested" }; dd { +"An update was asked for "; ago(requested.toString(), now); +"; the updater takes it when it next runs." } }
    }
    div("actions mt-14") {
        if (update.image != null) postForm("/admin/system/update/check", chrome.session, keepScroll = true) { button(type = ButtonType.submit) { +"Check now" } }
        val canRequest = report != null && requested == null
        if (canRequest) postForm("/admin/system/update/request", chrome.session, keepScroll = true) {
            button(type = ButtonType.submit, classes = if (update.current == false) "primary" else "") { +"Update now" }
        }
        span("muted small") {
            +when {
                report == null -> "Update now needs the host's updater; see the deploy guide."
                requested != null -> "Update now is waiting for the updater."
                update.current == false -> "Update now backs up the database, swaps to the published image and comes back to this one if the new one is not healthy."
                else -> "Update now makes the updater pull and compare at once; it changes nothing while the published image is the one running."
            }
        }
    }
}

internal fun MAIN.updateConfirm(chrome: Chrome, update: UpdateState) {
    askDialog(chrome, "Update the server now?", "/admin/system/update/request", "/admin/system/about#update", "Update", danger = false) {
        p {
            +"The host's updater backs up the database, pulls "; code { +(update.image ?: "the published image") }; +" and recreates the container. "
            +"Streams stop for about half a minute while it restarts; you stay signed in. If the new image is not healthy within three minutes, the previous image and its database come back."
        }
        if (update.current != false) p("muted") { +"The published image looks like the one running, so this most likely changes nothing." }
    }
}

package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.MIB
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.http.serverStatus
import dev.jamesrich.sofaking.backend.logging.EventRing
import dev.jamesrich.sofaking.backend.logging.logFile
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.AdChannelRecord
import dev.jamesrich.sofaking.backend.store.adChannels
import dev.jamesrich.sofaking.backend.store.library
import dev.jamesrich.sofaking.backend.update.UpdateChecker
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.date
import io.ktor.http.parameters
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.header
import io.ktor.server.routing.host
import io.ktor.server.routing.route
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** The System pages: jobs, events, about and updates, the API reference, and the ads census. */
internal class SystemPages(private val portal: Portal, private val events: EventRing) {
    private val runtime get() = portal.runtime
    private val config get() = portal.config
    private val overview = OverviewReader(portal)
    private val ffmpeg = AtomicReference<String?>()
    private val host = HostFacts(portal.config)

    fun install(route: Route) = with(portal) {
        route.get("/system/jobs") { call.html(jobsPage(chrome(), JobsView(runtime.jobs.snapshot()))) }
        route.action("/system/jobs/{id}/run") { session, form ->
            val id = call.parameters["id"]!!
            val job = runtime.jobs.snapshot().firstOrNull { it.id == id }
            if (job == null || !runtime.jobs.runNow(id)) {
                session.flash.set(Flash("No such job to run.", Tone.BAD, id))
            } else {
                session.flash.set(Flash("Started ${job.label}.", Tone.OK, id))
            }
            // A run comes back to the panel that asked for it, from a fixed list; every other run to the Jobs page.
            seeOther(call, form["back"]?.takeIf { it in JOB_RETURNS } ?: "/admin/system/jobs")
        }
        route.get("/system/events") { call.html(eventsPage(chrome(), eventsView(call.request.queryParameters))) }
        route.get("/system/about") { call.html(aboutPage(chrome(), aboutView())) }
        route.action("/system/update/check") { session, _ ->
            val state = updates().check()
            session.flash.set(
                when {
                    state.error != null -> Flash("The check failed: ${state.error}", Tone.BAD)
                    state.current == true -> Flash("Checked: the server is up to date.")
                    state.current == false -> Flash("Checked: an update is available.", Tone.WARN)
                    else -> Flash("Checked: the registry names no commit to compare.", Tone.WARN)
                },
            )
            seeOther(call, "/admin/system/about#update")
        }
        route.action("/system/update/request") { session, form -> requestUpdate(call, session, form) }
        route.get("/system/snapshot.json") {
            call.session()
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"sofa-king-snapshot.json\"")
            call.respondText(snapshot().toJson(), ContentType.Application.Json)
        }
        route.get("/api") { call.html(apiPage(chrome(), apiReference)) }
        route.get("/api/openapi.json") {
            call.session()
            call.respondText(apiReference.json, ContentType.Application.Json)
        }
        route.get("/ads") { call.html(adsPage(chrome(), adsView())) }
    }

    private fun updates(): UpdateChecker =
        runtime.updates ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")

    private suspend fun requestUpdate(call: ApplicationCall, session: AdminSession, form: Parameters) {
        val updates = updates()
        val flash = when {
            updates.state().updater == null ->
                Flash("The host's updater has not reported, so there is nothing to ask.", Tone.WARN)

            form["confirm"] != "yes" -> null

            updates.requestUpdate() ->
                Flash("Asked the updater to run. The server restarts if a newer image is published.")

            else -> Flash("Could not write the request; the data directory is not writable.", Tone.BAD)
        }
        if (flash == null) {
            call.html(aboutPage(portal.chrome(call, session), AboutView(aboutView(), confirm = true)))
        } else {
            session.flash.set(flash)
            seeOther(call, "/admin/system/about#update")
        }
    }

    private fun eventsView(parameters: Parameters): EventsView {
        val level = parameters["level"]?.takeIf { it == "warn" || it == "error" }.orEmpty()
        val q = parameters["q"]?.trim().orEmpty().take(EVENT_QUERY_LIMIT)
        val all = events.snapshot()
        val shown = all.filter { event ->
            val leveled = when (level) {
                "warn" -> event.level == "WARN" || event.level == "ERROR"
                "error" -> event.level == "ERROR"
                else -> true
            }
            leveled &&
                (
                    q.isEmpty() || event.message.contains(q, ignoreCase = true) ||
                        event.logger.contains(q, ignoreCase = true)
                    )
        }
        val debug = runtime.debugWindow
        return EventsView(
            shown,
            all.size,
            events.capacity,
            level,
            q,
            config?.let { Files.isRegularFile(logFile(it.dataDirectory)) } ?: false,
            debug?.until,
            debug?.length?.toMinutes(),
        )
    }

    private suspend fun aboutServer(): AboutServer {
        val now = portal.clock.instant()
        val memory = Runtime.getRuntime()
        return AboutServer(
            config?.revision ?: "unknown", config?.version ?: "unknown", runtime.startedAt.toString(),
            Duration.between(runtime.startedAt, now).seconds.coerceAtLeast(0),
            runtime.store.schemaVersion(),
            "${Runtime.version()} (${System.getProperty("java.vendor")})",
            "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            ffmpeg.get() ?: ffmpegVersion(config?.ffmpeg ?: "ffmpeg").also(ffmpeg::set),
            config?.dataDirectory?.toAbsolutePath()?.toString(), runtime.settings.current.logLevel,
            config?.logging?.format ?: "text",
            (memory.totalMemory() - memory.freeMemory()) / MIB, memory.maxMemory() / MIB,
            host.tools(),
            host.folders(runCatchingCancellable { runtime.dvr?.recordings()?.storage?.usedBytes }.getOrNull()),
        )
    }

    /** The support snapshot as the About page copies it. */
    suspend fun snapshotJson(): String = snapshot().toJson()

    private suspend fun snapshot(): SupportSnapshot = SupportSnapshot(
        portal.clock.instant().toString(),
        aboutServer(),
        serverStatus(runtime, portal.clock, config?.revision ?: "unknown"),
        runtime.jobs.snapshot().map { it.report() },
        overview.read().attention.map { "${it.tone.css}: ${it.title}" + (it.detail?.let { d -> " ($d)" } ?: "") },
    )

    private suspend fun aboutView(): AboutView {
        val snapshot = snapshot()
        return AboutView(
            snapshot.server,
            snapshot.status,
            snapshot.toJson(),
            runtime.updates?.state(),
            encoders = runtime.vod?.encoders,
        )
    }

    /** Each channel's ad census, the most watched first, by its lineup name. */
    private suspend fun adsView(): AdsView {
        val names = runtime.lineup.entries().associate { it.id to it.name }
        val listening = runtime.listener?.listening().orEmpty()
        val rows = runtime.store.adChannels()
            .sortedWith(compareByDescending<AdChannelRecord> { it.watchedMs }.thenByDescending { it.listenedMs })
            .map { AdChannelRow(it, names[it.channelId], it.channelId in listening) }
        return AdsView(
            runtime.sessions.learnedBreaks,
            runtime.settings.current.adListeners,
            runtime.sessions.library?.stats(),
            listening.size,
            rows,
        )
    }
}

private const val EVENT_QUERY_LIMIT = 200

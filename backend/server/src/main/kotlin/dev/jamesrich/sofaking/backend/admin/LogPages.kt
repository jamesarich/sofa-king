package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.logging.logFile
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.hiddenInput
import kotlinx.html.p
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val UNTIL = DateTimeFormatter.ofPattern("HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

/** The Events page's log file download and its spell of DEBUG logging. */
internal class LogPages(private val portal: Portal) {
    fun install(route: Route) = with(portal) {
        route.get("/system/events/server.log") {
            call.session()
            val file = config?.let { logFile(it.dataDirectory) }?.takeIf { Files.isRegularFile(it) }
                ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")
            val name = ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName,
                "sofa-king.log",
            )
            call.response.header(HttpHeaders.ContentDisposition, name.toString())
            call.respondFile(file.toFile())
        }
        route.action("/system/events/debug") { session, form ->
            val window = runtime.debugWindow
                ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")
            if (form["op"] == "stop") {
                window.close()
                session.flash.set(Flash("Logging is back at ${runtime.settings.current.logLevel.uppercase()}."))
            } else {
                window.open()
                session.flash.set(Flash("Logging at DEBUG for ${window.length.toMinutes()} minutes."))
            }
            seeOther(call, "/admin/system/events#log")
        }
    }
}

/** The log file's download and the DEBUG switch, above the Events page's lines. */
internal fun FlowContent.logPanel(chrome: Chrome, view: EventsView) = panel(
    "Log file",
    id = "log",
    aside = { if (view.logFile) a("/admin/system/events/server.log", classes = "btn small") { +"Download the log" } },
) {
    p("mt-0") {
        if (view.logFile) {
            +"The server writes every line at its level to "
            code { +"logs/server.log" }
            +(
                " in the data directory. The download is that file: the lines since midnight UTC, or since it last " +
                    "reached 20 MB; the older ones are gzipped beside it."
                )
        } else {
            +"The server writes no log file: "
            code { +"SOFA_LOG_DAYS" }
            +" is 0."
        }
    }
    val minutes = view.debugMinutes ?: return@panel
    val until = view.debugUntil
    postForm("/admin/system/events/debug", chrome.session) {
        if (until != null) {
            p("mt-0") { debugState(until, chrome.now) }
            hiddenInput(name = "op") { value = "stop" }
            button(type = ButtonType.submit, classes = "small") { +"Stop debug logging" }
        } else {
            p("muted mt-0") {
                +"To chase a problem, log at DEBUG for $minutes minutes, reproduce it, then download the log. "
                +"The level goes back on its own."
            }
            hiddenInput(name = "op") { value = "start" }
            button(type = ButtonType.submit, classes = "small") { +"Debug for $minutes minutes" }
        }
    }
}

private fun FlowContent.debugState(until: Instant, now: Instant) {
    chip("DEBUG", Tone.WARN)
    +" until ${UNTIL.format(until)} (${relative(until, now)}). The Events page keeps INFO and above; the DEBUG lines "
    +"are in the log file."
}

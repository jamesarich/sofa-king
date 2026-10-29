package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.sameSecret
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.cookies
import io.ktor.http.headers
import io.ktor.http.path
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.header
import io.ktor.server.routing.path
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.query
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * What every portal page shares: the services in process, the session and its CSRF check, and the page furniture.
 */
internal class Portal(
    val runtime: LiveRuntime,
    val outputs: OutputConfig,
    val hdhr: HdhrConfig,
    val clock: Clock,
    val plexStatus: PlexStatusSource?,
    /** The settings the server started with, for the settings page; null leaves that section out. */
    val config: ServerConfig?,
) {
    /** A POST that needs the session and its CSRF token, from the form or the `X-CSRF-Token` header. */
    fun Route.action(path: String, body: suspend RoutingContext.(AdminSession, Parameters) -> Unit) {
        post(path) {
            val session = call.session()
            val form = call.formOrEmpty()
            if (!sameSecret(form["csrf"] ?: call.request.headers["X-CSRF-Token"], session.csrf)) {
                call.respondText("The form expired; reload the page and try again", status = HttpStatusCode.Forbidden)
                return@post
            }
            body(session, form)
        }
    }

    /** The page furniture for [session]; takes the one-shot notice unless [flash] replaces it. */
    suspend fun chrome(call: ApplicationCall, session: AdminSession, flash: Flash? = null, ask: Ask? = null): Chrome =
        Chrome(
            session,
            clock.instant(),
            flash ?: session.flash.getAndSet(null),
            badges(),
            config?.revision ?: "unknown",
            runtime.startedAt,
            theme(call),
            // A page answering a POST sends a theme change back to the section it belongs to;
            // the posted path is the action.
            if (call.request.httpMethod == HttpMethod.Get) call.request.uri else "/admin",
            ask,
        )

    /**
     * Runs [perform] once the form says `confirm=yes`. Without it, answers with [page] showing [ask] over it and
     * changes nothing, so the check works without JavaScript.
     */
    suspend fun ApplicationCall.confirmed(
        session: AdminSession,
        form: Parameters,
        ask: Ask,
        page: suspend (Chrome) -> String,
        perform: suspend () -> Unit,
    ) {
        if (form["confirm"] == "yes") perform() else html(page(chrome(this, session, ask = ask)))
    }

    /** The furniture for the page answering this request. */
    suspend fun RoutingContext.chrome(flash: Flash? = null): Chrome = chrome(call, call.session(), flash)

    /** The pinned color scheme from the cookie, or null to follow the device. */
    fun theme(call: ApplicationCall): String? = call.request.cookies[THEME_COOKIE]?.takeIf { it in THEMES }

    /**
     * Nav and tab counts from in-memory state only, so every page stays cheap; a section's
     * count is the sum of its tabs'.
     */
    private suspend fun badges(): Map<String, Int> {
        val applied = runtime.policy?.lastApplied()
        val plex = plexStatus?.status()
        val jobs = runtime.jobs.snapshot().count { it.lastOk == false }
        val merges = applied?.result?.suggestedAliases?.size ?: 0
        val required = applied?.stations?.count { it.required && !it.enabled } ?: 0
        val sources =
            runtime.feeds.statuses.count { it.errorCode != null } +
                runtime.guides.statuses.count { it.errorCode != null }
        val refused = if (hdhr.enabled) runtime.hdhrAccess.refusals().size else 0
        val plexCount = (if (plex?.lastError != null) 1 else 0) + (plex?.missingDevices?.size ?: 0) + refused
        val recordings =
            runtime.dvr?.let { dvr -> dvr.alerts.conflictCount() + (if (dvr.files.lastWritable()) 0 else 1) } ?: 0
        return mapOf(
            "jobs" to jobs, "merges" to merges, "required" to required, "plex" to plexCount,
            "channels" to merges + required, "sources" to sources, "players" to plexCount, "system" to jobs,
            "recordings" to recordings,
        )
    }
}

internal const val THEME_COOKIE = "sofa_theme"
internal val SESSION = AttributeKey<AdminSession>("AdminSession")

/** The signed-in session [AdminPortal.admit] put on this call. */
internal fun ApplicationCall.session(): AdminSession = attributes.getOrNull(SESSION)
    ?: throw ApiFailure(HttpStatusCode.Unauthorized, "AUTH_REQUIRED", "Sign in required")

/**
 * The posted form; a body that is not a form reads as an empty one, so it fails the
 * token checks instead of erroring.
 */
internal suspend fun ApplicationCall.formOrEmpty(): Parameters = try {
    receiveParameters()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    Parameters.Empty
}

internal suspend fun seeOther(call: ApplicationCall, location: String) {
    call.response.header(HttpHeaders.Location, location)
    call.respond(HttpStatusCode.SeeOther)
}

internal suspend fun ApplicationCall.html(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(body, ContentType.Text.Html.withCharset(Charsets.UTF_8), status)

/** The query string of [filter] with its `?`, or nothing when it holds no filter. */
internal fun queryOf(filter: LineupFilter): String = filter.query().let { if (it.isEmpty()) "" else "?$it" }

/** The form's fields but the token, revision and confirmation, which a confirmation dialog carries again. */
internal fun Parameters.editFields(): List<Pair<String, String>> =
    entries().filter { it.key !in setOf("csrf", "revision", "confirm") }.flatMap { (name, values) ->
        values.map { name to it }
    }

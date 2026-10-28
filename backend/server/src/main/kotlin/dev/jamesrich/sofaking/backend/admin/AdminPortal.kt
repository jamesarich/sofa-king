package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.logging.EventRing
import dev.jamesrich.sofaking.backend.mcp.McpAttention
import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.cookies
import io.ktor.http.headers
import io.ktor.http.path
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.header
import io.ktor.server.routing.path
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.time.Clock

/** Supplies the last Plex sync's status; without a source the portal treats Plex sync as not set up. */
fun interface PlexStatusSource {
    suspend fun status(): PlexStatus
}

private const val HTMX_PATH = "/admin/static/htmx.min.js"
private const val ICON_PATH = "/admin/static/icon.svg"

/** The sign-in form, the brand icon its tab shows and the stylesheet; everything else needs a session. */
private val PUBLIC_PATHS = setOf("/admin", "/admin/login", ICON_PATH, STYLESHEET_PATH)
private const val CSP =
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action " +
        "'self'; frame-ancestors 'none'; base-uri 'none'"

/** Whether [path], as routing resolves it, belongs to the portal. */
internal fun isPortalPath(path: String): Boolean = path == "/admin" || path.startsWith("/admin/")

/**
 * The admin portal under `/admin`: HTML pages over the services in process, behind a session that the admin token
 * ([secret]) opens. With no [secret] nobody can sign in.
 */
class AdminPortal(
    runtime: LiveRuntime,
    private val secret: String?,
    outputs: OutputConfig,
    hdhr: HdhrConfig,
    clock: Clock,
    plexStatus: PlexStatusSource? = null,
    /** The settings the server started with, for the settings page; null leaves that section out. */
    config: ServerConfig? = null,
    /** The log events the Events page lists. */
    private val events: EventRing = EventRing.shared,
) {
    val sessions = AdminSessions(clock, secret, runtime.store)
    private val portal = Portal(runtime, outputs, hdhr, clock, plexStatus, config)

    /**
     * Decides a portal request on the routed [path] before routing runs, so no spelling of a page skips the session.
     * Returns false once it has answered.
     */
    internal suspend fun admit(call: ApplicationCall, path: String): Boolean {
        if (path != HTMX_PATH && path != ICON_PATH && path != STYLESHEET_PATH) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
        }
        call.response.header("Content-Security-Policy", CSP)
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        val session = if (path in PUBLIC_PATHS) null else sessions.find(call.request.cookies[COOKIE])
        session?.let { call.attributes.put(SESSION, it) }
        val admitted = path in PUBLIC_PATHS || session != null
        if (!admitted) {
            if (call.request.httpMethod == HttpMethod.Get && call.request.headers["HX-Request"] == null) {
                seeOther(call, "/admin")
            } else {
                call.response.header("HX-Redirect", "/admin")
                call.respondText("Sign in required", status = HttpStatusCode.Unauthorized)
            }
        }
        return admitted
    }

    /** What the home page lists as needing the operator, worst first, for the MCP endpoint. */
    internal suspend fun attention(): List<McpAttention> = OverviewReader(portal).read().attention.map {
        McpAttention(it.tone.name.lowercase(), it.title, it.detail, it.action, it.href, it.doc)
    }

    /** Publishes the home page's attention changes to the event stream until [scope] ends. */
    fun watchAttention(scope: CoroutineScope): Job =
        AttentionWatch(OverviewReader(portal), portal.runtime.events).start(scope)

    fun install(root: Route) {
        val editor = PolicyEditor(portal)
        root.route("/admin") {
            HomePages(portal, sessions, secret).install(this)
            LineupPages(portal, editor).install(this)
            PolicyPages(portal, editor).install(this)
            VirtualPages(portal).install(this)
            SourcePages(portal, editor).install(this)
            RecordingPages(portal).install(this)
            WantedPages(portal).install(this)
            LibraryPages(portal).install(this)
            MatchPages(portal).install(this)
            ViewerPages(portal).install(this)
            HistoryPages(portal).install(this)
            PlexPages(portal).install(this)
            SettingsPages(portal).install(this)
            ConnectPages(portal).install(this)
            SystemPages(portal, events).install(this)
            ActivityPages(portal).install(this)
            BackupPages(portal).install(this)
            LogPages(portal).install(this)
        }
    }
}

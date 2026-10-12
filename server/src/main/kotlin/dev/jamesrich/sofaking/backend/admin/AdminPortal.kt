package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.http.deviceShare
import dev.jamesrich.sofaking.backend.http.hdhrChannels
import dev.jamesrich.sofaking.backend.http.publicBase
import dev.jamesrich.sofaking.backend.http.tokenQuery
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import io.ktor.util.date.GMTDate
import java.time.Clock
import java.time.Instant

/** One line of status from outside the portal, such as a Plex sync's last result. */
data class AdminStatusLine(val label: String, val value: String)

/** Supplies the Plex page's status lines; the page leaves the section out when no source is given. */
fun interface PlexStatusSource {
    suspend fun lines(): List<AdminStatusLine>
}

fun plexStatusLines(status: PlexStatus): List<AdminStatusLine> = buildList {
    add(AdminStatusLine("Last sync", status.lastSyncAt?.let { "$it (${status.lastCause ?: "scheduled"})" } ?: "never"))
    add(AdminStatusLine("Sync interval", "${status.syncMinutes} min"))
    status.lastError?.let { add(AdminStatusLine("Last error", it)) }
    status.devices.forEach { d -> add(AdminStatusLine("Tuner ${d.device} (${d.deviceId})", "DVR ${d.dvr}, ${d.mapped} channels mapped")) }
    if (status.missingDevices.isNotEmpty()) add(AdminStatusLine("Not in Plex", status.missingDevices.joinToString()))
    status.findings.forEach { add(AdminStatusLine("Finding", it)) }
}

private const val COOKIE = "sofa_admin"
private const val PAGE_SIZE = 100
private val SESSION = AttributeKey<AdminSession>("AdminSession")
private val PUBLIC_PATHS = setOf("/admin", "/admin/login")
private const val CSP =
    "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; img-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

/** Whether [path], as routing resolves it, belongs to the portal. */
internal fun isPortalPath(path: String): Boolean = path == "/admin" || path.startsWith("/admin/")

/**
 * The admin portal under `/admin`: HTML pages over the services in process, behind a session that the admin token
 * ([secret]) opens. With no [secret] nobody can sign in.
 */
class AdminPortal(
    private val runtime: LiveRuntime,
    private val secret: String?,
    private val outputs: OutputConfig,
    private val hdhr: HdhrConfig,
    private val clock: Clock,
    private val plexStatus: PlexStatusSource? = null,
) {
    val sessions = AdminSessions(clock)

    /**
     * Decides a portal request on the routed [path] before routing runs, so no spelling of a page skips the session.
     * Returns false once it has answered.
     */
    internal suspend fun admit(call: ApplicationCall, path: String): Boolean {
        if (path != "/admin/static/htmx.min.js") call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("Content-Security-Policy", CSP)
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        if (path in PUBLIC_PATHS) return true
        val session = sessions.find(call.request.cookies[COOKIE])
        if (session != null) {
            call.attributes.put(SESSION, session)
            return true
        }
        if (call.request.httpMethod == HttpMethod.Get && call.request.headers["HX-Request"] == null) seeOther(call, "/admin")
        else {
            call.response.header("HX-Redirect", "/admin")
            call.respondText("Sign in required", status = HttpStatusCode.Unauthorized)
        }
        return false
    }

    fun install(root: Route) {
        root.route("/admin") {
            get {
                val session = sessions.find(call.request.cookies[COOKIE])
                if (session == null) call.html(signInPage(enabled = secret != null))
                else call.html(dashboardPage(session, dashboard()))
            }
            post("/login") {
                val expected = secret ?: throw ApiFailure(404, "NOT_FOUND", "Resource not found")
                val presented = call.receiveParameters()["token"]
                if (!sameSecret(presented, expected)) {
                    call.html(signInPage(enabled = true, error = "That token is not the admin token."), HttpStatusCode.Unauthorized)
                    return@post
                }
                call.request.cookies[COOKIE]?.let(sessions::close)
                val session = sessions.open()
                val secure = call.request.headers["X-Forwarded-Proto"] == "https" || call.request.local.scheme == "https"
                call.response.cookies.append(sessionCookie(session.id, maxAge = 12 * 3600, secure = secure))
                seeOther(call, "/admin")
            }
            action("/logout") { session, _ ->
                sessions.close(session.id)
                call.response.cookies.append(sessionCookie("", maxAge = 0, secure = false))
                seeOther(call, "/admin")
            }
            get("/static/htmx.min.js") {
                val bytes = AdminPortal::class.java.getResourceAsStream("/admin/htmx.min.js")?.use { it.readBytes() }
                    ?: throw ApiFailure(404, "NOT_FOUND", "Resource not found")
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respondBytes(bytes, ContentType.Text.JavaScript)
            }
            get("/lineup") { call.html(lineupPage(call.session(), lineupView(LineupFilter.from(call.request.queryParameters)))) }
            action("/lineup/{id}/visibility") { session, form ->
                val enabled = form["enabled"]?.toBooleanStrictOrNull() ?: throw ApiFailure(400, "INVALID_REQUEST", "enabled must be true or false")
                changeEntry(call, session, form, call.parameters["id"]!!) { UpdateLineupEntry(enabled = enabled) }
            }
            action("/lineup/{id}/edit") { session, form ->
                changeEntry(call, session, form, call.parameters["id"]!!) { entry ->
                    val name = form["name"]?.trim().orEmpty()
                    val number = form["number"]?.trim().orEmpty()
                    val group = form["group"]?.trim()
                    UpdateLineupEntry(
                        name = name.takeIf { it.isNotEmpty() && it != entry.name },
                        number = number.takeIf { it.isNotEmpty() && it != entry.number },
                        group = group?.takeIf { it.isNotEmpty() && it != entry.group },
                        clearGroup = group != null && group.isEmpty() && entry.group != null,
                    )
                }
            }
            get("/merges") { call.html(mergesPage(call.session(), mergesView())) }
            action("/merges/never") { session, form -> policyAction(call, session, form) { policy, from, to, revision -> policy.neverMerge(from, to, revision) } }
            action("/merges/accept") { session, form -> policyAction(call, session, form) { policy, from, to, revision -> policy.addAlias(from, to, revision) } }
            action("/merges/dismiss") { session, form -> policyAction(call, session, form) { policy, from, to, revision -> policy.neverMerge(from, to, revision) } }
            get("/required") { call.html(requiredPage(call.session(), requiredView())) }
            get("/plex") { call.html(plexPage(call.session(), plexView(call, reveal = false))) }
            action("/plex/reveal") { session, _ -> call.html(plexPage(session, plexView(call, reveal = true))) }
        }
    }

    /** A POST that needs the session and its CSRF token, from the form or the `X-CSRF-Token` header. */
    private fun Route.action(path: String, body: suspend RoutingContext.(AdminSession, Parameters) -> Unit) {
        post(path) {
            val session = call.session()
            val form = call.receiveParameters()
            if (!sameSecret(form["csrf"] ?: call.request.headers["X-CSRF-Token"], session.csrf)) {
                call.respondText("The form expired; reload the page and try again", status = HttpStatusCode.Forbidden)
                return@post
            }
            body(session, form)
        }
    }

    /**
     * Changes one lineup entry the way `PATCH /api/v1/admin/lineup/{id}` does: through the policy, or through the
     * lineup without one. Portal edits touch one station's fields, so they apply to the file as it is now rather than
     * to the revision the page showed; background runs write the file often.
     */
    private suspend fun changeEntry(call: ApplicationCall, session: AdminSession, form: Parameters, id: String, request: (LineupEntryRecord) -> UpdateLineupEntry) {
        val filter = LineupFilter.from(form)
        val failure = try {
            val entry = runtime.lineup.entry(id) ?: throw ApiFailure(404, "LINEUP_ENTRY_NOT_FOUND", "That station is no longer in the lineup")
            val update = request(entry)
            val policy = runtime.policy
            if (update != UpdateLineupEntry()) {
                if (policy == null) runtime.lineup.update(id, update)
                else policy.updateStation(id, update, null) ?: throw ApiFailure(404, "LINEUP_ENTRY_NOT_FOUND", "The station left the lineup")
            }
            null
        } catch (e: ApiFailure) { e }
        if (failure == null) seeOther(call, "/admin/lineup" + filter.query().let { if (it.isEmpty()) "" else "?$it" })
        else call.html(lineupPage(session, lineupView(filter), RowError(id, failure.message)), HttpStatusCode.fromValue(failure.status))
    }

    private suspend fun policyAction(
        call: ApplicationCall, session: AdminSession, form: Parameters,
        write: suspend (dev.jamesrich.sofaking.backend.catalog.PolicyService, String, String, String?) -> Unit,
    ) {
        val policy = runtime.policy ?: throw ApiFailure(404, "POLICY_NOT_CONFIGURED", "No lineup policy is configured")
        val failure = try {
            val from = form["from"]?.trim().orEmpty()
            val to = form["to"]?.trim().orEmpty()
            if (from.isEmpty() || to.isEmpty()) throw ApiFailure(400, "INVALID_REQUEST", "Both keys are required")
            write(policy, from, to, null)
            null
        } catch (e: ApiFailure) { e }
        if (failure == null) seeOther(call, "/admin/merges")
        else call.html(mergesPage(session, mergesView(), failure.message), HttpStatusCode.fromValue(failure.status))
    }

    private suspend fun dashboard(): DashboardView {
        val entries = runtime.lineup.entries()
        val applied = runtime.policy?.lastApplied()
        return DashboardView(
            providers = runtime.feeds.providers(),
            guides = runtime.guides.statuses(),
            sessions = runtime.sessions.snapshot().map { it to runtime.lineup.entry(it.channelId)?.name },
            stations = entries.size,
            enabled = entries.count { it.enabled },
            policy = runtime.policy != null,
            unverified = applied?.stations?.count { it.disabledReason == "unverified" },
            required = applied?.stations?.count { it.required },
            findings = applied?.result?.findings?.size,
        )
    }

    private fun lineupView(filter: LineupFilter): LineupView {
        val policy = runtime.policy
        val stations = policy?.lastApplied()?.stations.orEmpty().associateBy { it.id }
        val coverage = runtime.guides.coverage()
        val now = clock.instant()
        val rows = runtime.lineup.entries().map { entry -> StationRow(entry, stations[entry.id], guideStatus(entry, coverage, now)) }
        val matches = rows.filter { filter.admits(it) }
        val pages = maxOf(1, (matches.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = filter.page.coerceIn(1, pages)
        val groups = rows.mapNotNull { it.entry.group }.distinct().sorted()
        val document = policy?.document()
        return LineupView(
            filter.copy(page = page), matches.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE), matches.size, rows.size, pages,
            groups, document?.revision, (groups + document?.policy?.numbering?.groups?.keys.orEmpty()).distinct().sorted(),
        )
    }

    private suspend fun mergesView(): MergesView? {
        val policy = runtime.policy ?: return null
        val document = policy.document()
        val findings = policy.findings()
        val suggestions = findings.suggestedAliases.mapNotNull { line ->
            SUGGESTION.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }
        return MergesView(document.revision, document.policy.names.learned, document.policy.names.never, suggestions, findings.findings, findings.unmappedCategories)
    }

    private suspend fun requiredView(): RequiredView? {
        val policy = runtime.policy ?: return null
        val applied = policy.lastApplied() ?: return RequiredView(emptyList(), ran = false)
        val health = runtime.store.allHealth()
        val overrides = policy.policy.stations
        val rows = applied.stations.filter { it.required }.map { station ->
            val counts = station.offeringIds.groupingBy { health[it]?.status?.name ?: "UNVERIFIED" }.eachCount()
            RequiredRow(station, overrides[station.key]?.sources.orEmpty(), counts.toSortedMap())
        }
        return RequiredView(rows, ran = true)
    }

    private suspend fun plexView(call: ApplicationCall, reveal: Boolean): PlexView {
        val base = publicBase(call)
        val host = Url(base).host.let { if (it.contains(':')) "[$it]" else it }
        val ports = hdhr.publicPorts(Url(base).port)
        val enabled = hdhrChannels(runtime.lineup.entries())
        val byRange = runtime.policy != null
        val devices = if (!hdhr.enabled) emptyList() else (0 until hdhr.devices).map { device ->
            val port = ports[device]
            val name = if (device == 0) hdhr.friendlyName else "${hdhr.friendlyName} ${device + 1}"
            HdhrDevice(name, "$host:$port", deviceShare(enabled, device, hdhr, byRange).size)
        }
        val tokenNeeded = outputs.token != null
        val shown = if (reveal) tokenQuery(outputs.token) else if (tokenNeeded) "?token=$HIDDEN_TOKEN" else ""
        return PlexView(
            playlist = "$base/output/playlist.m3u$shown",
            guide = "$base/output/guide.xml$shown",
            tokenConfigured = tokenNeeded,
            revealed = reveal && tokenNeeded,
            hdhrEnabled = hdhr.enabled,
            devices = devices,
            devicesNeeded = if (byRange && hdhr.enabled) (enabled.size + hdhr.deviceSize - 1) / hdhr.deviceSize else null,
            enabledChannels = enabled.size,
            tuners = hdhr.tuners,
            status = plexStatus?.lines(),
        )
    }

    private fun ApplicationCall.session(): AdminSession = attributes.getOrNull(SESSION) ?: throw ApiFailure(401, "AUTH_REQUIRED", "Sign in required")
}

private val SUGGESTION = Regex("(.+): possible alias of (.+)")

internal const val HIDDEN_TOKEN = "••••••••"

private fun sessionCookie(value: String, maxAge: Int, secure: Boolean) = Cookie(
    COOKIE, value, encoding = CookieEncoding.RAW, maxAge = maxAge, expires = if (maxAge == 0) GMTDate.START else null, path = "/admin", secure = secure,
    httpOnly = true, extensions = mapOf("SameSite" to "Strict"),
)

private suspend fun seeOther(call: ApplicationCall, location: String) {
    call.response.header(HttpHeaders.Location, location)
    call.respond(HttpStatusCode.SeeOther)
}

private suspend fun ApplicationCall.html(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(body, ContentType.Text.Html.withCharset(Charsets.UTF_8), status)

private fun guideStatus(entry: LineupEntryRecord, coverage: Map<String, Map<String, Instant>>, now: Instant): String {
    val guide = entry.guide ?: return "unassigned"
    val ends = coverage[guide.guideId]?.get(guide.channelId) ?: return "empty"
    return if (ends > now) "available" else "stale"
}

/** The lineup page's filters, read from a query or from the hidden fields of a row action. */
internal data class LineupFilter(val q: String = "", val group: String = "", val state: String = "", val required: Boolean = false, val page: Int = 1) {
    fun admits(row: StationRow): Boolean {
        val entry = row.entry
        if (q.isNotEmpty() && !entry.name.contains(q, ignoreCase = true) && entry.number != q && row.station?.key?.contains(q, ignoreCase = true) != true) return false
        if (group.isNotEmpty() && entry.group != group) return false
        if (required && !row.required) return false
        return when (state) {
            "enabled" -> entry.enabled
            "hidden" -> !entry.enabled
            "unverified" -> row.station?.disabledReason == "unverified"
            else -> true
        }
    }

    fun query(page: Int = this.page): String =
        listOf("q" to q, "group" to group, "state" to state, "required" to if (required) "true" else "", "page" to if (page > 1) page.toString() else "")
            .filter { it.second.isNotEmpty() }.formUrlEncode()

    companion object {
        fun from(parameters: Parameters) = LineupFilter(
            parameters["q"]?.trim().orEmpty().take(200),
            parameters["group"].orEmpty().take(200),
            parameters["state"]?.takeIf { it in setOf("enabled", "hidden", "unverified") }.orEmpty(),
            parameters["required"] == "true",
            parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
        )
    }
}

internal class StationRow(val entry: LineupEntryRecord, val station: PolicyStation?, val guideStatus: String) {
    val required: Boolean get() = station?.required == true
    val state: String get() = if (entry.enabled) "on" else "off: " + (station?.disabledReason ?: entry.autoDisabledReason ?: "hidden")
}

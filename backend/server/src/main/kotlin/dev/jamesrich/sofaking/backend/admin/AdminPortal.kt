package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.LoadedPolicy
import dev.jamesrich.sofaking.backend.catalog.parsePolicy
import dev.jamesrich.sofaking.backend.catalog.policyBytes
import dev.jamesrich.sofaking.backend.catalog.policyRevision
import dev.jamesrich.sofaking.backend.catalog.validatePolicy
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import dev.jamesrich.sofaking.backend.http.ADMIN_LOGIN_LIMIT
import dev.jamesrich.sofaking.backend.http.authFailed
import dev.jamesrich.sofaking.backend.http.deviceShare
import io.ktor.server.plugins.ratelimit.rateLimit
import dev.jamesrich.sofaking.backend.http.hdhrChannels
import dev.jamesrich.sofaking.backend.http.publicBase
import dev.jamesrich.sofaking.backend.http.tokenQuery
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.*
import kotlinx.coroutines.CancellationException
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import io.ktor.util.date.GMTDate
import java.time.Clock
import java.time.Instant

/** Supplies the last Plex sync's status; without a source the portal treats Plex sync as not set up. */
fun interface PlexStatusSource {
    suspend fun status(): PlexStatus
}

private const val COOKIE = "sofa_admin"
private const val PAGE_SIZE = 100
private val SESSION = AttributeKey<AdminSession>("AdminSession")
private const val HTMX_PATH = "/admin/static/htmx.min.js"
private const val ICON_PATH = "/admin/static/icon.svg"
/** The sign-in form, the brand icon its tab shows and the stylesheet; everything else needs a session. */
private val PUBLIC_PATHS = setOf("/admin", "/admin/login", ICON_PATH, STYLESHEET_PATH)
private const val CSP =
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

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
    /** The settings the server started with, for the settings page; null leaves that section out. */
    private val config: ServerConfig? = null,
) {
    val sessions = AdminSessions(clock)

    /**
     * Decides a portal request on the routed [path] before routing runs, so no spelling of a page skips the session.
     * Returns false once it has answered.
     */
    internal suspend fun admit(call: ApplicationCall, path: String): Boolean {
        if (path != HTMX_PATH && path != ICON_PATH && path != STYLESHEET_PATH) call.response.header(HttpHeaders.CacheControl, "no-store")
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
                else call.html(overviewPage(chrome(session), overview()))
            }
            rateLimit(ADMIN_LOGIN_LIMIT) { post("/login") {
                val expected = secret ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND", "Resource not found")
                val presented = call.formOrEmpty()["token"]
                if (!sameSecret(presented, expected)) {
                    call.authFailed()
                    call.html(signInPage(enabled = true, error = "That token is not the admin token."), HttpStatusCode.Unauthorized)
                    return@post
                }
                call.request.cookies[COOKIE]?.let(sessions::close)
                val session = sessions.open()
                val secure = call.request.headers["X-Forwarded-Proto"] == "https" || call.request.local.scheme == "https"
                call.response.cookies.append(sessionCookie(session.id, maxAge = 12 * 3600, secure = secure))
                seeOther(call, "/admin")
            } }
            action("/logout") { session, _ ->
                sessions.close(session.id)
                call.response.cookies.append(sessionCookie("", maxAge = 0, secure = false))
                seeOther(call, "/admin")
            }
            staticResources("/static", "admin") { modify { _, call -> call.response.header(HttpHeaders.CacheControl, "private, max-age=86400") } }
            get("/lineup") { call.html(lineupPage(chrome(call.session()), lineupView(LineupFilter.from(call.request.queryParameters)))) }
            get("/lineup/{id}") {
                val session = call.session()
                val view = stationView(call.parameters["id"]!!, LineupFilter.from(call.request.queryParameters))
                // 404 would be replaced by the API's JSON error page.
                if (view == null) call.html(goneStationPage(chrome(session)), HttpStatusCode.Gone) else call.html(stationPage(chrome(session), view))
            }
            action("/lineup/{id}/visibility") { session, form ->
                val enabled = form["enabled"]?.toBooleanStrictOrNull() ?: throw ApiFailure(400, "INVALID_REQUEST", "enabled must be true or false")
                changeEntry(call, session, form, call.parameters["id"]!!, if (enabled) "Showing" else "Hid") { UpdateLineupEntry(enabled = enabled) }
            }
            action("/lineup/{id}/edit") { session, form ->
                changeEntry(call, session, form, call.parameters["id"]!!, "Saved") { entry ->
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
            get("/merges") { call.html(mergesPage(chrome(call.session()), mergesView())) }
            action("/merges/never") { session, form -> policyAction(call, session, form, "Kept apart") { policy, from, to, revision -> policy.neverMerge(from, to, revision) } }
            action("/merges/accept") { session, form -> policyAction(call, session, form, "Merged") { policy, from, to, revision -> policy.addAlias(from, to, revision) } }
            action("/merges/dismiss") { session, form -> policyAction(call, session, form, "Kept apart") { policy, from, to, revision -> policy.neverMerge(from, to, revision) } }
            get("/required") { call.html(requiredPage(chrome(call.session()), requiredView())) }
            get("/providers") {
                val chrome = chrome(call.session())
                val view = providersView()
                call.html(providersPage(chrome, view) { providerEntries(chrome, view, null, null) })
            }
            action("/providers/edit") { session, form ->
                policyEdit(call, session, form, "/admin/providers", { providersEdit(form, it) }) { chrome, errors, confirm ->
                    val view = providersView()
                    providersPage(chrome, view) { providerEntries(chrome, view, errors, confirm) }
                }
            }
            get("/groups") { call.html(groupsPage(chrome(call.session()), groupsView())) }
            action("/groups/edit") { session, form ->
                policyEdit(call, session, form, "/admin/groups", { groupsEdit(form) }) { chrome, errors, confirm -> groupsPage(chrome, groupsView(), errors, confirm) }
            }
            get("/plex") { call.html(plexPage(chrome(call.session()), plexView(call, reveal = false))) }
            action("/plex/reveal") { session, _ -> call.html(plexPage(chrome(session), plexView(call, reveal = true))) }
            get("/settings") {
                val limit = call.request.queryParameters["changes"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 50
                call.html(settingsPage(chrome(call.session()), settingsView(limit)))
            }
            action("/settings/reveal-api-token") { session, _ -> call.html(settingsPage(chrome(session), settingsView(50, revealApiToken = true))) }
            action("/policy/apply") { session, _ ->
                val policy = runtime.policy
                if (policy == null) {
                    call.html(settingsPage(chrome(session, Flash("No lineup policy is configured.", Tone.BAD)), settingsView(50)), HttpStatusCode.Conflict)
                    return@action
                }
                val result = policy.apply("api")
                val summary = "${thousands(result.enabledStations)} on of ${thousands(result.stations)}, " + if (result.changed) plural(result.changes, "field") + " changed." else "no change."
                session.flash.set(Flash("Applied the policy: $summary", if (result.findings.any { it.startsWith("feeds:") }) Tone.WARN else Tone.OK))
                seeOther(call, "/admin/settings")
            }
        }
    }

    /** The posted form; a body that is not a form reads as an empty one, so it fails the token checks instead of erroring. */
    private suspend fun ApplicationCall.formOrEmpty(): Parameters =
        try { receiveParameters() } catch (e: CancellationException) { throw e } catch (_: Exception) { Parameters.Empty }

    /** A POST that needs the session and its CSRF token, from the form or the `X-CSRF-Token` header. */
    private fun Route.action(path: String, body: suspend RoutingContext.(AdminSession, Parameters) -> Unit) {
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
    private suspend fun chrome(session: AdminSession, flash: Flash? = null): Chrome = Chrome(
        session, clock.instant(), flash ?: session.flash.getAndSet(null), badges(), config?.revision ?: "unknown", runtime.startedAt,
    )

    /** Nav counts from in-memory state only, so every page stays cheap. */
    private suspend fun badges(): Map<String, Int> {
        val applied = runtime.policy?.lastApplied()
        val plex = plexStatus?.status()
        return mapOf(
            "merges" to (applied?.result?.suggestedAliases?.size ?: 0),
            "required" to (applied?.stations?.count { it.required && !it.enabled } ?: 0),
            "providers" to runtime.feeds.providers().count { it.errorCode != null } + runtime.guides.statuses().count { it.errorCode != null },
            "plex" to (if (plex?.lastError != null) 1 else 0) + (plex?.missingDevices?.size ?: 0),
        )
    }

    /**
     * Changes one lineup entry the way `PATCH /api/v1/admin/lineup/{id}` does: through the policy, or through the
     * lineup without one. Portal edits touch one station's fields, so they apply to the file as it is now rather than
     * to the revision the page showed; background runs write the file often.
     */
    private suspend fun changeEntry(
        call: ApplicationCall, session: AdminSession, form: Parameters, id: String, verb: String, request: (LineupEntryRecord) -> UpdateLineupEntry,
    ) {
        val filter = LineupFilter.from(form)
        var name: String? = null
        val failure = try {
            val entry = runtime.lineup.entry(id) ?: throw ApiFailure(404, "LINEUP_ENTRY_NOT_FOUND", "That station is no longer in the lineup")
            name = entry.name
            val update = request(entry)
            val policy = runtime.policy
            if (update != UpdateLineupEntry()) {
                if (policy == null) runtime.lineup.update(id, update)
                else policy.updateStation(id, update, null) ?: throw ApiFailure(404, "LINEUP_ENTRY_NOT_FOUND", "The station left the lineup")
            }
            null
        } catch (e: ApiFailure) { e }
        val query = filter.query().let { if (it.isEmpty()) "" else "?$it" }
        val fromStation = form["from"] == "station" || form["name"] != null
        if (failure == null) {
            val now = runtime.lineup.entry(id)?.name ?: name
            session.flash.set(Flash("$verb $now.", Tone.OK, id))
            seeOther(call, if (form["from"] == "station") "/admin/lineup/$id$query" else "/admin/lineup$query")
            return
        }
        val status = HttpStatusCode.fromValue(failure.status)
        val station = if (fromStation) stationView(id, filter, failure.message) else null
        if (station != null) call.html(stationPage(chrome(session), station), status)
        else call.html(lineupPage(chrome(session, Flash("Not saved: ${failure.message}", Tone.BAD, id)), lineupView(filter)), status)
    }

    private suspend fun policyAction(
        call: ApplicationCall, session: AdminSession, form: Parameters, verb: String,
        write: suspend (dev.jamesrich.sofaking.backend.catalog.PolicyService, String, String, String?) -> Unit,
    ) {
        val policy = runtime.policy ?: throw ApiFailure(404, "POLICY_NOT_CONFIGURED", "No lineup policy is configured")
        val from = form["from"]?.trim().orEmpty()
        val to = form["to"]?.trim().orEmpty()
        val failure = try {
            if (from.isEmpty() || to.isEmpty()) throw ApiFailure(400, "INVALID_REQUEST", "Both keys are required")
            write(policy, from, to, null)
            null
        } catch (e: ApiFailure) { e }
        if (failure == null) {
            session.flash.set(Flash("$verb: $from and $to. The policy was applied."))
            seeOther(call, "/admin/merges")
        } else call.html(mergesPage(chrome(session, Flash("Not saved: ${failure.message}", Tone.BAD)), mergesView(), failure.message), HttpStatusCode.fromValue(failure.status))
    }

    /**
     * A taxonomy or provider edit, made the way `PUT /api/v1/admin/policy` makes one: the whole file, written against
     * the revision the page showed, validated, then applied. An edit that changes any station, or that meets a policy
     * that moved on since the page loaded, is shown with its effect and waits for `confirm=yes`.
     */
    private suspend fun policyEdit(
        call: ApplicationCall, session: AdminSession, form: Parameters, page: String,
        parse: (LineupPolicy) -> PolicyEdit, render: suspend (Chrome, EditErrors?, Confirm?) -> String,
    ) {
        val section = sectionOf(form["op"])
        val policy = runtime.policy
        if (policy == null) {
            call.html(render(chrome(session, Flash("No lineup policy is configured.", Tone.BAD)), null, null), HttpStatusCode.Conflict)
            return
        }
        suspend fun refuse(messages: List<String>, status: HttpStatusCode = HttpStatusCode.BadRequest) =
            call.html(render(chrome(session, Flash("Not saved: ${messages.first()}", Tone.BAD)), EditErrors(section, messages), null), status)
        val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(policy.file.path) }
        val current = policyRevision(bytes)
        val (edit, next) = try {
            val edit = parse(parsePolicy(bytes))
            val tree = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: throw ApiFailure(409, "POLICY_CHANGED", "The policy file is not a JSON object")
            edit to policyBytes(edit.change(tree))
        } catch (e: ApiFailure) { refuse(listOf(e.message)); return }
        val candidate = try { parsePolicy(next) } catch (e: Exception) { refuse(listOf("The edit does not fit the policy schema")); return }
        val errors = validatePolicy(candidate, runtime.feeds.configs.mapTo(HashSet()) { it.id })
        if (errors.isNotEmpty()) { refuse(errors); return }
        val stale = form["revision"] != current
        if (stale || form["confirm"] != "yes") {
            val impact = impactOf(policy.run().stations, policy.run(LoadedPolicy(policy.file.path, candidate, "candidate")).stations)
            if (stale || !impact.isEmpty) {
                val fields = form.entries().filter { it.key !in setOf("csrf", "revision", "confirm") }.flatMap { (name, values) -> values.map { name to it } }
                val confirm = Confirm("$page/edit", edit.describe, fields, current, impact, stale, "$page#${edit.section}")
                call.html(render(chrome(session), null, confirm), if (stale) HttpStatusCode.Conflict else HttpStatusCode.OK)
                return
            }
        }
        val result = try { policy.replace(current, next).apply } catch (e: ApiFailure) {
            if (e.status == 409) refuse(listOf("The policy changed while saving; nothing was saved. Reload the page and repeat the edit."), HttpStatusCode.Conflict)
            else refuse(e.message.split("; "))
            return
        }
        session.flash.set(Flash("Saved: ${edit.describe}. ${thousands(result.enabledStations)} channels on of ${thousands(result.stations)}."))
        seeOther(call, "$page#${edit.section}")
    }

    private fun groupsView(): GroupsView? {
        val policy = runtime.policy ?: return null
        val document = policy.document()
        val p = document.policy
        val rows = rows()
        val byGroup = rows.groupBy { it.entry.group }
        val starts = p.numbering.groups.entries.sortedBy { it.value }
        val blocks = starts.mapIndexed { i, (group, start) ->
            val next = starts.getOrNull(i + 1)?.value ?: Int.MAX_VALUE
            val members = byGroup[group].orEmpty()
            BlockRow(group, start, minOf(start.toLong() + p.numbering.groupSize - 1, next.toLong() - 1).toInt(), members.count { it.entry.enabled }, members.size)
        }
        val unblocked = byGroup.filterKeys { it != null && it !in p.numbering.groups }.map { (group, members) -> group!! to members.size }.sortedByDescending { it.second }
        return GroupsView(document.revision, p, blocks, groupChoices(rows), policy.lastApplied()?.result?.unmappedCategories.orEmpty(), unblocked)
    }

    private suspend fun overview(): OverviewView {
        val now = clock.instant()
        val entries = runtime.lineup.entries()
        val policy = runtime.policy
        val applied = policy?.lastApplied()
        val coverage = runtime.guides.coverage()
        val enabled = entries.filter { it.enabled }
        val byGuide = enabled.groupingBy { guideStatus(it, coverage, now) }.eachCount()
        val guide = GuideCoverage(byGuide["available"] ?: 0, byGuide["stale"] ?: 0, byGuide["empty"] ?: 0, byGuide["unassigned"] ?: 0)
        val providers = runtime.feeds.providers()
        val guides = runtime.guides.statuses()
        val plex = plexStatus?.status()
        val stations = applied?.stations
        val requiredOff = stations?.count { it.required && !it.enabled }
        val unverified = stations?.count { it.disabledReason == "unverified" }
        val attention = buildList {
            val failed = providers.filter { it.errorCode != null }
            if (failed.isNotEmpty()) add(Attention(
                Tone.BAD, if (failed.size == 1) "${failed[0].id} failed its last import" else "${failed.size} providers failed their last import",
                failed.joinToString { "${it.id} (${it.errorCode})" }, "/admin/providers#imports", "Providers",
            ))
            val waiting = providers.filter { it.errorCode == null && it.lastSuccessAt == null }
            if (waiting.isNotEmpty()) add(Attention(Tone.WARN, "Waiting for the first import of ${waiting.joinToString { it.id }}", null, "/admin/providers#imports", "Providers"))
            val badGuides = guides.filter { it.errorCode != null }
            if (badGuides.isNotEmpty()) add(Attention(
                Tone.BAD, if (badGuides.size == 1) "Guide ${badGuides[0].id} failed its last import" else "${badGuides.size} guides failed their last import",
                badGuides.joinToString { "${it.id} (${it.errorCode})" }, "/admin/providers#guides", "Guides",
            ))
            if (plex?.lastError != null) add(Attention(Tone.BAD, "Plex sync failed", plex.lastError, "/admin/plex#sync", "Plex"))
            if (!plex?.missingDevices.isNullOrEmpty()) add(Attention(
                Tone.WARN, "${plural(plex!!.missingDevices.size, "tuner")} not in Plex", plex.missingDevices.joinToString(), "/admin/plex#tuners", "Plex",
            ))
            val needed = applied?.result?.devicesNeeded
            val configured = applied?.result?.devicesConfigured
            if (needed != null && configured != null && needed > configured) add(Attention(
                Tone.BAD, "The lineup needs $needed tuners; $configured are configured", "Add ports to SOFA_HDHR_EXTRA_PORTS, then add the devices in Plex.", "/admin/plex#tuners", "Plex",
            ))
            if ((requiredOff ?: 0) > 0) add(Attention(Tone.BAD, "${plural(requiredOff!!, "required channel")} off", null, "/admin/required", "Required"))
            val suggestions = applied?.result?.suggestedAliases?.size ?: 0
            if (suggestions > 0) add(Attention(Tone.WARN, "${plural(suggestions, "merge suggestion")} to settle", "Pairs of stations that are probably one channel.", "/admin/merges#suggested", "Merges"))
            val findings = applied?.result?.findings?.size ?: 0
            if (findings > 0) add(Attention(Tone.WARN, "${plural(findings, "open finding")} from the last run", null, "/admin/merges#findings", "Findings"))
            val unmapped = applied?.result?.unmappedCategories?.size ?: 0
            if (unmapped > 0) add(Attention(Tone.WARN, "${plural(unmapped, "category", "categories")} map to no group", "Their stations fall to the default group.", "/admin/groups#unmapped", "Groups"))
            val ended = guide.stale + guide.empty
            if (ended > 0) add(Attention(Tone.WARN, "${plural(ended, "on-air channel")} show no programmes", "Their guide ended or holds nothing ahead.", "/admin/lineup?state=enabled&guide=missing", "Channels"))
            if (policy != null && applied == null) add(Attention(Tone.OFF, "No lineup run since the server started", "Runs follow imports and every 15 minutes.", "/admin/settings#policy", "Settings"))
            if ((unverified ?: 0) > 0) add(Attention(Tone.OFF, "${plural(unverified!!, "channel")} waiting for a verified source", "They come on as probes verify a source.", "/admin/lineup?state=unverified", "Channels"))
        }.sortedBy { listOf(Tone.BAD, Tone.WARN, Tone.OK, Tone.OFF).indexOf(it.tone) }
        return OverviewView(
            attention = attention,
            stations = entries.size, enabled = enabled.size, guide = guide,
            required = stations?.count { it.required }, requiredOff = requiredOff,
            suggestions = applied?.result?.suggestedAliases?.size, findings = applied?.result?.findings?.size, unverified = unverified,
            providersOk = providers.count { it.errorCode == null && it.lastSuccessAt != null }, providersTotal = providers.size,
            plex = plex?.let { PlexSummary(it.lastSyncAt, it.lastError, it.devices.size, it.devices.sumOf { d -> d.mapped }, it.missingDevices.size, it.syncMinutes) },
            sessions = runtime.sessions.snapshot().map { it to runtime.lineup.entry(it.channelId)?.name },
            lastRun = applied?.result, policy = policy != null,
        )
    }

    private fun rows(): List<StationRow> {
        val stations = runtime.policy?.lastApplied()?.stations.orEmpty().associateBy { it.id }
        val coverage = runtime.guides.coverage()
        val now = clock.instant()
        return runtime.lineup.entries().map { entry -> StationRow(entry, stations[entry.id], guideStatus(entry, coverage, now)) }
    }

    private fun groupChoices(rows: List<StationRow>): List<String> {
        val policy = runtime.policy?.policy
        val named = policy?.let { it.numbering.groups.keys + it.groups.map.values + it.groups.patterns.map { p -> p.group } + listOfNotNull(it.groups.default) }.orEmpty()
        return (rows.mapNotNull { it.entry.group } + named).distinct().sorted()
    }

    private fun lineupView(filter: LineupFilter): LineupView {
        val rows = rows()
        val matches = rows.filter { filter.admits(it) }
        val pages = maxOf(1, (matches.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = filter.page.coerceIn(1, pages)
        val groups = rows.mapNotNull { it.entry.group }.distinct().sorted()
        return LineupView(
            filter.copy(page = page), matches.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE), matches.size, rows.size, pages,
            groups, runtime.policy?.document()?.revision, groupChoices(rows), rows.count { it.entry.enabled },
        )
    }

    private suspend fun stationView(id: String, filter: LineupFilter, error: String? = null): StationView? {
        val rows = rows()
        val row = rows.firstOrNull { it.entry.id == id } ?: return null
        val health = runtime.store.allHealth()
        val sources = row.entry.offeringIds.map { offeringId ->
            val offering = runtime.feeds.offering(offeringId)
            if (offering == null) SourceLine("gone", null, "", "WITHDRAWN")
            else SourceLine(offering.providerId, runtime.feeds.channel(offering.channelId)?.name, offering.protocol, health[offeringId]?.status?.name ?: "UNVERIFIED")
        }
        val guideName = row.entry.guide?.let { runtime.guides.channel(it.guideId, it.channelId)?.displayName?.let { name -> "$name (${it.guideId})" } }
        return StationView(row, sources, guideName, filter, runtime.policy?.document()?.revision, groupChoices(rows), error)
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

    private fun providersView(): ProvidersView {
        val configs = runtime.feeds.configs.associateBy { it.id }
        val document = runtime.policy?.document()
        val entries = document?.policy?.providers
        val led = runtime.policy?.lastApplied()?.stations.orEmpty()
            .mapNotNull { station -> station.offeringIds.firstOrNull()?.let { runtime.feeds.offering(it)?.providerId } }
            .groupingBy { it }.eachCount()
        val rows = runtime.feeds.providers().map { status ->
            val config = configs[status.id]
            val kind = when {
                config == null -> ""
                config.native && config.service != config.id -> "native: ${config.service}"
                config.native -> "native"
                else -> "M3U"
            }
            ProviderRow(status, kind, entries.orEmpty().withIndex().filter { it.value.id == status.id })
        }
        return ProvidersView(rows, runtime.guides.statuses(), entries, document?.revision, led)
    }

    private suspend fun settingsView(changeLimit: Int, revealApiToken: Boolean = false): SettingsView {
        val policy = runtime.policy
        val document = policy?.document()
        return SettingsView(
            config?.let(::effectiveSettings).orEmpty(),
            runtime.feeds.configs.map(::providerSummary),
            runtime.guides.configs.map(::guideSummary),
            document?.let { PolicySummary(it.path, it.revision, it.policy, policy.lastApplied()?.result) },
            policy?.changes(changeLimit).orEmpty(),
            changeLimit,
            apiTokenSet = config?.apiToken != null,
            revealedApiToken = config?.apiToken?.takeIf { revealApiToken },
        )
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
            status = plexStatus?.status(),
        )
    }

    private fun ApplicationCall.session(): AdminSession = attributes.getOrNull(SESSION) ?: throw ApiFailure(401, "AUTH_REQUIRED", "Sign in required")
}

private val SUGGESTION = Regex("(.+): possible alias of (.+)")

/** The page section an edit's errors show in, by its op. */
private fun sectionOf(op: String?): String = when (op) {
    "block", "block-remove", "default" -> "blocks"
    "map", "map-remove" -> "map"
    "weak", "weak-remove" -> "weak"
    "pattern", "pattern-remove" -> "patterns"
    else -> "entries"
}

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
internal data class LineupFilter(
    val q: String = "", val group: String = "", val state: String = "", val required: Boolean = false, val page: Int = 1, val guide: String = "",
) {
    fun admits(row: StationRow): Boolean {
        val entry = row.entry
        if (q.isNotEmpty() && !entry.name.contains(q, ignoreCase = true) && entry.number != q && row.station?.key?.contains(q, ignoreCase = true) != true) return false
        if (group.isNotEmpty() && entry.group != group) return false
        if (required && !row.required) return false
        when (guide) {
            "available" -> if (row.guideStatus != "available") return false
            "missing" -> if (row.guideStatus == "available") return false
        }
        return when (state) {
            "enabled" -> entry.enabled
            "hidden" -> !entry.enabled
            "unverified" -> row.station?.disabledReason == "unverified"
            else -> true
        }
    }

    fun query(page: Int = this.page): String =
        listOf("q" to q, "group" to group, "state" to state, "guide" to guide, "required" to if (required) "true" else "", "page" to if (page > 1) page.toString() else "")
            .filter { it.second.isNotEmpty() }.formUrlEncode()

    companion object {
        fun from(parameters: Parameters) = LineupFilter(
            parameters["q"]?.trim().orEmpty().take(200),
            parameters["group"].orEmpty().take(200),
            parameters["state"]?.takeIf { it in setOf("enabled", "hidden", "unverified") }.orEmpty(),
            parameters["required"] == "true",
            parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            parameters["guide"]?.takeIf { it in setOf("available", "missing") }.orEmpty(),
        )
    }
}

internal class StationRow(val entry: LineupEntryRecord, val station: PolicyStation?, val guideStatus: String) {
    val required: Boolean get() = station?.required == true
    val state: String get() = if (entry.enabled) "on" else "off: " + (station?.disabledReason ?: entry.autoDisabledReason ?: "hidden")
}

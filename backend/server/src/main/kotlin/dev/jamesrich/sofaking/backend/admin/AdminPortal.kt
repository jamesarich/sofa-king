package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.MIB
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.StationOverride
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.api.UpdateSource
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.LoadedPolicy
import dev.jamesrich.sofaking.backend.catalog.SourceDocument
import dev.jamesrich.sofaking.backend.catalog.parsePolicy
import dev.jamesrich.sofaking.backend.catalog.policyBytes
import dev.jamesrich.sofaking.backend.catalog.policyRevision
import dev.jamesrich.sofaking.backend.catalog.validatePolicy
import dev.jamesrich.sofaking.backend.dvr.Dvr
import dev.jamesrich.sofaking.backend.dvr.Retention
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.http.ADMIN_LOGIN_LIMIT
import dev.jamesrich.sofaking.backend.http.authFailed
import dev.jamesrich.sofaking.backend.http.deviceShare
import dev.jamesrich.sofaking.backend.http.hdhrChannels
import dev.jamesrich.sofaking.backend.http.publicBase
import dev.jamesrich.sofaking.backend.http.serverStatus
import dev.jamesrich.sofaking.backend.http.tokenQuery
import dev.jamesrich.sofaking.backend.library.LIBRARY_SCAN_JOB
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.LibraryRecord
import dev.jamesrich.sofaking.backend.library.LibraryService
import dev.jamesrich.sofaking.backend.logging.EventRing
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.settings.RuntimeValues
import dev.jamesrich.sofaking.backend.store.AdChannelRecord
import dev.jamesrich.sofaking.backend.store.adChannels
import dev.jamesrich.sofaking.backend.store.allHealth
import dev.jamesrich.sofaking.backend.store.forgetViewer
import dev.jamesrich.sofaking.backend.store.ignoreAttention
import dev.jamesrich.sofaking.backend.store.ignoredAttention
import dev.jamesrich.sofaking.backend.store.library
import dev.jamesrich.sofaking.backend.store.showAttention
import dev.jamesrich.sofaking.backend.store.viewers
import dev.jamesrich.sofaking.contract.CreateRecordingRule
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.UpdateRecording
import dev.jamesrich.sofaking.contract.UpdateRecordingRule
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.CacheControl
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.append
import io.ktor.http.cookies
import io.ktor.http.date
import io.ktor.http.expires
import io.ktor.http.formUrlEncode
import io.ktor.http.headers
import io.ktor.http.maxAge
import io.ktor.http.parameters
import io.ktor.http.path
import io.ktor.http.set
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.expires
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.accept
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.header
import io.ktor.server.routing.host
import io.ktor.server.routing.path
import io.ktor.server.routing.port
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.query
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Supplies the last Plex sync's status; without a source the portal treats Plex sync as not set up. */
fun interface PlexStatusSource {
    suspend fun status(): PlexStatus
}

private const val COOKIE = "sofa_admin"
private const val THEME_COOKIE = "sofa_theme"
private const val PAGE_SIZE = 100

/** The change log entries the settings page shows at first, and the most it shows. */
private const val CHANGES_SHOWN = 50
internal const val CHANGES_LIMIT = 1000

/** A manual recording's length, up to 12 hours. */
private val MANUAL_MINUTES = 1..720
private const val ATTENTION_KEY_LIMIT = 64
private val SESSION = AttributeKey<AdminSession>("AdminSession")
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
    private val runtime: LiveRuntime,
    private val secret: String?,
    private val outputs: OutputConfig,
    private val hdhr: HdhrConfig,
    private val clock: Clock,
    private val plexStatus: PlexStatusSource? = null,
    /** The settings the server started with, for the settings page; null leaves that section out. */
    private val config: ServerConfig? = null,
    /** The log events the Events page lists. */
    private val events: EventRing = EventRing.shared,
) {
    val sessions = AdminSessions(clock, secret, runtime.store)
    private val ffmpeg = AtomicReference<String?>()

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
        if (path in PUBLIC_PATHS) return true
        val session = sessions.find(call.request.cookies[COOKIE])
        if (session != null) {
            call.attributes.put(SESSION, session)
            return true
        }
        if (call.request.httpMethod == HttpMethod.Get && call.request.headers["HX-Request"] == null) {
            seeOther(call, "/admin")
        } else {
            call.response.header("HX-Redirect", "/admin")
            call.respondText("Sign in required", status = HttpStatusCode.Unauthorized)
        }
        return false
    }

    fun install(root: Route) {
        root.route("/admin") {
            get {
                val session = sessions.find(call.request.cookies[COOKIE])
                if (session == null) {
                    call.html(signInPage(enabled = secret != null, theme = theme(call)))
                } else {
                    call.html(overviewPage(chrome(call, session), overview()))
                }
            }
            action("/theme") { _, form ->
                val theme = form["theme"]
                val back =
                    form["back"]?.takeIf {
                        it.startsWith("/admin") && !it.startsWith("//") && '\n' !in it && '\r' !in it
                    }
                        ?: "/admin"
                if (theme in THEMES) {
                    call.response.cookies.append(themeCookie(theme!!, maxAge = 365 * 86_400))
                } else {
                    call.response.cookies.append(themeCookie("", maxAge = 0))
                }
                seeOther(call, back)
            }
            action("/sessions/{id}/stop") { session, form ->
                val id = call.parameters["id"]!!
                val row = overview().sessions.firstOrNull { it.active.id == id }
                when {
                    row == null -> {
                        session.flash.set(Flash("That session already ended.", Tone.WARN))
                        seeOther(call, "/admin#watching")
                    }

                    form["confirm"] != "yes" -> call.html(
                        overviewPage(
                            chrome(call, session),
                            overview().let {
                                OverviewView(it, stop = StopConfirm(row))
                            },
                        ),
                    )

                    else -> {
                        runtime.sessions.release(id)
                        session.flash.set(Flash("Stopped the session on ${row.channel ?: row.active.channelId}."))
                        seeOther(call, "/admin#watching")
                    }
                }
            }
            action("/vod/{id}/stop") { session, _ ->
                val id = call.parameters["id"]!!
                val row = playingRows().firstOrNull { it.activity.id == id }
                if (row == null || runtime.vod?.release(id) != true) {
                    session.flash.set(Flash("That session already ended.", Tone.WARN))
                } else {
                    session.flash.set(Flash("Stopped playing ${row.title}."))
                }
                seeOther(call, "/admin#watching")
            }
            action("/attention/ignore") { session, form ->
                val key = form["key"]?.trim().orEmpty().take(64)
                val fingerprint = form["fingerprint"]?.trim().orEmpty().take(64)
                if (key.isEmpty() || fingerprint.isEmpty()) {
                    throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "key and fingerprint are required")
                }
                runtime.store.ignoreAttention(key, fingerprint, clock.instant())
                session.flash.set(Flash("Ignored until it changes."))
                seeOther(call, "/admin#attention")
            }
            action("/attention/show") { session, form ->
                runtime.store.showAttention(form["key"]?.trim().orEmpty().take(ATTENTION_KEY_LIMIT))
                session.flash.set(Flash("Shown again."))
                seeOther(call, "/admin#attention")
            }
            rateLimit(ADMIN_LOGIN_LIMIT) {
                post("/login") {
                    val expected =
                        secret ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")
                    val presented = call.formOrEmpty()["token"]
                    if (!sameSecret(presented, expected)) {
                        call.authFailed()
                        call.html(
                            signInPage(enabled = true, error = "That token is not the admin token."),
                            HttpStatusCode.Unauthorized,
                        )
                        return@post
                    }
                    call.request.cookies[COOKIE]?.let { sessions.close(it) }
                    val opened = sessions.open()
                    val secure =
                        call.request.headers["X-Forwarded-Proto"] == "https" || call.request.local.scheme == "https"
                    call.response.cookies.append(sessionCookie(opened.cookie, maxAge = 12 * 3600, secure = secure))
                    seeOther(call, "/admin")
                }
            }
            action("/logout") { session, _ ->
                sessions.close(session)
                call.response.cookies.append(sessionCookie("", maxAge = 0, secure = false))
                seeOther(call, "/admin")
            }
            staticResources("/static", "admin") {
                modify { _, call -> call.response.header(HttpHeaders.CacheControl, "private, max-age=86400") }
            }
            get("/lineup") {
                val view = lineupView(LineupFilter.from(call.request.queryParameters))
                call.html(
                    lineupPage(
                        chrome(),
                        if (call.request.queryParameters["select"] == "page") {
                            LineupView(view, selectAll = true)
                        } else {
                            view
                        },
                    ),
                )
            }
            get("/lineup/{id}") {
                val view = stationView(call.parameters["id"]!!, LineupFilter.from(call.request.queryParameters))
                // 404 would be replaced by the API's JSON error page.
                if (view == null) {
                    call.html(goneStationPage(chrome()), HttpStatusCode.Gone)
                } else {
                    call.html(stationPage(chrome(), view))
                }
            }
            action("/lineup/{id}/visibility") { session, form ->
                val enabled =
                    form["enabled"]?.toBooleanStrictOrNull()
                        ?: throw ApiFailure(
                            HttpStatusCode.BadRequest,
                            "INVALID_REQUEST",
                            "enabled must be true or false",
                        )
                changeEntry(call, session, form, call.parameters["id"]!!, if (enabled) "Showing" else "Hid") {
                    UpdateLineupEntry(enabled = enabled)
                }
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
            action("/lineup/{id}/sources/{offeringId}/disable") { session, form ->
                val id = call.parameters["id"]!!
                val offeringId = call.parameters["offeringId"]!!
                val filter = LineupFilter.from(form)
                val disabled =
                    form["disabled"]?.toBooleanStrictOrNull()
                        ?: throw ApiFailure(
                            HttpStatusCode.BadRequest,
                            "INVALID_REQUEST",
                            "disabled must be true or false",
                        )
                val back = "/admin/lineup/$id" + filter.query().let { if (it.isEmpty()) "" else "?$it" }
                val view =
                    stationView(id, filter)
                        ?: run {
                            call.html(goneStationPage(chrome(call, session)), HttpStatusCode.Gone)
                            return@action
                        }
                val source = view.sources.firstOrNull { it.id == offeringId } ?: run {
                    session.flash.set(Flash("That source no longer carries the channel.", Tone.WARN))
                    seeOther(call, back)
                    return@action
                }
                if (disabled && form["confirm"] != "yes") {
                    call.html(stationPage(chrome(call, session), StationView(view, disableConfirm = source)))
                    return@action
                }
                runtime.feeds.updateSource(offeringId, UpdateSource(manuallyDisabled = disabled))
                runtime.sessions.healthChanged(offeringId)
                session.flash.set(
                    Flash(
                        (if (disabled) "Disabled" else "Enabled") + " the source from ${source.provider}.",
                        Tone.OK,
                        offeringId,
                    ),
                )
                seeOther(call, back)
            }
            action("/lineup/{id}/sources/{offeringId}/probe") { session, form ->
                val id = call.parameters["id"]!!
                val offeringId = call.parameters["offeringId"]!!
                val back =
                    "/admin/lineup/$id" + LineupFilter.from(form).query().let { if (it.isEmpty()) "" else "?$it" }
                val probes = runtime.probes
                session.flash.set(
                    when {
                        probes == null -> Flash("Probes are off on this server.", Tone.WARN, offeringId)

                        probes.probeNow(
                            offeringId,
                        ) -> Flash("Queued a probe; the health updates when it is done.", Tone.OK, offeringId)

                        else -> Flash(
                            "A probe of that source is already running, or it is gone.",
                            Tone.WARN,
                            offeringId,
                        )
                    },
                )
                seeOther(call, back)
            }
            action("/lineup/bulk") { session, form -> bulkAction(call, session, form) }
            get("/merges") { call.html(mergesPage(chrome(), mergesView())) }
            action("/merges/never") { session, form ->
                policyAction(call, session, form, "Kept apart") { policy, from, to, revision ->
                    policy.neverMerge(from, to, revision)
                }
            }
            action("/merges/accept") { session, form ->
                policyAction(call, session, form, "Merged") { policy, from, to, revision ->
                    policy.addAlias(from, to, revision)
                }
            }
            action("/merges/dismiss") { session, form ->
                policyAction(call, session, form, "Kept apart") { policy, from, to, revision ->
                    policy.neverMerge(from, to, revision)
                }
            }
            get("/required") { call.html(requiredPage(chrome(), requiredView())) }
            get("/providers") {
                val chrome = chrome()
                val view = providersView()
                call.html(providersPage(chrome, view) { providerEntries(chrome, view, null, null) })
            }
            get("/ads") {
                val names = runtime.lineup.entries().associate { it.id to it.name }
                val listening = runtime.listener?.listening().orEmpty()
                val rows = runtime.store.adChannels()
                    .sortedWith(
                        compareByDescending<AdChannelRecord> {
                            it.watchedMs
                        }.thenByDescending { it.listenedMs },
                    )
                    .map { AdChannelRow(it, names[it.channelId], it.channelId in listening) }
                call.html(
                    adsPage(
                        chrome(),
                        AdsView(
                            runtime.sessions.learnedBreaks,
                            runtime.settings.current.adListeners,
                            runtime.sessions.library?.stats(),
                            listening.size,
                            rows,
                        ),
                    ),
                )
            }
            action("/sources/services") { session, form ->
                val starter = form.wantsStarter() && runtime.setup?.canWriteStarterPolicy == true
                setupWrite(call, session, form, "the services", starter) { document ->
                    applyServicesForm(document, form).map { next ->
                        val lines = servicesChanges(document, next) + listOfNotNull(
                            (
                                "Builds the starter lineup from them: one station per channel, grouped and numbered " +
                                    "in blocks"
                                ).takeIf {
                                starter && next.providers.isNotEmpty()
                            },
                        )
                        next to
                            SetupConfirm(
                                "Save the services and restart?",
                                "/admin/sources/services",
                                lines,
                                form.setupFields(),
                            )
                    }
                }
            }
            action("/sources/playlist") { session, form ->
                setupWrite(call, session, form, "the playlist") { document ->
                    applyPlaylistForm(document, form).map { next ->
                        next to
                            SetupConfirm(
                                "Add the playlist ${form["id"]?.trim()} and restart?",
                                "/admin/sources/playlist",
                                listOf("Adds an M3U playlist from ${hostOnly(form["url"])}"),
                                form.setupFields(),
                            )
                    }
                }
            }
            action("/sources/remove") { session, form ->
                val id = form["id"]?.trim().orEmpty()
                setupWrite(call, session, form, "the source file") { document ->
                    if (document.providers.none { it.id == id }) {
                        Result.failure(IllegalArgumentException("No provider named $id"))
                    } else {
                        Result.success(
                            document.copy(providers = document.providers.filter { it.id != id }) to
                                SetupConfirm(
                                    "Remove $id and restart?",
                                    "/admin/sources/remove",
                                    listOf("Its channels leave the lineup's stations as sources"),
                                    form.setupFields(),
                                ),
                        )
                    }
                }
            }
            action("/policy/starter") { session, form ->
                val setup = runtime.setup ?: run {
                    seeOther(call, "/admin")
                    return@action
                }
                if (form["confirm"] != "yes") {
                    call.html(overviewPage(chrome(call, session), OverviewView(overview(), confirmStarter = true)))
                    return@action
                }
                try {
                    val result = setup.writeStarterPolicy()
                    call.html(restartingPage(theme(call), "Wrote the lineup policy to ${result.path}."))
                } catch (e: ApiFailure) {
                    call.html(
                        overviewPage(chrome(call, session, Flash("Not built: ${e.message}", Tone.BAD)), overview()),
                        HttpStatusCode.Conflict,
                    )
                }
            }
            action("/providers/edit") { session, form ->
                policyEdit(call, session, form, "/admin/providers", {
                    providersEdit(form, it)
                }) { chrome, errors, confirm ->
                    val view = providersView()
                    providersPage(chrome, view) { providerEntries(chrome, view, errors, confirm) }
                }
            }
            get("/groups") { call.html(groupsPage(chrome(), groupsView())) }
            action("/groups/edit") { session, form ->
                policyEdit(call, session, form, "/admin/groups", {
                    groupsEdit(form)
                }) { chrome, errors, confirm -> groupsPage(chrome, groupsView(), errors, confirm) }
            }
            get("/plex") { call.html(plexPage(chrome(), plexView(call, reveal = false))) }
            get("/viewers") { call.html(viewersPage(chrome(), viewersView(call))) }
            get("/recordings") {
                val dvr = runtime.dvr ?: run {
                    seeOther(call, "/admin")
                    return@get
                }
                call.html(recordingsPage(chrome(), recordingsView(dvr)))
            }
            action("/recordings/rules") { session, form ->
                val dvr = runtime.dvr ?: run {
                    seeOther(call, "/admin")
                    return@action
                }
                try {
                    val rule = dvr.rules.create(
                        CreateRecordingRule(
                            form["title"].orEmpty(),
                            form["channelId"]?.takeIf { it.isNotBlank() },
                            form["newOnly"] == "true",
                            formInt(form, "keep", "Keep"),
                            formInt(form, "startPadding", "Start early", 1) * 60,
                            formInt(form, "endPadding", "Run late", 2) * 60,
                            formInt(form, "priority", "Priority", 0),
                        ),
                    )
                    session.flash.set(
                        Flash("Added the rule for ${rule.title}; the schedule updates in a moment.", target = rule.id),
                    )
                } catch (e: ApiFailure) {
                    session.flash.set(Flash("Not added: ${e.message}", Tone.BAD))
                }
                seeOther(call, "/admin/recordings#rules")
            }
            action("/recordings/rules/{id}") { session, form ->
                val dvr = runtime.dvr ?: run {
                    seeOther(call, "/admin")
                    return@action
                }
                val id = call.parameters["id"]!!
                try {
                    val channel = form["channelId"]?.takeIf { it.isNotBlank() }
                    val rule = dvr.rules.update(
                        id,
                        UpdateRecordingRule(
                            channelId = channel,
                            anyChannel = channel == null,
                            newOnly = form["newOnly"] == "true",
                            keep = formInt(form, "keep", "Keep"),
                            startPaddingSeconds = formInt(form, "startPadding", "Start early") * 60,
                            endPaddingSeconds =
                            formInt(form, "endPadding", "Run late") * 60,
                            priority = formInt(form, "priority", "Priority"),
                        ),
                    )
                    session.flash.set(
                        Flash("Saved the rule for ${rule.title}; the schedule updates in a moment.", target = rule.id),
                    )
                } catch (e: ApiFailure) {
                    session.flash.set(Flash("Not saved: ${e.message}", Tone.BAD, target = id))
                }
                seeOther(call, "/admin/recordings#rules")
            }
            action("/recordings/manual") { session, form ->
                val dvr = runtime.dvr ?: run {
                    seeOther(call, "/admin")
                    return@action
                }
                try {
                    val start = try {
                        java.time.LocalDateTime.parse(
                            form["start"].orEmpty(),
                        ).atZone(java.time.ZoneId.systemDefault()).toInstant()
                    } catch (_: java.time.format.DateTimeParseException) {
                        throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Pick a start date and time")
                    }
                    val minutes = formInt(form, "minutes", "Minutes")
                    if (minutes !in MANUAL_MINUTES) {
                        throw ApiFailure(
                            HttpStatusCode.BadRequest,
                            "INVALID_REQUEST",
                            "Minutes must be 1 to ${MANUAL_MINUTES.last}",
                        )
                    }
                    val recording = dvr.schedule(
                        ScheduleRecording(
                            form["channelId"].orEmpty(),
                            start.toString(),
                            start.plusSeconds(minutes * 60L).toString(),
                            form["title"]?.takeIf {
                                it.isNotBlank()
                            },
                        ),
                    )
                    session.flash.set(Flash("Scheduled ${recording.title}.", target = recording.id))
                } catch (e: ApiFailure) {
                    session.flash.set(Flash("Not scheduled: ${e.message}", Tone.BAD))
                }
                seeOther(call, "/admin/recordings#upcoming")
            }
            action("/recordings/rules/{id}/delete") { session, _ ->
                val dvr = runtime.dvr ?: run {
                    seeOther(call, "/admin")
                    return@action
                }
                try {
                    dvr.rules.delete(call.parameters["id"]!!)
                    session.flash.set(Flash("Deleted the rule and its scheduled recordings; what it recorded stays."))
                } catch (_: ApiFailure) {
                    session.flash.set(Flash("That rule is already gone.", Tone.WARN))
                }
                seeOther(call, "/admin/recordings#rules")
            }
            action("/recordings/{id}/protect") { session, form ->
                val dvr = runtime.dvr ?: run {
                    seeOther(call, "/admin")
                    return@action
                }
                val id = call.parameters["id"]!!
                try {
                    val recording = dvr.update(id, UpdateRecording(protected = form["protected"] == "true"))
                    session.flash.set(
                        Flash(
                            if (recording.protected) {
                                "Protected ${recording.title}; cleanup keeps it."
                            } else {
                                "Cleanup may delete ${recording.title} again."
                            },
                            target = id,
                        ),
                    )
                } catch (_: ApiFailure) {
                    session.flash.set(Flash("That recording is gone.", Tone.WARN))
                }
                seeOther(call, "/admin/recordings#recorded")
            }
            action("/recordings/{id}/delete") { session, _ ->
                val dvr = runtime.dvr ?: run {
                    seeOther(call, "/admin")
                    return@action
                }
                val id = call.parameters["id"]!!
                try {
                    val recording = dvr.recording(id)
                    dvr.delete(id)
                    session.flash.set(
                        Flash(
                            when (recording.state) {
                                Wire.RecordingState.SCHEDULED -> "Canceled ${recording.title}."
                                Wire.RecordingState.RECORDING -> "Stopped ${recording.title}; what it recorded is kept."
                                else -> "Deleted ${recording.title}."
                            },
                        ),
                    )
                } catch (_: ApiFailure) {
                    session.flash.set(Flash("That recording is already gone.", Tone.WARN))
                }
                seeOther(call, "/admin/recordings")
            }
            action("/viewers/pair") { session, _ ->
                runtime.pairing.issue()
                call.html(viewersPage(chrome(call, session), viewersView(call)))
            }
            action("/viewers/cancel") { session, _ ->
                runtime.pairing.cancel()
                session.flash.set(Flash("Canceled the pairing code."))
                seeOther(call, "/admin/viewers")
            }
            action("/viewers/{clientId}/forget") { session, _ ->
                val clientId = call.parameters["clientId"]!!
                val viewer = runtime.store.viewers().firstOrNull { it.clientId == clientId }
                if (viewer == null || !runtime.store.forgetViewer(clientId)) {
                    session.flash.set(Flash("That device is already gone.", Tone.WARN))
                } else {
                    session.flash.set(Flash("Forgot ${viewer.name.ifEmpty { "the device" }}."))
                }
                seeOther(call, "/admin/viewers#devices")
            }
            get("/library") { call.html(libraryPage(chrome(), libraryView())) }
            action("/library") { session, form ->
                val service = libraries()
                val proposed = listOf("name", "kind", "path").associateWith { form[it].orEmpty() }
                val kind = LibraryKind.of(proposed.getValue("kind"))
                try {
                    if (kind ==
                        null
                    ) {
                        throw ApiFailure(
                            HttpStatusCode.BadRequest,
                            "INVALID_REQUEST",
                            "Choose what the library holds",
                        )
                    }
                    val library = service.create(proposed.getValue("name"), kind, proposed.getValue("path"))
                    session.flash.set(Flash("Added ${library.name}; its first scan is running.", Tone.OK, library.id))
                    seeOther(call, "/admin/library#library-${library.id}")
                } catch (e: ApiFailure) {
                    call.html(
                        libraryPage(chrome(call, session), libraryView(listOf(e.message), proposed)),
                        HttpStatusCode.BadRequest,
                    )
                }
            }
            action("/library/tmdb") { session, form ->
                val setup = runtime.setup ?: run {
                    seeOther(call, "/admin/library#tmdb")
                    return@action
                }
                try {
                    setup.saveTmdbToken(form["token"].orEmpty())
                    call.html(
                        restartingPage(
                            theme(call),
                            "Saved the TMDB token to ${setup.secretsPath}. The libraries scan again once the server " +
                                "is back and fill in details and artwork.",
                        ),
                    )
                } catch (e: ApiFailure) {
                    call.html(
                        libraryPage(chrome(call, session), libraryView(tmdbError = e.message)),
                        e.status,
                    )
                }
            }
            action("/library/{id}/scan") { session, _ ->
                val id = call.parameters["id"]!!
                val library = runtime.store.library(id)
                if (library == null) {
                    session.flash.set(Flash("That library is gone.", Tone.WARN))
                } else {
                    libraries().requestScan(id)
                    session.flash.set(Flash("Scanning ${library.name}.", Tone.OK, id))
                }
                seeOther(call, "/admin/library#libraries")
            }
            action("/library/{id}/rename") { session, form ->
                val id = call.parameters["id"]!!
                try {
                    val library = libraries().rename(id, form["name"].orEmpty())
                    session.flash.set(Flash("Renamed to ${library.name}.", Tone.OK, id))
                } catch (e: ApiFailure) {
                    session.flash.set(Flash(e.message, Tone.BAD, id))
                }
                seeOther(call, "/admin/library#libraries")
            }
            action("/library/{id}/remove") { session, form ->
                val id = call.parameters["id"]!!
                val library = runtime.store.library(id)
                when {
                    library == null -> {
                        session.flash.set(Flash("That library is already gone.", Tone.WARN))
                        seeOther(call, "/admin/library")
                    }

                    form["confirm"] != "yes" -> call.html(
                        libraryPage(chrome(call, session), libraryView(removing = library)),
                    )

                    else -> {
                        libraries().delete(id)
                        session.flash.set(Flash("Removed ${library.name}. Its files are untouched."))
                        seeOther(call, "/admin/library")
                    }
                }
            }
            action("/plex/reveal") { session, _ ->
                call.html(plexPage(chrome(call, session), plexView(call, reveal = true)))
            }
            action("/plex/allow") { session, form ->
                val address = form["address"].orEmpty().trim()
                val flash = when {
                    address.isEmpty() -> Flash("Enter an IP address or CIDR block.", Tone.BAD)

                    else -> try {
                        runtime.hdhrAccess.allow(address)
                        Flash("Allowed $address. It can use the tuners now.")
                    } catch (e: IllegalArgumentException) {
                        Flash("Not allowed: ${e.message}", Tone.BAD)
                    }
                }
                session.flash.set(flash)
                seeOther(call, "/admin/plex#access")
            }
            action("/plex/disallow") { session, form ->
                val address = form["address"].orEmpty().trim()
                runtime.hdhrAccess.remove(address)
                session.flash.set(Flash("Removed $address. It can no longer use the tuners."))
                seeOther(call, "/admin/plex#access")
            }
            get("/settings") {
                val limit =
                    call.request.queryParameters["changes"]?.toIntOrNull()?.coerceIn(1, CHANGES_LIMIT) ?: CHANGES_SHOWN
                val advanced =
                    call.request.queryParameters["advanced"] == "1" || call.request.queryParameters.contains("changes")
                call.html(settingsPage(chrome(), settingsView(limit, openAdvanced = advanced)))
            }
            get("/system/jobs") { call.html(jobsPage(chrome(), JobsView(runtime.jobs.snapshot()))) }
            action("/system/jobs/{id}/run") { session, _ ->
                val id = call.parameters["id"]!!
                val job = runtime.jobs.snapshot().firstOrNull { it.id == id }
                if (job == null || !runtime.jobs.runNow(id)) {
                    session.flash.set(Flash("No such job to run.", Tone.BAD, id))
                } else {
                    session.flash.set(Flash("Started ${job.label}.", Tone.OK, id))
                }
                seeOther(call, "/admin/system/jobs")
            }
            get("/system/events") { call.html(eventsPage(chrome(), eventsView(call.request.queryParameters))) }
            get("/system/about") { call.html(aboutPage(chrome(), aboutView())) }
            action("/system/update/check") { session, _ ->
                val updates =
                    runtime.updates
                        ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")
                val state = updates.check()
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
            action("/system/update/request") { session, form ->
                val updates =
                    runtime.updates
                        ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")
                val state = updates.state()
                when {
                    state.updater == null -> session.flash.set(
                        Flash("The host's updater has not reported, so there is nothing to ask.", Tone.WARN),
                    )

                    form["confirm"] != "yes" -> {
                        call.html(aboutPage(chrome(call, session), AboutView(aboutView(), confirm = true)))
                        return@action
                    }

                    updates.requestUpdate() -> session.flash.set(
                        Flash("Asked the updater to run. The server restarts if a newer image is published."),
                    )

                    else -> session.flash.set(
                        Flash("Could not write the request; the data directory is not writable.", Tone.BAD),
                    )
                }
                seeOther(call, "/admin/system/about#update")
            }
            get("/system/snapshot.json") {
                call.session()
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"sofa-king-snapshot.json\"")
                call.respondText(snapshot().toJson(), ContentType.Application.Json)
            }
            action("/settings/reveal-api-token") { session, _ ->
                call.html(
                    settingsPage(
                        chrome(call, session),
                        settingsView(CHANGES_SHOWN, revealApiToken = true, openAdvanced = true),
                    ),
                )
            }
            action("/settings/runtime/check") { _, form ->
                // The form asks, on every change, whether its values differ from what the server uses; the
                // answer is the Save control alone.
                val proposed = form.names().associateWith { form[it].orEmpty() }
                call.html(runtimeSaveFragment(runtimeChanged(runtime.settings.current, proposed)))
            }
            action("/settings/runtime") { session, form ->
                val proposed = if (form["op"] == "reset") {
                    emptyMap()
                } else {
                    RuntimeValues.NAMES.associateWith { form[it]?.trim().orEmpty() }
                }
                try {
                    val before = runtime.settings.current
                    val after = runtime.settings.update(proposed)
                    session.flash.set(
                        Flash(
                            if (form["op"] == "reset") {
                                "Runtime settings follow the environment again."
                            } else if (before ==
                                after
                            ) {
                                "Nothing changed."
                            } else {
                                "Saved the runtime settings; they apply now."
                            },
                        ),
                    )
                    seeOther(call, "/admin/settings#runtime")
                } catch (e: ApiFailure) {
                    val view = settingsView(CHANGES_SHOWN)
                    call.html(
                        settingsPage(
                            chrome(call, session, Flash("Not saved: ${e.message}", Tone.BAD)),
                            SettingsView(
                                view,
                                runtime = view.runtime!!.let {
                                    RuntimeSettingsView(
                                        it.current,
                                        it.environment,
                                        it.saved,
                                        it.plex,
                                        listOf(e.message),
                                        proposed,
                                    )
                                },
                            ),
                        ),
                        HttpStatusCode.BadRequest,
                    )
                }
            }
            get("/api") { call.html(apiPage(chrome(), apiReference)) }
            get("/api/openapi.json") {
                call.session()
                call.respondText(apiReference.json, ContentType.Application.Json)
            }
            action("/policy/apply") { session, _ ->
                val policy = runtime.policy
                if (policy == null) {
                    call.html(
                        settingsPage(
                            chrome(call, session, Flash("No lineup policy is configured.", Tone.BAD)),
                            settingsView(CHANGES_SHOWN, openAdvanced = true),
                        ),
                        HttpStatusCode.Conflict,
                    )
                    return@action
                }
                val result = policy.apply("api")
                val summary =
                    "${thousands(result.enabledStations)} on of ${thousands(result.stations)}, " +
                        if (result.changed) plural(result.changes, "field") + " changed." else "no change."
                session.flash.set(
                    Flash(
                        "Applied the policy: $summary",
                        if (result.findings.any {
                                it.startsWith("feeds:")
                            }
                        ) {
                            Tone.WARN
                        } else {
                            Tone.OK
                        },
                    ),
                )
                seeOther(call, "/admin/settings?advanced=1#policy")
            }
        }
    }

    /**
     * The posted form; a body that is not a form reads as an empty one, so it fails the
     * token checks instead of erroring.
     */
    private suspend fun ApplicationCall.formOrEmpty(): Parameters = try {
        receiveParameters()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Parameters.Empty
    }

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
    private suspend fun chrome(call: ApplicationCall, session: AdminSession, flash: Flash? = null): Chrome = Chrome(
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
    )

    /** The furniture for the page answering this request. */
    private suspend fun RoutingContext.chrome(flash: Flash? = null): Chrome = chrome(call, call.session(), flash)

    private fun libraries(): LibraryService =
        runtime.libraries ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")

    private suspend fun libraryView(
        errors: List<String> = emptyList(),
        proposed: Map<String, String>? = null,
        removing: LibraryRecord? = null,
        tmdbError: String? = null,
    ): LibraryView {
        val service = libraries()
        return LibraryView(
            service.libraries(), service.counts(),
            service.roots.map {
                it.root.toString()
            },
            service.folderChoices().map { it.toString() },
            runtime.jobs.snapshot().any { it.id == LIBRARY_SCAN_JOB && it.running }, errors, proposed, removing,
            service.metadata?.enabled == true,
            tmdbSettable = runtime.setup?.tmdbFromEnvironment == false, tmdbError = tmdbError,
            suggestions = service.suggestions(),
        )
    }

    /** The recordings and rules, with the channels a rule may name and what cleanup keeps. */
    private suspend fun recordingsView(dvr: Dvr): RecordingsView {
        val channels = runtime.lineup.entries().filter { it.enabled && it.guide != null }.sortedBy {
            it.number.toDoubleOrNull()
                ?: Double.MAX_VALUE
        }
            .associate { it.id to "${it.number} ${it.name}" }
        val all = runtime.lineup.entries().filter { it.enabled }.sortedBy {
            it.number.toDoubleOrNull()
                ?: Double.MAX_VALUE
        }
            .associate { it.id to "${it.number} ${it.name}" }
        val values = runtime.settings.current
        return RecordingsView(
            dvr.recordings(),
            dvr.rules.list(),
            channels,
            dvr.root.toString(),
            Retention(values.recordingsFreeGib, values.recordingsKeepDays),
            all,
        )
    }

    /** The viewers the server knows and the live pairing code, with the address this request reached the server at. */
    private suspend fun viewersView(call: ApplicationCall): ViewersView {
        val base = publicBase(call)
        return ViewersView(runtime.store.viewers(), runtime.pairing.current()?.let { PairingView(it, base) }, base)
    }

    /** The pinned color scheme from the cookie, or null to follow the device. */
    private fun theme(call: ApplicationCall): String? = call.request.cookies[THEME_COOKIE]?.takeIf { it in THEMES }

    private fun eventsView(parameters: Parameters): EventsView {
        val level = parameters["level"]?.takeIf { it == "warn" || it == "error" }.orEmpty()
        val q = parameters["q"]?.trim().orEmpty().take(200)
        val all = events.snapshot()
        val shown = all.filter { event ->
            when (level) {
                "warn" -> event.level == "WARN" || event.level == "ERROR"
                "error" -> event.level == "ERROR"
                else -> true
            } &&
                (
                    q.isEmpty() || event.message.contains(q, ignoreCase = true) ||
                        event.logger.contains(q, ignoreCase = true)
                    )
        }
        return EventsView(shown, all.size, events.capacity, level, q)
    }

    private suspend fun aboutServer(): AboutServer {
        val now = clock.instant()
        val runtimeInfo = Runtime.getRuntime()
        return AboutServer(
            config?.revision ?: "unknown", config?.version ?: "unknown", runtime.startedAt.toString(),
            java.time.Duration.between(
                runtime.startedAt,
                now,
            ).seconds.coerceAtLeast(0),
            runtime.store.schemaVersion(),
            "${Runtime.version()} (${System.getProperty("java.vendor")})",
            "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            ffmpeg.get() ?: ffmpegVersion(config?.ffmpeg ?: "ffmpeg").also(ffmpeg::set),
            config?.dataDirectory?.toAbsolutePath()?.toString(), runtime.settings.current.logLevel,
            config?.logging?.format ?: "text",
            (runtimeInfo.totalMemory() - runtimeInfo.freeMemory()) / MIB, runtimeInfo.maxMemory() / MIB,
        )
    }

    private suspend fun snapshot(): SupportSnapshot = SupportSnapshot(
        clock.instant().toString(),
        aboutServer(),
        serverStatus(runtime, clock, config?.revision ?: "unknown"),
        runtime.jobs.snapshot().map { it.report() },
        overview().attention.map {
            "${it.tone.css}: ${it.title}" +
                (it.detail?.let { d -> " ($d)" } ?: "")
        },
    )

    private suspend fun aboutView(): AboutView {
        val snapshot = snapshot()
        return AboutView(snapshot.server, snapshot.status, snapshot.toJson(), runtime.updates?.state())
    }

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
        return mapOf(
            "jobs" to jobs, "merges" to merges, "required" to required, "plex" to plexCount,
            "channels" to merges + required, "sources" to sources, "players" to plexCount, "system" to jobs,
            "recordings" to
                (
                    runtime.dvr?.let { dvr ->
                        dvr.recordings().recordings.count { it.conflict } + (if (dvr.files.writable()) 0 else 1)
                    }
                        ?: 0
                    ),
        )
    }

    /**
     * Changes one lineup entry the way `PATCH /api/v1/admin/lineup/{id}` does: through the policy, or through the
     * lineup without one. Portal edits touch one station's fields, so they apply to the file as it is now rather than
     * to the revision the page showed; background runs write the file often.
     */
    private suspend fun changeEntry(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        id: String,
        verb: String,
        request: (LineupEntryRecord) -> UpdateLineupEntry,
    ) {
        val filter = LineupFilter.from(form)
        var name: String? = null
        val failure = try {
            val entry =
                runtime.lineup.entry(id)
                    ?: throw ApiFailure(
                        HttpStatusCode.NotFound,
                        "LINEUP_ENTRY_NOT_FOUND",
                        "That station is no longer in the lineup",
                    )
            name = entry.name
            val update = request(entry)
            val policy = runtime.policy
            if (update != UpdateLineupEntry()) {
                if (policy == null) {
                    runtime.lineup.update(id, update)
                } else {
                    policy.updateStation(id, update, null)
                        ?: throw ApiFailure(
                            HttpStatusCode.NotFound,
                            "LINEUP_ENTRY_NOT_FOUND",
                            "The station left the lineup",
                        )
                }
            }
            null
        } catch (e: ApiFailure) {
            e
        }
        val query = filter.query().let { if (it.isEmpty()) "" else "?$it" }
        val fromStation = form["from"] == "station" || form["name"] != null
        if (failure == null) {
            val now = runtime.lineup.entry(id)?.name ?: name
            session.flash.set(Flash("$verb $now.", Tone.OK, id))
            seeOther(call, if (form["from"] == "station") "/admin/lineup/$id$query" else "/admin/lineup$query")
            return
        }
        val status = failure.status
        val station = if (fromStation) stationView(id, filter, failure.message) else null
        if (station != null) {
            call.html(stationPage(chrome(call, session), station), status)
        } else {
            call.html(
                lineupPage(
                    chrome(call, session, Flash("Not saved: ${failure.message}", Tone.BAD, id)),
                    lineupView(filter),
                ),
                status,
            )
        }
    }

    private suspend fun policyAction(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        verb: String,
        write: suspend (dev.jamesrich.sofaking.backend.catalog.PolicyService, String, String, String?) -> Unit,
    ) {
        val policy =
            runtime.policy
                ?: throw ApiFailure(HttpStatusCode.NotFound, "POLICY_NOT_CONFIGURED", "No lineup policy is configured")
        val from = form["from"]?.trim().orEmpty()
        val to = form["to"]?.trim().orEmpty()
        val failure = try {
            if (from.isEmpty() ||
                to.isEmpty()
            ) {
                throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Both keys are required")
            }
            write(policy, from, to, null)
            null
        } catch (e: ApiFailure) {
            e
        }
        if (failure == null) {
            session.flash.set(Flash("$verb: $from and $to. The policy was applied."))
            seeOther(call, "/admin/merges")
        } else {
            call.html(
                mergesPage(
                    chrome(call, session, Flash("Not saved: ${failure.message}", Tone.BAD)),
                    mergesView(),
                    failure.message,
                ),
                failure.status,
            )
        }
    }

    /**
     * A taxonomy or provider edit, made the way `PUT /api/v1/admin/policy` makes one: the whole file, written against
     * the revision the page showed, validated, then applied. An edit that changes any station, or that meets a policy
     * that moved on since the page loaded, is shown with its effect and waits for `confirm=yes`.
     */
    private suspend fun policyEdit(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        page: String,
        parse: (LineupPolicy) -> PolicyEdit,
        action: String = "$page/edit",
        back: String? = null,
        render: suspend (Chrome, EditErrors?, Confirm?) -> String,
    ) {
        val section = sectionOf(form["op"])
        val policy = runtime.policy
        if (policy == null) {
            call.html(
                render(chrome(call, session, Flash("No lineup policy is configured.", Tone.BAD)), null, null),
                HttpStatusCode.Conflict,
            )
            return
        }
        suspend fun refuse(messages: List<String>, status: HttpStatusCode = HttpStatusCode.BadRequest) = call.html(
            render(
                chrome(call, session, Flash("Not saved: ${messages.first()}", Tone.BAD)),
                EditErrors(section, messages),
                null,
            ),
            status,
        )
        val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(policy.file.path) }
        val current = policyRevision(bytes)
        val (edit, next) = try {
            val edit = parse(parsePolicy(bytes))
            val tree =
                Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                    ?: throw ApiFailure(
                        HttpStatusCode.Conflict,
                        "POLICY_CHANGED",
                        "The policy file is not a JSON object",
                    )
            edit to policyBytes(edit.change(tree))
        } catch (e: ApiFailure) {
            refuse(listOf(e.message))
            return
        }
        val candidate = try {
            parsePolicy(next)
        } catch (_: IllegalArgumentException) {
            // A SerializationException is one.
            refuse(listOf("The edit does not fit the policy schema"))
            return
        }
        val errors = validatePolicy(candidate, runtime.feeds.configs.mapTo(HashSet()) { it.id })
        if (errors.isNotEmpty()) {
            refuse(errors)
            return
        }
        val stale = form["revision"] != current
        if (stale || form["confirm"] != "yes") {
            val impact =
                impactOf(
                    policy.run().stations,
                    policy.run(LoadedPolicy(policy.file.path, candidate, "candidate")).stations,
                )
            if (stale || !impact.isEmpty) {
                val fields = form.entries().filter {
                    it.key !in setOf("csrf", "revision", "confirm")
                }.flatMap { (name, values) ->
                    values.map {
                        name to
                            it
                    }
                }
                val confirm = Confirm(
                    action,
                    edit.describe,
                    fields,
                    current,
                    impact,
                    stale,
                    back ?: "$page#${edit.section}",
                )
                call.html(
                    render(chrome(call, session), null, confirm),
                    if (stale) HttpStatusCode.Conflict else HttpStatusCode.OK,
                )
                return
            }
        }
        val result = try {
            policy.replace(current, next).apply
        } catch (e: ApiFailure) {
            if (e.status == HttpStatusCode.Conflict) {
                refuse(
                    listOf(
                        "The policy changed while saving; nothing was saved. Reload the page and repeat the edit.",
                    ),
                    HttpStatusCode.Conflict,
                )
            } else {
                refuse(e.message.split("; "))
            }
            return
        }
        session.flash.set(
            Flash(
                "Saved: ${edit.describe}. ${thousands(result.enabledStations)} channels on of " +
                    "${thousands(result.stations)}.",
            ),
        )
        seeOther(call, back ?: "$page#${edit.section}")
    }

    private suspend fun groupsView(): GroupsView? {
        val policy = runtime.policy ?: return null
        val document = policy.document()
        val p = document.policy
        val rows = rows()
        val byGroup = rows.groupBy { it.entry.group }
        val starts = p.numbering.groups.entries.sortedBy { it.value }
        val blocks = starts.mapIndexed { i, (group, start) ->
            val next = starts.getOrNull(i + 1)?.value ?: Int.MAX_VALUE
            val members = byGroup[group].orEmpty()
            BlockRow(
                group,
                start,
                minOf(start.toLong() + p.numbering.groupSize - 1, next.toLong() - 1).toInt(),
                members.count {
                    it.entry.enabled
                },
                members.size,
            )
        }
        val unblocked = byGroup.filterKeys { it != null && it !in p.numbering.groups }.map { (group, members) ->
            group!! to
                members.size
        }.sortedByDescending { it.second }
        return GroupsView(
            document.revision,
            p,
            blocks,
            groupChoices(rows),
            policy.lastApplied()?.result?.unmappedCategories.orEmpty(),
            unblocked,
        )
    }

    private suspend fun overview(): OverviewView {
        val now = clock.instant()
        val entries = runtime.lineup.entries()
        val policy = runtime.policy
        val applied = policy?.lastApplied()
        val coverage = runtime.guides.coverage
        val enabled = entries.filter { it.enabled }
        val byGuide = enabled.groupingBy { guideStatus(it, coverage, now) }.eachCount()
        val guide = GuideCoverage(
            byGuide["available"] ?: 0,
            byGuide["stale"] ?: 0,
            byGuide["empty"] ?: 0,
            byGuide["unassigned"] ?: 0,
        )
        val providers = runtime.feeds.statuses
        val guides = runtime.guides.statuses
        val plex = plexStatus?.status()
        val stations = applied?.stations
        val requiredOff = stations?.count { it.required && !it.enabled }
        val unverified = stations?.count { it.disabledReason == "unverified" }
        val all = buildList {
            val failed = providers.filter { it.errorCode != null }
            if (failed.isNotEmpty()) {
                add(
                    Attention(
                        failingTone(failed.map { it.lastSuccessAt }, now),
                        if (failed.size == 1) {
                            "${failed[0].id} ${failingFor(failed[0].lastSuccessAt, now) ?: "failed its last import"}"
                        } else {
                            "${failed.size} providers failed their last import"
                        },
                        failed.joinToString {
                            "${it.id} (${it.errorCode}${failingFor(it.lastSuccessAt, now)?.let { f -> ", $f" } ?: ""})"
                        },
                        "/admin/providers#imports",
                        "Sources",
                        "providers-failed",
                        "troubleshooting.md#channels-and-sources",
                    ),
                )
            }
            // Imports start at once, so a wait is worth a word only once the server has been up a while.
            val waiting = providers.filter { it.errorCode == null && it.lastSuccessAt == null }
            if (waiting.isNotEmpty() && Duration.between(runtime.startedAt, now) > Duration.ofMinutes(10)) {
                add(
                    Attention(
                        Tone.WARN,
                        "Waiting for the first import of ${waiting.joinToString { it.id }}",
                        null,
                        "/admin/providers#imports",
                        "Sources",
                        "providers-waiting",
                        "live-tv.md#providers",
                    ),
                )
            }
            val badGuides = guides.filter { it.errorCode != null }
            if (badGuides.isNotEmpty()) {
                add(
                    Attention(
                        failingTone(badGuides.map { it.lastSuccessAt }, now),
                        if (badGuides.size == 1) {
                            "Guide ${badGuides[0].id} " +
                                "${failingFor(badGuides[0].lastSuccessAt, now) ?: "failed its last import"}"
                        } else {
                            "${badGuides.size} guides failed their last import"
                        },
                        badGuides.joinToString {
                            "${it.id} (${it.errorCode}${failingFor(it.lastSuccessAt, now)?.let { f -> ", $f" } ?: ""})"
                        },
                        "/admin/providers#guides",
                        "Sources",
                        "guides-failed",
                        "troubleshooting.md#guide",
                    ),
                )
            }
            val refused = if (hdhr.enabled) runtime.hdhrAccess.refusals() else emptyList()
            if (refused.isNotEmpty()) {
                add(
                    Attention(
                        Tone.WARN,
                        "The tuners refused ${plural(refused.size, "address", "addresses")}",
                        refused.joinToString { it.address } + ". Allow Plex's address on the Plex page.",
                        "/admin/plex#access",
                        "Plex",
                        "hdhr-refused",
                        "live-tv.md#plex-live-tv",
                    ),
                )
            }
            val dvr = runtime.dvr
            if (dvr != null && !dvr.files.writable()) {
                add(
                    Attention(
                        Tone.BAD,
                        "The server cannot write the recordings folder",
                        "${dvr.root}: every recording fails until the user the container runs as owns it. On the " +
                            "host: sudo chown -R UID:GID ./recordings, with the UID and GID from .env (1000 by " +
                            "default).",
                        "/admin/recordings",
                        "Recordings",
                        "recordings-unwritable",
                        "troubleshooting.md#recordings",
                    ),
                )
            }
            if (plex?.lastError != null) {
                add(
                    Attention(
                        Tone.BAD,
                        "Plex sync failed",
                        plex.lastError,
                        "/admin/plex#sync",
                        "Plex",
                        "plex-failed",
                        "troubleshooting.md#plex",
                    ),
                )
            }
            if (!plex?.missingDevices.isNullOrEmpty()) {
                add(
                    Attention(
                        Tone.WARN,
                        "${plural(plex.missingDevices.size, "tuner")} not in Plex",
                        plex.missingDevices.joinToString(),
                        "/admin/plex#tuners",
                        "Plex",
                        "plex-missing",
                        "live-tv.md#keep-plex-in-step",
                    ),
                )
            }
            val needed = applied?.result?.devicesNeeded
            val configured = applied?.result?.devicesConfigured
            if (needed != null && configured != null && needed > configured) {
                add(
                    Attention(
                        Tone.BAD,
                        "The lineup needs $needed tuners; $configured are configured",
                        "Add ports to SOFA_HDHR_EXTRA_PORTS, then add the devices in Plex.",
                        "/admin/plex#tuners",
                        "Plex",
                        "tuners-short",
                        "lineup-policy.md#hdhomerun-devices",
                    ),
                )
            }
            if ((requiredOff ?: 0) > 0) {
                add(
                    Attention(
                        Tone.BAD,
                        "${plural(requiredOff!!, "required channel")} off",
                        null,
                        "/admin/required",
                        "Required",
                        "required-off",
                        "lineup-policy.md#required-stations",
                    ),
                )
            }
            val suggestions = applied?.result?.suggestedAliases?.size ?: 0
            if (suggestions > 0) {
                add(
                    Attention(
                        Tone.WARN,
                        "${plural(suggestions, "merge suggestion")} to settle",
                        "Pairs of stations that are probably one channel.",
                        "/admin/merges#suggested",
                        "Merges",
                        "merges",
                        "lineup-policy.md#learned-aliases",
                    ),
                )
            }
            // Run findings, unmapped categories (their stations fall to the default group) and stations
            // waiting for a probe are normal operation: the Merges and Groups tabs and the Sources tile carry
            // them, so the home page does not nag.
            val unmapped = applied?.result?.unmappedCategories?.size ?: 0
            if (unmapped > 0) {
                add(
                    Attention(
                        Tone.OFF,
                        "${plural(unmapped, "category", "categories")} map to no group",
                        "Their stations fall to the default group; map one to choose.",
                        "/admin/groups#unmapped",
                        "Groups",
                        "unmapped",
                        "lineup-policy.md#unmapped-categories",
                    ),
                )
            }
            val ended = guide.stale + guide.empty
            if (ended > 0) {
                add(
                    Attention(
                        Tone.WARN,
                        "${plural(ended, "on-air channel")} show no programs",
                        "Their guide ended or holds nothing ahead.",
                        "/admin/lineup?state=enabled&guide=missing",
                        "Channels",
                        "guide-ended",
                        "troubleshooting.md#guide",
                    ),
                )
            }
        }.sortedBy { listOf(Tone.BAD, Tone.WARN, Tone.OK, Tone.OFF).indexOf(it.tone) }
        // An ignored item stays out while its text is what it was when ignored; a change brings it back.
        val ignored = runtime.store.ignoredAttention()
        val (hidden, attention) = all.partition { ignored[it.key] == it.fingerprint }
        return OverviewView(
            attention = attention, ignored = hidden,
            stations = entries.size, enabled = enabled.size, guide = guide,
            required = stations?.count { it.required }, requiredOff = requiredOff,
            suggestions = applied?.result?.suggestedAliases?.size, findings = applied?.result?.findings?.size,
            unverified = unverified,
            providersOk = providers.count {
                it.errorCode == null && it.lastSuccessAt != null
            },
            providersTotal = providers.size,
            plex = plex?.let {
                PlexSummary(
                    it.lastSyncAt,
                    it.lastError,
                    it.devices.size,
                    it.devices.sumOf { d ->
                        d.mapped
                    },
                    it.missingDevices.size,
                    it.syncMinutes,
                )
            },
            sessions = sessionRows(now),
            playing = playingRows(),
            lastRun = applied?.result, policy = policy != null, setup = runtime.setup,
        )
    }

    /** The library files and recordings being played, named as their pages name them. */
    private suspend fun playingRows(): List<PlayingRow> {
        val active = runtime.vod?.snapshot().orEmpty()
        if (active.isEmpty()) return emptyList()
        val views = runtime.libraries?.catalog?.snapshot()?.views.orEmpty()
        return active.map { activity ->
            val title = activity.recordingId?.let { id ->
                runCatchingCancellable {
                    runtime.dvr?.recording(id)
                }.getOrNull()?.let { r -> listOfNotNull(r.title, r.episodeTitle).joinToString(" · ") }
                    ?: "Recording"
            } ?: views[activity.itemId]?.item?.let { item ->
                val episode = item.season?.let { s -> item.episode?.let { e -> "S${s}E$e" } }
                listOfNotNull(item.showTitle, episode, item.title).joinToString(" · ")
            } ?: "Removed item"
            PlayingRow(activity, title)
        }
    }

    /** The live sessions with their channel's name and the program it airs now, read once per guide. */
    private suspend fun sessionRows(now: Instant): List<SessionRow> {
        val active = runtime.sessions.snapshot()
        if (active.isEmpty()) return emptyList()
        val entries = active.associate { it.channelId to runtime.lineup.entry(it.channelId) }
        val onNow = HashMap<String, String>()
        for ((guideId, group) in entries.values.filterNotNull().mapNotNull { entry ->
            entry.guide?.let {
                it.guideId to
                    it.channelId
            }
        }.groupBy({ it.first }, { it.second })) {
            for ((channelId, programs) in runtime.guides.programs(guideId, group, now, now.plusSeconds(1))) {
                programs.firstOrNull()?.let { onNow["$guideId/$channelId"] = it.title }
            }
        }
        return active.map { session ->
            val entry = entries[session.channelId]
            SessionRow(session, entry?.name, entry?.guide?.let { onNow["${it.guideId}/${it.channelId}"] })
        }
    }

    /**
     * The bulk edit of the selected channels: with a policy, one edit of their overrides, previewed like any policy
     * edit; without one, the lineup entries themselves, after a confirmation that lists what changes.
     */
    private suspend fun bulkAction(call: ApplicationCall, session: AdminSession, form: Parameters) {
        val filter = LineupFilter.from(form)
        val ids = form.getAll("id").orEmpty().distinct()
        val op = form["op"]
        val query = filter.query().let { if (it.isEmpty()) "" else "?$it" }
        val page = "/admin/lineup$query"
        suspend fun refuse(message: String) = call.html(
            lineupPage(
                chrome(call, session, Flash("Not saved: $message", Tone.BAD)),
                LineupView(lineupView(filter), selected = ids.toSet()),
            ),
            HttpStatusCode.BadRequest,
        )
        if (ids.isEmpty()) {
            refuse("Select at least one channel first.")
            return
        }
        val policy = runtime.policy
        if (policy != null) {
            val stations = policy.lastApplied()?.stations.orEmpty().associateBy { it.id }
            val keys = ids.mapNotNull { stations[it]?.key }
            if (keys.size < ids.size) {
                refuse(
                    "${plural(ids.size - keys.size, "selected channel")} " +
                        "${if (ids.size - keys.size == 1) "is" else "are"} not in the last policy run; reload the " +
                        "page.",
                )
                return
            }
            // A refused edit is explained by the notice; the page keeps the selection so the operator can correct it.
            policyEdit(call, session, form, page, {
                bulkEdit(op, form["target"], keys)
            }, action = "/admin/lineup/bulk", back = page) { chrome, _, confirm ->
                lineupPage(chrome, LineupView(lineupView(filter), selected = ids.toSet(), confirm = confirm))
            }
            return
        }
        val entries = ids.mapNotNull { runtime.lineup.entry(it) }
        val update = when (op) {
            "hide" -> UpdateLineupEntry(enabled = false)

            "show" -> UpdateLineupEntry(enabled = true)

            "group" -> form["target"]?.trim().orEmpty().let {
                if (it.isEmpty()) UpdateLineupEntry(clearGroup = true) else UpdateLineupEntry(group = it)
            }

            else -> {
                refuse("Unknown edit ${op ?: ""}".trim())
                return
            }
        }
        if (form["confirm"] != "yes") {
            val describe = when (op) {
                "hide" -> "hide ${plural(entries.size, "channel")}"
                "show" -> "show ${plural(entries.size, "channel")}"
                else -> "move ${plural(entries.size, "channel")} to ${update.group ?: "no group"}"
            }
            val impact = Impact(
                entries.count { it.enabled },
                entries.count {
                    if (update.enabled != null) {
                        update.enabled
                    } else {
                        it.enabled
                    }
                },
                if (op == "group") {
                    entries.map { Moved(it.name, it.group, update.group) }
                } else {
                    emptyList()
                },
                emptyList(), emptyList(),
                if (op == "hide") entries.filter { it.enabled }.map { Moved(it.name, "on", "off") } else emptyList(),
                if (op == "show") entries.filter { !it.enabled }.map { Moved(it.name, "off", "on") } else emptyList(),
                emptyList(), emptyList(),
            )
            val fields = form.entries().filter {
                it.key !in setOf("csrf", "revision", "confirm")
            }.flatMap { (name, values) ->
                values.map {
                    name to
                        it
                }
            }
            call.html(
                lineupPage(
                    chrome(call, session),
                    LineupView(
                        lineupView(filter),
                        selected = ids.toSet(),
                        confirm = Confirm("/admin/lineup/bulk", describe, fields, "", impact, false, page),
                    ),
                ),
            )
            return
        }
        var changed = 0
        for (entry in entries) {
            runtime.lineup.update(entry.id, update)
            changed++
        }
        session.flash.set(Flash("Changed ${plural(changed, "channel")}."))
        seeOther(call, page)
    }

    private suspend fun rows(): List<StationRow> {
        val stations = runtime.policy?.lastApplied()?.stations.orEmpty().associateBy { it.id }
        val overrides = runtime.policy?.policy?.let { p ->
            p.stations.filter { (key, station) -> isOverride(key, station, p) }.keys
        }.orEmpty()
        val coverage = runtime.guides.coverage
        val health = runtime.store.allHealth()
        val now = clock.instant()
        return runtime.lineup.entries().map { entry ->
            val station = stations[entry.id]
            StationRow(
                entry,
                station,
                guideStatus(entry, coverage, now),
                healthy = entry.offeringIds.count { health[it]?.status == HealthStatus.HEALTHY },
                overridden = station?.key?.let { it in overrides } == true,
            )
        }
    }

    /** The entries the newest run that changed anything touched, by lineup entry id. */
    private suspend fun changedInLastRun(): Set<String> {
        val changes = runtime.policy?.changes(1000).orEmpty()
        val last = changes.firstOrNull()?.appliedAt ?: return emptySet()
        return changes.filter { it.appliedAt == last }.mapNotNullTo(HashSet()) { it.change.entryId }
    }

    private fun groupChoices(rows: List<StationRow>): List<String> {
        val policy = runtime.policy?.policy
        val named = policy?.let {
            it.numbering.groups.keys + it.groups.map.values +
                it.groups.patterns.map { p -> p.group } + listOfNotNull(it.groups.default)
        }.orEmpty()
        return (rows.mapNotNull { it.entry.group } + named).distinct().sorted()
    }

    private suspend fun lineupView(filter: LineupFilter): LineupView {
        val rows = rows()
        val changed = if (filter.changed) changedInLastRun() else null
        val matches = rows.filter {
            filter.admits(it) && (changed == null || it.entry.id in changed)
        }.let { if (filter.sort.isEmpty()) it else it.sortedWith(filter.order()) }
        val pages = maxOf(1, (matches.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = filter.page.coerceIn(1, pages)
        val groups = rows.mapNotNull { it.entry.group }.distinct().sorted()
        return LineupView(
            filter.copy(
                page = page,
            ),
            matches.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE), matches.size, rows.size, pages,
            groups, runtime.policy?.document()?.revision, groupChoices(rows), rows.count { it.entry.enabled },
        )
    }

    private suspend fun stationView(id: String, filter: LineupFilter, error: String? = null): StationView? {
        val rows = rows()
        val row = rows.firstOrNull { it.entry.id == id } ?: return null
        val health = runtime.store.allHealth()
        val sources = row.entry.offeringIds.map { offeringId ->
            val offering = runtime.feeds.published.offering(offeringId)
            val state = health[offeringId]
            if (offering == null) {
                SourceLine(offeringId, "gone", null, "", "WITHDRAWN", state?.manuallyDisabled == true)
            } else {
                SourceLine(
                    offeringId,
                    offering.providerId,
                    runtime.feeds.published.channel(offering.channelId)?.name,
                    offering.protocol,
                    state?.status?.name ?: "UNVERIFIED",
                    state?.manuallyDisabled == true,
                )
            }
        }
        val guideName = row.entry.guide?.let {
            runtime.guides.channel(it.guideId, it.channelId)?.displayName?.let { name -> "$name (${it.guideId})" }
        }
        return StationView(
            row,
            sources,
            guideName,
            filter,
            runtime.policy?.document()?.revision,
            groupChoices(rows),
            error,
            probes =
            runtime.probes != null,
        )
    }

    private suspend fun mergesView(): MergesView? {
        val policy = runtime.policy ?: return null
        val document = policy.document()
        val findings = policy.findings()
        val suggestions = findings.suggestedAliases.mapNotNull { line ->
            SUGGESTION.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }
        return MergesView(
            document.revision,
            document.policy.names.learned,
            document.policy.names.never,
            suggestions,
            findings.findings,
            findings.unmappedCategories,
        )
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

    /**
     * A write to the source file from the Sources page: [edit] turns the file as written into the next document and
     * the confirmation to show; a refused edit re-renders the page with its reasons and the form, a first submission
     * asks, and a confirmed one writes, restarts and answers the restarting page.
     */
    private suspend fun setupWrite(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        what: String,
        starter: Boolean = false,
        edit: (SourceDocument) -> Result<Pair<SourceDocument, SetupConfirm>>,
    ) {
        val setup = runtime.setup ?: run {
            seeOther(call, "/admin/providers")
            return
        }
        val document = setup.document()
        suspend fun refuse(message: String, status: HttpStatusCode) {
            val view = providersView()
            val chrome = chrome(call, session, Flash("Not saved: ${message.lineSequence().first()}", Tone.BAD))
            call.html(
                providersPage(
                    chrome,
                    ProvidersView(view, SetupView(view.setup!!, errors = message.lines(), proposed = form)),
                ) {
                    providerEntries(chrome, view, null, null)
                },
                status,
            )
        }
        val (next, confirm) = edit(document).getOrElse {
            refuse(it.message ?: "The change is not valid", HttpStatusCode.BadRequest)
            return
        }
        if (form["confirm"] != "yes") {
            val view = providersView()
            val chrome = chrome(call, session)
            call.html(
                providersPage(
                    chrome,
                    ProvidersView(view, SetupView(view.setup!!, proposed = form, confirm = confirm)),
                ) {
                    providerEntries(chrome, view, null, null)
                },
            )
            return
        }
        try {
            val result = setup.replaceSources(next, starterPolicy = starter && next.providers.isNotEmpty())
            val policy = result.policyPath?.let { " and the starter lineup to $it" }.orEmpty()
            call.html(restartingPage(theme(call), "Saved $what to ${result.path}$policy."))
        } catch (e: ApiFailure) {
            refuse(e.message, e.status)
        }
    }

    private suspend fun providersView(): ProvidersView {
        val configs = runtime.feeds.configs.associateBy { it.id }
        val document = runtime.policy?.document()
        val entries = document?.policy?.providers
        val led = runtime.policy?.lastApplied()?.stations.orEmpty()
            .mapNotNull { station ->
                station.offeringIds.firstOrNull()?.let { runtime.feeds.published.offering(it)?.providerId }
            }
            .groupingBy { it }.eachCount()
        val rows = runtime.feeds.statuses.map { status ->
            val config = configs[status.id]
            val kind = when {
                config == null -> ""
                config.native && config.service != config.id -> "native: ${config.service}"
                config.native -> "native"
                else -> "M3U"
            }
            ProviderRow(status, kind, entries.orEmpty().withIndex().filter { it.value.id == status.id })
        }
        return ProvidersView(
            rows,
            runtime.guides.statuses,
            entries,
            document?.revision,
            led,
            providerSummaries = runtime.feeds.configs.map(::providerSummary),
            guideSummaries = runtime.guides.configs.map(::guideSummary),
            setup = runtime.setup?.let { SetupView.of(it.document(), it) },
        )
    }

    private suspend fun settingsView(
        changeLimit: Int,
        revealApiToken: Boolean = false,
        openAdvanced: Boolean = false,
    ): SettingsView {
        val policy = runtime.policy
        val document = policy?.document()
        return SettingsView(
            config?.let(::effectiveSettings).orEmpty(),
            document?.let { PolicySummary(it.path, it.revision, it.policy, policy.lastApplied()?.result) },
            policy?.changes(changeLimit).orEmpty(),
            changeLimit,
            apiTokenSet = config?.apiToken != null,
            revealedApiToken = config?.apiToken?.takeIf { revealApiToken },
            runtime = RuntimeSettingsView(
                runtime.settings.current,
                runtime.settings.environment,
                runtime.settings.saved,
                runtime.plex != null,
            ),
            openAdvanced = openAdvanced,
        )
    }

    private suspend fun plexView(call: ApplicationCall, reveal: Boolean): PlexView {
        val base = publicBase(call)
        val host = Url(base).host.let { if (it.contains(':')) "[$it]" else it }
        val ports = hdhr.publicPorts(Url(base).port)
        val enabled = hdhrChannels(runtime.lineup.entries())
        val byRange = runtime.policy != null
        val devices = if (!hdhr.enabled) {
            emptyList()
        } else {
            (0 until hdhr.devices).map { device ->
                val port = ports[device]
                val name = if (device == 0) hdhr.friendlyName else "${hdhr.friendlyName} ${device + 1}"
                HdhrDevice(name, "$host:$port", deviceShare(enabled, device, hdhr, byRange).size)
            }
        }
        val tokenNeeded = outputs.token != null
        val shown = if (reveal) {
            tokenQuery(outputs.token)
        } else if (tokenNeeded) {
            "?token=$HIDDEN_TOKEN"
        } else {
            ""
        }
        return PlexView(
            playlist = "$base/output/playlist.m3u$shown",
            guide = "$base/output/guide.xml$shown",
            tokenConfigured = tokenNeeded,
            revealed = reveal && tokenNeeded,
            hdhrEnabled = hdhr.enabled,
            devices = devices,
            devicesNeeded = if (byRange && hdhr.enabled) {
                (enabled.size + hdhr.deviceSize - 1) / hdhr.deviceSize
            } else {
                null
            },
            enabledChannels = enabled.size,
            tuners = hdhr.tuners,
            status = plexStatus?.status(),
            access = if (hdhr.enabled) runtime.hdhrAccess.status(true) else null,
        )
    }

    private fun ApplicationCall.session(): AdminSession = attributes.getOrNull(SESSION)
        ?: throw ApiFailure(HttpStatusCode.Unauthorized, "AUTH_REQUIRED", "Sign in required")
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
    COOKIE, value, encoding = CookieEncoding.RAW, maxAge = maxAge,
    expires = if (maxAge == 0) {
        GMTDate.START
    } else {
        null
    },
    path = "/admin", secure = secure,
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
    val q: String = "",
    val group: String = "",
    val state: String = "",
    val required: Boolean = false,
    val page: Int = 1,
    val guide: String = "",
    /** Stations with an override in the policy. */
    val override: Boolean = false,
    /** Entries the newest run that changed anything touched; matched by the view, which reads the change log. */
    val changed: Boolean = false,
    /** A column of [SORTS], or empty for lineup order; [dir] is `asc` or `desc`. */
    val sort: String = "",
    val dir: String = "",
) {
    fun admits(row: StationRow): Boolean {
        val entry = row.entry
        val station = row.station?.key?.contains(q, ignoreCase = true) == true
        val found = entry.name.contains(q, ignoreCase = true) || entry.number == q || station
        if (q.isNotEmpty() && !found) return false
        if (group.isNotEmpty() && entry.group != group) return false
        if (required && !row.required) return false
        if (override && !row.overridden) return false
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

    /** The rows in the order [sort] and [dir] ask for; numbers sort by value, names without case. */
    fun order(): Comparator<StationRow> {
        val by: Comparator<StationRow> = when (sort) {
            "number" -> compareBy<StationRow> {
                it.entry.number.toDoubleOrNull() ?: Double.MAX_VALUE
            }.thenBy { it.entry.number }

            "name" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.entry.name }

            "group" -> compareBy<StationRow> {
                it.entry.group == null
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.entry.group.orEmpty() }

            "sources" -> compareBy<StationRow> { it.entry.offeringIds.size }.thenBy { it.healthy }

            "guide" -> compareBy { GUIDE_ORDER.indexOf(it.guideStatus) }

            "state" -> compareBy<StationRow> { !it.entry.enabled }.thenBy { it.state }

            else -> compareBy { 0 }
        }
        return if (dir == "desc") by.reversed() else by
    }

    fun query(page: Int = this.page): String = listOf(
        "q" to q, "group" to group, "state" to state, "guide" to guide, "required" to if (required) "true" else "",
        "override" to if (override) "true" else "", "changed" to if (changed) "true" else "", "sort" to sort,
        "dir" to dir,
        "page" to if (page > 1) page.toString() else "",
    ).filter { it.second.isNotEmpty() }.formUrlEncode()

    companion object {
        fun from(parameters: Parameters) = LineupFilter(
            parameters["q"]?.trim().orEmpty().take(QUERY_LIMIT),
            parameters["group"].orEmpty().take(QUERY_LIMIT),
            parameters["state"]?.takeIf { it in STATES }.orEmpty(),
            parameters["required"] == "true",
            parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            parameters["guide"]?.takeIf { it in GUIDE_STATES }.orEmpty(),
            parameters["override"] == "true",
            parameters["changed"] == "true",
            parameters["sort"]?.takeIf { it in SORTS }.orEmpty(),
            parameters["dir"]?.takeIf { it == "desc" }.orEmpty(),
        )

        private const val QUERY_LIMIT = 200
        private val STATES = setOf("enabled", "hidden", "unverified")
        private val GUIDE_STATES = setOf("available", "missing")
        internal val SORTS = setOf("number", "name", "group", "sources", "guide", "state")
        private val GUIDE_ORDER = listOf("available", "stale", "empty", "unassigned")
    }
}

/** One lineup entry on the Channels page, with its policy station, guide state, healthy source count and override. */
internal class StationRow(
    val entry: LineupEntryRecord,
    val station: PolicyStation?,
    val guideStatus: String,
    val healthy: Int = 0,
    val overridden: Boolean = false,
) {
    val required: Boolean get() = station?.required == true
    val state: String get() = if (entry.enabled) {
        "on"
    } else {
        "off: " +
            (station?.disabledReason ?: entry.autoDisabledReason ?: "hidden")
    }

    /**
     * The row's tint: bad for an on-air station with sources and none healthy, warn for one
     * whose guide ended; null otherwise.
     */
    val tint: Tone? get() = when {
        !entry.enabled -> null
        entry.offeringIds.isNotEmpty() && healthy == 0 -> Tone.BAD
        guideStatus == "stale" || guideStatus == "empty" -> Tone.WARN
        else -> null
    }
}

/** Whether the entry for station [key] holds something a person set: anything but the number a run wrote back. */
internal fun isOverride(key: String, station: StationOverride, policy: LineupPolicy): Boolean = with(station) {
    name != null || group != null || logo != null || guide != null || enabled != null || required ||
        sources.isNotEmpty() ||
        prefer.isNotEmpty() ||
        exclude.isNotEmpty() ||
        (number != null && key !in policy.numbering.assigned)
}

/**
 * The tone of a failing import: warn while every last success is under a day old, bad after
 * that or with no success at all.
 */
internal fun failingTone(lastSuccesses: List<String?>, now: Instant): Tone = if (lastSuccesses.all {
        it != null && Duration.between(Instant.parse(it), now) < Duration.ofDays(1)
    }
) {
    Tone.WARN
} else {
    Tone.BAD
}

/**
 * "has failed for 3 days" once a day has passed since [lastSuccessAt]; "has never imported"
 * without one; null under a day.
 */
internal fun failingFor(lastSuccessAt: String?, now: Instant): String? {
    val since = lastSuccessAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "has never imported"
    val days = Duration.between(since, now).toDays()
    return if (days < 1) null else "has failed for ${plural(days.toInt(), "day")}"
}

private fun themeCookie(value: String, maxAge: Int) = Cookie(
    THEME_COOKIE,
    value,
    encoding = CookieEncoding.RAW,
    maxAge = maxAge,
    expires = if (maxAge == 0) {
        GMTDate.START
    } else {
        null
    },
    path = "/admin",
    extensions = mapOf("SameSite" to "Strict"),
)

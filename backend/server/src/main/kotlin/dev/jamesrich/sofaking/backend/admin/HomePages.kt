package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.http.ADMIN_LOGIN_LIMIT
import dev.jamesrich.sofaking.backend.http.authFailed
import dev.jamesrich.sofaking.backend.store.ignoreAttention
import dev.jamesrich.sofaking.backend.store.showAttention
import io.ktor.http.CacheControl
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.append
import io.ktor.http.cookies
import io.ktor.http.expires
import io.ktor.http.headers
import io.ktor.http.maxAge
import io.ktor.http.parameters
import io.ktor.http.path
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.expires
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.header
import io.ktor.server.routing.path
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.date.GMTDate

/** The home page and what is done from it: sign-in, the theme, stopping a session, attention items and the lineup. */
internal class HomePages(private val portal: Portal, private val sessions: AdminSessions, private val secret: String?) {
    private val runtime get() = portal.runtime
    private val overview = OverviewReader(portal)

    fun install(route: Route) = with(portal) {
        route.get {
            val session = sessions.find(call.request.cookies[COOKIE])
            if (session == null) {
                call.html(signInPage(enabled = secret != null, theme = theme(call)))
            } else {
                call.html(overviewPage(chrome(call, session), overview.read()))
            }
        }
        route.rateLimit(ADMIN_LOGIN_LIMIT) { post("/login") { signIn(call) } }
        route.action("/logout") { session, _ ->
            sessions.close(session)
            call.response.cookies.append(sessionCookie("", maxAge = 0, secure = false))
            seeOther(call, "/admin")
        }
        route.staticResources("/static", "admin") {
            modify { _, call -> call.response.header(HttpHeaders.CacheControl, "private, max-age=86400") }
        }
        route.action("/theme") { _, form -> pickTheme(call, form) }
        route.action("/sessions/{id}/stop") { session, form -> stopSession(call, session, form) }
        route.action("/vod/{id}/stop") { session, _ ->
            val id = call.parameters["id"]!!
            val row = overview.playingRows().firstOrNull { it.activity.id == id }
            if (row == null || runtime.vod?.release(id) != true) {
                session.flash.set(Flash("That session already ended.", Tone.WARN))
            } else {
                session.flash.set(Flash("Stopped playing ${row.title}."))
            }
            seeOther(call, "/admin#watching")
        }
        route.action("/attention/ignore") { session, form ->
            val key = form["key"]?.trim().orEmpty().take(ATTENTION_KEY_LIMIT)
            val fingerprint = form["fingerprint"]?.trim().orEmpty().take(ATTENTION_KEY_LIMIT)
            if (key.isEmpty() || fingerprint.isEmpty()) {
                throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "key and fingerprint are required")
            }
            runtime.store.ignoreAttention(key, fingerprint, portal.clock.instant())
            session.flash.set(Flash("Ignored until it changes."))
            seeOther(call, "/admin#attention")
        }
        route.action("/attention/show") { session, form ->
            runtime.store.showAttention(form["key"]?.trim().orEmpty().take(ATTENTION_KEY_LIMIT))
            session.flash.set(Flash("Shown again."))
            seeOther(call, "/admin#attention")
        }
        route.action("/policy/starter") { session, form -> starter(call, session, form) }
    }

    private suspend fun signIn(call: ApplicationCall) {
        val expected = secret ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")
        if (!sameSecret(call.formOrEmpty()["token"], expected)) {
            call.authFailed()
            call.html(
                signInPage(enabled = true, error = "That token is not the admin token."),
                HttpStatusCode.Unauthorized,
            )
            return
        }
        call.request.cookies[COOKIE]?.let { sessions.close(it) }
        val opened = sessions.open()
        val secure = call.request.headers["X-Forwarded-Proto"] == "https" || call.request.local.scheme == "https"
        call.response.cookies.append(sessionCookie(opened.cookie, maxAge = SESSION_SECONDS, secure = secure))
        seeOther(call, "/admin")
    }

    private suspend fun pickTheme(call: ApplicationCall, form: Parameters) {
        val theme = form["theme"]
        val back = form["back"]?.takeIf {
            it.startsWith("/admin") && !it.startsWith("//") && '\n' !in it && '\r' !in it
        } ?: "/admin"
        if (theme in THEMES) {
            call.response.cookies.append(themeCookie(theme!!, maxAge = THEME_SECONDS))
        } else {
            call.response.cookies.append(themeCookie("", maxAge = 0))
        }
        seeOther(call, back)
    }

    private suspend fun stopSession(call: ApplicationCall, session: AdminSession, form: Parameters) {
        val id = call.parameters["id"]!!
        val view = overview.read()
        val row = view.sessions.firstOrNull { it.active.id == id }
        when {
            row == null -> {
                session.flash.set(Flash("That session already ended.", Tone.WARN))
                seeOther(call, "/admin#watching")
            }

            form["confirm"] != "yes" ->
                call.html(overviewPage(portal.chrome(call, session), OverviewView(view, stop = StopConfirm(row))))

            else -> {
                runtime.sessions.release(id)
                session.flash.set(Flash("Stopped the session on ${row.channel ?: row.active.channelId}."))
                seeOther(call, "/admin#watching")
            }
        }
    }

    private suspend fun starter(call: ApplicationCall, session: AdminSession, form: Parameters) {
        val setup = runtime.setup
        when {
            setup == null -> seeOther(call, "/admin")

            form["confirm"] != "yes" -> call.html(
                overviewPage(portal.chrome(call, session), OverviewView(overview.read(), confirmStarter = true)),
            )

            else -> try {
                val result = setup.writeStarterPolicy()
                call.html(restartingPage(portal.theme(call), "Wrote the lineup policy to ${result.path}."))
            } catch (e: ApiFailure) {
                call.html(
                    overviewPage(
                        portal.chrome(call, session, Flash("Not built: ${e.message}", Tone.BAD)),
                        overview.read(),
                    ),
                    HttpStatusCode.Conflict,
                )
            }
        }
    }
}

internal const val COOKIE = "sofa_admin"
private const val ATTENTION_KEY_LIMIT = 64
private const val SESSION_SECONDS = 12 * 3600
private const val THEME_SECONDS = 365 * 86_400

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

package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.content
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.headers
import io.ktor.http.path
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sign-in, the session, CSRF and the pages every visitor sees. */
class AdminPortalSignInTest : AdminPortalCase() {
    @Test fun `sign-in takes the admin token and sets a session cookie that never holds it`() = testApplication {
        portal {
            runtime.adoptAll()
            val form = browser.get("/admin")
            assertEquals(HttpStatusCode.OK, form.status)
            assertTrue(form.bodyAsText().contains("name=\"token\""))
            assertNull(form.headers[HttpHeaders.SetCookie])

            val page = browser.get("/admin/lineup")
            assertEquals(HttpStatusCode.SeeOther, page.status)
            assertEquals("/admin", page.headers[HttpHeaders.Location])
            assertEquals(
                HttpStatusCode.Unauthorized,
                browser.post(
                    "/admin/lineup/anything/visibility",
                    "enabled" to "false",
                ).status,
            )

            for (wrong in listOf(admin.reversed(), token, "")) {
                val refused = browser.signIn(wrong)
                assertEquals(HttpStatusCode.Unauthorized, refused.status, "token: $wrong")
                assertNull(refused.headers[HttpHeaders.SetCookie])
                assertTrue(refused.bodyAsText().contains("not the admin token"))
            }

            val signedIn = browser.signIn()
            assertEquals(HttpStatusCode.SeeOther, signedIn.status)
            val cookie = signedIn.headers[HttpHeaders.SetCookie]!!
            assertTrue(cookie.startsWith("sofa_admin="), cookie)
            for (attribute in listOf(
                "HttpOnly",
                "SameSite=Strict",
                "Path=/admin",
                "Max-Age=43200",
            )) {
                assertTrue(cookie.contains(attribute), "$attribute in $cookie")
            }
            assertFalse(cookie.contains(admin), "the cookie never carries the token")

            val dashboard = browser.get("/admin")
            assertTrue(dashboard.bodyAsText().contains("Providers"))
            assertEquals("no-store", dashboard.headers[HttpHeaders.CacheControl])
            assertTrue(dashboard.headers["Content-Security-Policy"]!!.contains("script-src 'self'; style-src 'self'"))
            assertFalse(dashboard.bodyAsText().contains(" style=\""), "no inline style, which the policy would block")
            val lineup = browser.get("/admin/lineup")
            assertEquals(HttpStatusCode.OK, lineup.status)
            assertEquals(HttpStatusCode.OK, browser.get("/admin/static/htmx.min.js").status)

            val csrf = field(lineup.bodyAsText(), "csrf")
            assertEquals(
                HttpStatusCode.Forbidden,
                browser.post("/admin/logout").status,
                "sign-out takes the CSRF token too",
            )
            assertEquals(HttpStatusCode.SeeOther, browser.post("/admin/logout", "csrf" to csrf).status)
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/lineup").status, "the session is gone")
        }
    }

    @Test fun `the footer of a signed-in page shows the revision and uptime`() = testApplication {
        portal(show = { it.copy(revision = "abc1234") }) {
            assertFalse(
                browser.get("/admin").bodyAsText().contains("abc1234"),
                "the sign-in page does not name the revision",
            )
            browser.signIn()
            val footer = Regex(
                "<footer>(.*?)</footer>",
            ).find(browser.get("/admin/lineup").bodyAsText())!!.groupValues[1]
            assertTrue(footer.contains("Revision <code>abc1234</code>."), footer)
            assertTrue(footer.contains("Up 0 min."), footer)
        }
    }

    @Test fun `uptime reads in the largest two units`() {
        val start = java.time.Instant.parse("2026-10-10T00:00:00Z")
        assertEquals("7 min", dev.jamesrich.sofaking.backend.admin.uptime(start, start.plusSeconds(7 * 60 + 59)))
        assertEquals(
            "5 h 12 min",
            dev.jamesrich.sofaking.backend.admin.uptime(start, start.plusSeconds(5 * 3600 + 12 * 60)),
        )
        assertEquals(
            "3 d 4 h",
            dev.jamesrich.sofaking.backend.admin.uptime(start, start.plusSeconds(3 * 86_400 + 4 * 3600 + 59)),
        )
    }

    @Test
    fun `sign-in attempts are limited per address, and each refusal is logged without the token`() = testApplication {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        application { attributes.put(AuthFailureLogKey, AuthFailureLog(MutableClock(), sink = { lines += it })) }
        portal {
            repeat(ADMIN_LOGINS_PER_MINUTE) { attempt ->
                assertEquals(HttpStatusCode.Unauthorized, browser.signIn(admin.reversed()).status, "attempt $attempt")
            }
            assertEquals(
                HttpStatusCode.TooManyRequests,
                browser.signIn().status,
                "even the right token waits once the limit is spent",
            )
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/lineup").status, "no session was opened")
            assertEquals(ADMIN_LOGINS_PER_MINUTE, lines.size, "$lines")
            assertTrue(lines.all { it.startsWith("auth failed route=/admin/login remote=") }, "$lines")
            assertTrue(lines.none { admin in it || admin.reversed() in it }, "$lines")
        }
    }

    @Test fun `portal paths need the session under any spelling`() = testApplication {
        portal {
            for (path in listOf(
                "//admin/lineup",
                "/%61dmin/lineup",
                "/admin//lineup",
                "/admin/lineup/",
                "/%61dmin/plex",
                "/admin/%70lex",
                "//admin/static/htmx.min.js",
            )) {
                val response = browser.get { url { encodedPath = path } }
                assertEquals(HttpStatusCode.SeeOther, response.status, path)
                assertFalse(response.bodyAsText().contains("<table"), path)
            }
            for (path in listOf(
                "/%61dmin/lineup/x/visibility",
                "//admin/plex/reveal",
                "/admin//merges/never",
                "/%61dmin/logout",
            )) {
                assertEquals(HttpStatusCode.Unauthorized, browser.post { url { encodedPath = path } }.status, path)
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get {
                    url { encodedPath = "/admin/%2e%2e/api/v1/channels" }
                }.status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/admin/lineup").status,
                "the portal opens nothing under /api",
            )
        }
    }

    @Test fun `actions take the session's CSRF token, and search filters the lineup`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val all = browser.get("/admin/lineup").bodyAsText()
            assertTrue(all.contains("Twin Sports") && all.contains("TS Station"))
            val found = browser.get("/admin/lineup?q=twin").bodyAsText()
            assertTrue(found.contains("Twin Sports"))
            assertFalse(found.contains("TS Station"))
            assertEquals(1, Regex("""id="row-""").findAll(found).count())

            val twin = runtime.lineup.entries().single { it.name == "Twin Sports" }
            val csrf = field(all, "csrf")
            assertEquals(
                HttpStatusCode.Forbidden,
                browser.post(
                    "/admin/lineup/${twin.id}/visibility",
                    "enabled" to "false",
                ).status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                browser.post(
                    "/admin/lineup/${twin.id}/visibility",
                    "enabled" to "false",
                    "csrf" to csrf.reversed(),
                ).status,
            )
            assertTrue(runtime.lineup.entry(twin.id)!!.enabled, "a refused action changes nothing")

            val hidden = browser.post(
                "/admin/lineup/${twin.id}/visibility",
                "enabled" to "false",
                "csrf" to csrf,
                "q" to "twin",
            )
            assertEquals(HttpStatusCode.SeeOther, hidden.status)
            assertEquals("/admin/lineup?q=twin", hidden.headers[HttpHeaders.Location], "the filters come back")
            assertFalse(runtime.lineup.entry(twin.id)!!.enabled, "without a policy the lineup entry changes")
            assertTrue(browser.get("/admin/lineup?state=hidden").bodyAsText().contains("Twin Sports"))

            val renamed = browser.post(
                "/admin/lineup/${twin.id}/edit",
                "name" to "Twin Sports East",
                "number" to twin.number,
                "group" to "",
                "csrf" to csrf,
            )
            assertEquals(HttpStatusCode.SeeOther, renamed.status)
            assertEquals("Twin Sports East", runtime.lineup.entry(twin.id)!!.name)
            assertEquals(twin.number, runtime.lineup.entry(twin.id)!!.number)
            assertTrue(browser.get("/admin/merges").bodyAsText().contains("needs a lineup policy"))
            assertTrue(browser.get("/admin/required").bodyAsText().contains("needs a lineup policy"))
        }
    }

    @Test fun `the output token stays out of the page until revealed`() = testApplication {
        portal {
            browser.signIn()
            val page = browser.get("/admin/plex")
            assertEquals(HttpStatusCode.OK, page.status)
            val html = page.bodyAsText()
            assertFalse(html.contains(outputToken))
            assertTrue(html.contains("/output/playlist.m3u?token="))
            assertFalse(browser.get("/admin").bodyAsText().contains(outputToken))
            val revealed = browser.post("/admin/plex/reveal", "csrf" to field(html, "csrf")).bodyAsText()
            assertTrue(revealed.contains("/output/playlist.m3u?token=$outputToken"))
            assertTrue(revealed.contains("/output/guide.xml?token=$outputToken"))
        }
    }

    @Test
    fun `the stylesheet is served without a session and every page links it by content version`() = testApplication {
        portal {
            val signIn = client.get("/admin").bodyAsText()
            val href = Regex("""href="(/admin/static/admin\.css\?v=[0-9a-f]{16})"""").find(signIn)?.groupValues?.get(1)
            assertNotNull(href, signIn.take(400))
            val sheet = client.get(href)
            assertEquals(HttpStatusCode.OK, sheet.status)
            assertEquals(ContentType.Text.CSS, sheet.contentType()?.withoutParameters())
            assertEquals("private, max-age=86400", sheet.headers[HttpHeaders.CacheControl])
            assertTrue(sheet.bodyAsText().contains("--plum:"))
            assertFalse(signIn.contains("<style"), "the page carries no inline stylesheet")
        }
    }

    @Test fun `the brand icon is served without a session`() = testApplication {
        portal {
            val icon = client.get("/admin/static/icon.svg")
            assertEquals(HttpStatusCode.OK, icon.status)
            assertEquals(ContentType.Image.SVG, icon.contentType()?.withoutParameters())
            assertEquals("private, max-age=86400", icon.headers[HttpHeaders.CacheControl])
            assertFalse(icon.bodyAsText().contains("<script"))
        }
    }

    @Test fun `the API token is revealed only by a signed-in POST with the CSRF token`() = testApplication {
        portal {
            assertEquals(HttpStatusCode.Unauthorized, browser.post("/admin/settings/reveal-api-token").status)
            browser.signIn()
            val html = browser.get("/admin/settings").bodyAsText()
            assertFalse(html.contains(token))
            assertTrue(html.contains("Reveal the API token"))
            val refused = browser.post("/admin/settings/reveal-api-token")
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertFalse(refused.bodyAsText().contains(token))
            val revealed = browser.post("/admin/settings/reveal-api-token", "csrf" to field(html, "csrf"))
            assertEquals(HttpStatusCode.OK, revealed.status)
            val page = revealed.bodyAsText()
            assertTrue(page.contains(token))
            for (secret in listOf(
                admin,
                outputToken,
                plexToken,
            )) {
                assertFalse(page.contains(secret), "only the API token is revealed")
            }
            assertFalse(browser.get("/admin/settings").bodyAsText().contains(token), "the next page hides it again")
        }
    }

    @Test fun `the theme cookie pins a color scheme on every page, and the nav folds into a menu`() = testApplication {
        portal {
            browser.signIn()
            val page = browser.get("/admin/lineup").bodyAsText()
            assertFalse(page.contains("data-theme="), "with no cookie the device decides")
            assertTrue(page.contains("<details class=\"menu\"><summary>Menu"), page.take(1500))
            assertTrue(page.contains("<form action=\"/admin/theme\" method=\"post\" class=\"inline theme\""))
            val pinned = browser.post(
                "/admin/theme",
                "theme" to "light",
                "back" to "/admin/lineup?q=twin",
                "csrf" to field(page, "csrf"),
            )
            assertEquals(HttpStatusCode.SeeOther, pinned.status)
            assertEquals("/admin/lineup?q=twin", pinned.headers[HttpHeaders.Location])
            assertTrue(pinned.headers[HttpHeaders.SetCookie]!!.startsWith("sofa_theme=light;"))
            val light = browser.get("/admin/plex").bodyAsText()
            assertTrue(light.contains("<html lang=\"en\" data-theme=\"light\">"), light.take(200))
            assertTrue(light.contains("<meta name=\"theme-color\" content=\"#F7F4FC\">"))
            assertTrue(light.contains("value=\"light\" aria-pressed=\"true\""))
            val elsewhere = browser.post(
                "/admin/theme",
                "theme" to "auto",
                "back" to "https://evil.example/",
                "csrf" to field(page, "csrf"),
            )
            assertEquals("/admin", elsewhere.headers[HttpHeaders.Location], "only a portal path is returned to")
            assertFalse(browser.get("/admin").bodyAsText().contains("data-theme="))
        }
    }
}

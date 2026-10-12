package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class AdminPortalTest {
    private val json = Json { encodeDefaults = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)
    private val outputToken = "output".repeat(6)
    private val basePolicy = """{"names":{"version":1},"health":{"requireVerified":false},"providers":[{"id":"fixture"},{"id":"alt"},{"id":"twin"},{"id":"ts"}],"numbering":{"floor":100}}"""

    private class Setup(val policyFile: Path?, val runtime: LiveRuntime, val browser: HttpClient)

    /** A runtime over the fixture's feeds, with [policy] when given, and a browser that keeps cookies and never follows redirects. */
    private suspend fun ApplicationTestBuilder.portal(
        policy: String? = null,
        feeds: (Fixture) -> List<FeedConfig> = { listOf(it.feed, it.altFeed, it.twinFeed, it.tsFeed) },
        block: suspend Setup.() -> Unit,
    ) {
        val dir = Files.createTempDirectory("sofa-admin-portal-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(feeds(fixture))},"guides":[]}""")
            val policyFile = policy?.let { text -> Files.createDirectories(dir.resolve("policy")).resolve("policy.json").also { Files.writeString(it, text) } }
            val store = openStore(dir)
            val config = ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin, policyFile = policyFile)
            val runtime = LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 3_600_000, probes = false)
            try {
                application { backendModule(store, MutableClock(), runtime, token, admin, OutputConfig(outputToken)) }
                for (provider in runtime.feeds.configs) runtime.feeds.refresh(provider)
                val browser = createClient { install(HttpCookies); followRedirects = false }
                Setup(policyFile, runtime, browser).block()
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }

    private suspend fun HttpClient.signIn(secret: String = admin): HttpResponse = submitForm("/admin/login", parameters { append("token", secret) })

    private fun field(html: String, name: String): String = Regex("""name="$name" value="([^"]*)"""").find(html)?.groupValues?.get(1) ?: fail("no $name field in the page")

    private suspend fun HttpClient.post(path: String, vararg fields: Pair<String, String>): HttpResponse =
        submitForm(path, parameters { for ((name, value) in fields) append(name, value) })

    private fun stationsIn(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject["stations"]?.jsonObject ?: JsonObject(emptyMap())

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
            assertEquals(HttpStatusCode.Unauthorized, browser.post("/admin/lineup/anything/visibility", "enabled" to "false").status)

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
            for (attribute in listOf("HttpOnly", "SameSite=Strict", "Path=/admin", "Max-Age=43200")) assertTrue(cookie.contains(attribute), "$attribute in $cookie")
            assertFalse(cookie.contains(admin), "the cookie never carries the token")

            val dashboard = browser.get("/admin")
            assertTrue(dashboard.bodyAsText().contains("Providers"))
            assertEquals("no-store", dashboard.headers[HttpHeaders.CacheControl])
            assertTrue(dashboard.headers["Content-Security-Policy"]!!.contains("script-src 'self'"))
            val lineup = browser.get("/admin/lineup")
            assertEquals(HttpStatusCode.OK, lineup.status)
            assertEquals(HttpStatusCode.OK, browser.get("/admin/static/htmx.min.js").status)

            val csrf = field(lineup.bodyAsText(), "csrf")
            assertEquals(HttpStatusCode.Forbidden, browser.post("/admin/logout").status, "sign-out takes the CSRF token too")
            assertEquals(HttpStatusCode.SeeOther, browser.post("/admin/logout", "csrf" to csrf).status)
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/lineup").status, "the session is gone")
        }
    }

    @Test fun `portal paths need the session under any spelling`() = testApplication {
        portal {
            for (path in listOf("//admin/lineup", "/%61dmin/lineup", "/admin//lineup", "/admin/lineup/", "/%61dmin/plex", "/admin/%70lex", "//admin/static/htmx.min.js")) {
                val response = browser.get { url { encodedPath = path } }
                assertEquals(HttpStatusCode.SeeOther, response.status, path)
                assertFalse(response.bodyAsText().contains("<table"), path)
            }
            for (path in listOf("/%61dmin/lineup/x/visibility", "//admin/plex/reveal", "/admin//merges/never", "/%61dmin/logout")) {
                assertEquals(HttpStatusCode.Unauthorized, browser.post { url { encodedPath = path } }.status, path)
            }
            assertEquals(HttpStatusCode.BadRequest, client.get { url { encodedPath = "/admin/%2e%2e/api/v1/channels" } }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/lineup").status, "the portal opens nothing under /api")
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
            assertEquals(HttpStatusCode.Forbidden, browser.post("/admin/lineup/${twin.id}/visibility", "enabled" to "false").status)
            assertEquals(HttpStatusCode.Forbidden, browser.post("/admin/lineup/${twin.id}/visibility", "enabled" to "false", "csrf" to csrf.reversed()).status)
            assertTrue(runtime.lineup.entry(twin.id)!!.enabled, "a refused action changes nothing")

            val hidden = browser.post("/admin/lineup/${twin.id}/visibility", "enabled" to "false", "csrf" to csrf, "q" to "twin")
            assertEquals(HttpStatusCode.SeeOther, hidden.status)
            assertEquals("/admin/lineup?q=twin", hidden.headers[HttpHeaders.Location], "the filters come back")
            assertFalse(runtime.lineup.entry(twin.id)!!.enabled, "without a policy the lineup entry changes")
            assertTrue(browser.get("/admin/lineup?state=hidden").bodyAsText().contains("Twin Sports"))

            val renamed = browser.post("/admin/lineup/${twin.id}/edit", "name" to "Twin Sports East", "number" to twin.number, "group" to "", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, renamed.status)
            assertEquals("Twin Sports East", runtime.lineup.entry(twin.id)!!.name)
            assertEquals(twin.number, runtime.lineup.entry(twin.id)!!.number)
            assertTrue(browser.get("/admin/merges").bodyAsText().contains("needs a lineup policy"))
            assertTrue(browser.get("/admin/required").bodyAsText().contains("needs a lineup policy"))
        }
    }

    @Test fun `hide writes the policy override, and the lineup reflects it after the apply`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            for (path in listOf("/admin", "/admin/merges", "/admin/required", "/admin/plex")) assertEquals(HttpStatusCode.OK, browser.get(path).status, path)
            val page = browser.get("/admin/lineup").bodyAsText()
            assertTrue(Regex("""<select name="group"[^>]*><option value=""[^>]*>Policy decides</option>""").containsMatchIn(page), "the group is picked from a list")
            val csrf = field(page, "csrf")
            val revision = field(page, "revision")
            val id = stationId("twin sports")
            val response = browser.post("/admin/lineup/$id/visibility", "enabled" to "false", "csrf" to csrf, "revision" to revision)
            assertEquals(HttpStatusCode.SeeOther, response.status, response.bodyAsText())
            assertEquals(false, stationsIn(policyFile!!).getValue("twin sports").jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertFalse(runtime.lineup.entry(id)!!.enabled)
            assertTrue(browser.get("/admin/lineup?state=hidden").bodyAsText().contains("off: policy"))

            // The file has moved on since the page loaded; a one-station edit still applies to it as it is now.
            val later = browser.post("/admin/lineup/$id/visibility", "enabled" to "true", "csrf" to csrf, "revision" to revision)
            assertEquals(HttpStatusCode.SeeOther, later.status, later.bodyAsText())
            assertTrue(runtime.lineup.entry(id)!!.enabled)
        }
    }

    @Test fun `never merge moves a learned alias to names never`() = testApplication {
        portal("""{"health":{"requireVerified":false},"providers":[{"id":"learn"},{"id":"learn2"}]}""", feeds = { listOf(it.learnFeed, it.learnTwinFeed) }) {
            for (guide in runtime.guides.configs) runtime.guides.refresh(guide)
            runtime.policy!!.apply("test")
            for (guide in runtime.guides.configs) runtime.guides.refresh(guide)
            assertEquals(mapOf("learnshowtv" to "learnshow"), runtime.policy!!.apply("test").learned)

            browser.signIn()
            val page = browser.get("/admin/merges").bodyAsText()
            assertTrue(page.contains("learnshowtv") && page.contains("Never merge"))
            val response = browser.post("/admin/merges/never", "from" to "learnshowtv", "to" to "learnshow", "csrf" to field(page, "csrf"), "revision" to field(page, "revision"))
            assertEquals(HttpStatusCode.SeeOther, response.status, response.bodyAsText())
            val names = Json.parseToJsonElement(Files.readString(policyFile!!)).jsonObject["names"]!!.jsonObject
            assertEquals(Json.parseToJsonElement("""[["learnshowtv","learnshow"]]"""), names["never"])
            assertNull(names["learned"], "the learned entry goes")
            assertTrue(runtime.lineup.entries().any { it.name == "Learn Show TV" }, "the station is its own again: ${runtime.lineup.entries().map { it.name }}")
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
}

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
    private val plexToken = "plexsecret".repeat(3)
    private val basePolicy = """{"names":{"version":1},"health":{"requireVerified":false},"providers":[{"id":"fixture"},{"id":"alt"},{"id":"twin"},{"id":"ts"}],"numbering":{"floor":100}}"""

    private class Setup(val policyFile: Path?, val runtime: LiveRuntime, val browser: HttpClient)

    /** A runtime over the fixture's feeds, with [policy] when given, and a browser that keeps cookies and never follows redirects. */
    private suspend fun ApplicationTestBuilder.portal(
        policy: String? = null,
        feeds: (Fixture) -> List<FeedConfig> = { listOf(it.feed, it.altFeed, it.twinFeed, it.tsFeed) },
        guides: (Fixture) -> List<GuideConfig> = { emptyList() },
        block: suspend Setup.() -> Unit,
    ) {
        val dir = Files.createTempDirectory("sofa-admin-portal-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(feeds(fixture))},"guides":${json.encodeToString(guides(fixture))}}""")
            val policyFile = policy?.let { text -> Files.createDirectories(dir.resolve("policy")).resolve("policy.json").also { Files.writeString(it, text) } }
            val store = openStore(dir)
            val config = ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin, policyFile = policyFile)
            val runtime = LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 3_600_000, probes = false)
            try {
                // The portal is told of a Plex token and the output token the runtime never uses, so redaction is checked against them.
                val shown = config.copy(outputs = OutputConfig(outputToken), plex = PlexConfig("http://127.0.0.1:32400", plexToken))
                application { backendModule(store, MutableClock(), runtime, token, admin, OutputConfig(outputToken), config = shown) }
                for (provider in runtime.feeds.configs) runtime.feeds.refresh(provider)
                val extraGuides = guides(fixture).map { it.id }.toSet()
                for (guide in runtime.guides.configs.filter { it.id in extraGuides }) runtime.guides.refresh(guide)
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
            val id = stationId("twin sports")
            val station = browser.get("/admin/lineup/$id").bodyAsText()
            assertTrue(Regex("""<select name="group"[^>]*><option value=""[^>]*>Policy decides</option>""").containsMatchIn(station), "the group is picked from a list")
            val csrf = field(page, "csrf")
            val revision = field(page, "revision")
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

    @Test fun `the overview lists what needs attention and links to the fix`() = testApplication {
        portal(basePolicy, guides = { listOf(it.brokenGuide) }) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val html = browser.get("/admin").bodyAsText()
            assertTrue(html.contains("Needs attention"))
            assertTrue(html.contains("Guide broken failed its last import"), html)
            assertTrue(html.contains("href=\"/admin/providers#guides\""), "the item links to where it is fixed")
            assertTrue(html.contains("Guide coverage"))
            assertTrue(html.contains("href=\"/admin/lineup?state=enabled\""), "the channel count links to the lineup")
            assertFalse(html.contains(admin) || html.contains(token) || html.contains(outputToken))
        }
    }

    @Test fun `the providers page needs the session and shows each import`() = testApplication {
        portal(basePolicy, guides = { listOf(it.brokenGuide) }) {
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/providers").status)
            browser.signIn()
            val html = browser.get("/admin/providers").bodyAsText()
            for (id in listOf("fixture", "alt", "twin", "ts", "broken")) assertTrue(html.contains(">$id<"), id)
            assertFalse(html.contains("/feed.m3u"), "no source URL reaches the page")
        }
    }

    @Test fun `the brand icon is served without a session`() = testApplication {
        portal {
            val icon = client.get("/admin/static/icon.svg")
            assertEquals(HttpStatusCode.OK, icon.status)
            assertEquals(ContentType.Image.SVG, icon.contentType()?.withoutParameters())
            assertFalse(icon.bodyAsText().contains("<script"))
        }
    }

    @Test fun `the station page edits a channel and the lineup confirms the save inline`() = testApplication {
        portal {
            runtime.adoptAll()
            val twin = runtime.lineup.entries().single { it.name == "Twin Sports" }
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/lineup/${twin.id}").status, "the station page needs the session")
            browser.signIn()
            val page = browser.get("/admin/lineup/${twin.id}?q=twin")
            assertEquals(HttpStatusCode.OK, page.status)
            val html = page.bodyAsText()
            assertTrue(html.contains("Sources, in failover order") && html.contains("twin"))
            assertFalse(html.contains("/twin-feed.m3u"), "source URLs stay off the page")
            val gone = browser.get("/admin/lineup/not-a-station")
            assertEquals(HttpStatusCode.Gone, gone.status)
            assertTrue(gone.bodyAsText().contains("Channel not found"))

            val saved = browser.post("/admin/lineup/${twin.id}/edit", "name" to "Twin Sports West", "number" to twin.number, "group" to "", "q" to "twin", "csrf" to field(html, "csrf"))
            assertEquals(HttpStatusCode.SeeOther, saved.status)
            assertEquals("/admin/lineup?q=twin", saved.headers[HttpHeaders.Location])
            val list = browser.get("/admin/lineup?q=twin").bodyAsText()
            assertTrue(list.contains("role=\"status\">Saved Twin Sports West."), "the save is confirmed once")
            assertTrue(list.contains("<tr class=\"flash\" id=\"row-${twin.id}\""), "the saved row is marked")
            assertFalse(browser.get("/admin/lineup?q=twin").bodyAsText().contains("Saved Twin Sports West."), "the notice shows once")

            val shown = browser.post("/admin/lineup/${twin.id}/visibility", "enabled" to "false", "from" to "station", "csrf" to field(html, "csrf"))
            assertEquals("/admin/lineup/${twin.id}", shown.headers[HttpHeaders.Location], "an action from the station page returns to it")
        }
    }

    @Test fun `a rejected station edit shows the policy's error on the station page`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val id = stationId("twin sports")
            val other = runtime.lineup.entries().first { it.id != id }
            val html = browser.get("/admin/lineup/$id").bodyAsText()
            val response = browser.post("/admin/lineup/$id/edit", "name" to "Twin Sports", "number" to other.number, "group" to "", "csrf" to field(html, "csrf"))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Not saved:") && body.contains("is claimed by"), body)
            assertTrue(body.contains("name=\"number\""), "the form comes back to correct")
            assertNotEquals(other.number, runtime.lineup.entry(id)!!.number)
        }
    }

    @Test fun `settings show the effective configuration with every secret redacted`() = testApplication {
        val query = "sekret".repeat(6)
        portal(basePolicy, feeds = { listOf(it.feed, it.altFeed, it.twinFeed, it.tsFeed.copy(playlistUrl = it.tsFeed.playlistUrl + "?auth=$query", headers = mapOf("X-Key" to query))) }) {
            runtime.policy!!.apply("test")
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/settings").status)
            browser.signIn()
            val html = browser.get("/admin/settings").bodyAsText()
            for (secret in listOf(admin, token, outputToken, plexToken, query)) assertFalse(html.contains(secret), "a secret reached the page")
            for (shown in listOf("SOFA_ADMIN_TOKEN", "SOFA_PLEX_URL", "http://127.0.0.1:32400", "policy.json", "headers: X-Key", "Change log")) assertTrue(html.contains(shown), shown)
            assertTrue(html.contains("Twin Sports"), "the change log lists the stations the run added")
        }
    }

    @Test fun `apply now runs the policy behind the session and the CSRF token`() = testApplication {
        portal(basePolicy) {
            assertEquals(HttpStatusCode.Unauthorized, browser.post("/admin/policy/apply").status)
            browser.signIn()
            val html = browser.get("/admin/settings").bodyAsText()
            assertEquals(HttpStatusCode.Forbidden, browser.post("/admin/policy/apply").status)
            assertNull(runtime.policy!!.lastApplied(), "a refused apply runs nothing")
            val applied = browser.post("/admin/policy/apply", "csrf" to field(html, "csrf"))
            assertEquals(HttpStatusCode.SeeOther, applied.status)
            assertEquals("/admin/settings", applied.headers[HttpHeaders.Location])
            assertNotNull(runtime.policy!!.lastApplied())
            assertTrue(browser.get("/admin/settings").bodyAsText().contains("Applied the policy:"))
        }
    }

    @Test fun `apply now without a policy says so`() = testApplication {
        portal {
            browser.signIn()
            val html = browser.get("/admin/settings").bodyAsText()
            assertTrue(html.contains("No lineup policy"))
            val response = browser.post("/admin/policy/apply", "csrf" to field(html, "csrf"))
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("No lineup policy is configured."))
        }
    }

    @Test fun `the guide filter splits the lineup`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val all = Regex("""id="row-""").findAll(browser.get("/admin/lineup").bodyAsText()).count()
            val with = Regex("""id="row-""").findAll(browser.get("/admin/lineup?guide=available").bodyAsText()).count()
            val without = Regex("""id="row-""").findAll(browser.get("/admin/lineup?guide=missing").bodyAsText()).count()
            assertTrue(all > 0)
            assertEquals(all, with + without)
        }
    }
}

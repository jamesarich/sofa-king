package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.PlexConfig
import dev.jamesrich.sofaking.backend.SecretOrigin
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.main
import dev.jamesrich.sofaking.backend.admin.spacedCode
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.GuideConfig
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.catalog.stationId
import dev.jamesrich.sofaking.backend.playback.AdCensus
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.recordAdChannel
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.query
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.content
import io.ktor.client.statement.request
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.append
import io.ktor.http.contentType
import io.ktor.http.cookies
import io.ktor.http.date
import io.ktor.http.encodedPath
import io.ktor.http.headers
import io.ktor.http.parameters
import io.ktor.http.path
import io.ktor.http.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AdminPortalTest {
    private val json = Json { encodeDefaults = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)
    private val outputToken = "output".repeat(6)
    private val plexToken = "plexsecret".repeat(3)
    private val basePolicy = """{"names":{"version":1},"health":{"requireVerified":false},"providers":[{"id":"fixture"},{"id":"alt"},{"id":"twin"},{"id":"ts"}],"numbering":{"floor":100}}"""

    private class Setup(val policyFile: Path?, val runtime: LiveRuntime, val browser: HttpClient, val dataDirectory: Path)

    /** A runtime over the fixture's feeds, with [policy] when given, and a browser that keeps cookies and never follows redirects. */
    private suspend fun ApplicationTestBuilder.portal(
        policy: String? = null,
        feeds: (Fixture) -> List<FeedConfig> = { listOf(it.feed, it.altFeed, it.twinFeed, it.tsFeed) },
        guides: (Fixture) -> List<GuideConfig> = { emptyList() },
        show: (ServerConfig) -> ServerConfig = { it },
        restart: () -> Unit = {},
        block: suspend Setup.() -> Unit,
    ) {
        val dir = Files.createTempDirectory("sofa-admin-portal-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(feeds(fixture))},"guides":${json.encodeToString(guides(fixture))}}""")
            val policyFile = policy?.let { text -> Files.createDirectories(dir.resolve("policy")).resolve("policy.json").also { Files.writeString(it, text) } }
            val store = openStore(dir)
            // No update image: the registry is never asked from a test; the update check itself is tested over a mock registry.
            val config = ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin, policyFile = policyFile, updateImage = null)
            val runtime = LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 3_600_000, probes = false, restart = restart)
            try {
                // The portal is told of a Plex token and the output token the runtime never uses, so redaction is checked against them.
                val shown = show(config.copy(outputs = OutputConfig(outputToken), plex = PlexConfig("http://127.0.0.1:32400", plexToken)))
                application { backendModule(store, MutableClock(), runtime, token, admin, OutputConfig(outputToken), config = shown) }
                for (provider in runtime.feeds.configs) runtime.feeds.refresh(provider)
                val extraGuides = guides(fixture).map { it.id }.toSet()
                for (guide in runtime.guides.configs.filter { it.id in extraGuides }) runtime.guides.refresh(guide)
                val browser = createClient { install(HttpCookies); followRedirects = false }
                Setup(policyFile, runtime, browser, dir).block()
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }

    private suspend fun HttpClient.signIn(secret: String = admin): HttpResponse = submitForm("/admin/login", parameters { append("token", secret) })

    private fun field(html: String, name: String): String = Regex("""name="$name" value="([^"]*)"""").find(html)?.groupValues?.get(1) ?: fail("no $name field in the page")

    private suspend fun HttpClient.post(path: String, vararg fields: Pair<String, String>): HttpResponse =
        submitForm(path, parameters { for ((name, value) in fields) append(name, value) })

    /** Whether the page has an input named [name] holding [value], whatever other attributes sit between. */
    private fun input(html: String, name: String, value: String): Boolean = Regex("""name="$name"[^>]*value="$value"""").containsMatchIn(html)

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
            assertTrue(dashboard.headers["Content-Security-Policy"]!!.contains("script-src 'self'; style-src 'self'"))
            assertFalse(dashboard.bodyAsText().contains(" style=\""), "no inline style, which the policy would block")
            val lineup = browser.get("/admin/lineup")
            assertEquals(HttpStatusCode.OK, lineup.status)
            assertEquals(HttpStatusCode.OK, browser.get("/admin/static/htmx.min.js").status)

            val csrf = field(lineup.bodyAsText(), "csrf")
            assertEquals(HttpStatusCode.Forbidden, browser.post("/admin/logout").status, "sign-out takes the CSRF token too")
            assertEquals(HttpStatusCode.SeeOther, browser.post("/admin/logout", "csrf" to csrf).status)
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/lineup").status, "the session is gone")
        }
    }

    @Test fun `the footer of a signed-in page shows the revision and uptime`() = testApplication {
        portal(show = { it.copy(revision = "abc1234") }) {
            assertFalse(browser.get("/admin").bodyAsText().contains("abc1234"), "the sign-in page does not name the revision")
            browser.signIn()
            val footer = Regex("<footer>(.*?)</footer>").find(browser.get("/admin/lineup").bodyAsText())!!.groupValues[1]
            assertTrue(footer.contains("Revision <code>abc1234</code>."), footer)
            assertTrue(footer.contains("Up 0 min."), footer)
        }
    }

    @Test fun `uptime reads in the largest two units`() {
        val start = java.time.Instant.parse("2026-10-10T00:00:00Z")
        assertEquals("7 min", dev.jamesrich.sofaking.backend.admin.uptime(start, start.plusSeconds(7 * 60 + 59)))
        assertEquals("5 h 12 min", dev.jamesrich.sofaking.backend.admin.uptime(start, start.plusSeconds(5 * 3600 + 12 * 60)))
        assertEquals("3 d 4 h", dev.jamesrich.sofaking.backend.admin.uptime(start, start.plusSeconds(3 * 86_400 + 4 * 3600 + 59)))
    }

    @Test fun `sign-in attempts are limited per address, and each refusal is logged without the token`() = testApplication {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        application { attributes.put(AuthFailureLogKey, AuthFailureLog(MutableClock(), sink = { lines += it })) }
        portal {
            repeat(ADMIN_LOGINS_PER_MINUTE) { attempt -> assertEquals(HttpStatusCode.Unauthorized, browser.signIn(admin.reversed()).status, "attempt $attempt") }
            assertEquals(HttpStatusCode.TooManyRequests, browser.signIn().status, "even the right token waits once the limit is spent")
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/lineup").status, "no session was opened")
            assertEquals(ADMIN_LOGINS_PER_MINUTE, lines.size, "$lines")
            assertTrue(lines.all { it.startsWith("auth failed route=/admin/login remote=") }, "$lines")
            assertTrue(lines.none { admin in it || admin.reversed() in it }, "$lines")
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
            assertEquals(mapOf("learnshowtv" to "learnshow"), runtime.policy.apply("test").learned)

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

    @Test fun `the ads page lists each channel's census by its lineup name`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            val channel = runtime.lineup.entries().first()
            runtime.store.recordAdChannel(channel.id, "fixture", AdCensus(3_720_000, marked = true, breaks = 4, learned = 1), java.time.Instant.now())
            browser.signIn()
            val page = browser.get("/admin/ads").bodyAsText()
            assertTrue(page.contains(channel.name), "the channel shows by name")
            assertTrue(page.contains("1 h 2 min"), "the time watched")
            assertTrue(page.contains("Shadow: logged, never blanked"), "the default mode")
            assertTrue(browser.get("/admin/providers").bodyAsText().contains("""href="/admin/ads""""), "Sources links its Ads tab")
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

    @Test fun `the Plex page allows and removes tuner addresses, and lists the ones refused`() = testApplication {
        portal {
            browser.signIn()
            val open = browser.get("/admin/plex").bodyAsText()
            assertTrue(open.contains("Every address may use the tuners"), "a loopback bind with no address is open")
            val csrf = field(open, "csrf")

            assertEquals(HttpStatusCode.SeeOther, browser.post("/admin/plex/allow", "csrf" to csrf, "address" to "10.0.0.0/8").status)
            val closed = browser.get("/admin/plex").bodyAsText()
            assertTrue(closed.contains("Allowed 10.0.0.0/8") && closed.contains("Remove 10.0.0.0/8"))
            assertEquals(HttpStatusCode.Forbidden, client.get("/discover.json").status)
            assertTrue(browser.get("/admin/plex").bodyAsText().contains("Addresses refused since the server started"))
            assertTrue(browser.get("/admin").bodyAsText().contains("The tuners refused 1 address"))

            browser.post("/admin/plex/allow", "csrf" to csrf, "address" to "plex.lan")
            assertTrue(browser.get("/admin/plex").bodyAsText().contains("Not allowed: Not an IP address or CIDR block: plex.lan"))
            assertEquals(listOf("10.0.0.0/8"), runtime.hdhrAccess.allowed)

            browser.post("/admin/plex/allow", "csrf" to csrf, "address" to "127.0.0.1")
            assertEquals(HttpStatusCode.OK, client.get("/discover.json").status)
            assertEquals(HttpStatusCode.Forbidden, browser.post("/admin/plex/disallow", "csrf" to csrf.reversed(), "address" to "10.0.0.0/8").status)
            browser.post("/admin/plex/disallow", "csrf" to csrf, "address" to "10.0.0.0/8")
            assertEquals(listOf("127.0.0.1"), runtime.hdhrAccess.allowed)
        }
    }

    @Test fun `the Library page takes a TMDB token and refuses one that cannot be a token before asking TMDB`() = testApplication {
        portal {
            browser.signIn()
            val page = browser.get("/admin/library").bodyAsText()
            assertTrue(page.contains("action=\"/admin/library/tmdb\"") && page.contains("TMDB API read access token"))
            val refused = browser.post("/admin/library/tmdb", "csrf" to field(page, "csrf"), "token" to "short")
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.bodyAsText().contains("Not saved: A TMDB API read access token or API key has at least 32 characters"))
            assertFalse(Files.exists(dataDirectory.resolve("secrets.json")))
        }
    }

    @Test fun `the home page says when the server cannot write the recordings folder`() = testApplication {
        portal {
            browser.signIn()
            assertFalse(browser.get("/admin").bodyAsText().contains("cannot write the recordings folder"))
            val root = runtime.dvr!!.root
            root.toFile().deleteRecursively()
            Files.writeString(root, "a file where the folder should be")
            val home = browser.get("/admin").bodyAsText()
            assertTrue(home.contains("The server cannot write the recordings folder") && home.contains("sudo chown -R UID:GID ./recordings"))
        }
    }

    @Test fun `the overview lists what needs attention and links to the fix`() = testApplication {
        portal(basePolicy, guides = { listOf(it.brokenGuide) }) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val html = browser.get("/admin").bodyAsText()
            assertTrue(html.contains("Needs attention"))
            assertTrue(html.contains("Guide broken has never imported"), html)
            assertTrue(html.contains("href=\"/admin/providers#guides\""), "the item links to where it is fixed")
            assertTrue(html.contains("href=\"https://github.com/jamesarich/sofa-king/blob/main/backend/docs/troubleshooting.md#guide\""), "the item links to the doc that explains it")
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

    @Test fun `the services form asks before it writes the source file, then restarts and answers the restarting page`() = testApplication {
        var restarts = 0
        portal(restart = { restarts++ }) {
            browser.signIn()
            val page = browser.get("/admin/providers").bodyAsText()
            assertTrue(page.contains("<section id=\"services\">") && page.contains("Pluto TV") && page.contains("Add an M3U playlist"), "the Sources page offers the services and the playlist form")
            assertTrue(Regex("""name="svc"[^>]*value="whale"(?![^>]*checked)""").containsMatchIn(page), "a service the file lacks starts unticked when a file exists")
            for (playlist in listOf("fixture", "alt", "twin", "ts")) assertTrue(page.contains("<th scope=\"row\">$playlist</th>"), "the M3U providers list as playlists")
            val csrf = field(page, "csrf")

            val refused = browser.post("/admin/sources/services", "csrf" to csrf, "svc" to "pbs", "opt.pbs.zip" to "12")
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.bodyAsText().contains("ZIP code does not look right"))
            assertEquals(0, restarts)

            val asked = browser.post("/admin/sources/services", "csrf" to csrf, "svc" to "pluto", "svc" to "whale")
            assertEquals(HttpStatusCode.OK, asked.status)
            val dialog = asked.bodyAsText()
            assertTrue(dialog.contains("Save the services and restart?") && dialog.contains("Turns on Pluto TV, Whale TV+"), dialog.substringAfter("<div class=\"modal\">").take(800))
            assertTrue(dialog.contains("name=\"svc\" value=\"pluto\""), "the confirmation carries the form")
            assertEquals(0, restarts)
            assertEquals(4, runtime.setup!!.document().providers.size, "nothing is written before the confirmation")

            val saved = browser.post("/admin/sources/services", "csrf" to csrf, "svc" to "pluto", "svc" to "whale", "confirm" to "yes")
            assertEquals(HttpStatusCode.OK, saved.status)
            val restarting = saved.bodyAsText()
            assertTrue(restarting.contains("<title>Restarting") && restarting.contains("http-equiv=\"refresh\""), restarting.take(600))
            assertEquals(1, restarts)
            val written = runtime.setup!!.document()
            assertEquals(listOf("fixture", "alt", "twin", "ts", "whale", "pluto"), written.providers.map { it.id }, "new services follow the catalog's order")
            assertTrue(written.providers.takeLast(2).all { it.native })

            val removed = browser.post("/admin/sources/remove", "csrf" to csrf, "id" to "alt", "confirm" to "yes")
            assertEquals(HttpStatusCode.OK, removed.status)
            assertEquals(listOf("fixture", "twin", "ts", "whale", "pluto"), runtime.setup!!.document().providers.map { it.id })
            assertEquals(1, restarts, "one restart is pending for every write")
        }
    }

    @Test fun `without a policy one services save writes the source file and the starter lineup, with one restart`() = testApplication {
        var restarts = 0
        portal(restart = { restarts++ }) {
            browser.signIn()
            val page = browser.get("/admin/providers").bodyAsText()
            assertTrue(Regex("""name="starter"[^>]*value="on"[^>]*checked""").containsMatchIn(page), "the starter lineup is offered, ticked")
            val csrf = field(page, "csrf")
            val asked = browser.post("/admin/sources/services", "csrf" to csrf, "svc" to "pluto", "starter" to "on").bodyAsText()
            assertTrue(asked.contains("Builds the starter lineup from them") && asked.contains("name=\"starter\" value=\"on\""), asked.substringAfter("<div class=\"modal\">").take(800))
            assertFalse(Files.exists(runtime.setup!!.policyPath), "nothing is written before the confirmation")

            val saved = browser.post("/admin/sources/services", "csrf" to csrf, "svc" to "pluto", "starter" to "on", "confirm" to "yes").bodyAsText()
            assertTrue(saved.contains("and the starter lineup to ${runtime.setup!!.policyPath}"), saved.take(800))
            assertEquals(1, restarts)
            val policy = Json.parseToJsonElement(Files.readString(runtime.setup!!.policyPath)).jsonObject
            assertEquals(listOf("pluto", "fixture", "alt", "twin", "ts"), policy["providers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }, "the services lead, the playlists follow")
            assertFalse(browser.get("/admin/providers").bodyAsText().contains("Build the starter lineup too"), "the policy file is written, so the box is not offered again before the restart")
        }
    }

    @Test fun `the source file API builds the starter lineup in the same write when asked`() = testApplication {
        var restarts = 0
        portal(restart = { restarts++ }) {
            val document = """{"providers":[{"id":"pluto","type":"native","service":"pluto"}],"guides":[]}"""
            val written = client.put("/api/v1/admin/source-file?starterPolicy=true") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody(document) }
            assertEquals(HttpStatusCode.OK, written.status, written.bodyAsText())
            val result = Json.parseToJsonElement(written.bodyAsText()).jsonObject
            assertEquals(runtime.setup!!.policyPath.toString(), result["policyPath"]!!.jsonPrimitive.content)
            assertEquals(1, restarts)
            val again = client.put("/api/v1/admin/source-file?starterPolicy=true") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody(document) }
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/admin/source-file?starterPolicy=yes") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody(document) }.status)
        }
    }

    @Test fun `the home page offers to build the lineup while there is no policy, and asks before it does`() = testApplication {
        var restarts = 0
        portal(restart = { restarts++ }) {
            browser.signIn()
            val home = browser.get("/admin").bodyAsText()
            assertTrue(home.contains("<section id=\"setup\">") && home.contains("<li class=\"done\"><b>Add your sources</b>") && home.contains(">Build the lineup</button>"), home.substringAfter("id=\"setup\"").take(900))
            val csrf = field(home, "csrf")
            val asked = browser.post("/admin/policy/starter", "csrf" to csrf)
            assertTrue(asked.bodyAsText().contains("Build the lineup from every configured service?"))
            assertEquals(0, restarts)
            val built = browser.post("/admin/policy/starter", "csrf" to csrf, "confirm" to "yes")
            assertEquals(HttpStatusCode.OK, built.status)
            assertTrue(built.bodyAsText().contains("Wrote the lineup policy"))
            assertEquals(1, restarts)
            assertTrue(Files.exists(runtime.setup!!.policyPath))
            assertEquals(HttpStatusCode.Conflict, browser.post("/admin/policy/starter", "csrf" to csrf, "confirm" to "yes").status)
        }
    }

    @Test fun `the setup steps leave the home page once the server has sources and a policy`() = testApplication {
        portal(basePolicy) {
            browser.signIn()
            assertFalse(browser.get("/admin").bodyAsText().contains("<section id=\"setup\">"))
        }
    }

    @Test fun `the stylesheet is served without a session and every page links it by content version`() = testApplication {
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
            for (shown in listOf("SOFA_ADMIN_TOKEN", "SOFA_PLEX_URL", "http://127.0.0.1:32400", "policy.json", "Change log")) assertTrue(html.contains(shown), shown)
            assertTrue(html.contains("Twin Sports"), "the change log lists the stations the run added")
            assertTrue(html.contains("<details class=\"advanced\" id=\"advanced\">"), "the startup settings fold behind Advanced")
            assertTrue(browser.get("/admin/settings?advanced=1").bodyAsText().contains(Regex("""<details class="advanced" id="advanced" open(="[^"]*")?>""")), "advanced=1 opens the section")
            val sources = browser.get("/admin/providers").bodyAsText()
            for (secret in listOf(admin, token, outputToken, plexToken, query)) assertFalse(sources.contains(secret), "a secret reached the Sources page")
            assertTrue(sources.contains("headers: X-Key") && sources.contains("Source file"), "the Sources page describes the source file without its values")
        }
    }

    @Test fun `settings say which tokens were generated, read from a file or set in the environment`() = testApplication {
        val origins = mapOf(
            "SOFA_API_TOKEN" to SecretOrigin.GENERATED, "SOFA_ADMIN_TOKEN" to SecretOrigin.GENERATED,
            "SOFA_OUTPUT_TOKEN" to SecretOrigin.FILE, "SOFA_PLEX_TOKEN" to SecretOrigin.ENVIRONMENT,
        )
        portal(show = { it.copy(secretOrigins = origins, secretsFile = Path.of("/config/secrets.json"), foundInDataDirectory = setOf("SOFA_SOURCES")) }) {
            browser.signIn()
            val html = browser.get("/admin/settings").bodyAsText()
            fun row(name: String) = html.substringAfter(">$name<").substringBefore("class=\"setting\"")
            for (name in listOf("SOFA_API_TOKEN", "SOFA_ADMIN_TOKEN")) {
                assertTrue(row(name).contains(">generated<"), "$name: ${row(name)}")
                assertTrue(row(name).contains("Generated and stored in /config/secrets.json"), name)
            }
            assertTrue(row("SOFA_OUTPUT_TOKEN").contains(">set<") && row("SOFA_OUTPUT_TOKEN").contains("SOFA_OUTPUT_TOKEN_FILE"))
            assertTrue(row("SOFA_PLEX_TOKEN").contains(">set<") && row("SOFA_PLEX_TOKEN").contains("Set in the environment"))
            assertTrue(row("SOFA_SOURCES").contains("Found in SOFA_DATA_DIR"))
            for (secret in listOf(admin, token, outputToken, plexToken)) assertFalse(html.contains(secret), "a secret reached the page")
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
            for (secret in listOf(admin, outputToken, plexToken)) assertFalse(page.contains(secret), "only the API token is revealed")
            assertFalse(browser.get("/admin/settings").bodyAsText().contains(token), "the next page hides it again")
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
            assertEquals("/admin/settings?advanced=1#policy", applied.headers[HttpHeaders.Location])
            assertNotNull(runtime.policy.lastApplied())
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

    private fun policyJson(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject

    @Test fun `policy edits need the session and the CSRF token`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/groups").status)
            for (path in listOf("/admin/groups/edit", "/admin/providers/edit")) assertEquals(HttpStatusCode.Unauthorized, browser.post(path, "op" to "block").status, path)
            browser.signIn()
            val before = Files.readString(policyFile!!)
            val page = browser.get("/admin/groups").bodyAsText()
            for (path in listOf("/admin/groups/edit", "/admin/providers/edit")) {
                val refused = browser.post(path, "op" to "block", "group" to "News", "start" to "500", "revision" to field(page, "revision"), "confirm" to "yes")
                assertEquals(HttpStatusCode.Forbidden, refused.status, path)
                val notAForm = browser.post(path) { contentType(ContentType.Text.Plain); setBody("op=block") }
                assertEquals(HttpStatusCode.Forbidden, notAForm.status, "a body that is not a form is refused, not an error: $path")
            }
            val signIn = browser.post("/admin/login") { contentType(ContentType.Application.Json); setBody("""{"token":"$admin"}""") }
            assertEquals(HttpStatusCode.Unauthorized, signIn.status, "sign-in takes only a form")
            assertEquals(before, Files.readString(policyFile), "a refused edit writes nothing")
        }
    }

    @Test fun `accepting a merge folds the merged-away key's override into the kept one`() = testApplication {
        val stations = """{"numberonly":{"number":"870","required":false,"sources":[]},"keptnumber":{"number":"1976"},""" +
            """"named":{"name":"Renamed"},"plain":{"number":"40"},"both":{"name":"One"},"other":{"group":"Sports"}}"""
        portal(basePolicy.dropLast(1) + ""","stations":$stations}""") {
            browser.signIn()
            suspend fun accept(from: String, to: String): HttpResponse {
                val page = browser.get("/admin/groups").bodyAsText()
                return browser.post("/admin/merges/accept", "from" to from, "to" to to, "csrf" to field(page, "csrf"))
            }
            assertEquals(HttpStatusCode.SeeOther, accept("numberonly", "keptnumber").status)
            var saved = stationsIn(policyFile!!)
            assertNull(saved["numberonly"], "an override holding only a number goes with its key")
            assertEquals("1976", saved["keptnumber"]!!.jsonObject["number"]!!.jsonPrimitive.content)
            assertEquals("keptnumber", policyJson(policyFile)["names"]!!.jsonObject["aliases"]!!.jsonObject["numberonly"]!!.jsonPrimitive.content)

            assertEquals(HttpStatusCode.SeeOther, accept("named", "plain").status)
            saved = stationsIn(policyFile)
            assertNull(saved["named"])
            assertEquals("Renamed", saved["plain"]!!.jsonObject["name"]!!.jsonPrimitive.content, "other settings move to the kept key")
            assertEquals("40", saved["plain"]!!.jsonObject["number"]!!.jsonPrimitive.content, "the kept key's number stays")

            val before = Files.readString(policyFile)
            val refused = accept("both", "other")
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertTrue(refused.bodyAsText().contains("both have station settings"), "the reason shows inline")
            assertEquals(before, Files.readString(policyFile), "nothing is written")
        }
    }

    @Test fun `a number block is written into the policy and applied`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin/groups").bodyAsText()
            assertTrue(page.contains("Groups and number blocks") && page.contains("Category map"))
            val saved = browser.post("/admin/groups/edit", "op" to "block", "group" to "Sports", "start" to "500", "csrf" to field(page, "csrf"), "revision" to field(page, "revision"), "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertEquals("/admin/groups#blocks", saved.headers[HttpHeaders.Location])
            assertEquals(500, policyJson(policyFile!!)["numbering"]!!.jsonObject["groups"]!!.jsonObject["Sports"]!!.jsonPrimitive.int)
            assertEquals(Files.readString(policyFile).let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }, runtime.policy.document().revision)
            val after = browser.get("/admin/groups").bodyAsText()
            assertTrue(after.contains("Saved: number Sports from 500."), "the save is confirmed")
            assertTrue(after.contains(">Sports<"), "the block is listed")
        }
    }

    @Test fun `an invalid edit shows every policy error inline and writes nothing`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val before = Files.readString(policyFile!!)
            val page = browser.get("/admin/groups").bodyAsText()
            val response = browser.post("/admin/groups/edit", "op" to "pattern", "name" to "(", "group" to "News", "csrf" to field(page, "csrf"), "revision" to field(page, "revision"), "confirm" to "yes")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("The policy would not be valid") && body.contains("groups.patterns[0].name: invalid pattern"), body)
            assertEquals(before, Files.readString(policyFile))

            val unknown = browser.post("/admin/providers/edit", "op" to "add", "provider" to "nope", "csrf" to field(page, "csrf"), "revision" to field(page, "revision"), "confirm" to "yes")
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertTrue(unknown.bodyAsText().contains("unknown provider nope"))
            assertEquals(before, Files.readString(policyFile))
        }
    }

    @Test fun `an edit against a stale revision is held for confirmation against the current policy`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val before = Files.readString(policyFile!!)
            val page = browser.get("/admin/groups").bodyAsText()
            val stale = "0".repeat(64)
            val held = browser.post("/admin/groups/edit", "op" to "weak", "category" to "Featured", "csrf" to field(page, "csrf"), "revision" to stale, "confirm" to "yes")
            assertEquals(HttpStatusCode.Conflict, held.status)
            val body = held.bodyAsText()
            assertTrue(body.contains("The policy changed since this page loaded"), body)
            assertEquals(before, Files.readString(policyFile), "nothing is saved on a stale revision")
            val current = runtime.policy.document().revision
            assertTrue(body.contains("name=\"revision\" value=\"$current\""), "the confirm step carries the current revision")
            val saved = browser.post("/admin/groups/edit", "op" to "weak", "category" to "Featured", "csrf" to field(page, "csrf"), "revision" to current, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertEquals(Json.parseToJsonElement("""["featured"]"""), policyJson(policyFile)["groups"]!!.jsonObject["weak"])
        }
    }

    @Test fun `hiding a provider entry shows what turns off and saves only once confirmed`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin/providers").bodyAsText()
            assertTrue(page.contains("Priority"))
            val ts = runtime.lineup.entries().single { it.name == "TS Station" }
            assertTrue(ts.enabled)
            val fields = arrayOf("op" to "enabled", "index" to "3", "enabled" to "false", "csrf" to field(page, "csrf"), "revision" to field(page, "revision"))
            val asked = browser.post("/admin/providers/edit", *fields)
            assertEquals(HttpStatusCode.OK, asked.status)
            val dialog = asked.bodyAsText()
            assertTrue(dialog.contains("role=\"dialog\"") && dialog.contains("turn off") && dialog.contains("TS Station") && dialog.contains("Save and apply"), dialog)
            assertNull(policyJson(policyFile!!)["providers"]!!.jsonArray[3].jsonObject["enabled"], "nothing is saved before the confirm")
            val saved = browser.post("/admin/providers/edit", *fields, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertEquals(false, policyJson(policyFile)["providers"]!!.jsonArray[3].jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertFalse(runtime.lineup.entry(ts.id)!!.enabled, "the apply turned the station off")

            val moved = browser.post("/admin/providers/edit", "op" to "move", "index" to "1", "to" to "up", "csrf" to field(page, "csrf"), "revision" to runtime.policy.document().revision, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, moved.status, moved.bodyAsText())
            assertEquals(listOf("alt", "fixture", "twin", "ts"), policyJson(policyFile)["providers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        }
    }

    @Test fun `an attention item can be ignored until its text changes, and shown again`() = testApplication {
        portal(basePolicy, guides = { listOf(it.brokenGuide) }) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin").bodyAsText()
            val csrf = field(page, "csrf")
            val fingerprint = Regex("""name="key" value="guides-failed"><input type="hidden" name="fingerprint" value="([0-9a-f]{16})"""").find(page)?.groupValues?.get(1)
            assertNotNull(fingerprint, page)
            assertTrue(page.contains("Ignore until it changes: Guide broken has never imported"))

            val ignored = browser.post("/admin/attention/ignore", "key" to "guides-failed", "fingerprint" to fingerprint, "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, ignored.status, ignored.bodyAsText())
            val after = browser.get("/admin").bodyAsText()
            assertTrue(after.contains("Ignored until it changes (1)"), after)
            assertTrue(after.contains("Show again: Guide broken has never imported"), "the item sits under the ignored list")
            assertFalse(after.contains("Ignore until it changes: Guide broken"), "and not in the main list")

            val shown = browser.post("/admin/attention/show", "key" to "guides-failed", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, shown.status)
            assertTrue(browser.get("/admin").bodyAsText().contains("Ignore until it changes: Guide broken has never imported"))
        }
    }

    @Test fun `the channels table sorts by column, tints rows without a healthy source and filters overrides`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val plain = browser.get("/admin/lineup").bodyAsText()
            assertTrue(plain.contains("<caption class=\"sr\">Channels</caption>"), "every table has a caption")
            assertTrue(plain.contains("<th class=\"num\" scope=\"col\">"), "headers carry their scope")
            assertTrue(plain.contains("class=\"tint-bad\""), "an on-air station with no healthy source is tinted")
            fun names(html: String) = Regex("""class="rowlink">([^<]+)</a>""").findAll(html).map { it.groupValues[1] }.toList()
            val desc = browser.get("/admin/lineup?sort=name&dir=desc").bodyAsText()
            assertTrue(desc.contains("aria-sort=\"descending\""), desc.take(2000))
            assertEquals(names(desc).sortedWith(String.CASE_INSENSITIVE_ORDER).reversed(), names(desc))
            val asc = browser.get("/admin/lineup?sort=name").bodyAsText()
            assertEquals(names(asc).sortedWith(String.CASE_INSENSITIVE_ORDER), names(asc))
            assertTrue(asc.contains("href=\"/admin/lineup?sort=name&amp;dir=desc\""), "the sorted header's link flips the direction")

            assertEquals(0, Regex("""id="row-""").findAll(browser.get("/admin/lineup?override=true").bodyAsText()).count(), "no station has an override yet")
            val id = stationId("twin sports")
            val hidden = browser.post("/admin/lineup/$id/visibility", "enabled" to "false", "csrf" to field(plain, "csrf"), "revision" to field(plain, "revision"))
            assertEquals(HttpStatusCode.SeeOther, hidden.status)
            val overridden = browser.get("/admin/lineup?override=true").bodyAsText()
            assertEquals(1, Regex("""id="row-""").findAll(overridden).count())
            assertTrue(overridden.contains("Twin Sports") && overridden.contains(">Override<"), overridden)
            val changed = browser.get("/admin/lineup?changed=true").bodyAsText()
            assertTrue(changed.contains("Twin Sports"), "the hide was the last run's change")
        }
    }

    @Test fun `the jobs page lists every import with its result, and Run now starts one`() = testApplication {
        portal(basePolicy, guides = { listOf(it.brokenGuide) }) {
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/system/jobs").status)
            browser.signIn()
            val html = browser.get("/admin/system/jobs").bodyAsText()
            for (label in listOf("Import fixture", "Import alt", "Guide broken", "Lineup policy run", "Logo sweep")) assertTrue(html.contains(label), label)
            assertTrue(html.contains(">OK<") && html.contains(">Failed<"), "the fixture imports worked and the broken guide did not")
            assertTrue(html.contains("IMPORT_FAILED"), html)
            assertTrue(html.contains("aria-label=\"Run Import fixture now\""))
            assertFalse(html.contains("/feed.m3u") || html.contains(token) || html.contains(admin))
            val csrf = field(html, "csrf")
            val started = browser.post("/admin/system/jobs/import:fixture/run", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, started.status, started.bodyAsText())
            assertTrue(browser.get("/admin/system/jobs").bodyAsText().contains("Started Import fixture."))
            browser.post("/admin/system/jobs/nothing/run", "csrf" to csrf)
            assertTrue(browser.get("/admin/system/jobs").bodyAsText().contains("No such job to run."))
        }
    }

    @Test fun `the events page lists what the server logged, filtered by level`() = testApplication {
        portal {
            val marker = "portal-events-" + System.nanoTime()
            org.slf4j.LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.http.PortalEvents").warn("something to see {}", marker)
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/system/events").status)
            browser.signIn()
            val all = browser.get("/admin/system/events").bodyAsText()
            assertTrue(all.contains("something to see $marker"), all.take(3000))
            assertTrue(all.contains("<caption class=\"sr\">Log events, newest first</caption>"))
            assertTrue(all.contains("hx-trigger=\"every 10s\""), "the list refreshes itself with JavaScript")
            assertTrue(browser.get("/admin/system/events?level=warn&q=$marker").bodyAsText().contains("something to see $marker"))
            assertFalse(browser.get("/admin/system/events?level=error&q=$marker").bodyAsText().contains("something to see $marker"))
        }
    }

    @Test fun `the about page describes the server and the support snapshot holds no secret`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/system/snapshot.json").status, "the snapshot needs the session")
            browser.signIn()
            val html = browser.get("/admin/system/about").bodyAsText()
            for (text in listOf("Revision", "FFmpeg", "Support snapshot", "&quot;takenAt&quot;", "Lineup policy run")) assertTrue(html.contains(text), text)
            assertFalse(html.contains(admin) || html.contains(token) || html.contains(outputToken) || html.contains(plexToken) || html.contains("/feed.m3u"))
            val snapshot = browser.get("/admin/system/snapshot.json")
            assertEquals(HttpStatusCode.OK, snapshot.status)
            assertEquals(ContentType.Application.Json, snapshot.contentType()?.withoutParameters())
            val body = Json.parseToJsonElement(snapshot.bodyAsText()).jsonObject
            assertEquals(4, body["status"]!!.jsonObject["providers"]!!.jsonObject["total"]!!.jsonPrimitive.int)
            assertTrue(body["jobs"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "policy" })
            assertFalse(snapshot.bodyAsText().contains(admin) || snapshot.bodyAsText().contains(token))
        }
    }

    @Test fun `the about page shows the update picture and Update now hands the host's updater a request after a confirmation`() = testApplication {
        portal {
            browser.signIn()
            val before = browser.get("/admin/system/about").bodyAsText()
            assertTrue(before.contains("<h2>Update</h2>"), before.take(3000))
            assertTrue(before.contains("The update check is off"), "no image, no registry check")
            assertTrue(before.contains("Has not reported"), "no status file yet")
            assertFalse(before.contains("/admin/system/update/request"), "no updater, no Update now")
            Files.writeString(
                dataDirectory.resolve("update-status.json"),
                """{"checkedAt":"2026-01-01T00:00:00Z","image":"ghcr.io/jamesarich/sofa-king:main","running":"abc1234","candidate":"def5678","outcome":"rolled-back","message":"def5678 was not healthy after 180s; running abc1234 again"}""",
            )
            val reported = browser.get("/admin/system/about").bodyAsText()
            assertTrue(reported.contains("Tried def5678 and went back to abc1234"), reported.take(4000))
            assertTrue(reported.contains("Not checking in"), "a report that old is flagged")
            assertTrue(reported.contains("def5678 was not healthy after 180s"))
            val ask = browser.post("/admin/system/update/request", "csrf" to field(reported, "csrf"))
            assertEquals(HttpStatusCode.OK, ask.status)
            val dialog = ask.bodyAsText()
            assertTrue(dialog.contains("Update the server now?") && dialog.contains("backs up the database"), dialog.take(4000))
            assertFalse(Files.exists(dataDirectory.resolve("update-requested")), "nothing is written before the yes")
            val yes = browser.post("/admin/system/update/request", "csrf" to field(reported, "csrf"), "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, yes.status)
            assertEquals("/admin/system/about#update", yes.headers[HttpHeaders.Location])
            assertTrue(Files.isRegularFile(dataDirectory.resolve("update-requested")))
            val pending = browser.get("/admin/system/about").bodyAsText()
            assertTrue(pending.contains("Asked the updater to run") && pending.contains("An update was asked for"), pending.take(4000))
            assertFalse(pending.contains("""action="/admin/system/update/request""""), "one request at a time")
            assertEquals(HttpStatusCode.Forbidden, browser.post("/admin/system/update/request", "confirm" to "yes").status, "the request needs the CSRF token")
        }
    }

    @Test fun `the theme cookie pins a colour scheme on every page, and the nav folds into a menu`() = testApplication {
        portal {
            browser.signIn()
            val page = browser.get("/admin/lineup").bodyAsText()
            assertFalse(page.contains("data-theme="), "with no cookie the device decides")
            assertTrue(page.contains("<details class=\"menu\"><summary>Menu"), page.take(1500))
            assertTrue(page.contains("<form action=\"/admin/theme\" method=\"post\" class=\"inline theme\""))
            val pinned = browser.post("/admin/theme", "theme" to "light", "back" to "/admin/lineup?q=twin", "csrf" to field(page, "csrf"))
            assertEquals(HttpStatusCode.SeeOther, pinned.status)
            assertEquals("/admin/lineup?q=twin", pinned.headers[HttpHeaders.Location])
            assertTrue(pinned.headers[HttpHeaders.SetCookie]!!.startsWith("sofa_theme=light;"))
            val light = browser.get("/admin/plex").bodyAsText()
            assertTrue(light.contains("<html lang=\"en\" data-theme=\"light\">"), light.take(200))
            assertTrue(light.contains("<meta name=\"theme-color\" content=\"#F7F4FC\">"))
            assertTrue(light.contains("value=\"light\" aria-pressed=\"true\""))
            val elsewhere = browser.post("/admin/theme", "theme" to "auto", "back" to "https://evil.example/", "csrf" to field(page, "csrf"))
            assertEquals("/admin", elsewhere.headers[HttpHeaders.Location], "only a portal path is returned to")
            assertFalse(browser.get("/admin").bodyAsText().contains("data-theme="))
        }
    }

    @Test fun `watching now lists each session with its player, and Stop ends it after a confirmation`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val station = runtime.lineup.entries().single { it.name == "Station" && runtime.feeds.offering(it.offeringIds.first())?.providerId == "fixture" }
            val opened = browser.post("/api/v1/playback-sessions") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${station.id}","intent":"watch","idempotencyKey":"portal-watch"}""")
            }
            assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
            val sessionId = Json.parseToJsonElement(opened.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
            val overview = browser.get("/admin").bodyAsText()
            assertTrue(overview.contains("1 session") && overview.contains("Sofa King app") && overview.contains("localhost"), "the session, its player and its address are listed: $overview")
            assertTrue(overview.contains("/admin/lineup/${station.id}") && overview.contains("via fixture"), "the channel links to its page and names its source")
            if (station.guide != null) assertTrue(overview.contains("Morning"), "the programme on now is named")
            assertTrue(overview.contains("/admin/sessions/$sessionId/stop"), "each session has a Stop")
            val listed = Json.parseToJsonElement(browser.get("/api/v1/admin/sessions") { bearerAuth(admin) }.bodyAsText()).jsonArray.single().jsonObject
            assertEquals("localhost", listed["client"]!!.jsonPrimitive.content)
            assertEquals(0, listed["bytes"]!!.jsonPrimitive.int)

            val csrf = field(overview, "csrf")
            val asked = browser.post("/admin/sessions/$sessionId/stop", "csrf" to csrf)
            assertEquals(HttpStatusCode.OK, asked.status)
            assertTrue(asked.bodyAsText().contains("role=\"dialog\"") && asked.bodyAsText().contains("Stop the session on Station?"), "stopping asks first")
            assertEquals(1, runtime.sessions.count(), "nothing stops before the confirm")
            val stopped = browser.post("/admin/sessions/$sessionId/stop", "csrf" to csrf, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, stopped.status)
            assertEquals(0, runtime.sessions.count())
            assertTrue(browser.get("/admin").bodyAsText().contains("Stopped the session on Station."))
            val again = browser.post("/admin/sessions/$sessionId/stop", "csrf" to csrf, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, again.status, "a session that already ended is reported, not an error")
        }
    }

    @Test fun `a source is disabled from the channel page after a confirmation, and enabled again at once`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val station = runtime.lineup.entries().single { it.name == "Station" && runtime.feeds.offering(it.offeringIds.first())?.providerId == "fixture" }
            val offeringId = station.offeringIds.first()
            val page = browser.get("/admin/lineup/${station.id}?q=stat").bodyAsText()
            assertTrue(page.contains("/admin/lineup/${station.id}/sources/$offeringId/disable") && page.contains(">Disable<"), page)
            assertTrue(page.contains("Probes are off on this server"), "no Probe now without a probe worker")
            val csrf = field(page, "csrf")
            val asked = browser.post("/admin/lineup/${station.id}/sources/$offeringId/disable", "disabled" to "true", "q" to "stat", "csrf" to csrf)
            assertEquals(HttpStatusCode.OK, asked.status)
            assertTrue(asked.bodyAsText().contains("role=\"dialog\"") && asked.bodyAsText().contains("Disable source 1 from fixture?"), asked.bodyAsText())
            assertFalse(runtime.store.health(offeringId)!!.manuallyDisabled, "nothing changes before the confirm")
            val disabled = browser.post("/admin/lineup/${station.id}/sources/$offeringId/disable", "disabled" to "true", "q" to "stat", "csrf" to csrf, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, disabled.status, disabled.bodyAsText())
            assertEquals("/admin/lineup/${station.id}?q=stat", disabled.headers[HttpHeaders.Location])
            assertTrue(runtime.store.health(offeringId)!!.manuallyDisabled)
            val after = browser.get("/admin/lineup/${station.id}").bodyAsText()
            assertTrue(after.contains("Disabled the source from fixture.") && after.contains("Disabled by hand") && after.contains(">Enable<"), after)
            val enabled = browser.post("/admin/lineup/${station.id}/sources/$offeringId/disable", "disabled" to "false", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, enabled.status)
            assertFalse(runtime.store.health(offeringId)!!.manuallyDisabled)
            val probe = browser.post("/admin/lineup/${station.id}/sources/$offeringId/probe", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, probe.status)
            assertTrue(browser.get("/admin/lineup/${station.id}").bodyAsText().contains("Probes are off on this server."))
        }
    }

    @Test fun `a bulk edit writes every selected station's override in one policy edit, once confirmed`() = testApplication {
        portal(basePolicy) {
            runtime.policy!!.apply("test")
            browser.signIn()
            val page = browser.get("/admin/lineup").bodyAsText()
            assertTrue(page.contains("form=\"bulk\"") && page.contains("Select all on this page"), "every row has a checkbox of the bulk form")
            assertFalse(page.contains("checked"), "nothing is selected at first")
            assertTrue(browser.get("/admin/lineup?select=page").bodyAsText().contains("checked"), "the link selects the page")
            val ids = listOf(stationId("twin sports"), stationId("ts station"))
            val csrf = field(page, "csrf")
            val revision = field(page, "revision")
            val none = browser.post("/admin/lineup/bulk", "op" to "hide", "csrf" to csrf, "revision" to revision)
            assertEquals(HttpStatusCode.BadRequest, none.status)
            assertTrue(none.bodyAsText().contains("Select at least one channel first."))

            val fields = arrayOf("op" to "hide", "id" to ids[0], "id" to ids[1], "csrf" to csrf, "revision" to revision)
            val asked = browser.post("/admin/lineup/bulk", *fields)
            assertEquals(HttpStatusCode.OK, asked.status)
            val dialog = asked.bodyAsText()
            assertTrue(dialog.contains("Save: hide 2 channels?") && dialog.contains("2 channels turn off") && dialog.contains("Twin Sports") && dialog.contains("TS Station"), dialog)
            assertTrue(dialog.contains("value=\"${ids[0]}\" form=\"bulk\" aria-label=\"Select Twin Sports\" checked"), "the selection is kept while the edit waits")
            assertTrue(stationsIn(policyFile!!).values.none { "enabled" in it.jsonObject }, "nothing is saved before the confirm; the run only learns numbers")
            val saved = browser.post("/admin/lineup/bulk", *fields, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertEquals("/admin/lineup", saved.headers[HttpHeaders.Location])
            val stations = stationsIn(policyFile)
            assertEquals(false, stations.getValue("twin sports").jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertEquals(false, stations.getValue("ts station").jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertFalse(runtime.lineup.entry(ids[0])!!.enabled); assertFalse(runtime.lineup.entry(ids[1])!!.enabled)
            assertTrue(browser.get("/admin/lineup").bodyAsText().contains("Saved: hide 2 channels."))

            val grouped = browser.post("/admin/lineup/bulk", "op" to "group", "target" to "Sports", "id" to ids[0], "q" to "twin", "csrf" to csrf, "revision" to runtime.policy.document().revision, "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, grouped.status, grouped.bodyAsText())
            assertEquals("/admin/lineup?q=twin", grouped.headers[HttpHeaders.Location], "the filters survive the edit")
            assertEquals("Sports", stationsIn(policyFile).getValue("twin sports").jsonObject["group"]!!.jsonPrimitive.content)
            assertEquals("Sports", runtime.lineup.entry(ids[0])!!.group)
        }
    }

    @Test fun `without a policy a bulk edit changes the lineup entries after a confirmation`() = testApplication {
        portal {
            runtime.adoptAll()
            browser.signIn()
            val page = browser.get("/admin/lineup").bodyAsText()
            val entries = runtime.lineup.entries().filter { it.name in setOf("TS Station", "Twin Sports") }
            val fields = entries.map { "id" to it.id }.toTypedArray()
            val asked = browser.post("/admin/lineup/bulk", "op" to "hide", *fields, "csrf" to field(page, "csrf"))
            assertEquals(HttpStatusCode.OK, asked.status)
            assertTrue(asked.bodyAsText().contains("2 channels turn off"), asked.bodyAsText())
            assertTrue(entries.all { runtime.lineup.entry(it.id)!!.enabled })
            val saved = browser.post("/admin/lineup/bulk", "op" to "hide", *fields, "csrf" to field(page, "csrf"), "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertTrue(entries.none { runtime.lineup.entry(it.id)!!.enabled })
            val regrouped = browser.post("/admin/lineup/bulk", "op" to "group", "target" to "News", *fields, "csrf" to field(page, "csrf"), "confirm" to "yes")
            assertEquals(HttpStatusCode.SeeOther, regrouped.status)
            assertTrue(entries.all { runtime.lineup.entry(it.id)!!.group == "News" })
        }
    }

    @Test fun `runtime settings are saved from the settings page, apply at once and refuse bad values`() = testApplication {
        portal(basePolicy) {
            browser.signIn()
            val page = browser.get("/admin/settings").bodyAsText()
            assertTrue(page.contains("Runtime settings") && page.contains("Startup settings") && input(page, "SOFA_XMLTV_DAYS", "3"), page)
            assertTrue(page.contains("No changes") && page.contains("hx-post=\"/admin/settings/runtime/check\""), "the Save control starts disabled and the form asks on change")
            assertFalse(Regex("""<div class="name mono">SOFA_MAX_SESSIONS</div>""").containsMatchIn(page), "a runtime setting is not listed among the startup ones")
            val csrf = field(page, "csrf")
            val values = mapOf("SOFA_LOG_LEVEL" to "info", "SOFA_XMLTV_DAYS" to "3", "SOFA_PLEX_SYNC_MINUTES" to "60", "SOFA_HDHR_TUNERS" to "2", "SOFA_MAX_SESSIONS" to "4", "SOFA_RECORDINGS_FREE_GIB" to "10", "SOFA_RECORDINGS_KEEP_DAYS" to "0", "SOFA_LEARNED_BREAKS" to "shadow", "SOFA_AD_LISTENERS" to "6")
            fun fields(vararg changes: Pair<String, String>) = (values + changes).map { it.key to it.value }.toTypedArray()
            val unchanged = browser.post("/admin/settings/runtime/check", *fields(), "csrf" to csrf).bodyAsText()
            assertTrue(unchanged.contains("No changes") && unchanged.contains("disabled"), unchanged)
            val changed = browser.post("/admin/settings/runtime/check", *fields("SOFA_XMLTV_DAYS" to "7"), "csrf" to csrf).bodyAsText()
            assertTrue(changed.contains(">Save<") && !changed.contains("disabled"), changed)

            val refused = browser.post("/admin/settings/runtime", *fields("SOFA_HDHR_TUNERS" to "9"), "op" to "save", "csrf" to csrf)
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.bodyAsText().contains("SOFA_HDHR_TUNERS must be between 1 and SOFA_MAX_SESSIONS (4)"), refused.bodyAsText())
            assertTrue(input(refused.bodyAsText(), "SOFA_HDHR_TUNERS", "9"), "the refused values stay in the form")
            assertEquals(2, runtime.settings.current.hdhrTuners)

            val saved = browser.post("/admin/settings/runtime", *fields("SOFA_XMLTV_DAYS" to "7", "SOFA_LOG_LEVEL" to "warn", "SOFA_MAX_SESSIONS" to "6", "SOFA_HDHR_TUNERS" to "5"), "op" to "save", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, saved.status, saved.bodyAsText())
            assertEquals("/admin/settings#runtime", saved.headers[HttpHeaders.Location])
            with(runtime.settings.current) { assertEquals(7, guideDays); assertEquals("warn", logLevel); assertEquals(6, maxSessions); assertEquals(5, hdhrTuners); assertEquals(60, plexSyncMinutes) }
            assertEquals(setOf("SOFA_XMLTV_DAYS", "SOFA_LOG_LEVEL", "SOFA_MAX_SESSIONS", "SOFA_HDHR_TUNERS"), runtime.settings.saved)
            assertEquals(6, runtime.sessions.capacity, "the session cap follows at once")
            val after = browser.get("/admin/settings").bodyAsText()
            assertTrue(after.contains("Saved the runtime settings; they apply now.") && after.contains("Saved here; the environment has 3.") && input(after, "SOFA_XMLTV_DAYS", "7"), after)
            assertTrue(browser.get("/admin/system/about").bodyAsText().contains("warn, text"), "About reports the level in use")

            val reset = browser.post("/admin/settings/runtime", "op" to "reset", "csrf" to csrf)
            assertEquals(HttpStatusCode.SeeOther, reset.status)
            assertEquals(4, runtime.settings.current.maxSessions)
            assertTrue(runtime.settings.saved.isEmpty())
            assertTrue(browser.get("/admin/settings").bodyAsText().contains("follow the environment again"))
            assertEquals(HttpStatusCode.Forbidden, browser.post("/admin/settings/runtime", *fields(), "op" to "save").status, "the save needs the CSRF token")
        }
    }

    @Test fun `the API page renders the OpenAPI document behind the session`() = testApplication {
        portal {
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/api").status)
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/api/openapi.json").status, "the document needs the session too")
            browser.signIn()
            val page = browser.get("/admin/api")
            assertEquals(HttpStatusCode.OK, page.status)
            val html = page.bodyAsText()
            for (shown in listOf("/api/v1/channels", "/api/v1/admin/sessions", "/output/guide.xml", "PATCH", "Takes: admin token", "Download the OpenAPI document", "id=\"api-playback\"")) assertTrue(html.contains(shown), shown)
            assertTrue(html.contains("aria-current=\"page\">API<"), "the nav marks the page")
            val document = browser.get("/admin/api/openapi.json")
            assertEquals(HttpStatusCode.OK, document.status)
            assertEquals(ContentType.Application.Json, document.contentType()?.withoutParameters())
            val spec = Json.parseToJsonElement(document.bodyAsText()).jsonObject
            assertEquals("3.1.0", spec["openapi"]!!.jsonPrimitive.content)
            val paths = spec["paths"]!!.jsonObject.keys
            for (path in listOf("/healthz", "/api/v1/bootstrap", "/api/v1/playback-sessions/{id}", "/api/v1/admin/policy/apply", "/auto/v{number}")) assertTrue(path in paths, path)
        }
    }

    @Test fun `the viewers page issues a pairing code with its QR code, lists devices and forgets one`() = testApplication {
        portal {
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/viewers").status)
            browser.signIn()
            val empty = browser.get("/admin/viewers")
            assertEquals(HttpStatusCode.OK, empty.status)
            val emptyHtml = empty.bodyAsText()
            assertTrue(emptyHtml.contains("Show a pairing code"))
            assertTrue(emptyHtml.contains("No device has paired"))
            assertFalse(emptyHtml.contains("pairing-code"))
            assertTrue(emptyHtml.contains("aria-current=\"page\">Devices<"), "the nav marks the page")

            val issued = browser.post("/admin/viewers/pair", "csrf" to field(emptyHtml, "csrf"))
            assertEquals(HttpStatusCode.OK, issued.status)
            val issuedHtml = issued.bodyAsText()
            val code = runtime.pairing.current()!!.code
            assertTrue(issuedHtml.contains(spacedCode(code)), "the code is on the page")
            assertTrue(issuedHtml.contains("<svg") && issuedHtml.contains("Pairing code $code"), "the QR code is drawn inline")
            assertTrue(issuedHtml.contains("Pairing code $code for http://"), "the QR is labelled with the code and the address")
            assertTrue(issuedHtml.contains("Cancel the code"))
            assertTrue(browser.get("/admin/viewers").bodyAsText().contains(spacedCode(code)), "a reload shows the live code")

            // A device claims the code and saves its preferences; it then appears with them.
            val api = createClient { }
            val paired = api.post("/api/v1/pair") { contentType(ContentType.Application.Json); setBody("""{"code":"$code","clientId":"den-tv","name":"Den TV","platform":"android-tv"}""") }
            assertEquals(HttpStatusCode.OK, paired.status)
            api.put("/api/v1/preferences/den-tv") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"hidden":["a","b"],"favorites":["c"],"recent":["c","d","e"]}""") }
            val listed = browser.get("/admin/viewers").bodyAsText()
            assertFalse(listed.contains("pairing-code"), "the claimed code is gone")
            for (shown in listOf("Den TV", "den-tv", "android-tv", "id=\"viewer-den-tv\"")) assertTrue(listed.contains(shown), shown)
            assertFalse(listed.contains(token), "the API token never reaches the page")

            val forgot = browser.post("/admin/viewers/den-tv/forget", "csrf" to field(listed, "csrf"))
            assertEquals(HttpStatusCode.SeeOther, forgot.status)
            assertTrue(browser.get("/admin/viewers").bodyAsText().contains("No device has paired"))
            assertEquals(HttpStatusCode.NotFound, api.get("/api/v1/preferences/den-tv") { bearerAuth(token) }.status)
        }
    }

    @Test fun `the library page adds, scans, renames and removes a library without touching its files`() = testApplication {
        portal {
            val movies = Files.createDirectories(dataDirectory.resolve("media/movies/Alien (1979)")).parent
            Files.writeString(movies.resolve("Alien (1979)/Alien (1979).mkv"), "not really a movie")
            browser.signIn()
            val page = browser.get("/admin/library").bodyAsText()
            assertTrue(page.contains("No library yet"), page)
            assertTrue(page.contains(">Library<"), "the nav names the destination")
            assertTrue(page.contains("""value="$movies""""), "the folder is offered")
            val csrf = field(page, "csrf")

            val refused = browser.post("/admin/library", "csrf" to csrf, "name" to "", "kind" to "movies", "path" to movies.toString())
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.bodyAsText().contains("1 to 100 characters"))

            assertEquals(HttpStatusCode.SeeOther, browser.post("/admin/library", "csrf" to csrf, "name" to "Movies", "kind" to "movies", "path" to movies.toString()).status)
            val library = runtime.libraries!!.libraries().single()
            runtime.libraries!!.scan(library.id)
            val listed = browser.get("/admin/library").bodyAsText()
            assertTrue(listed.contains("1 movie"), listed)

            browser.post("/admin/library/${library.id}/rename", "csrf" to csrf, "name" to "Films")
            assertEquals("Films", runtime.libraries!!.library(library.id).name)

            val ask = browser.post("/admin/library/${library.id}/remove", "csrf" to csrf)
            assertTrue(ask.bodyAsText().contains("Remove Films?"), "removal asks first")
            assertEquals(1, runtime.libraries!!.libraries().size)
            browser.post("/admin/library/${library.id}/remove", "csrf" to csrf, "confirm" to "yes")
            assertTrue(runtime.libraries!!.libraries().isEmpty())
            assertTrue(Files.exists(movies.resolve("Alien (1979)/Alien (1979).mkv")), "the files stay")
        }
    }

    @Test fun `the recordings page adds and deletes rules, protects and deletes recordings, and matches the API`() = testApplication {
        portal {
            browser.signIn()
            val empty = browser.get("/admin/recordings").bodyAsText()
            assertTrue(empty.contains("Nothing is scheduled") && empty.contains("No recordings yet") && empty.contains("Keeps 10 GiB free"), empty)
            assertTrue(empty.contains("aria-current=\"page\">Recordings<"), "the nav marks the destination")

            val added = browser.post("/admin/recordings/rules", "title" to "Evening News", "channelId" to "", "keep" to "3", "newOnly" to "true", "csrf" to field(empty, "csrf"))
            assertEquals(HttpStatusCode.SeeOther, added.status)
            val withRule = browser.get("/admin/recordings").bodyAsText()
            assertTrue(withRule.contains("Added the rule for Evening News") && withRule.contains("Any channel"), withRule)
            val refused = browser.post("/admin/recordings/rules", "title" to " ", "keep" to "3", "csrf" to field(withRule, "csrf"))
            assertEquals(HttpStatusCode.SeeOther, refused.status)
            assertTrue(browser.get("/admin/recordings").bodyAsText().contains("Not added: A rule needs a title"))

            val api = createClient { }
            val rules = Json.parseToJsonElement(api.get("/api/v1/recording-rules") { bearerAuth(token) }.bodyAsText()).jsonObject["rules"]!!.jsonArray
            assertEquals(1, rules.size)
            val ruleId = rules.single().jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(3, rules.single().jsonObject["keep"]!!.jsonPrimitive.int)
            assertTrue(withRule.contains("1 min before, 2 min after"), withRule)

            // Editing sets the padding and priority the add form left at their defaults.
            browser.post(
                "/admin/recordings/rules/$ruleId", "channelId" to "", "keep" to "5", "startPadding" to "3", "endPadding" to "10", "priority" to "7",
                "csrf" to field(withRule, "csrf"),
            )
            val edited = browser.get("/admin/recordings").bodyAsText()
            assertTrue(edited.contains("Saved the rule for Evening News") && edited.contains("3 min before, 10 min after") && edited.contains("All airings"), edited)
            val rule = Json.parseToJsonElement(api.get("/api/v1/recording-rules") { bearerAuth(token) }.bodyAsText()).jsonObject["rules"]!!.jsonArray.single().jsonObject
            assertEquals(180, rule["startPaddingSeconds"]!!.jsonPrimitive.int)
            assertEquals(600, rule["endPaddingSeconds"]!!.jsonPrimitive.int)
            assertEquals(7, rule["priority"]!!.jsonPrimitive.int)
            assertEquals(5, rule["keep"]!!.jsonPrimitive.int)
            assertFalse(rule["newOnly"]!!.jsonPrimitive.boolean)

            // A time window records any enabled channel, guide or not.
            runtime.adoptAll()
            val channelId = runtime.lineup.entries().first { it.enabled }.id
            val badWindow = browser.post("/admin/recordings/manual", "channelId" to channelId, "start" to "", "minutes" to "30", "csrf" to field(edited, "csrf"))
            assertEquals(HttpStatusCode.SeeOther, badWindow.status)
            assertTrue(browser.get("/admin/recordings").bodyAsText().contains("Not scheduled: Pick a start date and time"))
            val startsAt = java.time.LocalDateTime.now().plusDays(1).withSecond(0).withNano(0)
            browser.post("/admin/recordings/manual", "channelId" to channelId, "start" to startsAt.toString(), "minutes" to "45", "title" to "Late Game", "csrf" to field(edited, "csrf"))
            val manualPage = browser.get("/admin/recordings").bodyAsText()
            assertTrue(manualPage.contains("Scheduled Late Game"), manualPage)
            val manual = Json.parseToJsonElement(api.get("/api/v1/recordings") { bearerAuth(token) }.bodyAsText()).jsonObject["recordings"]!!.jsonArray
                .single { it.jsonObject["title"]!!.jsonPrimitive.content == "Late Game" }.jsonObject
            val start = java.time.Instant.parse(manual["startsAt"]!!.jsonPrimitive.content)
            assertEquals(startsAt.atZone(java.time.ZoneId.systemDefault()).toInstant(), start)
            assertEquals(start.plusSeconds(45 * 60), java.time.Instant.parse(manual["endsAt"]!!.jsonPrimitive.content))
            browser.post("/admin/recordings/${manual["id"]!!.jsonPrimitive.content}/delete", "csrf" to field(manualPage, "csrf"))

            val now = java.time.Instant.parse("2026-10-17T12:00:00Z")
            runtime.store.addRecording(dev.jamesrich.sofaking.backend.dvr.StoredRecording(
                "rec-1", null, "c", "Channel C", now, now.plusSeconds(1800), java.time.Duration.ZERO, java.time.Duration.ZERO, "Old Movie",
                state = Wire.RecordingState.COMPLETED, path = "Movies/Old Movie (1950)/Old Movie (1950).ts", bytes = 2_000_000, createdAt = now, updatedAt = now,
            ))
            val listed = browser.get("/admin/recordings").bodyAsText()
            assertTrue(listed.contains("id=\"recording-rec-1\"") && listed.contains("Old Movie (1950).ts") && listed.contains("Recorded"), listed)
            browser.post("/admin/recordings/rec-1/protect", "protected" to "true", "csrf" to field(listed, "csrf"))
            val protectedPage = browser.get("/admin/recordings").bodyAsText()
            assertTrue(protectedPage.contains("cleanup keeps it") && protectedPage.contains("Unprotect"), protectedPage)
            val wire = Json.parseToJsonElement(api.get("/api/v1/recordings/rec-1") { bearerAuth(token) }.bodyAsText()).jsonObject
            assertTrue(wire["protected"]!!.jsonPrimitive.boolean)

            browser.post("/admin/recordings/rec-1/delete", "csrf" to field(protectedPage, "csrf"))
            browser.post("/admin/recordings/rules/$ruleId/delete", "csrf" to field(protectedPage, "csrf"))
            val cleared = browser.get("/admin/recordings").bodyAsText()
            assertTrue(cleared.contains("No recordings yet") && !cleared.contains("Evening News"), cleared)
            assertEquals(HttpStatusCode.NotFound, api.get("/api/v1/recordings/rec-1") { bearerAuth(token) }.status)
        }
    }
}

package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.main
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.content
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Setup from the portal: the home page, the overview, sources and imports. */
class AdminPortalSetupTest : AdminPortalCase() {
    @Test fun `the home page says when the server cannot write the recordings folder`() = testApplication {
        portal {
            browser.signIn()
            assertFalse(browser.get("/admin").bodyAsText().contains("cannot write the recordings folder"))
            val root = runtime.dvr!!.root
            root.toFile().deleteRecursively()
            Files.writeString(root, "a file where the folder should be")
            val home = browser.get("/admin").bodyAsText()
            assertTrue(
                home.contains("The server cannot write the recordings folder") &&
                    home.contains("sudo chown -R UID:GID ./recordings"),
            )
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
            assertTrue(
                html.contains(
                    "href=\"https://github.com/jamesarich/sofa-king/blob/main/backend/docs/troubleshooting.md#guide\"",
                ),
                "the item links to the doc that explains it",
            )
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

    @Test fun `the Sources page offers services and playlists, and refuses a bad ZIP code`() = testApplication {
        var restarts = 0
        portal(restart = { restarts++ }) {
            browser.signIn()
            val page = browser.get("/admin/providers").bodyAsText()
            assertTrue(
                page.contains("<section id=\"services\">") && page.contains("Pluto TV") &&
                    page.contains("Add an M3U playlist"),
                "the Sources page offers the services and the playlist form",
            )
            assertTrue(
                Regex("""name="svc"[^>]*value="whale"(?![^>]*checked)""").containsMatchIn(page),
                "a service the file lacks starts unticked when a file exists",
            )
            for (playlist in listOf("fixture", "alt", "twin", "ts")) {
                assertTrue(page.contains("<th scope=\"row\">$playlist</th>"), "the M3U providers list as playlists")
            }
            val csrf = field(page, "csrf")
            val pbs = arrayOf("csrf" to csrf, "svc" to "pbs", "opt.pbs.zip" to "12")
            val refused = browser.post("/admin/sources/services", *pbs)
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.bodyAsText().contains("ZIP code does not look right"))
            assertEquals(0, restarts)
        }
    }

    @Test
    fun `the services form asks before it writes the source file, then restarts and answers the restarting page`() =
        testApplication {
            var restarts = 0
            portal(restart = { restarts++ }) {
                browser.signIn()
                val csrf = field(browser.get("/admin/providers").bodyAsText(), "csrf")
                val asked = browser.post("/admin/sources/services", "csrf" to csrf, "svc" to "pluto", "svc" to "whale")
                assertEquals(HttpStatusCode.OK, asked.status)
                val dialog = asked.bodyAsText()
                assertTrue(
                    dialog.contains("Save the services and restart?") &&
                        dialog.contains("Turns on Pluto TV, Whale TV+"),
                    dialog.substringAfter("<div class=\"modal\">").take(800),
                )
                assertTrue(dialog.contains("name=\"svc\" value=\"pluto\""), "the confirmation carries the form")
                assertEquals(0, restarts)
                assertEquals(4, runtime.setup!!.document().providers.size, "nothing is written before the confirmation")

                val saved = browser.post(
                    "/admin/sources/services",
                    "csrf" to csrf,
                    "svc" to "pluto",
                    "svc" to "whale",
                    "confirm" to "yes",
                )
                assertEquals(HttpStatusCode.OK, saved.status)
                val restarting = saved.bodyAsText()
                assertTrue(
                    restarting.contains("<title>Restarting") && restarting.contains("http-equiv=\"refresh\""),
                    restarting.take(600),
                )
                assertEquals(1, restarts)
                val written = runtime.setup!!.document()
                assertEquals(
                    listOf("fixture", "alt", "twin", "ts", "whale", "pluto"),
                    written.providers.map { it.id },
                    "new services follow the catalog's order",
                )
                assertTrue(written.providers.takeLast(2).all { it.native })

                val removed = browser.post("/admin/sources/remove", "csrf" to csrf, "id" to "alt", "confirm" to "yes")
                assertEquals(HttpStatusCode.OK, removed.status)
                assertEquals(
                    listOf("fixture", "twin", "ts", "whale", "pluto"),
                    runtime.setup!!.document().providers.map { it.id },
                )
                assertEquals(1, restarts, "one restart is pending for every write")
            }
        }

    @Test fun `without a policy one services save writes the source file and the starter lineup, with one restart`() =
        testApplication {
            var restarts = 0
            portal(restart = { restarts++ }) {
                browser.signIn()
                val page = browser.get("/admin/providers").bodyAsText()
                assertTrue(
                    Regex("""name="starter"[^>]*value="on"[^>]*checked""").containsMatchIn(page),
                    "the starter lineup is offered, ticked",
                )
                val csrf = field(page, "csrf")
                val asked = browser.post(
                    "/admin/sources/services",
                    "csrf" to csrf,
                    "svc" to "pluto",
                    "starter" to "on",
                ).bodyAsText()
                assertTrue(
                    asked.contains(
                        "Builds the starter lineup from them",
                    ) && asked.contains("name=\"starter\" value=\"on\""),
                    asked.substringAfter("<div class=\"modal\">").take(800),
                )
                assertFalse(Files.exists(runtime.setup!!.policyPath), "nothing is written before the confirmation")

                val saved = browser.post(
                    "/admin/sources/services",
                    "csrf" to csrf,
                    "svc" to "pluto",
                    "starter" to "on",
                    "confirm" to "yes",
                ).bodyAsText()
                assertTrue(saved.contains("and the starter lineup to ${runtime.setup!!.policyPath}"), saved.take(800))
                assertEquals(1, restarts)
                val policy = Json.parseToJsonElement(Files.readString(runtime.setup!!.policyPath)).jsonObject
                assertEquals(
                    listOf("pluto", "fixture", "alt", "twin", "ts"),
                    policy["providers"]!!.jsonArray.map {
                        it.jsonObject["id"]!!.jsonPrimitive.content
                    },
                    "the services lead, the playlists follow",
                )
                assertFalse(
                    browser.get("/admin/providers").bodyAsText().contains("Build the starter lineup too"),
                    "the policy file is written, so the box is not offered again before the restart",
                )
            }
        }

    @Test fun `the source file API builds the starter lineup in the same write when asked`() = testApplication {
        var restarts = 0
        portal(restart = { restarts++ }) {
            val document = """{"providers":[{"id":"pluto","type":"native","service":"pluto"}],"guides":[]}"""
            val written = client.put("/api/v1/admin/source-file?starterPolicy=true") {
                bearerAuth(admin)
                contentType(ContentType.Application.Json)
                setBody(document)
            }
            assertEquals(HttpStatusCode.OK, written.status, written.bodyAsText())
            val result = Json.parseToJsonElement(written.bodyAsText()).jsonObject
            assertEquals(runtime.setup!!.policyPath.toString(), result["policyPath"]!!.jsonPrimitive.content)
            assertEquals(1, restarts)
            val again = client.put("/api/v1/admin/source-file?starterPolicy=true") {
                bearerAuth(admin)
                contentType(ContentType.Application.Json)
                setBody(document)
            }
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.put("/api/v1/admin/source-file?starterPolicy=yes") {
                    bearerAuth(admin)
                    contentType(ContentType.Application.Json)
                    setBody(document)
                }.status,
            )
        }
    }

    @Test fun `the home page offers to build the lineup while there is no policy, and asks before it does`() =
        testApplication {
            var restarts = 0
            portal(restart = { restarts++ }) {
                browser.signIn()
                val home = browser.get("/admin").bodyAsText()
                assertTrue(
                    home.contains(
                        "<section id=\"setup\">",
                    ) && home.contains("<li class=\"done\"><b>Add your sources</b>") &&
                        home.contains(">Build the lineup</button>"),
                    home.substringAfter("id=\"setup\"").take(900),
                )
                val csrf = field(home, "csrf")
                val asked = browser.post("/admin/policy/starter", "csrf" to csrf)
                assertTrue(asked.bodyAsText().contains("Build the lineup from every configured service?"))
                assertEquals(0, restarts)
                val built = browser.post("/admin/policy/starter", "csrf" to csrf, "confirm" to "yes")
                assertEquals(HttpStatusCode.OK, built.status)
                assertTrue(built.bodyAsText().contains("Wrote the lineup policy"))
                assertEquals(1, restarts)
                assertTrue(Files.exists(runtime.setup!!.policyPath))
                assertEquals(
                    HttpStatusCode.Conflict,
                    browser.post(
                        "/admin/policy/starter",
                        "csrf" to csrf,
                        "confirm" to "yes",
                    ).status,
                )
            }
        }

    @Test fun `the setup steps leave the home page once the server has sources and a policy`() = testApplication {
        portal(basePolicy) {
            browser.signIn()
            assertFalse(browser.get("/admin").bodyAsText().contains("<section id=\"setup\">"))
        }
    }

    @Test fun `the jobs page lists every import with its result, and Run now starts one`() = testApplication {
        portal(basePolicy, guides = { listOf(it.brokenGuide) }) {
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/system/jobs").status)
            browser.signIn()
            val html = browser.get("/admin/system/jobs").bodyAsText()
            for (label in listOf(
                "Import fixture",
                "Import alt",
                "Guide broken",
                "Lineup policy run",
                "Logo sweep",
            )) {
                assertTrue(html.contains(label), label)
            }
            assertTrue(
                html.contains(">OK<") && html.contains(">Failed<"),
                "the fixture imports worked and the broken guide did not",
            )
            assertTrue(html.contains("IMPORT_FAILED"), html)
            assertTrue(html.contains("aria-label=\"Run Import fixture now\""))
            val home = browser.get("/admin").bodyAsText()
            assertTrue(home.contains("broken: ") && !home.contains("broken: IMPORT_FAILED"), "Home says why: $home")
            assertFalse(home.contains("broken-guide.xml"), "the reason carries no URL")
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
            org.slf4j.LoggerFactory.getLogger(
                "dev.jamesrich.sofaking.backend.http.PortalEvents",
            ).warn("something to see {}", marker)
            assertEquals(HttpStatusCode.SeeOther, browser.get("/admin/system/events").status)
            browser.signIn()
            val all = browser.get("/admin/system/events").bodyAsText()
            assertTrue(all.contains("something to see $marker"), all.take(3000))
            assertTrue(all.contains("<caption class=\"sr\">Log events, newest first</caption>"))
            assertTrue(all.contains("hx-trigger=\"every 10s\""), "the list refreshes itself with JavaScript")
            assertTrue(
                browser.get(
                    "/admin/system/events?level=warn&q=$marker",
                ).bodyAsText().contains("something to see $marker"),
            )
            assertFalse(
                browser.get(
                    "/admin/system/events?level=error&q=$marker",
                ).bodyAsText().contains("something to see $marker"),
            )
        }
    }
}
